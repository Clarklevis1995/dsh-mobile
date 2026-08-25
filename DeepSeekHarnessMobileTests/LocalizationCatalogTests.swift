import XCTest
@testable import DeepSeekHarnessMobile

/// Guards the localization catalogs shipped in the app bundle: the compiled
/// English tables must exist, stay populated, and keep their placeholder
/// arguments intact, so an English device never silently falls back to
/// source-language text.
final class LocalizationCatalogTests: XCTestCase {

    private func englishTable(_ name: String) throws -> [String: String] {
        guard let path = Bundle.main.path(forResource: name,
                                          ofType: "strings",
                                          inDirectory: nil,
                                          forLocalization: "en"),
              let dict = NSDictionary(contentsOfFile: path) as? [String: String] else {
            return [:]
        }
        return dict
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

    func testEnglishInfoPlistStringsShipCameraAndLocalNetworkCopy() throws {
        let table = try englishTable("InfoPlist")
        XCTAssertNotNil(table["NSCameraUsageDescription"])
        XCTAssertNotNil(table["NSLocalNetworkUsageDescription"])
    }
}
