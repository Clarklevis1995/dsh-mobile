import Foundation
import MarkdownUI
import SwiftMath
import SwiftUI
import UIKit

/// 在 CommonMark 吞掉 \(、\[ 的转义符之前，保护数学内容；原消息仍用于复制和选择文字。
enum MarkdownMath {
    struct Formula: Equatable {
        let latex: String
        let display: Bool

        var url: URL {
            let payload = Data(latex.utf8).base64EncodedString()
                .replacingOccurrences(of: "+", with: "-")
                .replacingOccurrences(of: "/", with: "_")
                .replacingOccurrences(of: "=", with: "")
            return URL(string: "dsh-math://\(display ? "display" : "inline")/\(payload)")!
        }

        init(latex: String, display: Bool) {
            self.latex = latex
            self.display = display
        }

        init?(url: URL?) {
            guard let url, url.scheme == "dsh-math",
                  url.host == "display" || url.host == "inline" else { return nil }
            var payload = String(url.path.dropFirst()).replacingOccurrences(of: "-", with: "+")
                .replacingOccurrences(of: "_", with: "/")
            payload += String(repeating: "=", count: (4 - payload.count % 4) % 4)
            guard let data = Data(base64Encoded: payload),
                  let latex = String(data: data, encoding: .utf8) else { return nil }
            self.init(latex: latex, display: url.host == "display")
        }
    }

