import Foundation
import CryptoKit
import DeepSeekHarnessShared

/// 平台只负责固定的私有副本及后台 I/O，协议状态统一由 KMM 管理。
@MainActor
final class FileUploadController {
    private let shared = SharedFileUploadStore()
    private let gatewayID: String
    private let previewCacheRoot: URL?
    private let localFilesRoot: URL?
    private var previewCache: WorkspacePreviewCache {
        WorkspacePreviewCache(gatewayID: gatewayID, root: previewCacheRoot,
                              namespace: "uploaded-attachment")
    }
    private var preservedDraftIDs: Set<String> = []
    private var localReferenceWrites: [String: Task<Void, Never>] = [:]
    private var localReferenceFailures: Set<String> = []
    private var localURLs: [String: URL] = [:]
    private var localStore: LocalUploadedFileStore {
        LocalUploadedFileStore(gatewayID: gatewayID,
            root: localFilesRoot ?? previewCacheRoot?.appendingPathComponent("LocalUploads", isDirectory: true))
    }

    init(gatewayID: String = "legacy", previewCacheRoot: URL? = nil, localFilesRoot: URL? = nil) {
        self.gatewayID = gatewayID
        self.previewCacheRoot = previewCacheRoot
        self.localFilesRoot = localFilesRoot
    }

    /// 本机上传副本拥有独立的持久化路径，打开时不依赖网络或可淘汰的预览缓存。
    func previewURL(for file: PresentedFile, sessionID: String) async -> URL? {
        guard let attachmentID = file.attachmentId, !attachmentID.isEmpty else { return nil }
        let key = Self.referenceKey(sessionID: sessionID, attachmentID: attachmentID)
        await localReferenceWrites[key]?.value
        let store = localStore
        let localURL = localURLs[key]
        let local = await Task.detached(priority: .userInitiated) { () -> URL? in
            if let localURL, let available = store.lease(localURL) { return available }
            return store.find(sessionID: sessionID, attachmentID: attachmentID)
        }.value
        if let local { return local }
        // 兼容此前版本已经保存的附件缓存；新上传文件直接使用私有文件路径。
        guard let entry = Self.previewEntry(file, sessionID: sessionID) else { return nil }
        let cache = previewCache
        return await Task.detached(priority: .userInitiated) { cache.find(entry) }.value
    }

    private static func previewEntry(_ file: PresentedFile, sessionID: String,
                                     sha256: String? = nil) -> WorkspacePreviewCacheEntry? {
        guard let id = file.attachmentId, !id.isEmpty,
              let name = file.name, !name.isEmpty, let size = file.bytes, size >= 0 else { return nil }
        return WorkspacePreviewCacheEntry(sessionID: sessionID, path: "\(id.utf8.count):\(id)\(name)", name: name,
                                          size: size, modifiedAt: 0, sha256: sha256)
    }

    private static func referenceKey(sessionID: String, attachmentID: String) -> String {
        "\(sessionID.utf8.count):\(sessionID)\(attachmentID)"
    }

    private func preserveReadyFiles() {
        guard let sessionID else { return }
        for draft in drafts where draft.status == "ready" && !preservedDraftIDs.contains(draft.id) {
            guard let attachmentID = draft.file?.attachmentId, !attachmentID.isEmpty,
                  let source = localFiles[draft.id] else { continue }
            let key = Self.referenceKey(sessionID: sessionID, attachmentID: attachmentID)
            guard localReferenceWrites[key] == nil else { continue }
            preservedDraftIDs.insert(draft.id)
            localReferenceFailures.remove(draft.id)
            localURLs[key] = source
            let store = localStore
            let draftID = draft.id
            let size = draft.size
            WorkspaceFileLifetime.shared.retain(source)
            localReferenceWrites[key] = Task {
                let saved = await Task.detached(priority: .utility) {
                    defer { WorkspaceFileLifetime.shared.release(source) }
                    return store.save(source, sessionID: sessionID, attachmentID: attachmentID, size: size)
                }.value
                localReferenceWrites[key] = nil
                if let saved { localURLs[key] = saved }
                else if localFiles[draftID] == source {
                    preservedDraftIDs.remove(draftID)
                    localReferenceFailures.insert(draftID)
                    reportError?(String(localized: "无法保存文件的本地引用，请重试。"))
                }
                changed?()
            }
        }
    }

