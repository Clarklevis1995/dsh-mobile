import XCTest
import UIKit
import SwiftUI
@testable import DeepSeekHarnessMobile

/// Guards the localization catalogs shipped in the app bundle: compiled
/// tables must exist, stay populated, and keep placeholder arguments intact,
/// so the UI never exposes localization identifiers to users.
final class LocalizationCatalogTests: XCTestCase {
    func testAllWorkspaceCopyShipsInBothLanguagesAndPreservesCountsAndWorkspaceNames() throws {
        let english = try englishTable("Localizable")
        let chinese = try localizedTable("Localizable", localization: "zh-Hans")
        XCTAssertEqual(english["请先选择工作区"], "Select a workspace first")
        XCTAssertEqual(english["请先选择一个具体工作区，再创建新会话。"],
            "Select a specific workspace before creating a new session.")
        XCTAssertEqual(chinese["请先选择一个具体工作区，再创建新会话。"], "请先选择一个具体工作区，再创建新会话。")
        XCTAssertEqual(english["全部会话"], "All sessions")
        XCTAssertEqual(english["收起"], "Collapse")
        XCTAssertEqual(english["workspace.session.count.one"], "1 session")
        XCTAssertEqual(english["workspace.section.expand.one"], "Show 1 more session")
        XCTAssertEqual(String(format: try XCTUnwrap(english["workspace.section.expand"]), 95), "Show 95 more sessions")
        XCTAssertEqual(String(format: try XCTUnwrap(english["workspace.section.named-accessibility"]), "dsh-mobile", 7),
            "Workspace dsh-mobile, 7 sessions")
        XCTAssertEqual(String(format: try XCTUnwrap(chinese["workspace.section.named-accessibility"]), "移动端工程", 7),
            "工作区 移动端工程，7 个会话")
    }

    func testFontScaleNormalizesInvalidValues() {
        XCTAssertEqual(AppFontScale.normalized(.nan), 1)
        XCTAssertEqual(AppFontScale.normalized(.infinity), 1)
        XCTAssertEqual(AppFontScale.normalized(0), 0.8)
        XCTAssertEqual(AppFontScale.normalized(4), 1.5)
    }

    @MainActor
    func testNativeFontScaleAppliesToBodyAndCodeFonts() {
        let previous = UserDefaults.standard.object(forKey: AppFontScale.key)
        let previousFollowsSystem = UserDefaults.standard.object(forKey: AppFontScale.followsSystemKey)
        UserDefaults.standard.set(false, forKey: AppFontScale.followsSystemKey)
        defer {
            if let previous { UserDefaults.standard.set(previous, forKey: AppFontScale.key) }
            else { UserDefaults.standard.removeObject(forKey: AppFontScale.key) }
            if let previousFollowsSystem { UserDefaults.standard.set(previousFollowsSystem, forKey: AppFontScale.followsSystemKey) }
            else { UserDefaults.standard.removeObject(forKey: AppFontScale.followsSystemKey) }
        }
        UserDefaults.standard.set(1.0, forKey: AppFontScale.key)
        let body = UIFont.appPreferredFont(forTextStyle: .body).pointSize
        let code = UIFont.appMonospacedFont(ofSize: 13, weight: .regular).pointSize
        UserDefaults.standard.set(1.5, forKey: AppFontScale.key)
        XCTAssertEqual(UIFont.appPreferredFont(forTextStyle: .body).pointSize, body * 1.5, accuracy: 0.01)
        XCTAssertEqual(UIFont.appMonospacedFont(ofSize: 13, weight: .regular).pointSize, code * 1.5, accuracy: 0.01)
        UserDefaults.standard.set(true, forKey: AppFontScale.followsSystemKey)
        XCTAssertEqual(UIFont.appPreferredFont(forTextStyle: .body).pointSize, body, accuracy: 0.01)
        XCTAssertEqual(UIFont.appMonospacedFont(ofSize: 13, weight: .regular).pointSize, code, accuracy: 0.01)
        XCTAssertEqual(UserDefaults.standard.double(forKey: AppFontScale.key), 1.5)
    }

    @MainActor
    func testMarkdownAppliesFontScaleOnlyOnce() {
        let defaults = UserDefaults.standard
        let previous = defaults.object(forKey: AppFontScale.key)
        let previousFollowsSystem = defaults.object(forKey: AppFontScale.followsSystemKey)
        defer {
            if let previous { defaults.set(previous, forKey: AppFontScale.key) }
            else { defaults.removeObject(forKey: AppFontScale.key) }
            if let previousFollowsSystem { defaults.set(previousFollowsSystem, forKey: AppFontScale.followsSystemKey) }
            else { defaults.removeObject(forKey: AppFontScale.followsSystemKey) }
        }
        defaults.set(false, forKey: AppFontScale.followsSystemKey)
        func height(scale: Double) -> CGFloat {
            defaults.set(scale, forKey: AppFontScale.key)
            let host = UIHostingController(rootView: MarkdownContent("字号预览").modifier(AppFontScope()))
            return host.sizeThatFits(in: CGSize(width: 400, height: CGFloat.greatestFiniteMagnitude)).height
        }
        let standard = height(scale: 1)
        let larger = height(scale: 1.5)
        XCTAssertGreaterThan(standard, 0)
        XCTAssertGreaterThan(larger / standard, 1.3)
        XCTAssertLessThan(larger / standard, 1.7)
    }

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
        XCTAssertGreaterThanOrEqual(table.count, 600, "English Localizable table looks truncated")

