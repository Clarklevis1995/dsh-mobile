import Foundation
import CryptoKit
import DeepSeekHarnessShared

struct WorkspacePreviewCacheEntry: Sendable {
    let sessionID: String
    let path: String
    let name: String
    let size: Int64
    let modifiedAt: Double
    let sha256: String?

    init(sessionID: String, path: String, name: String, size: Int64, modifiedAt: Double, sha256: String?) {
        self.sessionID = sessionID
        self.path = path
        self.name = name
        self.size = size
        self.modifiedAt = modifiedAt
        self.sha256 = sha256
    }

    init(_ lookup: SharedWorkspaceFileCacheLookup) {
        sessionID = lookup.sessionId; path = lookup.path; name = lookup.name
        size = lookup.size; modifiedAt = lookup.modifiedAt; sha256 = nil
    }

    init?(_ completion: SharedWorkspaceFileCompletion) {
        guard completion.purpose == "preview", let version = completion.modifiedAt?.doubleValue else { return nil }
        sessionID = completion.sessionId; path = completion.path; name = completion.name
        size = completion.size; modifiedAt = version; sha256 = completion.sha256
    }
}

/// 只接受真实管理目录；拒绝类别根或其直接父目录是符号链接，避免维护穿入外部文件。
func isSafeManagedCacheDirectory(_ url: URL) -> Bool {
    [url, url.deletingLastPathComponent()].allSatisfy {
        (try? FileManager.default.attributesOfItem(atPath: $0.path)[.type] as? FileAttributeType) != .typeSymbolicLink
    }
}

/// 所有预览操作共用串行锁与引用计数；维护只回收没有使用者的文件。
final class WorkspaceFileLifetime: @unchecked Sendable {
    static let shared = WorkspaceFileLifetime()
    private let lock = NSRecursiveLock()
    private var leases: [String: Int] = [:]
    private var epochs: [String: UInt64] = [:]
    private var reservations: [String: Int64] = [:]
    private var deferredDeletes: Set<String> = []
    private var revisions: [String: UInt64] = [:]
    private var usage: [String: Int64] = [:]
    func revision(_ root: URL) -> UInt64 { synchronized { revisions[root.path, default: 0] } }
    func markMutation(_ root: URL) { synchronized { revisions[root.path, default: 0] &+= 1; usage[root.path] = nil } }
    func publishUsage(_ root: URL, bytes: Int64, revision: UInt64) -> Bool { synchronized {
        guard revisions[root.path, default: 0] == revision else { return false }
        usage[root.path] = bytes
        return true
    } }
    func knownUsage(_ root: URL) -> Int64? { synchronized { usage[root.path] } }
    func removeAfterLastLease(_ url: URL) { synchronized { _ = deferredDeletes.insert(url.path) } }
    func reservedBytes(_ root: URL) -> Int64 { synchronized {
        reservations.filter { $0.key.hasPrefix(root.path + "/") }.values.reduce(0, +)
    } }
    func reserve(_ url: URL, bytes: Int64) { synchronized { reservations[url.path] = bytes } }
    func unreserve(_ url: URL) { synchronized { reservations[url.path] = nil } }
    func epoch(_ root: URL) -> UInt64 { synchronized { epochs[root.path, default: 0] } }
    func invalidate(_ root: URL) { synchronized { epochs[root.path, default: 0] &+= 1; markMutation(root) } }
    func synchronized<T>(_ body: () throws -> T) rethrows -> T {
        lock.lock(); defer { lock.unlock() }; return try body()
    }
    func retain(_ url: URL) { synchronized { leases[url.standardizedFileURL.path, default: 0] += 1 } }
    func isLeased(_ url: URL) -> Bool {
        synchronized {
            let path = url.standardizedFileURL.path
            return leases.contains { $0.value > 0 && ($0.key == path || $0.key.hasPrefix(path + "/")) }
        }
    }
    func release(_ url: URL, deleteTemporary: Bool = false) {
        synchronized {
            let path = url.standardizedFileURL.path
            if let count = leases[path], count > 1 { leases[path] = count - 1 } else { leases[path] = nil }
            if deleteTemporary, !isLeased(url) { removeTemporary(url) }
            for pending in deferredDeletes where !isLeased(URL(fileURLWithPath: pending)) {
                guard isSafeManagedCacheDirectory(URL(fileURLWithPath: pending)) else { continue }
                try? FileManager.default.removeItem(atPath: pending)
                markMutation(URL(fileURLWithPath: pending).deletingLastPathComponent())
                deferredDeletes.remove(pending)
            }
        }
    }
    func removeTemporary(_ url: URL) {
        synchronized {
            guard !isLeased(url), isSafeManagedCacheDirectory(url.deletingLastPathComponent()) else { return }
            try? FileManager.default.removeItem(at: url)
            let parent = url.deletingLastPathComponent()
            if (try? FileManager.default.contentsOfDirectory(atPath: parent.path).isEmpty) == true {
                try? FileManager.default.removeItem(at: parent)
            }
        }
    }
}