    private func discardLocalFile(_ url: URL) {
        Task.detached(priority: .utility) {
            WorkspaceFileLifetime.shared.synchronized {
                if WorkspaceFileLifetime.shared.isLeased(url) {
                    WorkspaceFileLifetime.shared.removeAfterLastLease(url)
                } else {
                    try? FileManager.default.removeItem(at: url)
                }
            }
        }
    }
    private var localFiles: [String: URL] = [:]
    private var timeouts: [String: Task<Void, Never>] = [:]
    private var connectionKey: String?
    private var context = UUID()
    private(set) var sessionID: String?
    private(set) var drafts: [SharedUploadDraft] = []
    private(set) var preparing = false
    private(set) var supported = false
    var changed: (() -> Void)?
    var send: ((String) -> Void)?
    var reportError: ((String) -> Void)?

    var canSend: Bool { !preparing && localReferenceWrites.isEmpty && localReferenceFailures.isEmpty && shared.canSend() }
    var receipts: [String] { shared.receipts() }

    func connected(sessionID: String?, supported: Bool) {
        context = UUID()
        preparing = false
        timeouts.values.forEach { $0.cancel() }
        timeouts.removeAll()
        connectionKey = UUID().uuidString
        self.supported = supported
        apply(shared.bind(sessionId: sessionID, connectionKey: connectionKey, available: supported))
        self.sessionID = sessionID
    }

    func disconnected() {
        supported = false
        context = UUID()
        preparing = false
        connectionKey = nil
        timeouts.values.forEach { $0.cancel() }
        timeouts.removeAll()
        apply(shared.bind(sessionId: sessionID, connectionKey: nil, available: false))
    }

    func select(sessionID: String?, allowed: Bool) {
        if self.sessionID != sessionID {
            context = UUID()
            preparing = false
            clear()
            self.sessionID = sessionID
        }
        apply(shared.bind(sessionId: sessionID, connectionKey: connectionKey, available: supported && allowed))
    }

    /// 未发送草稿切换工作区时保留私有副本，凭证必须在新会话重新上传取得。
    func retargetDraft(sessionID: String) {
        context = UUID()
        timeouts.values.forEach { $0.cancel() }; timeouts.removeAll()
        self.sessionID = sessionID
        preservedDraftIDs.removeAll()
        localReferenceFailures.removeAll()
        apply(shared.bind(sessionId: sessionID, connectionKey: connectionKey, available: supported))
        for id in drafts.map(\.id) { apply(shared.retry(id: id)) }
    }

    func importFiles(_ urls: [URL]) async {
        guard supported, connectionKey != nil, sessionID != nil, !preparing else { return }
        let token = context
        preparing = true
        changed?()
        defer { if context == token { preparing = false; changed?() } }
        for url in urls.prefix(max(0, 20 - drafts.count)) {
            do {
                let id = UUID().uuidString
                let store = localStore
                let prepared = try await Task.detached(priority: .userInitiated) {
                    try Self.prepare(url, id: id, store: store)
                }.value
                guard token == context, !Task.isCancelled else {
                    try? FileManager.default.removeItem(at: prepared.url)
                    continue
                }
                localFiles[id] = prepared.url
                apply(shared.add(id: id, name: prepared.name, size: prepared.size, sha256: prepared.sha256))
            } catch {
                if token == context { reportError?(error.localizedDescription) }
            }
        }
    }

    func accept(_ frame: GatewayFrame) {
        guard frame.kind.hasPrefix("file-upload-") || frame.requestType?.hasPrefix("file-upload-") == true else { return }
        if let id = frame.requestId { timeouts.removeValue(forKey: id)?.cancel() }
        guard let json = try? JSONEncoder().encode(frame) else { return }
        apply(shared.acceptFrame(json: String(decoding: json, as: UTF8.self)))
    }

    func retry(_ id: String) { apply(shared.retry(id: id)) }
    func remove(_ id: String) { remove(id, keepLocalFile: false) }

    private func remove(_ id: String, keepLocalFile: Bool) {
        preservedDraftIDs.remove(id)
        localReferenceFailures.remove(id)
        let reference = drafts.first { $0.id == id }?.file?.attachmentId.map {
            Self.referenceKey(sessionID: sessionID ?? "", attachmentID: $0)
        }
        let preservedURL = reference.flatMap { localURLs[$0] }
        apply(shared.remove(id: id))
        if let url = localFiles.removeValue(forKey: id), !keepLocalFile || preservedURL != url {
            if let reference, localURLs[reference] == url { localURLs[reference] = nil }
            discardLocalFile(url)
        }
    }