        let blank = table.filter { $0.value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
        XCTAssertTrue(blank.isEmpty, "Blank English translations for: \(blank.keys.sorted())")
        XCTAssertEqual(table["语言"], "Language")
        XCTAssertEqual(table["语言设置将在重新启动应用后生效。"],
                       "Language changes take effect after restarting the app.")
        XCTAssertEqual(table["保存"], "Save")
        XCTAssertEqual(table["探索未至之境"], "Into the Unknown")
        XCTAssertEqual(table["编辑定时任务"], "Edit scheduled task")
        XCTAssertEqual(table["请连接网关后重试。"], "Connect to the gateway and try again.")
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
        XCTAssertEqual(table["assistant.interrupted"], "生成已中断")
        XCTAssertEqual(table["control.request.rejected"], "服务端未确认 %@ 请求。")
        XCTAssertEqual(table["Input"], "输入")
    }

    func testAgentNotificationCopyIsReadableInChinese() throws {
        let table = try localizedTable("Localizable", localization: "zh-Hans")
        XCTAssertEqual(table["agent.notification.approval.title"], "需要审批")
        XCTAssertEqual(table["agent.notification.approval.body"], "%@：%@")
        XCTAssertEqual(table["agent.notification.approval.body.generic"], "%@：Agent 请求批准本次操作")
        XCTAssertEqual(table["agent.notification.execution.completed.title"], "执行完成")
        XCTAssertEqual(table["agent.notification.execution.completed.body"], "%@：Agent 已完成本次任务")
        XCTAssertEqual(table["agent.notification.execution.failed.title"], "执行失败")
        XCTAssertEqual(table["agent.notification.execution.failed.body"], "%@：打开 App 查看执行结果")

        let sessionTitle = "模拟器测试会话"
        let chinese = Locale(identifier: "zh-Hans")
        XCTAssertEqual(
            String(
                localized: "agent.notification.execution.completed.title",
                defaultValue: "执行完成",
                bundle: .main,
                locale: chinese
            ),
            "执行完成"
        )
        XCTAssertEqual(
            String(
                localized: "agent.notification.execution.completed.body",
                defaultValue: "\(sessionTitle)：Agent 已完成本次任务",
                bundle: .main,
                locale: chinese
            ),
            "模拟器测试会话：Agent 已完成本次任务"
        )
    }

    func testScheduledTaskRuleResolvesInEnglish() throws {
        let weekdays = "Mon, Wed"
        let time = "08:00"
        let zone = "Asia/Shanghai"
        // The locale argument formats substitutions; the bundle chooses the translated table.
        let englishPath = try XCTUnwrap(Bundle.main.path(forResource: "en", ofType: "lproj"))
        let englishBundle = try XCTUnwrap(Bundle(path: englishPath))
        XCTAssertEqual(
            String(
                localized: "agent.notification.execution.completed.title",
                defaultValue: "执行完成",
                bundle: englishBundle,
                locale: Locale(identifier: "en")
            ),
            "Run completed"
        )
        XCTAssertEqual(
            String(localized: "schedule.weekly.detail", bundle: englishBundle, locale: Locale(identifier: "en")),
            "Every %@ at %@ · %@"
        )
        XCTAssertEqual(
            String(
                localized: "schedule.weekly.detail",
                defaultValue: "每周 \(weekdays) · \(time) · \(zone)",
                bundle: englishBundle,
                locale: Locale(identifier: "en")
            ),
            "Every Mon, Wed at 08:00 · Asia/Shanghai"
        )
    }

    func testEnglishInfoPlistStringsShipCameraAndLocalNetworkCopy() throws {
        let table = try englishTable("InfoPlist")
        XCTAssertNotNil(table["NSCameraUsageDescription"])
        XCTAssertNotNil(table["NSLocalNetworkUsageDescription"])
    }

    @MainActor
    func testCertificateAdviceOnlyAppearsForCertificateTrustFailures() throws {
        let chinese = try localizedTable("Localizable", localization: "zh-Hans")
        let english = try englishTable("Localizable")
        XCTAssertEqual(chinese["gateway.certificate-trust.advice"],
                       "如果确认这是安全的个人服务器，请在网关中更新公网配置，并确保其公网证书有效后重试。")
        XCTAssertTrue(english["gateway.certificate-trust.advice"]?.contains("public access configuration") == true)

        let certificateError = NSError(domain: NSURLErrorDomain, code: NSURLErrorServerCertificateUntrusted)
        let offlineError = NSError(domain: NSURLErrorDomain, code: NSURLErrorNotConnectedToInternet)
        XCTAssertNotNil(GatewayClient.certificateTrustAdvice(for: certificateError))
        XCTAssertNil(GatewayClient.certificateTrustAdvice(for: offlineError))
    }
}