struct WorkspaceCacheStatistics: Sendable {
    let previewBytes: Int64
    let temporaryBytes: Int64
    let protectedBytes: Int64
    var totalBytes: Int64 { previewBytes + temporaryBytes }
}

/// 可重建的资源只进入 Caches；下载源与用户导出目标不在管理范围内。
struct WorkspacePreviewCache: Sendable {
    static let maximumBytes: Int64 = 192 * 1024 * 1024
    static let targetBytes: Int64 = 144 * 1024 * 1024
    static let minimumFreeBytes: Int64 = 128 * 1024 * 1024
    static var temporaryRoot: URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("dsh-workspace-files", isDirectory: true)
    }
    let root: URL
    let gatewayID: String
    private let namespace: String
    private let maximumBytes: Int64
    private let targetBytes: Int64
    private let availableBytes: @Sendable () -> Int64
    private let copyFile: @Sendable (URL, URL) throws -> Void
    private let creationEpoch: UInt64
    private var lifetime: WorkspaceFileLifetime { .shared }

    init(gatewayID: String, root: URL? = nil, namespace: String = "",
         maximumBytes: Int64 = Self.maximumBytes, targetBytes: Int64 = Self.targetBytes,
         availableBytes: @escaping @Sendable () -> Int64 = {
             let home = URL(fileURLWithPath: NSHomeDirectory())
             return (try? home.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
                .volumeAvailableCapacityForImportantUsage) ?? Int64.max
         }, copyFile: @escaping @Sendable (URL, URL) throws -> Void = { source, target in
             try FileManager.default.copyItem(at: source, to: target)
         }) {
        self.gatewayID = gatewayID
        self.namespace = namespace
        self.root = root ?? FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("WorkspacePreviews", isDirectory: true)
        self.maximumBytes = maximumBytes
        self.targetBytes = min(maximumBytes, targetBytes)
        self.availableBytes = availableBytes
        self.copyFile = copyFile
        self.creationEpoch = WorkspaceFileLifetime.shared.epoch(self.root)
    }

    /// 返回值带租约；调用方关闭预览或放弃异步结果时必须 release。
    func find(_ version: WorkspacePreviewCacheEntry) -> URL? {
        guard isSafeManagedCacheDirectory(root) else { return nil }
        let directory = directory(sessionID: version.sessionID, path: version.path,
                                  size: version.size, modifiedAt: version.modifiedAt)
        guard isSafeManagedCacheDirectory(directory) else { return nil }
        let file = directory.appendingPathComponent(safeName(version.name))
        lifetime.retain(file)
        do {
            let expected = try String(contentsOf: directory.appendingPathComponent("sha256"), encoding: .utf8)
            let attributes = try FileManager.default.attributesOfItem(atPath: file.path)
            guard attributes[.type] as? FileAttributeType == .typeRegular,
                  (attributes[.size] as? NSNumber)?.int64Value == version.size,
                  try digest(file) == expected else { throw CocoaError(.fileReadCorruptFile) }
            try? FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: directory.path)
            appLog("[ResourceCache] hit bytes=%lld", version.size)
            return file
        } catch {
            appLog("[ResourceCache] miss reason=missing-or-invalid")
            lifetime.synchronized {
                lifetime.release(file)
                if isSafeManagedCacheDirectory(root), !lifetime.isLeased(directory) {
                    try? FileManager.default.removeItem(at: directory)
                    lifetime.markMutation(root)
                }
            }
            return nil
        }
    }

    func store(_ source: URL, entry: WorkspacePreviewCacheEntry) -> URL? {
        guard isSafeManagedCacheDirectory(root), creationEpoch == lifetime.epoch(root), let sha256 = entry.sha256, entry.size >= 0,
              entry.size <= maximumBytes else { return nil }
        let estimatedAllocation = ((entry.size + 4095) / 4096) * 4096 + 4096
        guard estimatedAllocation <= maximumBytes,
              availableBytes() >= Self.minimumFreeBytes + estimatedAllocation else { return nil }
        let directory = directory(sessionID: entry.sessionID, path: entry.path,
                                  size: entry.size, modifiedAt: entry.modifiedAt)
        let finalFile = directory.appendingPathComponent(safeName(entry.name))
        let staging = root.appendingPathComponent(".pending-\(UUID().uuidString)", isDirectory: true)
        _ = maintain(temporaryRoot: nil)
        prune(to: max(0, targetBytes - estimatedAllocation), expectedEpoch: creationEpoch)
        guard refreshUsage() else { return nil }
        let admitted = lifetime.synchronized {
            guard creationEpoch == lifetime.epoch(root), !lifetime.isLeased(directory),
                  let bytes = lifetime.knownUsage(root),
                  bytes + lifetime.reservedBytes(root) + estimatedAllocation <= maximumBytes else { return false }
            lifetime.retain(staging)
            lifetime.reserve(staging, bytes: estimatedAllocation)
            return true
        }
        guard admitted else { return nil }
        defer {
            lifetime.synchronized {
                lifetime.unreserve(staging)
                lifetime.markMutation(root)
                lifetime.release(staging)
            }
        }
        do {
            // 大文件复制和哈希绝不持有生命周期锁，关闭/取消预览可立即响应。
            try FileManager.default.createDirectory(at: staging, withIntermediateDirectories: true)
            let file = staging.appendingPathComponent(safeName(entry.name))
            try copyFile(source, file)
            try sha256.write(to: staging.appendingPathComponent("sha256"), atomically: true, encoding: .utf8)
            return try lifetime.synchronized {
                guard isSafeManagedCacheDirectory(root), creationEpoch == lifetime.epoch(root), !lifetime.isLeased(directory) else {
                    try? FileManager.default.removeItem(at: staging)
                    return nil
                }
                if FileManager.default.fileExists(atPath: directory.path) { try FileManager.default.removeItem(at: directory) }
                try FileManager.default.moveItem(at: staging, to: directory)
                lifetime.markMutation(root)
                lifetime.retain(finalFile)
                appLog("[ResourceCache] stored bytes=%lld", entry.size)
                return finalFile
            }
        } catch {
            try? FileManager.default.removeItem(at: staging)
            return nil
        }
    }

    /// 启动、后台及用户清理均使用同一入口；正在下载/预览/导出的文件始终保留。
    func statistics(temporaryRoot: URL? = Self.temporaryRoot) -> WorkspaceCacheStatistics {
        let all = children(root) + (temporaryRoot.map(children) ?? [])
        return WorkspaceCacheStatistics(previewBytes: treeBytes(root),
            temporaryBytes: temporaryRoot.map(treeBytes) ?? 0,
            protectedBytes: all.filter { lifetime.isLeased($0) }.reduce(0) { $0 + treeBytes($1) })
    }

    func clearTemporaryFiles(temporaryRoot: URL? = Self.temporaryRoot) {
        guard let temporaryRoot else { return }
        for directory in children(temporaryRoot) {
            if lifetime.isLeased(directory) { lifetime.removeAfterLastLease(directory) }
            else { _ = removeUnused(directory) }
        }
    }

    @discardableResult
    func maintain(temporaryRoot: URL? = Self.temporaryRoot, clear: Bool = false) -> WorkspaceCacheStatistics {
        if clear { lifetime.invalidate(root) }
        return maintainFiles(temporaryRoot: temporaryRoot, clear: clear)
    }

    private func maintainFiles(temporaryRoot: URL?, clear: Bool) -> WorkspaceCacheStatistics {
        if clear {
            for directory in children(root) where lifetime.isLeased(directory) { lifetime.removeAfterLastLease(directory) }
        }
        for directory in children(root) where !lifetime.isLeased(directory) {
            let modified = (try? directory.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
            if directory.lastPathComponent.hasPrefix(".pending-") || modified < Date().addingTimeInterval(-7 * 24 * 60 * 60) {
                _ = removeUnused(directory)
            }
        }
        if let temporaryRoot {
            for directory in children(temporaryRoot) { _ = removeUnused(directory) }
        }
        let bytes = treeBytes(root)
        let free = availableBytes()
        let overLimit = bytes > maximumBytes || free < Self.minimumFreeBytes
        let needed = max(0, Self.minimumFreeBytes - free)
        if clear || overLimit { prune(to: clear ? 0 : min(targetBytes, max(0, bytes - needed))) }
        let all = children(root) + (temporaryRoot.map(children) ?? [])
        let stats = WorkspaceCacheStatistics(previewBytes: treeBytes(root),
            temporaryBytes: temporaryRoot.map(treeBytes) ?? 0,
            protectedBytes: all.filter { lifetime.isLeased($0) }.reduce(0) { $0 + treeBytes($1) })
        appLog("[ResourceCache] maintenance previewBytes=%lld temporaryBytes=%lld protectedBytes=%lld", stats.previewBytes, stats.temporaryBytes, stats.protectedBytes)
        return stats
    }

    /// 扫描/排序和容量统计均在生命周期锁外。删除单条不可变缓存时才短暂持锁重新检查租约。
    private func prune(to target: Int64, expectedEpoch: UInt64? = nil) {
        var bytes = treeBytes(root)
        let sorted = children(root).map { directory in
            (directory, (try? directory.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast,
             treeBytes(directory))
        }.sorted { $0.1 < $1.1 }
        for (directory, _, size) in sorted where bytes > target {
            if removeUnused(directory, expectedEpoch: expectedEpoch) { bytes -= size }
        }
    }

    private func removeUnused(_ directory: URL, expectedEpoch: UInt64? = nil) -> Bool {
        lifetime.synchronized {
            guard expectedEpoch == nil || expectedEpoch == lifetime.epoch(root), !lifetime.isLeased(directory), isSafeManagedCacheDirectory(directory.deletingLastPathComponent()) else { return false }
            do {
                try FileManager.default.removeItem(at: directory)
                lifetime.markMutation(root)
                return true
            } catch { return false }
        }
    }

    /// 并发提交使旧扫描失效时重读；持续竞争则拒绝持久化并继续使用临时预览。
    private func refreshUsage() -> Bool {
        for _ in 0..<3 {
            let revision = lifetime.revision(root)
            let bytes = treeBytes(root)
            if lifetime.publishUsage(root, bytes: bytes, revision: revision) { return true }
        }
        return false
    }

    private func children(_ url: URL) -> [URL] {
        guard isSafeManagedCacheDirectory(url) else { return [] }
        return (try? FileManager.default.contentsOfDirectory(at: url, includingPropertiesForKeys: [.contentModificationDateKey])) ?? []
    }

    func treeBytes(_ url: URL) -> Int64 {
        guard isSafeManagedCacheDirectory(url.deletingLastPathComponent()) else { return 0 }
        let rootValues = try? url.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey, .totalFileAllocatedSizeKey, .fileSizeKey])
        if rootValues?.isSymbolicLink == true { return 0 }
        if rootValues?.isRegularFile == true { return Int64(rootValues?.totalFileAllocatedSize ?? rootValues?.fileSize ?? 0) }
        guard let enumerator = FileManager.default.enumerator(at: url, includingPropertiesForKeys: [.isRegularFileKey, .isSymbolicLinkKey, .totalFileAllocatedSizeKey, .fileSizeKey]) else { return 0 }
        return enumerator.compactMap { $0 as? URL }.reduce(0) { total, file in
            let values = try? file.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey, .totalFileAllocatedSizeKey, .fileSizeKey])
            return total + (values?.isRegularFile == true && values?.isSymbolicLink != true ? Int64(values?.totalFileAllocatedSize ?? values?.fileSize ?? 0) : 0)
        }
    }

    private func directory(sessionID: String, path: String, size: Int64, modifiedAt: Double) -> URL {
        // 空命名空间保持既有工作区缓存键；上传附件与工作区路径独立。
        let components = [gatewayID, sessionID, path, String(size), String(modifiedAt)]
        let identity = (namespace.isEmpty ? components : [namespace] + components)
            .map { "\($0.utf8.count):\($0)" }.joined()
        let key = SHA256.hash(data: Data(identity.utf8)).map { String(format: "%02x", $0) }.joined()
        return root.appendingPathComponent(key, isDirectory: true)
    }
    private func safeName(_ name: String) -> String {
        let value = URL(fileURLWithPath: name).lastPathComponent
        return ["", ".", "..", "sha256"].contains(value) ? "file" : value
    }
    private func digest(_ url: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var hash = SHA256()
        while let data = try handle.read(upToCount: 64 * 1024), !data.isEmpty { hash.update(data: data) }
        return hash.finalize().map { String(format: "%02x", $0) }.joined()
    }
}

