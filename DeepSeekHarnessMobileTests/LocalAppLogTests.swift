import XCTest
@testable import DeepSeekHarnessMobile

final class LocalAppLogTests: XCTestCase {
    func testRangeSummaryAndArchiveExcludeOtherRecordsInSameHour() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = LocalAppLog(directory: directory)
        let base = Date(timeIntervalSince1970: 1_800_000_000)
        store.write("before", at: base)
        store.write("inside\ncontinuation", at: base.addingTimeInterval(60))
        store.write("end-exclusive", at: base.addingTimeInterval(120))
        let interval = DateInterval(start: base.addingTimeInterval(60), duration: 60)
        let summary = try await store.summary(in: interval)
        XCTAssertEqual(summary.fileCount, 1)
        XCTAssertEqual(summary.earliest, base)
        let url = try await store.exportArchive(in: interval)
        let data = try Data(contentsOf: url)
        XCTAssertEqual(Array(data.prefix(4)), [0x50, 0x4b, 0x03, 0x04])
        XCTAssertNotNil(data.range(of: Data("inside\ncontinuation".utf8)))
        XCTAssertNil(data.range(of: Data("before".utf8)))
        XCTAssertNil(data.range(of: Data("end-exclusive".utf8)))
        let empty = try await store.summary(in: DateInterval(start: base.addingTimeInterval(180), duration: 60))
        XCTAssertEqual(empty.fileCount, 0)
        do {
            _ = try await store.exportArchive(in: DateInterval(start: base.addingTimeInterval(180), duration: 60))
            XCTFail("空时间段应拒绝导出")
        } catch {}
    }

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
