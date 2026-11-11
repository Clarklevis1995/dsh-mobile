import Foundation
import CryptoKit

struct PhoneConnectorFailure: Error { let code: String }

/// 原生端同样校验参数；不能只信任主机发来的 JSON。
enum PhoneConnectorWriteInput {
    static let tools: Set<String> = ["calendar.create", "reminders.create", "alarms.create", "alarms.cancel"]
    static func date(_ value: Any?) -> Date? {
        guard let text = value as? String, text.utf16.count <= 40,
              text.range(of: #"^\d{4}-\d\d-\d\dT.*(?:Z|[+-]\d\d:\d\d)$"#, options: .regularExpression) != nil else { return nil }
        let formatter = ISO8601DateFormatter()
        if let date = formatter.date(from: text) { return date }
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter.date(from: text)
    }
    private static func text(_ value: Any?, max: Int, empty: Bool = false) -> Bool {
        guard let value = value as? String else { return false }
        return value.utf16.count <= max && (empty || !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
    }
    private static func bool(_ value: Any) -> Bool { (value as? NSNumber).map { CFGetTypeID($0) == CFBooleanGetTypeID() } ?? false }
    private static func integer(_ value: Any, in range: ClosedRange<Int>) -> Bool {
        guard let value = value as? NSNumber, CFGetTypeID(value) != CFBooleanGetTypeID() else { return false }
        return value.doubleValue.isFinite && value.doubleValue.rounded() == value.doubleValue && range.contains(value.intValue)
    }
    static func validate(_ tool: String, _ args: [String: Any]) throws {
        let fields: Set<String>
        switch tool {
        case "calendar.create": fields = ["operationId", "title", "start", "end", "timeZone", "notes", "location", "allDay", "alertMinutesBefore"]
        case "reminders.create": fields = ["operationId", "title", "dueDate", "timeZone", "notes", "priority", "notify"]
        case "alarms.create": fields = ["operationId", "title", "fireDate", "timeZone"]
        case "alarms.cancel": fields = ["operationId", "alarmId"]
        default: throw PhoneConnectorFailure(code: "tool_unsupported")
        }
        guard Set(args.keys).isSubset(of: fields), let operation = args["operationId"] as? String,
              UUID(uuidString: operation) != nil else { throw PhoneConnectorFailure(code: "invalid_request") }
        if tool == "alarms.cancel" {
            guard let id = args["alarmId"] as? String, UUID(uuidString: id) != nil else { throw PhoneConnectorFailure(code: "invalid_request") }
            return
        }
        guard text(args["title"], max: 500), let zone = args["timeZone"] as? String,
              zone.utf16.count <= 80, TimeZone(identifier: zone) != nil else { throw PhoneConnectorFailure(code: "invalid_request") }
        if let notes = args["notes"], !text(notes, max: 2000, empty: true) { throw PhoneConnectorFailure(code: "invalid_request") }
        switch tool {
        case "calendar.create":
            guard let start = date(args["start"]), let end = date(args["end"]), end > start,
                  end.timeIntervalSince(start) <= 31 * 86400 else { throw PhoneConnectorFailure(code: "invalid_request") }
            if let location = args["location"], !text(location, max: 500, empty: true) { throw PhoneConnectorFailure(code: "invalid_request") }
            if let value = args["allDay"], !bool(value) { throw PhoneConnectorFailure(code: "invalid_request") }
            if let value = args["alertMinutesBefore"], !integer(value, in: 0...10080) { throw PhoneConnectorFailure(code: "invalid_request") }
            if args["allDay"] as? Bool == true {
                var calendar = Calendar(identifier: .gregorian); calendar.timeZone = TimeZone(identifier: zone)!
                guard calendar.startOfDay(for: start) == start, calendar.startOfDay(for: end) == end else { throw PhoneConnectorFailure(code: "invalid_request") }
            }
        case "reminders.create":
            if let due = args["dueDate"], date(due) == nil { throw PhoneConnectorFailure(code: "invalid_request") }
            if let value = args["priority"], !integer(value, in: 0...9) || ![0, 1, 5, 9].contains((value as? NSNumber)?.intValue ?? -1) { throw PhoneConnectorFailure(code: "invalid_request") }
            if let value = args["notify"], !bool(value) { throw PhoneConnectorFailure(code: "invalid_request") }
            if args["notify"] as? Bool == true, args["dueDate"] == nil { throw PhoneConnectorFailure(code: "invalid_request") }
        case "alarms.create":
            guard date(args["fireDate"]) != nil else { throw PhoneConnectorFailure(code: "invalid_request") }
        default: break
        }
    }
}

/// 每个主机独立的持久执行回执。先原子保存 attempted，再调用系统；不明结果不自动重做。
@MainActor
final class PhoneConnectorWriteLedger {
    private let directory: URL
    init(hostID: String, root: URL? = nil) {
        let root = root ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("phone-connector-writes", isDirectory: true)
        directory = root.appendingPathComponent(Self.digest(Data(hostID.utf8)), isDirectory: true)
    }
    private static func digest(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }
    func alarmIdentifier(sessionID: String, operationID: String) -> UUID {
        let hash = Self.digest(Data((directory.lastPathComponent + "\u{0}" + sessionID + "\u{0}" + operationID.lowercased()).utf8))
        let parts = [String(hash.prefix(8)), String(hash.dropFirst(8).prefix(4)), String(hash.dropFirst(12).prefix(4)), String(hash.dropFirst(16).prefix(4)), String(hash.dropFirst(20).prefix(12))]
        return UUID(uuidString: parts.joined(separator: "-"))!
    }
    private func save(_ value: [String: Any], to file: URL) throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication, .posixPermissions: 0o700])
        try JSONSerialization.data(withJSONObject: value, options: [.sortedKeys]).write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: file.path)
    }
    func run(tool: String, sessionID: String, arguments: [String: Any], operation: () async throws -> [String: Any]) async throws -> [String: Any] {
        guard !sessionID.isEmpty, let id = arguments["operationId"] as? String, UUID(uuidString: id) != nil else { throw PhoneConnectorFailure(code: "invalid_request") }
        let identity = try JSONSerialization.data(withJSONObject: [sessionID, tool, id.lowercased()])
        let file = directory.appendingPathComponent(Self.digest(identity) + ".json")
        let fingerprint = Self.digest(try JSONSerialization.data(withJSONObject: arguments, options: [.sortedKeys]))
        if FileManager.default.fileExists(atPath: file.path) {
            guard let bytes = try? Data(contentsOf: file), let old = try? JSONSerialization.jsonObject(with: bytes) as? [String: Any],
                  old["fingerprint"] as? String == fingerprint else { throw PhoneConnectorFailure(code: "operation_conflict") }
            guard old["status"] as? String == "completed", let result = old["result"] as? [String: Any] else { throw PhoneConnectorFailure(code: "outcome_unknown") }
            return result
        }
        var record: [String: Any] = ["tool": tool, "fingerprint": fingerprint, "status": "attempted"]
        if tool == "alarms.create" {
            record["result"] = ["id": alarmIdentifier(sessionID: sessionID, operationID: id).uuidString,
                "title": arguments["title"] ?? "", "fireDate": arguments["fireDate"] ?? "", "timeZone": arguments["timeZone"] ?? ""]
        }
        do { try save(record, to: file) } catch { throw PhoneConnectorFailure(code: "write_history_unavailable") }
        let result = try await operation()
        record["status"] = "completed"; record["result"] = result
        // Even if a task was cancelled after the system committed, persist its receipt before returning.
        do { try save(record, to: file) } catch { throw PhoneConnectorFailure(code: "outcome_unknown") }
        return result
    }
    func alarmReceipts() throws -> [String: [String: Any]] {
        guard FileManager.default.fileExists(atPath: directory.path) else { return [:] }
        var result: [String: [String: Any]] = [:]
        for file in try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil) where file.pathExtension == "json" {
            guard let bytes = try? Data(contentsOf: file), let row = try? JSONSerialization.jsonObject(with: bytes) as? [String: Any],
                  row["tool"] as? String == "alarms.create", ["attempted", "completed"].contains(row["status"] as? String ?? ""),
                  let receipt = row["result"] as? [String: Any], let id = receipt["id"] as? String else { continue }
            result[id.lowercased()] = receipt
        }
        return result
    }
}