enum CacheStorageCategory: String, CaseIterable, Identifiable, Sendable {
    case chats, images, previews, temporary
    var id: String { rawValue }
}

struct AppStorageUsage: Sendable {
    let resources: WorkspaceCacheStatistics
    let chatBytes: Int64
    let imageBytes: Int64
    let freeBytes: Int64
    var totalBytes: Int64 { resources.totalBytes + chatBytes + imageBytes }
    func bytes(for category: CacheStorageCategory) -> Int64 {
        switch category {
        case .chats: chatBytes
        case .images: imageBytes
        case .previews: resources.previewBytes
        case .temporary: resources.temporaryBytes
        }
    }
}

struct AppCacheStorageStatistics: Sendable {
    let resources: WorkspaceCacheStatistics
    let databaseBytes: Int64
    let imageBytes: Int64
    let memoryBytes: Int64
    let memoryHits: Int64
    let diskHits: Int64
    let misses: Int64
    let freeBytes: Int64
    var totalBytes: Int64 { resources.totalBytes + databaseBytes + imageBytes }
    var underPressure: Bool {
        totalBytes > 384 * 1024 * 1024 || resources.temporaryBytes > 32 * 1024 * 1024 || freeBytes < WorkspacePreviewCache.minimumFreeBytes
    }
}

