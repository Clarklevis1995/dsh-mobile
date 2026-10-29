import DeepSeekHarnessShared
import MarkdownUI
import SwiftUI
import UIKit

struct DSHCodeSyntaxHighlighter: CodeSyntaxHighlighter {
    var dark = false

    private struct Key: Hashable {
        let source: String
        let language: String?
        let dark: Bool
    }

    private static let cache = MarkdownRenderProductCache<Key, NSAttributedString>(
        countLimit: 128, costLimit: 4 * 1_024 * 1_024
    )

    func highlightCode(_ code: String, language: String?) -> Text {
        Text(highlightedCode(code, language: language))
    }

    func highlightedCode(_ code: String, language: String?) -> AttributedString {
        let value = attributedCode(code, language: language)
        let source = code as NSString
        var result = AttributedString()
        // 显式转为 SwiftUI 的 Color；NSAttributedString 的 UIKit 属性不能直接交给 Text。
        value.enumerateAttribute(.foregroundColor, in: NSRange(location: 0, length: value.length)) { color, range, _ in
            var part = AttributedString(source.substring(with: range))
            if let color = color as? UIColor { part.foregroundColor = Color(uiColor: color) }
            result += part
        }
        return result
    }

    func attributedCode(_ code: String, language: String?) -> NSAttributedString {
        Self.cache.value(for: Key(source: code, language: language, dark: dark)) {
            let result = NSMutableAttributedString(string: code)
            let tokens = CodeSyntaxHighlighting.shared.highlight(source: code, info: language)
            for token in tokens {
                let start = Int(token.start)
                let end = Int(token.endExclusive)
                guard start >= 0, end > start, end <= result.length else { continue }
                let rgb = UInt32(bitPattern: CodeSyntaxHighlighting.shared.color(kind: token.kind, dark: dark))
                let color = UIColor(red: CGFloat((rgb >> 16) & 0xff) / 255,
                                    green: CGFloat((rgb >> 8) & 0xff) / 255,
                                    blue: CGFloat(rgb & 0xff) / 255, alpha: 1)
                result.addAttribute(.foregroundColor, value: color, range: NSRange(location: start, length: end - start))
            }
            return (NSAttributedString(attributedString: result), code.utf16.count * 12)
        }
    }
}
