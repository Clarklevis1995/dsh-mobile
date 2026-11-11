import Foundation

extension MarkdownContent {
  /// 只识别整段的单个链接，保留代码示例、普通行内链接及其周围文字。
  public var standaloneLink: (destination: String, title: String)? {
    guard blocks.count == 1, case .paragraph(let nodes) = blocks[0],
          nodes.count == 1, case .link(let destination, let children) = nodes[0] else { return nil }
    return (destination, MarkdownContent(block: .paragraph(content: children)).renderPlainText())
  }

  public var containsTable: Bool {
    func contains(_ nodes: [BlockNode]) -> Bool {
      nodes.contains { node in
        if case .table = node { return true }
        return contains(node.children)
      }
    }
    return contains(blocks)
  }

  /// 选择页使用真实单元格，不依赖 cmark 重建扩展节点时的表格 HTML 元数据。
  public func renderSelectionHTML() -> String {
    func escape(_ text: String) -> String {
      text.replacingOccurrences(of: "&", with: "&amp;")
        .replacingOccurrences(of: "<", with: "&lt;")
        .replacingOccurrences(of: ">", with: "&gt;")
        .replacingOccurrences(of: "\"", with: "&quot;")
    }
    func inline(_ nodes: [InlineNode]) -> String {
      nodes.map { node in
        switch node {
        case .text(let text): return escape(text)
        case .softBreak: return " "
        case .lineBreak: return "<br>"
        case .code(let text): return "<code>\(escape(text))</code>"
        case .strong(let children): return "<strong>\(inline(children))</strong>"
        case .emphasis(let children): return "<em>\(inline(children))</em>"
        case .strikethrough(let children): return "<del>\(inline(children))</del>"
        case .link(_, let children): return "<a>\(inline(children))</a>"
        case .image(_, let children): return inline(children)
        case .html(let html):
          return html.range(of: #"^<br\s*/?>$"#, options: [.regularExpression, .caseInsensitive]) == nil ? "" : "<br>"
        }
      }.joined()
    }
    func render(_ nodes: [BlockNode]) -> String {
      nodes.map { node in
        switch node {
        case .paragraph(let children): return "<p>\(inline(children))</p>"
        case .heading(let level, let children): return "<h\(level)>\(inline(children))</h\(level)>"
        case .blockquote(let children): return "<blockquote>\(render(children))</blockquote>"
        case .bulletedList(_, let items): return "<ul>" + items.map { "<li>\(render($0.children))</li>" }.joined() + "</ul>"
        case .numberedList(_, let start, let items): return "<ol start=\"\(start)\">" + items.map { "<li>\(render($0.children))</li>" }.joined() + "</ol>"
        case .taskList(_, let items): return "<ul>" + items.map { "<li>\($0.isCompleted ? "☑" : "☐") \(render($0.children))</li>" }.joined() + "</ul>"
        case .codeBlock(let info, let code):
          let language = info?.split(whereSeparator: \.isWhitespace).first.map(String.init) ?? ""
          return "<pre><code class=\"language-\(escape(language))\">\(escape(code))</code></pre>"
        case .table(let alignments, let rows):
          let rendered = rows.enumerated().map { row, value in
            let tag = row == 0 ? "th" : "td"
            return "<tr>" + value.cells.enumerated().map { column, cell in
              let alignment = column < alignments.count ? alignments[column] : .none
              let align = alignment == .center ? "center" : alignment == .right ? "right" : "left"
              return "<\(tag) align=\"\(align)\">\(inline(cell.content))</\(tag)>"
            }.joined() + "</tr>"
          }
          return "<table><thead>\(rendered.first ?? "")</thead><tbody>\(rendered.dropFirst().joined())</tbody></table>"
        case .thematicBreak: return "<hr>"
        case .htmlBlock: return ""
        }
      }.joined()
    }
    return render(blocks)
  }

  /// 已解析的单元格内容，供应用渲染独立表格；代码块内的竖线不会被识别为表格。
  public var tableCells: [[MarkdownContent]]? {
    guard blocks.count == 1, case .table(_, let rows) = blocks[0] else { return nil }
    return rows.map { row in row.cells.map { .init(block: .paragraph(content: $0.content)) } }
  }

  /// 图片列需要保留可阅读的缩略图宽度，不能只按替代文本测量。
  public var tableImageColumns: [Bool] {
    guard blocks.count == 1, case .table(let alignments, let rows) = blocks[0] else { return [] }
    func containsImage(_ nodes: [InlineNode]) -> Bool {
      nodes.contains { node in
        if case .image(let source, _) = node { return !source.hasPrefix("dsh-math:") }
        return containsImage(node.children)
      }
    }
    return alignments.indices.map { column in
      rows.contains { $0.cells.indices.contains(column) && containsImage($0.cells[column].content) }
    }
  }

  public var tableColumnAlignments: [TextTableColumnAlignment] {
    guard blocks.count == 1, case .table(let alignments, _) = blocks[0] else { return [] }
    return alignments.map {
      switch $0 {
      case .center: return .center
      case .right: return .trailing
      default: return .leading
      }
    }
  }

  public var tablePlainTextRows: [[String]] {
    guard blocks.count == 1, case .table(_, let rows) = blocks[0] else { return [] }
    func plain(_ nodes: [InlineNode]) -> String {
      nodes.map { node in
        switch node {
        case .text(let text): return text
        case .code(let text):
          // PreparedMarkdownCache 为行内代码添加的排版间距不进入剪贴板或 CSV。
          if text.hasPrefix("\u{2009}"), text.hasSuffix("\u{2009}") {
            return String(text.dropFirst().dropLast()).replacingOccurrences(of: "\u{200B}", with: "")
          }
          return text
        case .softBreak: return " "
        case .lineBreak: return "\n"
        case .html(let html):
          return html.range(of: #"^<br\s*/?>$"#, options: [.regularExpression, .caseInsensitive]) != nil ? "\n" : ""
        default: return plain(node.children)
        }
      }.joined()
    }
    return rows.map { $0.cells.map { plain($0.content) } }
  }
}