    static func prepare(_ source: String, forSelection: Bool = false) -> String {
        guard source.contains("$") || source.contains("\\(") || source.contains("\\[") else { return source }
        let characters = Array(source)
        var result = ""
        var index = 0
        var fence: (Character, Int)?
        var lineStart = true

        func matches(_ token: String, at offset: Int) -> Bool {
            let token = Array(token)
            return offset + token.count <= characters.count
                && characters[offset..<(offset + token.count)].elementsEqual(token)
        }
        func runLength(at offset: Int) -> Int {
            var end = offset
            while end < characters.count, characters[end] == characters[offset] { end += 1 }
            return end - offset
        }
        func appendThrough(_ end: Int) {
            result += String(characters[index..<end])
            lineStart = end > index && characters[end - 1] == "\n"
            index = end
        }

        while index < characters.count {
            if lineStart {
                let end = characters[index...].firstIndex(of: "\n").map { $0 + 1 } ?? characters.count
                var marker = index
                // 引用和列表中的围栏也必须保持源码，不能把示例当成公式。
                while marker < end, characters[marker] == " " || characters[marker] == ">" { marker += 1 }
                if marker < end, characters[marker] == "[",
                   String(characters[marker..<end]).range(of: #"^\[[^\]]+\]:"#, options: .regularExpression) != nil {
                    appendThrough(end)
                    continue
                }
                if marker < end, characters[marker] == "`" || characters[marker] == "~" {
                    let count = runLength(at: marker)
                    if count >= 3 {
                        if let active = fence {
                            let tail = String(characters[(marker + count)..<end]).trimmingCharacters(in: .whitespacesAndNewlines)
                            if active.0 == characters[marker], count >= active.1, tail.isEmpty { fence = nil }
                        } else {
                            fence = (characters[marker], count)
                        }
                        appendThrough(end)
                        continue
                    }
                }
                if fence != nil || matches("    ", at: index) || characters[index] == "\t" {
                    appendThrough(end)
                    continue
                }
            }
            lineStart = false
            let character = characters[index]
            if matches("![", at: index) {
                var end = index + 2
                var depth = 1
                while end < characters.count, depth > 0 {
                    if characters[end] == "\\" { end = min(end + 2, characters.count); continue }
                    if characters[end] == "[" { depth += 1 }
                    if characters[end] == "]" { depth -= 1 }
                    end += 1
                }
                // 留下 ] 给下方的链接目的地分支处理。
                appendThrough(depth == 0 ? end - 1 : end)
                continue
            }
            if character == "`" {
                let count = runLength(at: index)
                var end = index + count
                var closing: Int?
                while end < characters.count {
                    if characters[end] == "`" {
                        let length = runLength(at: end)
                        if length == count { closing = end + length; break }
                        end += length
                    } else { end += 1 }
                }
                appendThrough(closing ?? index + count)
                continue
            }
            // 跳过链接目的地、自动链接及 HTML 标签中的美元和反斜杠。
            if matches("](", at: index) {
                var end = index + 2
                var depth = 1
                while end < characters.count, depth > 0 {
                    if characters[end] == "\\" { end = min(end + 2, characters.count); continue }
                    if characters[end] == "(" { depth += 1 }
                    if characters[end] == ")" { depth -= 1 }
                    end += 1
                }
                appendThrough(end)
                continue
            }
            if matches("https://", at: index) || matches("http://", at: index) {
                let end = characters[index...].firstIndex(where: \.isWhitespace) ?? characters.count
                appendThrough(end)
                continue
            }
            if character == "<", index + 1 < characters.count,
               characters[index + 1].isLetter || "/!?".contains(characters[index + 1]),
               let end = characters[index...].firstIndex(of: ">") {
                appendThrough(end + 1)
                continue
            }

            let opening: String?
            let closing: String
            let display: Bool
            if matches("\\[", at: index) { opening = "\\["; closing = "\\]"; display = true }
            else if matches("\\(", at: index) { opening = "\\("; closing = "\\)"; display = false }
            else if matches("$$", at: index) { opening = "$$"; closing = "$$"; display = true }
            else if character == "$" { opening = "$"; closing = "$"; display = false }
            else { opening = nil; closing = ""; display = false }

            if let opening {
                let start = index + opening.count
                var end = start
                var found: Int?
                let validStart = opening != "$" || (start < characters.count && !characters[start].isWhitespace)
                while validStart, end < characters.count {
                    if !display, characters[end] == "\n" { break }
                    if matches(closing, at: end) {
                        let after = end + closing.count
                        let validEnd = opening != "$" || (end > start && !characters[end - 1].isWhitespace
                            && (after == characters.count || (!characters[after].isNumber && characters[after] != "$")))
                        if validEnd { found = end; break }
                    }
                    if characters[end] == "\\" { end = min(end + 2, characters.count) }
                    else { end += 1 }
                }
                if let end = found, end > start {
                    if forSelection {
                        // 实体解码发生在 Markdown 转义解析之后，原始反斜杠、换行和分隔符不会丢失。
                        result += String(characters[index..<(end + closing.count)]).unicodeScalars
                            .map { "&#\($0.value);" }.joined()
                        index = end + closing.count
                        continue
                    }
                    var latex = String(characters[start..<end])
                    let lineBegin = characters[..<index].lastIndex(of: "\n").map { $0 + 1 } ?? 0
                    let prefix = String(characters[lineBegin..<index])
                    if display, prefix.range(of: #"^(?:[ \t]*>[ \t]?)+[ \t]*$"#, options: .regularExpression) != nil {
                        latex = latex.components(separatedBy: "\n").map {
                            $0.replacingOccurrences(of: #"^[ \t]*(?:>[ \t]?)+"#, with: "", options: .regularExpression)
                        }.joined(separator: "\n")
                    }
                    latex = latex.trimmingCharacters(in: .whitespacesAndNewlines)
                    if !latex.isEmpty {
                        let formula = Formula(latex: latex, display: display)
                        let alt = latex.reduce(into: "") { value, character in
                            if "\\[]`*_<>|".contains(character) { value.append("\\") }
                            value.append(character == "\n" ? " " : character)
                        }
                        result += "![\(alt)](\(formula.url.absoluteString))"
                        index = end + closing.count
                        continue
                    }
                }
                // 未闭合的 $$ 不能回头把第二个 $ 当成行内公式的起点。
                appendThrough(start)
                continue
            }
            if character == "\\" { appendThrough(min(index + 2, characters.count)) }
            else { appendThrough(index + 1) }
        }
        return result
    }
}

@MainActor
enum MarkdownMathRenderer {
    private static let cache = MarkdownRenderProductCache<String, UIImage?>(
        countLimit: 128, costLimit: 16 * 1_024 * 1_024
    )