/// 所有自动维护与设置页面共用预算；活跃下载允许超过临时目录的 32 MiB 软预算，关闭即删。
actor AppCacheMaintenance {
    static let shared = AppCacheMaintenance()
    /// 设置页统计不触发自动淘汰；手动清理仅作用于指定资源类别。
    func storageUsage(cache: ChatHistoryCache?, clearing category: CacheStorageCategory? = nil) async -> AppStorageUsage {
        if category == .chats { await cache?.clear() }
        return await Task.detached(priority: .utility) {
            let cache = WorkspacePreviewCache(gatewayID: "maintenance")
            switch category {
            case .images: ImageAttachmentCache.maintainDownloadedFiles(clear: true)
            case .previews: cache.maintain(temporaryRoot: nil, clear: true)
            case .temporary: cache.clearTemporaryFiles()
            case .chats, nil: break
            }
            let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            return AppStorageUsage(resources: cache.statistics(),
                chatBytes: cache.treeBytes(base.appendingPathComponent("ChatHistory")),
                imageBytes: cache.treeBytes(base.appendingPathComponent("GatewayAttachments")) +
                    cache.treeBytes(base.appendingPathComponent("DshMobile/ImageAttachments")),
                freeBytes: Self.freeBytes())
        }.value
    }

    func run(cache: ChatHistoryCache?, clear: Bool = false) async -> AppCacheStorageStatistics {
        if clear { await cache?.clear() }
        var files = await snapshot(clear: clear)
        let raw = await cache?.statistics()
        var object = raw.flatMap { $0.data(using: .utf8) }.flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] } ?? [:]
        let number: (String) -> Int64 = { (object[$0] as? NSNumber)?.int64Value ?? 0 }
        let logical = number("logicalBytes")
        let total = files.0.totalBytes + files.1 + files.2
        let free = Self.freeBytes()
        if total > 384 * 1024 * 1024 || free < WorkspacePreviewCache.minimumFreeBytes {
            let globalTarget = max(0, 288 * 1024 * 1024 - files.0.totalBytes - files.2)
            let deficit = max(0, WorkspacePreviewCache.minimumFreeBytes - free)
            await cache?.trim(to: min(logical, globalTarget, max(0, logical - deficit)))
            files = await snapshot(clear: false)
            if let json = await cache?.statistics(), let data = json.data(using: .utf8),
               let updated = try? JSONSerialization.jsonObject(with: data) as? [String: Any] { object = updated }
        }
        let stats = AppCacheStorageStatistics(resources: files.0, databaseBytes: files.1, imageBytes: files.2,
            memoryBytes: number("memoryBytes"), memoryHits: number("memoryHits"), diskHits: number("diskHits"),
            misses: number("misses"), freeBytes: Self.freeBytes())
        appLog("[CacheManagement] physicalBytes=%lld protectedBytes=%lld memoryBytes=%lld pressure=%d", stats.totalBytes,
              stats.resources.protectedBytes, stats.memoryBytes, stats.underPressure ? 1 : 0)
        return stats
    }
    private static func freeBytes() -> Int64 {
        (try? URL(fileURLWithPath: NSHomeDirectory()).resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
            .volumeAvailableCapacityForImportantUsage) ?? Int64.max
    }
    private func snapshot(clear: Bool) async -> (WorkspaceCacheStatistics, Int64, Int64) {
        await Task.detached(priority: .utility) {
            ImageAttachmentCache.maintainDownloadedFiles(clear: clear)
            let cache = WorkspacePreviewCache(gatewayID: "maintenance")
            let resources = cache.maintain(clear: clear)
            let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            return (resources, cache.treeBytes(base.appendingPathComponent("ChatHistory")),
                cache.treeBytes(base.appendingPathComponent("GatewayAttachments")) + cache.treeBytes(base.appendingPathComponent("DshMobile/ImageAttachments")))
        }.value
    }
}
