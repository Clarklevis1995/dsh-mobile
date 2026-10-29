import XCTest
import SwiftUI
import SwiftMath
#if DEBUG
@testable import MarkdownUI
#else
import MarkdownUI
#endif
@testable import DeepSeekHarnessMobile

@MainActor
final class MarkdownMathTests: XCTestCase {
    private let recurrence = #"I_n=\begin{cases}1,&n=1,\\[2pt]6,&n=2,\\[2pt]\left\lfloor I_{n-1}\cdot E\right\rceil,&n\ge3,\end{cases}\quad E\leftarrow\max\!\Bigl(1.3,\;E+0.1-(5-q)\bigl(0.08+0.02(5-q)\bigr)\Bigr)"#

    func testFourDelimitersPreserveFormulaBeforeCommonMarkEscapes() {
        for (source, display) in [(#"\(x_1^2\)"#, false), (#"$x_1^2$"#, false),
                                  (#"\[x_1^2\]"#, true), (#"$$x_1^2$$"#, true)] {
            let prepared = MarkdownMath.prepare(source)
            let expected = MarkdownMath.Formula(latex: "x_1^2", display: display)
            XCTAssertTrue(prepared.contains(expected.url.absoluteString))
            XCTAssertEqual(MarkdownMath.Formula(url: expected.url), expected)
            let html = PreparedMarkdownCache.document(for: source, filePaths: []).renderHTML()
            XCTAssertTrue(html.contains("<img"))
            XCTAssertTrue(html.contains("dsh-math:"))
        }
    }

    func testFormulaURLPreservesUnicodeBackslashesAndNewlines() {
        let formula = MarkdownMath.Formula(latex: "\\text{中文} + α\n\\frac{1}{2}", display: true)
        XCTAssertEqual(MarkdownMath.Formula(url: formula.url), formula)
        XCTAssertNil(MarkdownMath.Formula(url: URL(string: "https://example.com/image.png")))
        XCTAssertNil(MarkdownMath.Formula(url: URL(string: "dsh-math://display/%25")))
    }

    func testCodeSamplesEscapedDollarsAndPricesRemainUnchanged() {
        let fixtures = [
            #"`$x$` 和 ``\(a`b\)``"#,
            "```latex\n\\[x^2\\]\n$x$\n```",
            "~~~\n$$x$$\n~~~",
            "> ```latex\n> \\(x\\)\n> ```",
            "    $x$\n\t\\(x\\)",
            #"价格 $5 和 $10，转义 \$x\$"#,
            #"[地址](https://example.com/$x$/\(y\))"#,
            #"[地址]: https://example.com/$x$/\(y\)"#,
            #"![图片里的 $x$ 和 \(y\)](https://example.com/$image$.png)"#,
            #"参考 https://example.com/$x$ ，下一段文字。"#,
            #"<https://example.com/$x$> <span data-value="$x$">"#
        ]
        for source in fixtures { XCTAssertEqual(MarkdownMath.prepare(source), source, source) }
    }

    func testIncompleteDelimitersStayReadableUntilClosed() {
        for source in [#"开始 \[x_1"#, #"结果 \(x + 1"#, #"结果 $$x + 1"#, "费用 $5", "$ x $", "$x\ny$"] {
            XCTAssertEqual(MarkdownMath.prepare(source), source)
        }
        let source = #"先 $x$，后 \(y\)；最后 \[z\]"#
        XCTAssertEqual(MarkdownMath.prepare(source).components(separatedBy: "dsh-math:").count - 1, 3)
    }

    func testLatexSourceCodeAndFileReferencesSurvivePreparation() {
        let source = "公式 \\(x_1\\) 和 `report.pdf`\n\n```latex\n" + recurrence + "\n```"
        let html = PreparedMarkdownCache.document(for: source, filePaths: ["report.pdf"]).renderHTML()
        XCTAssertTrue(html.contains("dsh-math:"))
        XCTAssertTrue(html.contains("dsh-file-reference:///0"))
        XCTAssertTrue(html.contains("language-latex"))
        XCTAssertTrue(html.contains(#"\Bigl"#))
    }

    func testAbsoluteValueInTableDoesNotCreateExtraColumns() {
        let source = "| 名称 | 公式 |\n| --- | --- |\n| 绝对值 | $|x|$ |"
        let document = PreparedMarkdownCache.document(for: source, filePaths: [])
        let html = document.renderHTML()
        XCTAssertTrue(html.contains("<table>"))
        XCTAssertEqual(document.tableCells?.count, 2)
        XCTAssertEqual(document.tableCells?.last?.count, 2)
        XCTAssertTrue(html.contains(MarkdownMath.Formula(latex: "|x|", display: false).url.absoluteString))
    }

    func testCopyAndSelectionPreserveExactMathSource() {
        let formula = "\\[\n" + recurrence + "\n\\]"
        let source = "**分段函数**\n\n" + formula + "\n\n另一个 $x_1^2$。"
        let document = MarkdownUI.MarkdownContent(MarkdownMath.prepare(source, forSelection: true))
        XCTAssertTrue(document.renderPlainText().contains(formula))
        XCTAssertTrue(document.renderPlainText().contains("$x_1^2$"))
        XCTAssertTrue(document.renderSelectableText(font: .systemFont(ofSize: 17)).string.contains(formula))
        XCTAssertFalse(document.renderPlainText().contains("dsh-math:"))
    }

    func testQuotedDisplayFormulaDoesNotIncludeMarkdownQuoteMarkers() {
        let source = "> 公式\n> \\[\n> x^2 + y^2\n> \\]\n> 结束"
        let prepared = MarkdownMath.prepare(source)
        XCTAssertTrue(prepared.contains(MarkdownMath.Formula(latex: "x^2 + y^2", display: true).url.absoluteString))
        XCTAssertTrue(prepared.hasPrefix("> 公式\n> "))
        XCTAssertTrue(MarkdownMath.prepare(#"a < b，公式 $x^2$，c > d"#).contains("dsh-math:"))
    }

    func testDisplayMathWithoutBlankLinesKeepsHorizontalScrolling() async throws {
        let source = "前面的正文\n\\[" + recurrence + "\\]\n后面的正文"
        let host = UIHostingController(rootView: DeepSeekHarnessMobile.MarkdownContent(source)
            .padding(16).frame(width: 360, alignment: .leading))
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 360, height: 600))
        window.rootViewController = host
        window.isHidden = false
        defer { window.isHidden = true }
        host.view.frame = window.bounds
        try await Task.sleep(for: .milliseconds(200))
        host.view.layoutIfNeeded()
        func scrollViews(_ view: UIView) -> [UIScrollView] {
            (view as? UIScrollView).map { [$0] } ?? view.subviews.flatMap(scrollViews)
        }
        let scroll = try XCTUnwrap(scrollViews(host.view).first { $0.contentSize.width > $0.bounds.width + 10 })
        scroll.setContentOffset(CGPoint(x: 30, y: 0), animated: false)
        XCTAssertEqual(scroll.contentOffset.x, 30, accuracy: 1)
        XCTAssertLessThan(host.sizeThatFits(in: CGSize(width: 360, height: 1000)).height, 400)
    }

    func testScreenshotRecurrenceFractionsAndMatricesProduceVisibleImages() throws {
        let formulas = [recurrence, #"\frac{-b\pm\sqrt{b^2-4ac}}{2a}"#,
                        #"\sum_{i=1}^{n}i^2"#, #"\begin{pmatrix}1&2\\3&4\end{pmatrix}"#]
        for latex in formulas {
            for color: UIColor in [.black, .white] {
                let image = try XCTUnwrap(MarkdownMathRenderer.image(
                    for: .init(latex: latex, display: true), fontSize: 20, color: color), latex)
                XCTAssertGreaterThan(image.size.width, 10)
                XCTAssertGreaterThan(image.size.height, 10)
                XCTAssertGreaterThan(try paintedPixels(image), 20)
            }
        }
    }

    func testInvalidMathFallsBackAndCacheSeparatesSizeAndColor() throws {
        XCTAssertNil(MarkdownMathRenderer.image(for: .init(latex: #"\unknowncommand{x}"#, display: false), fontSize: 17, color: .black))
        let formula = MarkdownMath.Formula(latex: #"\frac{1}{2}"#, display: false)
        let small = try XCTUnwrap(MarkdownMathRenderer.image(for: formula, fontSize: 17, color: .black))
        let large = try XCTUnwrap(MarkdownMathRenderer.image(for: formula, fontSize: 34, color: .black))
        let dark = try XCTUnwrap(MarkdownMathRenderer.image(for: formula, fontSize: 17, color: .white))
        XCTAssertGreaterThan(large.size.height, small.size.height * 1.5)
        XCTAssertNotEqual(small.pngData(), dark.pngData())
        XCTAssertTrue(small === MarkdownMathRenderer.image(for: formula, fontSize: 17, color: .black))
    }

    func testRasterOrientationMatchesNativeMathImage() throws {
        let actual = try XCTUnwrap(MarkdownMathRenderer.image(for: .init(latex: "F", display: false),
                                                            fontSize: 40, color: .black))
        var reference = MathImage(latex: "F", fontSize: 40, textColor: .black, labelMode: .text)
        reference.contentInsets = MTEdgeInsets(top: 1, left: 0, bottom: 1, right: 2)
        let expected = try XCTUnwrap(reference.asImage().1)
        XCTAssertEqual(try upperHalfInkFraction(actual), try upperHalfInkFraction(expected), accuracy: 0.05,
                       "字形不能因 UIKit 与数学坐标系不同而上下翻转")
    }

    #if DEBUG
    func testNestedFormulaImagesAreCollectedAndRenderedWithSurroundingText() async throws {
        let formula = MarkdownMath.Formula(latex: "x^2", display: false)
        let nodes: [MarkdownUI.InlineNode] = [.strong(children: [.text("答案 "),
            .emphasis(children: [.image(source: formula.url.absoluteString, children: [.text("x^2")])])])]
        XCTAssertEqual(nodes.flatMap(\.inlineImages).count, 1)
        let image = try await MathMarkdownInlineImageProvider(fontSize: 17, dark: false)
            .image(with: formula.url, label: formula.latex)
        let theme = MarkdownUI.Theme.gitHub
        let text = nodes.renderText(baseURL: nil,
            textStyles: .init(code: theme.code, emphasis: theme.emphasis, strong: theme.strong,
                              strikethrough: theme.strikethrough, link: theme.link),
            images: [formula.url.absoluteString: image], softBreakMode: .space, attributes: .init())
        let host = UIHostingController(rootView: text)
        let size = host.sizeThatFits(in: CGSize(width: 320, height: 1000))
        XCTAssertGreaterThan(size.width, 40)
        XCTAssertGreaterThan(size.height, 10)
    }
    #endif

    func testConversationMathLayoutInLightAndDarkAtPhoneAndTabletWidths() async throws {
        let source = "这是 SM-2 公式：\n\n\\[" + recurrence + "\\]\n\n行内公式 $E=mc^2$ 和 **\\(x_1^2\\)**。\n\n```latex\n" + recurrence + "\n```"
        for (width, scheme) in [(CGFloat(360), ColorScheme.light), (CGFloat(900), ColorScheme.dark)] {
            let host = UIHostingController(rootView: DeepSeekHarnessMobile.MarkdownContent(source)
                .environment(\.colorScheme, scheme).padding(16).frame(width: width, alignment: .leading))
            let window = UIWindow(frame: CGRect(x: 0, y: 0, width: width, height: 850))
            window.rootViewController = host
            window.isHidden = false
            host.view.frame = window.bounds
            try await Task.sleep(for: .milliseconds(300))
            let size = host.sizeThatFits(in: CGSize(width: width, height: 2000))
            XCTAssertEqual(size.width, width, accuracy: 1)
            XCTAssertTrue(size.height.isFinite)
            XCTAssertGreaterThan(size.height, 150)
            XCTAssertLessThan(size.height, 1000)
            host.view.backgroundColor = scheme == .dark ? .black : .white
            host.view.layoutIfNeeded()
            let image = UIGraphicsImageRenderer(size: window.bounds.size).image { context in
                host.view.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
            }
            let attachment = XCTAttachment(image: image)
            attachment.name = "LaTeX-\(Int(width))-\(scheme)"
            attachment.lifetime = .keepAlways
            add(attachment)
            try image.pngData()?.write(to: URL(fileURLWithPath: "/private/tmp/dsh-latex-\(Int(width)).png"))
            window.isHidden = true
        }
    }

    private func paintedPixels(_ image: UIImage) throws -> Int {
        let cgImage = try XCTUnwrap(image.cgImage)
        var bytes = [UInt8](repeating: 0, count: cgImage.width * cgImage.height * 4)
        let context = try XCTUnwrap(CGContext(data: &bytes, width: cgImage.width, height: cgImage.height,
            bitsPerComponent: 8, bytesPerRow: cgImage.width * 4, space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
        context.draw(cgImage, in: CGRect(x: 0, y: 0, width: cgImage.width, height: cgImage.height))
        return stride(from: 3, to: bytes.count, by: 4).filter { bytes[$0] > 10 }.count
    }

    private func upperHalfInkFraction(_ image: UIImage) throws -> Double {
        let cgImage = try XCTUnwrap(image.cgImage)
        var bytes = [UInt8](repeating: 0, count: cgImage.width * cgImage.height * 4)
        let context = try XCTUnwrap(CGContext(data: &bytes, width: cgImage.width, height: cgImage.height,
            bitsPerComponent: 8, bytesPerRow: cgImage.width * 4, space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
        context.draw(cgImage, in: CGRect(x: 0, y: 0, width: cgImage.width, height: cgImage.height))
        let rows = (0..<cgImage.height).map { row in
            (0..<cgImage.width).reduce(0.0) { $0 + Double(bytes[(row * cgImage.width + $1) * 4 + 3]) }
        }
        return rows.prefix(rows.count / 2).reduce(0, +) / max(1, rows.reduce(0, +))
    }
}