    // 发送成功只移除上传草稿；本地文件已交给附件引用持有，供历史卡片直接打开。
    func accepted(_ ids: [String]) { ids.forEach { remove($0, keepLocalFile: true) } }
    func invalidReceipts() { apply(shared.invalidateReceipts()) }
    func submission(text: String, imageKey: String) -> String {
        shared.submission(text: text, imageKey: imageKey, proposedId: UUID().uuidString)
    }
    func clear() {
        context = UUID()
        preparing = false
        timeouts.values.forEach { $0.cancel() }; timeouts.removeAll()
        apply(shared.clear())
        localFiles.values.forEach(discardLocalFile)
        localFiles.removeAll()
        preservedDraftIDs.removeAll()
        localReferenceFailures.removeAll()
        localURLs.removeAll()
    }

    private func apply(_ transition: SharedUploadTransition) {
        drafts = transition.drafts
        // 凭证失效后重新上传可能取得新附件 ID，需要为同一草稿重新保存本地引用。
        preservedDraftIDs.formIntersection(Set(drafts.filter { $0.status == "ready" }.map(\.id)))
        preserveReadyFiles()
        changed?()
        for request in transition.requests {
            if request.requestType != "file-upload-cancel", let id = request.correlationId { watch(request, id: id) }
            send?(request.payload)
        }
        if let request = transition.watch, let id = request.correlationId { watch(request, id: id) }
        if let read = transition.read, let url = localFiles[read.id] {
            let token = read.token
            let offset = UInt64(read.offset)
            let length = Int(read.length)
            Task { [weak self] in
                do {
                    let encoded = try await Task.detached(priority: .userInitiated) {
                        let handle = try FileHandle(forReadingFrom: url)
                        defer { try? handle.close() }
                        try handle.seek(toOffset: offset)
                        let data = try handle.read(upToCount: length) ?? Data()
                        return (data.base64EncodedString(), data.count)
                    }.value
                    guard let self else { return }
                    self.apply(self.shared.chunkReady(token: token, data: encoded.0, bytes: Int32(encoded.1)))
                } catch {
                    guard let self else { return }
                    self.apply(self.shared.readFailed(token: token, message: error.localizedDescription.rawUiMessage))
                }
            }
        }
    }

    private func watch(_ request: GatewayRequest, id: String) {
        let seconds: Double = request.requestType == "file-upload-finish" ? 60 : 30
        timeouts[id]?.cancel()
        timeouts[id] = Task { [weak self] in
            do { try await Task.sleep(for: .seconds(seconds)) } catch { return }
            guard let self else { return }
            self.timeouts.removeValue(forKey: id)
            self.apply(self.shared.timedOut(requestId: id))
        }
    }

    private struct Prepared: Sendable {
        let url: URL
        let name: String
        let size: Int64
        let sha256: String
    }
    nonisolated private static func prepare(_ source: URL, id: String, store: LocalUploadedFileStore) throws -> Prepared {
        let access = source.startAccessingSecurityScopedResource()
        defer { if access { source.stopAccessingSecurityScopedResource() } }
        let name = source.lastPathComponent
        guard !name.isEmpty, name.utf16.count <= 255, name != ".", name != "..",
              !name.contains("/"), !name.contains("\\"), name.rangeOfCharacter(from: .controlCharacters) == nil else {
            throw UploadError.invalidName
        }
        let values = try source.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey])
        guard values.isRegularFile == true else { throw UploadError.invalidFile }
        guard Int64(values.fileSize ?? 0) <= 536_870_912 else { throw UploadError.tooLarge }
        let destination = try store.prepareDestination(id: id, name: name)
        var succeeded = false
        defer { if !succeeded { try? FileManager.default.removeItem(at: destination) } }
        guard FileManager.default.createFile(atPath: destination.path, contents: nil) else { throw UploadError.invalidFile }
        let input = try FileHandle(forReadingFrom: source)
        defer { try? input.close() }
        let output = try FileHandle(forWritingTo: destination)
        defer { try? output.close() }
        var digest = SHA256()
        var size: Int64 = 0
        while let data = try input.read(upToCount: 256 * 1024), !data.isEmpty {
            try Task.checkCancellation()
            size += Int64(data.count)
            guard size <= 536_870_912 else { throw UploadError.tooLarge }
            digest.update(data: data)
            try output.write(contentsOf: data)
        }
        succeeded = true
        return Prepared(url: destination, name: name, size: size,
            sha256: digest.finalize().map { String(format: "%02x", $0) }.joined())
    }
    private enum UploadError: LocalizedError {
        case invalidName, invalidFile, tooLarge
        var errorDescription: String? {
            switch self {
            case .invalidName: String(localized: "文件名无效")
            case .invalidFile: String(localized: "无法读取所选文件")
            case .tooLarge: String(localized: "文件不能超过 512 MiB")
            }
        }
    }
}

