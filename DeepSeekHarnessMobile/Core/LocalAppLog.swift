import Foundation

/// 日志与导出快照共用 200 MB 配额，所有磁盘操作在专用串行队列执行。
final class LocalAppLog: @unchecked Sendable {
    static let shared = LocalAppLog()
    private let queue = DispatchQueue(label: "ai.dsh.local-logs", qos: .utility)
    private let pending = DispatchSemaphore(value: 1024)
    private let directory: URL
    private let limit: Int64
    private let hour = DateFormatter()
    private let timestamp = DateFormatter()
    private let lineTime = ISO8601DateFormatter()
    private let files = FileManager.default

    init(directory: URL? = nil, limit: Int64 = 200_000_000) {
        precondition(limit > 0 && limit <= 200_000_000)
        self.directory = directory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("DiagnosticLogs", isDirectory: true)
        self.limit = limit
        for formatter in [hour, timestamp] {
            formatter.locale = Locale(identifier: "en_US_POSIX")
            formatter.calendar = Calendar(identifier: .gregorian)
        }
        hour.dateFormat = "yyyyMMddHH'00'"
        timestamp.dateFormat = "yyyyMMddHHmm"
        queue.async { [self] in
            do {
                try files.createDirectory(at: self.directory, withIntermediateDirectories: true)
                var url = self.directory
                var values = URLResourceValues()
                values.isExcludedFromBackup = true
                try url.setResourceValues(values)
                try? files.removeItem(at: exports)
                try trim(to: limit)
            } catch { NSLog("Local log initialization failed: %@", error.localizedDescription) }
        }
    }

    private var exports: URL { directory.appendingPathComponent("Exports", isDirectory: true) }

    func write(_ message: String, at date: Date = Date()) {
        guard pending.wait(timeout: .now()) == .success else { return }
        let text = String(message.prefix(16_384))
        queue.async { [self] in
            defer { pending.signal() }
            do {
                let data = Data("\(lineTime.string(from: date)) \(text)\n".utf8)
                guard Int64(data.count) <= limit else { throw CocoaError(.fileWriteOutOfSpace) }
                try trim(to: limit - Int64(data.count))
                let prefix = hour.string(from: date)
                let chunks = try logs().filter {
                    $0.deletingPathExtension().lastPathComponent == prefix || $0.lastPathComponent.hasPrefix("\(prefix)-part-")
                }
                var url = chunks.last ?? directory.appendingPathComponent("\(prefix).log")
                if ((try? size(url)) ?? 0) + Int64(data.count) > min(4_000_000, limit / 4) {
                    let next = (Int(url.deletingPathExtension().lastPathComponent.components(separatedBy: "-part-").last ?? "") ?? 0) + 1
                    let part = String(format: "%05d", next)
                    url = directory.appendingPathComponent("\(prefix)-part-\(part).log")
                }
                if !files.fileExists(atPath: url.path) { try Data().write(to: url) }
                let handle = try FileHandle(forWritingTo: url)
                defer { try? handle.close() }
                try handle.seekToEnd()
                try handle.write(contentsOf: data)
            } catch { NSLog("Local log write failed: %@", error.localizedDescription) }
        }
    }

