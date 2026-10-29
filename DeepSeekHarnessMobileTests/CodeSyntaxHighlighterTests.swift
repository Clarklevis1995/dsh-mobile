import XCTest
import SwiftUI
import MarkdownUI
import DeepSeekHarnessShared
@testable import DeepSeekHarnessMobile

final class CodeSyntaxHighlighterTests: XCTestCase {
    func testLanguageRulesProduceDifferentColorsWithoutChangingCode() throws {
        let source = "// 注释😀\r\n\tval answer = \"你好 return\"\r\n\treturn 42\r\n"
        let highlighter = DSHCodeSyntaxHighlighter(dark: false)
        let result = highlighter.attributedCode(source, language: "kotlin title=sample.kt")
        XCTAssertEqual(result.string, source)
        func color(_ token: String) throws -> UIColor {
            let location = (source as NSString).range(of: token).location
            return try XCTUnwrap(result.attribute(.foregroundColor, at: location, effectiveRange: nil) as? UIColor)
        }
        let colors = try [color("//"), color("val"), color("你好"), color("42")]
        XCTAssertEqual(Set(colors.map(\.description)).count, 4)
        let text = highlighter.highlightedCode(source, language: "kotlin")
        XCTAssertEqual(String(text.characters), source)
        XCTAssertTrue(text.runs.contains { $0.foregroundColor != nil }, "颜色必须转换成 SwiftUI 可绘制的属性")
        XCTAssertNil(DSHCodeSyntaxHighlighter().attributedCode("def", language: "swift")
            .attribute(.foregroundColor, at: 0, effectiveRange: nil))
    }

    func testAliasFallbackAndThemeCache() {
        let code = "def greet():\n\treturn \"你好😀\"\n"
        let light = DSHCodeSyntaxHighlighter(dark: false)
        let dark = DSHCodeSyntaxHighlighter(dark: true)
        let first = light.attributedCode(code, language: "py")
        XCTAssertTrue(first === light.attributedCode(code, language: "py"))
        XCTAssertEqual(first, light.attributedCode(code, language: "python"))
        XCTAssertNotEqual(first, dark.attributedCode(code, language: "python"))
        let plain = light.attributedCode(code, language: "unknown")
        XCTAssertEqual(plain.string, code)
        XCTAssertNil(plain.attribute(.foregroundColor, at: 0, effectiveRange: nil))
        XCTAssertFalse(light.attributedCode("{\"value\": true}", language: nil).isEqual(to:
            light.attributedCode("{\"value\": true}", language: "text")))
    }

    func testAllSupportedCodeBlocksHaveColoredTokens() {
        let fixtures = ["kotlin": "fun run() = 42", "swift": "func run() { return 42 }",
                        "python": "def run(): return 42", "java": "public class Test {}",
                        "cpp": "namespace app { const int value = 42; }", "javascript": "const x = true;",
                        "typescript": "interface Item { name: string }", "rust": "fn run() -> i32 { 42 }",
                        "go": "package main", "sql": "SELECT * FROM users WHERE id = 42",
                        "bash": "export NAME=\"你好\"", "json": "{\"value\": true}",
                        "yaml": "enabled: true", "html": "<div class=\"card\">Hello</div>",
                        "css": ".card {\n color: red;\n}", "latex": #"\frac{1}{2} % comment"#]
        for (language, code) in fixtures {
            let result = DSHCodeSyntaxHighlighter().highlightedCode(code, language: language)
            XCTAssertEqual(String(result.characters), code)
            XCTAssertTrue(result.runs.contains { $0.foregroundColor != nil }, language)
        }
    }

    func testSelectionPageKeepsHighlightingAndOriginalWhitespace() throws {
        let code = "\tdef greet():\n\t\treturn \"你好😀\"\n"
        let document = MarkdownUI.MarkdownContent("```python\n" + code + "```")
        let highlighter = DSHCodeSyntaxHighlighter()
        let result = document.renderSelectableText(font: .systemFont(ofSize: 17), codeHighlighter: highlighter.attributedCode)
        XCTAssertTrue(result.string.contains(code.trimmingCharacters(in: .newlines)))
        let position = (result.string as NSString).range(of: "def").location
        XCTAssertNotEqual(result.attribute(.foregroundColor, at: position, effectiveRange: nil) as? UIColor, UIColor.label)
        XCTAssertEqual((result.attribute(.font, at: position, effectiveRange: nil) as? UIFont)?.pointSize, 14)
        XCTAssertNotNil(result.attribute(SelectableCodeBlockStyle.blockID, at: position, effectiveRange: nil))
    }

    @MainActor
    func testRenderedCodeColorsInBothThemes() async throws {
        let source = "```kotlin\n// Kotlin 示例\nfun greet(name: String): String {\n    val message = \"你好😀\"\n    return message + name + 42\n}\n```\n\n```python\n# Python 示例\ndef greet(name):\n    return \"hello\" + name\n```\n\n```latex\n\\frac{1}{2} % LaTeX 源码\n```"
        for dark in [false, true] {
            let host = UIHostingController(rootView: DeepSeekHarnessMobile.MarkdownContent(source)
                .environment(\.colorScheme, dark ? .dark : .light).padding(16)
                .frame(width: 390, alignment: .leading))
            let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 850))
            window.rootViewController = host
            window.isHidden = false
            host.view.frame = window.bounds
            host.view.backgroundColor = dark ? .black : .white
            try await Task.sleep(for: .milliseconds(150))
            host.view.layoutIfNeeded()
            let image = UIGraphicsImageRenderer(size: window.bounds.size).image { _ in
                host.view.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
            }
            let cgImage = try XCTUnwrap(image.cgImage)
            var bytes = [UInt8](repeating: 0, count: cgImage.width * cgImage.height * 4)
            let context = try XCTUnwrap(CGContext(data: &bytes, width: cgImage.width, height: cgImage.height,
                bitsPerComponent: 8, bytesPerRow: cgImage.width * 4, space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
            context.draw(cgImage, in: CGRect(x: 0, y: 0, width: cgImage.width, height: cgImage.height))
            let colorfulPixels = stride(from: 0, to: bytes.count, by: 4).filter { index in
                Int(max(bytes[index], bytes[index + 1], bytes[index + 2])) - Int(min(bytes[index], bytes[index + 1], bytes[index + 2])) > 40
            }.count
            XCTAssertGreaterThan(colorfulPixels, 300, "代码必须真正绘制彩色字形")
            let attachment = XCTAttachment(image: image)
            attachment.name = "syntax-\(dark ? "dark" : "light")"
            attachment.lifetime = .keepAlways
            add(attachment)
            try image.pngData()?.write(to: URL(fileURLWithPath: "/private/tmp/dsh-syntax-ios-\(dark ? "dark" : "light").png"))
            window.isHidden = true
        }
    }
}
