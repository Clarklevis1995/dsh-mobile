import Foundation
import CryptoKit
import SQLite3

/// 地点标记与操作回执同事务保存；主机之间使用独立的私有数据库。
@MainActor
final class PhoneMapMarkerStore {
    static let tools = ["maps.markers.create", "maps.markers.list", "maps.markers.update", "maps.markers.delete"]
    private let file: URL
    private var database: OpaquePointer?
    private enum Value { case text(String), number(Double), integer(Int64) }
    private static let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

    init(hostID: String, root: URL? = nil) {
        let root = root ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("phone-map-markers", isDirectory: true)
        let key = SHA256.hash(data: Data(hostID.utf8)).map { String(format: "%02x", $0) }.joined()
        file = root.appendingPathComponent(key + ".sqlite")
    }
    deinit { if let database { sqlite3_close(database) } }
    private func connection() throws -> OpaquePointer {
        if let database { return database }
        do {
            try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true,
                attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication, .posixPermissions: 0o700])
        } catch { throw PhoneConnectorFailure(code: "marker_store_unavailable") }
        var handle: OpaquePointer?
        guard sqlite3_open_v2(file.path, &handle, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE, nil) == SQLITE_OK, let handle else {
            if let handle { sqlite3_close(handle) }; throw PhoneConnectorFailure(code: "marker_store_unavailable")
        }
        do {
            sqlite3_busy_timeout(handle, 1000)
            let schema = """
                CREATE TABLE IF NOT EXISTS markers (
                  id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, latitude REAL NOT NULL, longitude REAL NOT NULL,
                  address TEXT NOT NULL, notes TEXT NOT NULL, revision INTEGER NOT NULL, created REAL NOT NULL, updated REAL NOT NULL
                );
                CREATE TABLE IF NOT EXISTS receipts (id TEXT PRIMARY KEY NOT NULL, fingerprint TEXT NOT NULL, result TEXT NOT NULL);
                CREATE INDEX IF NOT EXISTS markers_updated ON markers(updated DESC, id ASC);
                """
            guard sqlite3_exec(handle, schema, nil, nil, nil) == SQLITE_OK else { throw PhoneConnectorFailure(code: "marker_store_unavailable") }
            try FileManager.default.setAttributes([.posixPermissions: 0o600, .protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: file.path)
            database = handle
            return handle
        } catch { sqlite3_close(handle); throw PhoneConnectorFailure(code: "marker_store_unavailable") }
    }
    private func statement(_ sql: String, _ values: [Value] = []) throws -> OpaquePointer {
        let db = try connection(); var statement: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &statement, nil) == SQLITE_OK, let statement else { throw PhoneConnectorFailure(code: "marker_store_unavailable") }
        for (index, value) in values.enumerated() {
            let result: Int32
            switch value {
            case .text(let value): result = value.withCString { sqlite3_bind_text(statement, Int32(index + 1), $0, -1, Self.transient) }
            case .number(let value): result = sqlite3_bind_double(statement, Int32(index + 1), value)
            case .integer(let value): result = sqlite3_bind_int64(statement, Int32(index + 1), value)
            }
            if result != SQLITE_OK { sqlite3_finalize(statement); throw PhoneConnectorFailure(code: "marker_store_unavailable") }
        }
        return statement
    }
    private func executeSQL(_ sql: String, _ values: [Value] = []) throws {
        let statement = try statement(sql, values); defer { sqlite3_finalize(statement) }
        guard sqlite3_step(statement) == SQLITE_DONE else { throw PhoneConnectorFailure(code: "marker_store_unavailable") }
    }
    private func string(_ row: OpaquePointer, _ column: Int32) -> String { sqlite3_column_text(row, column).map { String(cString: $0) } ?? "" }
    private func marker(_ row: OpaquePointer) -> [String: Any] {
        let formatter = ISO8601DateFormatter(); formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return ["id": string(row, 0), "name": string(row, 1), "latitude": sqlite3_column_double(row, 2), "longitude": sqlite3_column_double(row, 3),
                "address": string(row, 4), "notes": string(row, 5), "revision": Int(sqlite3_column_int64(row, 6)),
                "createdAt": formatter.string(from: Date(timeIntervalSince1970: sqlite3_column_double(row, 7))),
                "updatedAt": formatter.string(from: Date(timeIntervalSince1970: sqlite3_column_double(row, 8)))]
    }
    private func find(_ id: String) throws -> [String: Any]? {
        let row = try statement("SELECT * FROM markers WHERE id=?", [.text(id)]); defer { sqlite3_finalize(row) }
        switch sqlite3_step(row) {
        case SQLITE_ROW: return marker(row)
        case SQLITE_DONE: return nil
        default: throw PhoneConnectorFailure(code: "marker_store_unavailable")
        }
    }
    private static func text(_ value: Any?, max: Int, empty: Bool = false) -> Bool {
        guard let value = value as? String else { return false }
        return !value.contains("\0") && value.utf16.count <= max && (empty || !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
    }
    static func validate(_ tool: String, _ args: [String: Any]) throws {
        let allowed: Set<String>
        switch tool {
        case "maps.markers.create": allowed = ["operationId", "name", "latitude", "longitude", "address", "notes"]
        case "maps.markers.list": allowed = ["query", "offset", "limit"]
        case "maps.markers.update": allowed = ["operationId", "markerId", "name", "latitude", "longitude", "address", "notes", "expectedRevision"]
        case "maps.markers.delete": allowed = ["operationId", "markerId", "expectedRevision"]
        default: throw PhoneConnectorFailure(code: "tool_unsupported")
        }
        guard Set(args.keys).isSubset(of: allowed) else { throw PhoneConnectorFailure(code: "invalid_request") }
        if tool == "maps.markers.list" {
            if let query = args["query"], !text(query, max: 200) { throw PhoneConnectorFailure(code: "invalid_request") }
            for (key, range) in [("offset", 0.0...Double(Int32.max)), ("limit", 1.0...20.0)] {
                if let value = args[key] { guard let value = IOSMapConnector.number(value, range: range), value.rounded() == value else { throw PhoneConnectorFailure(code: "invalid_request") } }
            }
            return
        }
        guard let operation = args["operationId"] as? String, UUID(uuidString: operation) != nil else { throw PhoneConnectorFailure(code: "invalid_request") }
        if tool != "maps.markers.create" {
            guard let id = args["markerId"] as? String, UUID(uuidString: id) != nil else { throw PhoneConnectorFailure(code: "invalid_request") }
        }
        if tool == "maps.markers.create" || args["name"] != nil {
            guard text(args["name"], max: 200) else { throw PhoneConnectorFailure(code: "invalid_request") }
        }
        if tool == "maps.markers.create" || args["latitude"] != nil || args["longitude"] != nil { _ = try IOSMapConnector.coordinate(args) }
        if let address = args["address"], !text(address, max: 400, empty: true) { throw PhoneConnectorFailure(code: "invalid_request") }
        if let notes = args["notes"], !text(notes, max: 1000, empty: true) { throw PhoneConnectorFailure(code: "invalid_request") }
        if let value = args["expectedRevision"] { guard let value = IOSMapConnector.number(value, range: 1...Double(Int32.max)), value.rounded() == value else { throw PhoneConnectorFailure(code: "invalid_request") } }
        if tool == "maps.markers.update" && Set(args.keys).isDisjoint(with: ["name", "latitude", "longitude", "address", "notes"]) { throw PhoneConnectorFailure(code: "invalid_request") }
    }
    private func list(_ args: [String: Any]) throws -> [String: Any] {
        let offset = args["offset"] as? Int ?? 0, limit = args["limit"] as? Int ?? 20
        var filter = "", values: [Value] = []
        if let query = args["query"] as? String {
            let pattern = "%" + query.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "%", with: "\\%").replacingOccurrences(of: "_", with: "\\_") + "%"
            filter = #" WHERE name LIKE ? ESCAPE '\' OR address LIKE ? ESCAPE '\' OR notes LIKE ? ESCAPE '\'"#
            values = [.text(pattern), .text(pattern), .text(pattern)]
        }
        let count = try statement("SELECT COUNT(*) FROM markers" + filter, values); defer { sqlite3_finalize(count) }
        guard sqlite3_step(count) == SQLITE_ROW else { throw PhoneConnectorFailure(code: "marker_store_unavailable") }
        let total = Int(sqlite3_column_int64(count, 0))
        let rows = try statement("SELECT * FROM markers" + filter + " ORDER BY updated DESC,id ASC LIMIT ? OFFSET ?", values + [.integer(Int64(limit)), .integer(Int64(offset))]); defer { sqlite3_finalize(rows) }
        var items: [[String: Any]] = []
        while true {
            let step = sqlite3_step(rows)
            if step == SQLITE_DONE { break }
            guard step == SQLITE_ROW else { throw PhoneConnectorFailure(code: "marker_store_unavailable") }
            items.append(marker(rows))
        }
        let hasMore = offset + items.count < total
        return ["items": items, "total": total, "offset": offset, "limit": limit, "hasMore": hasMore,
                "nextOffset": hasMore ? offset + items.count : NSNull(), "source": "whaleness-host-markers"]
    }
    func execute(_ tool: String, _ args: [String: Any], sessionID: String = "") throws -> [String: Any] {
        try Self.validate(tool, args)
        if tool == "maps.markers.list" { return try list(args) }
        guard !sessionID.isEmpty, sessionID.utf16.count <= 256 else { throw PhoneConnectorFailure(code: "invalid_request") }
        let operationID = args["operationId"] as! String
        let key = SHA256.hash(data: try JSONSerialization.data(withJSONObject: [sessionID, tool, operationID.lowercased()])).map { String(format: "%02x", $0) }.joined()
        let fingerprint = SHA256.hash(data: try JSONSerialization.data(withJSONObject: args, options: [.sortedKeys])).map { String(format: "%02x", $0) }.joined()
        try executeSQL("BEGIN IMMEDIATE")
        do {
            let receipt = try statement("SELECT fingerprint,result FROM receipts WHERE id=?", [.text(key)])
            let step = sqlite3_step(receipt)
            if step == SQLITE_ROW {
                let oldFingerprint = string(receipt, 0), json = string(receipt, 1); sqlite3_finalize(receipt)
                guard oldFingerprint == fingerprint else { throw PhoneConnectorFailure(code: "operation_conflict") }
                guard var result = try JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any] else { throw PhoneConnectorFailure(code: "marker_store_unavailable") }
                result["replayed"] = true
                try executeSQL("COMMIT"); return result
            }
            sqlite3_finalize(receipt)
            guard step == SQLITE_DONE else { throw PhoneConnectorFailure(code: "marker_store_unavailable") }
            var result: [String: Any] = ["operationId": operationID, "source": "whaleness-host-markers", "replayed": false]
            let now = Date().timeIntervalSince1970
            if tool == "maps.markers.create" {
                let id = UUID().uuidString.lowercased()
                try executeSQL("INSERT INTO markers VALUES(?,?,?,?,?,?,1,?,?)", [.text(id), .text((args["name"] as! String).trimmingCharacters(in: .whitespacesAndNewlines)),
                    .number((args["latitude"] as! NSNumber).doubleValue), .number((args["longitude"] as! NSNumber).doubleValue), .text(args["address"] as? String ?? ""), .text(args["notes"] as? String ?? ""), .number(now), .number(now)])
                guard let saved = try find(id) else { throw PhoneConnectorFailure(code: "marker_store_unavailable") }; result["marker"] = saved; result["status"] = "created"
            } else {
                let id = (args["markerId"] as! String).lowercased(), old = try find(id)
                if let expected = args["expectedRevision"] as? Int, old?["revision"] as? Int != expected { throw PhoneConnectorFailure(code: "marker_revision_conflict") }
                if tool == "maps.markers.delete" {
                    try executeSQL("DELETE FROM markers WHERE id=?", [.text(id)])
                    result["markerId"] = id; result["status"] = "deleted"; result["existed"] = old != nil
                } else {
                    guard let old else { throw PhoneConnectorFailure(code: "marker_not_found") }
                    try executeSQL("UPDATE markers SET name=?,latitude=?,longitude=?,address=?,notes=?,revision=revision+1,updated=? WHERE id=?", [
                        .text(((args["name"] ?? old["name"]) as! String).trimmingCharacters(in: .whitespacesAndNewlines)),
                        .number(((args["latitude"] ?? old["latitude"]) as! NSNumber).doubleValue), .number(((args["longitude"] ?? old["longitude"]) as! NSNumber).doubleValue),
                        .text((args["address"] ?? old["address"]) as! String), .text((args["notes"] ?? old["notes"]) as! String), .number(now), .text(id)])
                    guard let saved = try find(id) else { throw PhoneConnectorFailure(code: "marker_store_unavailable") }; result["marker"] = saved; result["status"] = "updated"
                }
            }
            let json = String(decoding: try JSONSerialization.data(withJSONObject: result, options: [.sortedKeys]), as: UTF8.self)
            try executeSQL("INSERT INTO receipts VALUES(?,?,?)", [.text(key), .text(fingerprint), .text(json)])
            try executeSQL("COMMIT"); return result
        } catch { try? executeSQL("ROLLBACK"); throw error }
    }
}