    private func logs() throws -> [URL] {
        try files.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.fileSizeKey])
            .filter { $0.pathExtension == "log" }.sorted { $0.deletingPathExtension().lastPathComponent < $1.deletingPathExtension().lastPathComponent }
    }

    private func size(_ url: URL) throws -> Int64 {
        Int64(try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0)
    }

    private func directoryFiles(in root: URL? = nil) throws -> [URL] {
        let entries = try files.contentsOfDirectory(at: root ?? directory,
                                                    includingPropertiesForKeys: [.isDirectoryKey, .isSymbolicLinkKey])
        var result: [URL] = []
        for url in entries {
            let values = try url.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey])
            guard values.isSymbolicLink != true else { throw CocoaError(.fileReadUnsupportedScheme) }
            if values.isDirectory == true { result += try directoryFiles(in: url) }
            else { result.append(url) }
        }
        return result
    }

    private func directoryBytes() throws -> Int64 {
        try directoryFiles().reduce(Int64(0)) { try $0 + size($1) }
    }

    private func trim(to budget: Int64) throws {
        guard budget >= 0 else { throw CocoaError(.fileWriteOutOfSpace) }
        // 递归计入旧版本、嵌套目录和中断导出的残留文件；优先回收最旧日志。
        let candidates = try directoryFiles().sorted {
            let leftExport = $0.deletingLastPathComponent() == exports
            let rightExport = $1.deletingLastPathComponent() == exports
            if leftExport != rightExport { return !leftExport }
            let leftDate = try $0.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate ?? .distantPast
            let rightDate = try $1.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate ?? .distantPast
            return leftDate == rightDate ? $0.path < $1.path : leftDate < rightDate
        }
        var total = try candidates.reduce(Int64(0)) { try $0 + size($1) }
        for url in candidates {
            if total <= budget { break }
            let bytes = try size(url)
            try files.removeItem(at: url)
            total -= bytes
        }
        guard try directoryBytes() <= budget else { throw CocoaError(.fileWriteOutOfSpace) }
    }

    struct Summary: Sendable {
        let fileCount: Int
        let bytes: Int64
        let earliest: Date?
    }

    private func selected(_ source: URL, interval: DateInterval?) throws -> Data {
        guard let interval else { return try Data(contentsOf: source) }
        let text = try String(contentsOf: source, encoding: .utf8)
        var include = false
        var result = ""
        let lines = text.split(separator: "\n", omittingEmptySubsequences: false)
        for line in lines.dropLast(text.hasSuffix("\n") ? 1 : 0) {
            if let token = line.split(separator: " ", maxSplits: 1).first,
               let time = lineTime.date(from: String(token)) {
                include = time >= interval.start && time < interval.end
            }
            if include { result += line + "\n" }
        }
        return Data(result.utf8)
    }

    func summary(in interval: DateInterval) async throws -> Summary {
        try await withCheckedThrowingContinuation { continuation in
            queue.async { [self] in
                do {
                    var count = 0
                    var bytes: Int64 = 0
                    var earliest: Date?
                    for url in try logs() {
                        let text = try String(contentsOf: url, encoding: .utf8)
                        if let token = text.split(separator: " ", maxSplits: 1).first,
                           let date = lineTime.date(from: String(token)) {
                            earliest = min(earliest ?? date, date)
                        }
                        let data = try selected(url, interval: interval)
                        if !data.isEmpty { count += 1; bytes += Int64(data.count) }
                    }
                    continuation.resume(returning: Summary(fileCount: count, bytes: bytes, earliest: earliest))
                } catch { continuation.resume(throwing: error) }
            }
        }
    }

    /// ZIP 使用 STORE 编码，逐个小时分片写入，避免把全部日志装入内存。
    func exportArchive(in interval: DateInterval) async throws -> URL {
        try await withCheckedThrowingContinuation { continuation in
            queue.async { [self] in
                do {
                    try? files.removeItem(at: exports)
                    try files.createDirectory(at: exports, withIntermediateDirectories: true)
                    try trim(to: max(0, (limit - 1_000_000) / 2))
                    let target = exports.appendingPathComponent("Whaleness-logs-\(timestamp.string(from: Date())).zip")
                    try Data().write(to: target)
                    let handle = try FileHandle(forWritingTo: target)
                    defer { try? handle.close() }
                    var central = Data()
                    var offset: UInt32 = 0
                    var count: UInt16 = 0
                    let parts = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute, .second], from: Date())
                    let dosDate = UInt16(((max(1980, min(2107, parts.year ?? 1980)) - 1980) << 9)
                        | ((parts.month ?? 1) << 5) | (parts.day ?? 1))
                    let dosTime = UInt16(((parts.hour ?? 0) << 11) | ((parts.minute ?? 0) << 5) | ((parts.second ?? 0) / 2))
                    let remaining = try limit - directoryBytes()
                    func write(_ data: Data) throws {
                        guard Int64(offset) + Int64(data.count) <= remaining else { throw CocoaError(.fileWriteOutOfSpace) }
                        try handle.write(contentsOf: data)
                        offset += UInt32(data.count)
                    }
                    for source in try logs() {
                        let data = try selected(source, interval: interval)
                        guard !data.isEmpty else { continue }
                        let name = Data(source.lastPathComponent.utf8)
                        let crc = LogZip.crc(data)
                        let size = UInt32(data.count)
                        let recordOffset = offset
                        var header = Data()
                        header.logLE(UInt32(0x04034b50)); header.logLE(UInt16(20))
                        header.logLE(UInt16(0)); header.logLE(UInt16(0)); header.logLE(dosTime); header.logLE(dosDate)
                        header.logLE(crc); header.logLE(size); header.logLE(size)
                        header.logLE(UInt16(name.count)); header.logLE(UInt16(0)); header.append(name)
                        try write(header); try write(data)
                        central.logLE(UInt32(0x02014b50)); central.logLE(UInt16(20)); central.logLE(UInt16(20))
                        central.logLE(UInt16(0)); central.logLE(UInt16(0)); central.logLE(dosTime); central.logLE(dosDate)
                        central.logLE(crc); central.logLE(size); central.logLE(size)
                        central.logLE(UInt16(name.count))
                        for _ in 0..<4 { central.logLE(UInt16(0)) }
                        central.logLE(UInt32(0)); central.logLE(recordOffset); central.append(name)
                        count += 1
                    }
                    guard count > 0 else { throw CocoaError(.fileNoSuchFile) }
                    let centralOffset = offset
                    try write(central)
                    var end = Data()
                    end.logLE(UInt32(0x06054b50)); end.logLE(UInt16(0)); end.logLE(UInt16(0))
                    end.logLE(count); end.logLE(count); end.logLE(UInt32(central.count)); end.logLE(centralOffset); end.logLE(UInt16(0))
                    try write(end)
                    continuation.resume(returning: target)
                } catch {
                    try? files.removeItem(at: exports)
                    continuation.resume(throwing: error)
                }
            }
        }
    }

    func export() async throws -> [URL] {
        try await withCheckedThrowingContinuation { continuation in
            queue.async { [self] in
                do {
                    try? files.removeItem(at: exports)
                    try files.createDirectory(at: exports, withIntermediateDirectories: true)
                    // 为快照预留一半预算，避免导出瞬间突破容量上限。
                    try trim(to: limit / 2)
                    var result: [URL] = []
                    for source in try logs() {
                        let target = exports.appendingPathComponent(source.lastPathComponent)
                        guard try directoryBytes() + size(source) <= limit else { throw CocoaError(.fileWriteOutOfSpace) }
                        try files.copyItem(at: source, to: target)
                        result.append(target)
                    }
                    guard try directoryBytes() <= limit else { throw CocoaError(.fileWriteOutOfSpace) }
                    guard !result.isEmpty else { throw CocoaError(.fileNoSuchFile) }
                    continuation.resume(returning: result)
                } catch {
                    try? files.removeItem(at: exports)
                    continuation.resume(throwing: error)
                }
            }
        }
    }
}