/// 用户在本机提交的附件属于本地数据，不能随工作区预览缓存淘汰。
/// 文件和引用都存于 Application Support；磁盘操作仅由后台任务调用。
struct LocalUploadedFileStore: Sendable {
    let root: URL

    init(gatewayID: String, root: URL? = nil) {
        let base = root ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("UploadedFiles", isDirectory: true)
        self.root = base.appendingPathComponent(Self.key(gatewayID), isDirectory: true)
    }

    private struct Reference: Codable {
        let directoryID: String
        let name: String
        let size: Int64
    }

    func prepareDestination(id: String, name: String) throws -> URL {
        guard UUID(uuidString: id) != nil, Self.isSafeName(name),
              isSafeManagedCacheDirectory(root) else { throw CocoaError(.fileWriteInvalidFileName) }
        let directory = root.appendingPathComponent("files", isDirectory: true)
            .appendingPathComponent(id, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        guard isSafeManagedCacheDirectory(directory) else { throw CocoaError(.fileWriteInvalidFileName) }
        return directory.appendingPathComponent(name)
    }

    func save(_ source: URL, sessionID: String, attachmentID: String, size: Int64) -> URL? {
        // 相同附件再次提交时沿用已有本地副本，取消新草稿也不会破坏旧消息的引用。
        if let existing = find(sessionID: sessionID, attachmentID: attachmentID) {
            WorkspaceFileLifetime.shared.release(existing)
            return existing
        }
        let directoryID = source.deletingLastPathComponent().lastPathComponent
        guard UUID(uuidString: directoryID) != nil, Self.isSafeName(source.lastPathComponent),
              source.deletingLastPathComponent().deletingLastPathComponent().standardizedFileURL.path ==
                root.appendingPathComponent("files").standardizedFileURL.path,
              isSafeManagedCacheDirectory(source.deletingLastPathComponent()),
              (try? source.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey]).isRegularFile) == true,
              (try? source.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init) == size else { return nil }
        let reference = Reference(directoryID: directoryID, name: source.lastPathComponent, size: size)
        let destination = referenceURL(sessionID: sessionID, attachmentID: attachmentID)
        do {
            try FileManager.default.createDirectory(at: destination.deletingLastPathComponent(),
                                                    withIntermediateDirectories: true)
            try JSONEncoder().encode(reference).write(to: destination, options: .atomic)
            return source
        } catch { return nil }
    }

    /// 返回值携带预览租约，与现有 QuickLook/文本预览的释放流程一致。
    func find(sessionID: String, attachmentID: String) -> URL? {
        guard isSafeManagedCacheDirectory(root),
              let data = try? Data(contentsOf: referenceURL(sessionID: sessionID, attachmentID: attachmentID)),
              let reference = try? JSONDecoder().decode(Reference.self, from: data),
              UUID(uuidString: reference.directoryID) != nil, Self.isSafeName(reference.name) else { return nil }
        let url = root.appendingPathComponent("files", isDirectory: true)
            .appendingPathComponent(reference.directoryID, isDirectory: true)
            .appendingPathComponent(reference.name)
        return lease(url, expectedSize: reference.size)
    }

    func lease(_ url: URL, expectedSize: Int64? = nil) -> URL? {
        WorkspaceFileLifetime.shared.retain(url)
        let attributes = try? FileManager.default.attributesOfItem(atPath: url.path)
        if isSafeManagedCacheDirectory(url.deletingLastPathComponent()),
           attributes?[.type] as? FileAttributeType == .typeRegular,
           expectedSize == nil || (attributes?[.size] as? NSNumber)?.int64Value == expectedSize {
            return url
        }
        WorkspaceFileLifetime.shared.release(url)
        return nil
    }

    func forget() {
        guard isSafeManagedCacheDirectory(root) else { return }
        WorkspaceFileLifetime.shared.synchronized {
            if WorkspaceFileLifetime.shared.isLeased(root) {
                WorkspaceFileLifetime.shared.removeAfterLastLease(root)
            } else {
                try? FileManager.default.removeItem(at: root)
            }
        }
    }

    private func referenceURL(sessionID: String, attachmentID: String) -> URL {
        root.appendingPathComponent("references", isDirectory: true)
            .appendingPathComponent(Self.key(sessionID), isDirectory: true)
            .appendingPathComponent(Self.key(attachmentID) + ".json")
    }

    private static func isSafeName(_ name: String) -> Bool {
        !name.isEmpty && name != "." && name != ".." && !name.contains("/") && !name.contains("\\") &&
            name.rangeOfCharacter(from: .controlCharacters) == nil
    }

    private static func key(_ value: String) -> String {
        SHA256.hash(data: Data(value.utf8)).map { String(format: "%02x", $0) }.joined()
    }
}
