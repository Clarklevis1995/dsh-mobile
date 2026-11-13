import Foundation

/// A protocol that represents any Markdown content.
public protocol MarkdownContentProtocol {
  var _markdownContent: MarkdownContent { get }
}

/// A Markdown content value.
///
/// A Markdown content value consists of a sequence of blocks – structural elements like paragraphs, blockquotes, lists,
/// headings, thematic breaks, and code blocks. Some blocks, like blockquotes and list items, contain other blocks; others,
/// like headings and paragraphs, have inline text, links, emphasized text, etc.
///
/// You can create a Markdown content value by passing a Markdown-formatted string to ``init(_:)``.
///
/// ```swift
/// let content = MarkdownContent("You can try **CommonMark** [here](https://spec.commonmark.org/dingus/).")
/// ```
///
/// Alternatively, you can build a Markdown content value using a domain-specific language for blocks and inline text.
///
/// ```swift
/// let content = MarkdownContent {
///   Paragraph {
///     "You can try "
///     Strong("CommonMark")
///     SoftBreak()
///     InlineLink("here", destination: URL(string: "https://spec.commonmark.org/dingus/")!)
///     "."
///   }
/// }
/// ```
///
/// Once you have created a Markdown content value, you can display it using a ``Markdown`` view.
///
/// ```swift
/// var body: some View {
///   Markdown(self.content)
/// }
/// ```
///
/// A Markdown view also offers initializers that take a Markdown-formatted string ``Markdown/init(_:baseURL:imageBaseURL:)-63py1``,
/// or a Markdown content builder ``Markdown/init(baseURL:imageBaseURL:content:)``, so you don't need to create a
/// Markdown content value before displaying it.
///
/// ```swift
/// var body: some View {
///   VStack {
///     Markdown("You can try **CommonMark** [here](https://spec.commonmark.org/dingus/).")
///     Markdown {
///       Paragraph {
///         "You can try "
///         Strong("CommonMark")
///         SoftBreak()
///         InlineLink("here", destination: URL(string: "https://spec.commonmark.org/dingus/")!)
///         "."
///       }
///     }
///   }
/// }
/// ```
public struct MarkdownContent: Equatable, MarkdownContentProtocol {
  /// Returns a Markdown content value with the sum of the contents of all the container blocks
  /// present in this content.
  ///
  /// You can use this property to access the contents of a blockquote or a list. Returns `nil` if
  /// there are no container blocks.
  public var childContent: MarkdownContent? {
    let children = self.blocks.map(\.children).flatMap { $0 }
    return children.isEmpty ? nil : .init(blocks: children)
  }

  public var _markdownContent: MarkdownContent { self }
  let blocks: [BlockNode]

  init(blocks: [BlockNode] = []) {
    self.blocks = blocks
  }

  init(block: BlockNode) {
    self.init(blocks: [block])
  }

  init(_ components: [MarkdownContentProtocol]) {
    self.init(blocks: components.map(\._markdownContent).flatMap(\.blocks))
  }

  /// Creates a Markdown content value from a Markdown-formatted string.
  /// - Parameter markdown: A Markdown-formatted string.
  public init(_ markdown: String) {
    self.init(blocks: .init(markdown: markdown))
  }

  /// Creates a Markdown content value composed of any number of blocks.
  /// - Parameter content: A Markdown content builder that returns the blocks that form the Markdown content.
  public init(@MarkdownContentBuilder content: () -> MarkdownContent) {
    self.init(blocks: content().blocks)
  }

  /// Whether every block contains only unformatted text and line breaks.
  public var isPlainText: Bool {
    blocks.allSatisfy { block in
      guard case .paragraph(let nodes) = block else { return false }
      return nodes.allSatisfy { node in
        switch node {
        case .text, .softBreak, .lineBreak: return true
        default: return false
        }
      }
    }
  }

  /// Renders this Markdown content value as a Markdown-formatted text.
  public func renderMarkdown() -> String {
    let result = self.blocks.renderMarkdown()
    return result.hasSuffix("\n") ? String(result.dropLast()) : result
  }

  /// Renders this Markdown content value as plain text.
  public func renderPlainText() -> String {
    let result = self.blocks.renderPlainText()
    return result.hasSuffix("\n") ? String(result.dropLast()) : result
  }

  /// Renders this Markdown content value as HTML code.
  public func renderHTML() -> String {
    self.blocks.renderHTML()
  }
}

// 将应用提供的已知行内代码引用转换为链接，不扫描 Markdown 源文本。
extension MarkdownContent {
  public func linkingInlineCode(_ destinations: [String: String]) -> MarkdownContent {
    guard !destinations.isEmpty else { return self }
    let generated = Set(destinations.values)
    let rewritten = self.blocks.rewrite { (inline: InlineNode) -> [InlineNode] in
      switch inline {
      case .code(let value):
        guard let destination = destinations[value] else { return [inline] }
        return [.link(destination: destination, children: [inline])]
      case .link(let destination, let children), .image(let destination, let children):
        // 已处于链接内的代码保留原目的地，避免生成嵌套链接。
        let restored = children.rewrite { (child: InlineNode) -> [InlineNode] in
          if case .link(let target, let content) = child, generated.contains(target) { return content }
          return [child]
        }
        if case .image = inline { return [.image(source: destination, children: restored)] }
        return [.link(destination: destination, children: restored)]
      default:
        return [inline]
      }
    }
    return MarkdownContent(blocks: rewritten)
  }
}