/// 与系统控制台同步输出，调用方只记录已有的诊断字段。
struct LocalLogger {
    let subsystem: String
    let category: String
    func notice(_ message: String) { emit("NOTICE", message) }
    func info(_ message: String) { emit("INFO", message) }
    func debug(_ message: String) { emit("DEBUG", message) }
    private func emit(_ level: String, _ message: String) {
        let line = "\(level) [\(category)] \(message)"
        NSLog("%@", line)
        LocalAppLog.shared.write(line)
    }
}

func appLog(_ format: String, _ arguments: CVarArg...) {
    let message = String(format: format, arguments: arguments)
    NSLog("%@", message)
    LocalAppLog.shared.write(message)
}

func appPrint(_ message: String) {
    print(message)
    LocalAppLog.shared.write(message)
}

private enum LogZip {
    static let table: [UInt32] = (0..<256).map { value in
        var crc = UInt32(value)
        for _ in 0..<8 { crc = crc & 1 == 0 ? crc >> 1 : (crc >> 1) ^ 0xedb88320 }
        return crc
    }
    static func crc(_ data: Data) -> UInt32 {
        var crc: UInt32 = 0xffffffff
        for byte in data { crc = table[Int((crc ^ UInt32(byte)) & 0xff)] ^ (crc >> 8) }
        return crc ^ 0xffffffff
    }
}

private extension Data {
    mutating func logLE<T: FixedWidthInteger>(_ value: T) {
        var little = value.littleEndian
        Swift.withUnsafeBytes(of: &little) { append(contentsOf: $0) }
    }
}
