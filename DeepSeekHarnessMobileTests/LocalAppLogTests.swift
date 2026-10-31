import XCTest
@testable import DeepSeekHarnessMobile

final class LocalAppLogTests: XCTestCase {
    func testNestedResidualFilesCountAgainstProductionLimit() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let nested = directory.appendingPathComponent("abandoned/nested", isDirectory: true)
        try FileManager.default.createDirectory(at: nested, withIntermediateDirectories: true)
        let residual = nested.appendingPathComponent("export.partial")
        try Data().write(to: residual)
        let handle = try FileHandle(forWritingTo: residual)
        try handle.truncate(atOffset: 200_000_001)
        try handle.close()
        let store = LocalAppLog(directory: directory)
        store.write("latest")
        let snapshot = try await store.export()
        XCTAssertFalse(FileManager.default.fileExists(atPath: residual.path))
        let text = try snapshot.map { try String(contentsOf: $0, encoding: .utf8) }.joined()
        XCTAssertTrue(text.contains("latest"))
        let urls = FileManager.default.enumerator(at: directory, includingPropertiesForKeys: [.fileSizeKey])!
            .compactMap { $0 as? URL }
        let bytes = try urls.reduce(0) { $0 + (try $1.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0) }
        XCTAssertLessThanOrEqual(bytes, 200_000_000)
    }

    func testHourlyRotationAndBoundedExport() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = LocalAppLog(directory: directory, limit: 2_000_000)
        let date = Date(timeIntervalSince1970: 1_800_000_000)
        store.write("first", at: date)
        store.write("same-hour", at: date.addingTimeInterval(60))
        store.write("next-hour", at: date.addingTimeInterval(3600))
        let snapshot = try await store.export()
        XCTAssertEqual(snapshot.count, 2)
        XCTAssertTrue(snapshot.allSatisfy {
            $0.lastPathComponent.range(of: #"^\d{10}00\.log$"#, options: .regularExpression) != nil
        })
        let text = try snapshot.map { try String(contentsOf: $0, encoding: .utf8) }.joined()
        XCTAssertTrue(text.contains("same-hour"))
        XCTAssertTrue(text.contains("next-hour"))
        for index in 0..<160 {
            store.write("\(index) " + String(repeating: "x", count: 16_000),
                        at: date.addingTimeInterval(Double(index) * 3600))
        }
        _ = try await store.export()
        let urls = FileManager.default.enumerator(at: directory, includingPropertiesForKeys: [.fileSizeKey])!
            .compactMap { $0 as? URL }
        let total = try urls.reduce(0) { $0 + (try $1.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0) }
        XCTAssertLessThanOrEqual(total, 2_000_000)
    }
}
