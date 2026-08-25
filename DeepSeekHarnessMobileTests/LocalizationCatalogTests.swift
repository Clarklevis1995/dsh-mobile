import XCTest
@testable import DeepSeekHarnessMobile

/// Guards the localization catalogs shipped in the app bundle: compiled
/// tables must exist, stay populated, and keep placeholder arguments intact,
/// so the UI never exposes localization identifiers to users.
final class LocalizationCatalogTests: XCTestCase {

    private func localizedTable(_ name: String, localization: String) throws -> [String: String] {
        guard let path = Bundle.main.path(forResource: name,
                                          ofType: "strings",
                                          inDirectory: nil,
                                          forLocalization: localization),
              let dict = NSDictionary(contentsOfFile: path) as? [String: String] else {
            return [:]
        }
        return dict
    }

    private func englishTable(_ name: String) throws -> [String: String] {
        try localizedTable(name, localization: "en")
    }

    func testEnglishLocalizableTableIsPresentAndPopulated() throws {
        let table = try englishTable("Localizable")
        XCTAssertFalse(table.isEmpty, "en.lproj/Localizable.strings is missing from the app bundle")
        XCTAssertGreaterThanOrEqual(table.count, 350, "English Localizable table looks truncated")

        let blank = table.filter { $0.value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
        XCTAssertTrue(blank.isEmpty, "Blank English translations for: \(blank.keys.sorted())")
        XCTAssertEqual(table["语言"], "Language")
        XCTAssertEqual(table["语言设置将在重新启动应用后生效。"],
                       "Language changes take effect after restarting the app.")
    }

    func testAppLanguagePersistsSystemAndExplicitLanguageChoices() throws {
        let suiteName = "AppLanguageTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }

        XCTAssertEqual(AppLanguage.load(from: defaults), .system)

        AppLanguage.english.apply(to: defaults)
        XCTAssertEqual(AppLanguage.load(from: defaults), .english)
        XCTAssertEqual(defaults.stringArray(forKey: "AppleLanguages"), ["en"])

        AppLanguage.simplifiedChinese.apply(to: defaults)
        XCTAssertEqual(AppLanguage.load(from: defaults), .simplifiedChinese)
        XCTAssertEqual(defaults.stringArray(forKey: "AppleLanguages"), ["zh-Hans"])

        AppLanguage.system.apply(to: defaults)
        XCTAssertEqual(AppLanguage.load(from: defaults), .system)
        XCTAssertNil(defaults.persistentDomain(forName: suiteName)?["AppleLanguages"])
    }

    func testFormatSpecifiersSurviveTranslation() {
        let table = try! englishTable("Localizable")
        XCTAssertEqual(table["session.remote.title"], "Remote session %@")
        XCTAssertEqual(table["history.loaded.detail"], "%lld events%@%@")
        XCTAssertTrue(table["gateway.connected.detail"]?.contains("%lld") ?? false)
    }

    func testChineseRuntimeValuesDoNotExposeLocalizationKeys() throws {
        let table = try localizedTable("Localizable", localization: "zh-Hans")
        XCTAssertFalse(table.isEmpty, "zh-Hans.lproj/Localizable.strings is missing from the app bundle")
        XCTAssertEqual(table["context.items.count"], "%lld 项上下文")
        XCTAssertEqual(table["stats.turns.steps.line"], "%lld 轮 · %lld 步")
        XCTAssertEqual(table["projection.context-injection"], "上下文注入 · %@")
    }

    func testEnglishInfoPlistStringsShipCameraAndLocalNetworkCopy() throws {
        let table = try englishTable("InfoPlist")
        XCTAssertNotNil(table["NSCameraUsageDescription"])
        XCTAssertNotNil(table["NSLocalNetworkUsageDescription"])
    }
}
