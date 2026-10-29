#if canImport(UIKit)
import UIKit

/// 代码的容器装饰不写入正文字符串，语言标签不会混入选区或剪贴板。
public enum SelectableCodeBlockStyle {
  public static let blockID = NSAttributedString.Key("MarkdownSelectableCodeBlockID")
  public static let language = NSAttributedString.Key("MarkdownSelectableCodeLanguage")
  public static let decoration = NSAttributedString.Key("MarkdownSelectableCodeDecoration")
  public static let horizontalPadding: CGFloat = 12
  public static let headerHeight: CGFloat = 34
  public static let bottomPadding: CGFloat = 12
  public static let backgroundColor = UIColor { $0.userInterfaceStyle == .dark
    ? UIColor(red: 44/255, green: 44/255, blue: 46/255, alpha: 1)
    : UIColor(red: 235/255, green: 235/255, blue: 237/255, alpha: 1) }
}

extension MarkdownContent {
  /// 为独立全屏文字选择页生成连续富文本，所有偏移均以 UTF-16 计，与 UITextView 一致。
  /// 代码栏的布局换行单独标记，复制时排除；代码本身保持连续文字与原始缩进。
  public func renderSelectableText(
    font: UIFont, textColor: UIColor = .label,
    codeHighlighter: ((String, String?) -> NSAttributedString)? = nil
  ) -> NSAttributedString {
    let output = NSMutableAttributedString(string: "")
    var codeBlockID = 0
    let paragraph = NSMutableParagraphStyle()
    paragraph.lineSpacing = (font.pointSize * 0.22).rounded()
    paragraph.tabStops = []
    paragraph.defaultTabInterval = 56
    let base: [NSAttributedString.Key: Any] = [
      .font: font, .foregroundColor: textColor, .paragraphStyle: paragraph
    ]
    func append(_ value: String, _ attributes: [NSAttributedString.Key: Any]) {
      output.append(NSAttributedString(string: value, attributes: attributes))
    }
    func finishBlock(_ attributes: [NSAttributedString.Key: Any]) {
      guard output.length > 0 else { return }
      let lastCharacter = output.length - 1
      append("\n", attributes)
      let style = (attributes[.paragraphStyle] as? NSParagraphStyle)?.mutableCopy() as? NSMutableParagraphStyle
        ?? NSMutableParagraphStyle()
      style.paragraphSpacing = 10
      output.addAttribute(.paragraphStyle, value: style,
        range: (output.string as NSString).paragraphRange(for: NSRange(location: lastCharacter, length: 0)))
    }
    func inlines(_ nodes: [InlineNode], _ attributes: [NSAttributedString.Key: Any]) {
      for node in nodes {
        var style = attributes
        let currentFont = style[.font] as? UIFont ?? font
        switch node {
        case .text(let text): append(text, style)
        case .softBreak: append(" ", style)
        case .lineBreak: append("\u{2028}", style)
        case .code(let text):
          style[.font] = UIFont.monospacedSystemFont(ofSize: currentFont.pointSize * 0.86, weight: .regular)
          style[.backgroundColor] = SelectableCodeBlockStyle.backgroundColor
          append(text, style)
        case .strong(let children), .emphasis(let children):
          let trait: UIFontDescriptor.SymbolicTraits
          if case .strong = node { trait = .traitBold } else { trait = .traitItalic }
          if let descriptor = currentFont.fontDescriptor.withSymbolicTraits(
            currentFont.fontDescriptor.symbolicTraits.union(trait)
          ) { style[.font] = UIFont(descriptor: descriptor, size: currentFont.pointSize) }
          inlines(children, style)
        case .strikethrough(let children):
          style[.strikethroughStyle] = NSUnderlineStyle.single.rawValue
          inlines(children, style)
        case .link(_, let children):
          style[.foregroundColor] = UIColor.link
          inlines(children, style)
        case .image(_, let children): inlines(children, style)
        case .html: break
        }
      }
    }
    func blocks(_ nodes: [BlockNode], _ attributes: [NSAttributedString.Key: Any]) {
      for node in nodes {
        switch node {
        case .paragraph(let content):
          inlines(content, attributes)
          finishBlock(attributes)
        case .heading(let level, let content):
          var style = attributes
          style[.font] = UIFont.boldSystemFont(ofSize: font.pointSize * (level == 1 ? 1.65 : level == 2 ? 1.35 : 1.1))
          inlines(content, style)
          finishBlock(style)
        case .codeBlock(let language, let content):
          codeBlockID += 1
          var style = attributes
          let codeFont = UIFont.monospacedSystemFont(ofSize: (font.pointSize * 0.8).rounded(), weight: .regular)
          style[.font] = codeFont
          style[SelectableCodeBlockStyle.blockID] = codeBlockID
          style[SelectableCodeBlockStyle.language] = language?.split(whereSeparator: \.isWhitespace).first.map(String.init) ?? ""
          let codeParagraph = NSMutableParagraphStyle()
          codeParagraph.firstLineHeadIndent = SelectableCodeBlockStyle.horizontalPadding
          codeParagraph.headIndent = SelectableCodeBlockStyle.horizontalPadding
          codeParagraph.tailIndent = -SelectableCodeBlockStyle.horizontalPadding
          codeParagraph.lineSpacing = 3
          codeParagraph.tabStops = []
          codeParagraph.defaultTabInterval = ("    " as NSString).size(withAttributes: [.font: codeFont]).width
          style[.paragraphStyle] = codeParagraph
          let start = output.length
          // TextKit 不保留文档第一段的 paragraphSpacingBefore，因此使用明确的装饰行。
          // 它只提供标题高度，不参与复制内容。
          var header = style
          let headerParagraph = NSMutableParagraphStyle()
          headerParagraph.minimumLineHeight = SelectableCodeBlockStyle.headerHeight
          headerParagraph.maximumLineHeight = SelectableCodeBlockStyle.headerHeight
          header[.paragraphStyle] = headerParagraph
          header[.font] = UIFont.systemFont(ofSize: 1)
          header[SelectableCodeBlockStyle.decoration] = true
          append("\n", header)
          let displayedCode = content.hasSuffix("\n") ? String(content.dropLast()) : content
          let codeStart = output.length
          append(displayedCode.isEmpty ? "\n" : displayedCode, style)
          if let highlighted = codeHighlighter?(displayedCode, language), highlighted.string == displayedCode {
            highlighted.enumerateAttribute(.foregroundColor, in: NSRange(location: 0, length: highlighted.length)) { color, range, _ in
              guard let color else { return }
              output.addAttribute(.foregroundColor, value: color,
                range: NSRange(location: codeStart + range.location, length: range.length))
            }
          }
          finishBlock(style)
          let blockRange = NSRange(location: start, length: output.length - start)
          if blockRange.length > 0 {
            let lastRange = NSIntersectionRange(blockRange,
              (output.string as NSString).paragraphRange(for: NSRange(location: NSMaxRange(blockRange) - 1, length: 0)))
            let lastStyle = (output.attribute(.paragraphStyle, at: lastRange.location, effectiveRange: nil)
              as! NSParagraphStyle).mutableCopy() as! NSMutableParagraphStyle
            lastStyle.paragraphSpacing = SelectableCodeBlockStyle.bottomPadding + 10
            output.addAttribute(.paragraphStyle, value: lastStyle, range: lastRange)
          }
        case .blockquote(let children): blocks(children, attributes)
        case .bulletedList(_, let items):
          for item in items { append("• ", attributes); blocks(item.children, attributes) }
        case .numberedList(_, let start, let items):
          for (index, item) in items.enumerated() {
            append("\(start + index). ", attributes)
            blocks(item.children, attributes)
          }
        case .taskList(_, let items):
          for item in items {
            append(item.isCompleted ? "☑ " : "☐ ", attributes)
            blocks(item.children, attributes)
          }
        case .table(_, let rows):
          for row in rows {
            for (index, cell) in row.cells.enumerated() {
              if index > 0 { append("\t", attributes) }
              inlines(cell.content, attributes)
            }
            append("\n", attributes)
          }
        case .htmlBlock, .thematicBreak: break
        }
      }
    }
    blocks(self.blocks, base)
    // 段距由 paragraphSpacing 表达，不插入会占满一行高度的空段落。
    // 只移除最后一个结构分隔符，保留代码内部的空行。
    if output.string.hasSuffix("\n") {
      output.deleteCharacters(in: NSRange(location: output.length - 1, length: 1))
    }
    return output
  }
}
#endif