    static func image(for formula: MarkdownMath.Formula, fontSize: CGFloat, color: UIColor) -> UIImage? {
        let key = "\(formula.url.absoluteString)|\(fontSize)|\(color.description)"
        return cache.value(for: key) {
            guard formula.latex.count <= 8_000 else { return (nil, 0) }
            // SwiftMath 1.7 不支持显式括号尺寸和行间距参数；只移除排版提示，保留运算内容。
            let latex = formula.latex
                .replacingOccurrences(of: #"\\(?:big|Big|bigg|Bigg)[lrm]?\b"#, with: "", options: .regularExpression)
                .replacingOccurrences(of: #"(\\\\)\s*\[-?\d+(?:\.\d+)?(?:pt|em|ex)\]"#, with: "$1", options: .regularExpression)
            let label = MTMathUILabel()
            label.fontSize = fontSize
            label.labelMode = formula.display ? .display : .text
            label.textColor = color
            label.textAlignment = .left
            label.latex = latex
            guard label.error == nil else { return (nil, 0) }
            let size = label.intrinsicContentSize
            guard size.width.isFinite, size.height.isFinite, size.width > 0, size.height > 0,
                  size.width <= 8_192, size.height <= 2_048 else { return (nil, 0) }
            label.frame = CGRect(origin: .zero, size: CGSize(width: ceil(size.width) + 2, height: ceil(size.height) + 2))
            label.layoutIfNeeded()
            let image = UIGraphicsImageRenderer(size: label.bounds.size).image { context in
                // 数学字形采用左下角原点，不能直接快照 UIKit 的翻转图层。
                context.cgContext.translateBy(x: 0, y: label.bounds.height)
                context.cgContext.scaleBy(x: 1, y: -1)
                label.displayList?.draw(context.cgContext)
            }.withBaselineOffset(fromBottom: (label.displayList?.descent ?? 0) + 1)
            let cost = (image.cgImage?.bytesPerRow ?? 0) * (image.cgImage?.height ?? 0)
            return (image, cost)
        }
    }
}

struct MathMarkdownImageProvider: ImageProvider {
    let fontSize: CGFloat

    func makeImage(url: URL?) -> some View {
        if let formula = MarkdownMath.Formula(url: url) {
            MarkdownFormulaImage(formula: formula, fontSize: fontSize)
        } else {
            DefaultImageProvider.default.makeImage(url: url)
        }
    }
}

private struct MarkdownFormulaImage: View {
    @Environment(\.colorScheme) private var colorScheme
    let formula: MarkdownMath.Formula
    let fontSize: CGFloat

    var body: some View {
        let color = UIColor.label.resolvedColor(with: UITraitCollection(userInterfaceStyle: colorScheme == .dark ? .dark : .light))
        if let image = MarkdownMathRenderer.image(for: formula, fontSize: fontSize, color: color) {
            ScrollView(.horizontal) {
                Image(uiImage: image)
                    .accessibilityLabel(formula.latex)
                    .padding(.vertical, 4)
            }
            .frame(height: image.size.height + 8)
        } else {
            Text(formula.latex).font(.system(size: fontSize, design: .monospaced))
        }
    }
}

struct MathMarkdownInlineImageProvider: InlineImageProvider {
    let fontSize: CGFloat
    let dark: Bool

    func image(with url: URL, label: String) async throws -> Image {
        guard let formula = MarkdownMath.Formula(url: url) else {
            return try await DefaultInlineImageProvider.default.image(with: url, label: label)
        }
        return try await MainActor.run {
            let color = UIColor.label.resolvedColor(with: UITraitCollection(userInterfaceStyle: dark ? .dark : .light))
            guard let image = MarkdownMathRenderer.image(for: formula, fontSize: fontSize, color: color) else {
                throw CocoaError(.coderInvalidValue)
            }
            return Image(uiImage: image)
        }
    }
}
