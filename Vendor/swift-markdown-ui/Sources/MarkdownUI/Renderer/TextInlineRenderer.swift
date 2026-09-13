import SwiftUI

/// Marks inline-code runs so clients can provide a TextRenderer-backed
/// rounded background without replacing MarkdownUI's natural text layout.
public struct MarkdownInlineCodeAttribute: TextAttribute {
  public init() {}
}

/// Draws inline-code backgrounds on the final `Text` produced by MarkdownUI.
/// Applying this renderer here avoids relying on renderer propagation through
/// the block and theme view hierarchy.
@available(iOS 18.0, macOS 15.0, tvOS 18.0, watchOS 11.0, *)
struct MarkdownInlineCodeBackgroundRenderer: TextRenderer {
  func draw(layout: Text.Layout, in context: inout GraphicsContext) {
    for line in layout {
      var codeBounds: CGRect?

      for run in line {
        if run[MarkdownInlineCodeAttribute.self] != nil {
          let bounds = run.typographicBounds.rect
          codeBounds = codeBounds.map { $0.union(bounds) } ?? bounds
        } else if let bounds = codeBounds {
          drawBackground(bounds, in: &context)
          codeBounds = nil
        }
      }
      if let bounds = codeBounds {
        drawBackground(bounds, in: &context)
      }

      // Draw text only after all backgrounds for this line, so adjacent run
      // backgrounds can never cover previously drawn glyphs.
      for run in line {
        context.draw(run)
      }
    }
  }

  private func drawBackground(_ bounds: CGRect, in context: inout GraphicsContext) {
    // Horizontal padding is part of the code run's layout (thin spaces added
    // before parsing), so the painted background must not extend over adjacent
    // punctuation such as `、` or `，`.
    let paddedBounds = bounds.insetBy(dx: 0, dy: -2)
    context.fill(
      Path(roundedRect: paddedBounds, cornerRadius: 5),
      with: .color(Self.backgroundColor)
    )
  }

  private static var backgroundColor: Color {
    #if canImport(UIKit)
    Color(uiColor: .systemGray5).opacity(0.96)
    #else
    Color.secondary.opacity(0.14)
    #endif
  }
}

extension Sequence where Element == InlineNode {
  func renderText(
    baseURL: URL?,
    textStyles: InlineTextStyles,
    images: [String: Image],
    softBreakMode: SoftBreak.Mode,
    attributes: AttributeContainer
  ) -> Text {
    var renderer = TextInlineRenderer(
      baseURL: baseURL,
      textStyles: textStyles,
      images: images,
      softBreakMode: softBreakMode,
      attributes: attributes
    )
    renderer.render(self)
    return renderer.result
  }
}

private struct TextInlineRenderer {
  /// Inline fragments in document order. They are combined only when `result` is
  /// read, so the concatenation tree can stay balanced (see `balanced(_:)`).
  private var fragments: [Text] = []

  var result: Text { Self.balanced(self.fragments) }

  /// SwiftUI resolves a concatenated `Text` recursively, so the left-associative
  /// chain this renderer used to build (`result = result + next`) cost one stack
  /// frame per inline fragment. A single long paragraph — a few thousand inline
  /// nodes — then overflowed the main thread's stack while the view was being
  /// measured, which crashed the app on launch once a session contained a long
  /// message:
  ///
  ///   EXC_BAD_ACCESS (SIGSEGV), "Thread stack size exceeded due to excessive
  ///   recursion" in `Text.resolve` ⇄ `ConcatenatedTextStorage.resolve`
  ///
  /// Combining neighbouring fragments level by level keeps the fragment order and
  /// the rendered result identical while bounding the resolve depth to O(log n)
  /// instead of O(n).
  private static func balanced(_ fragments: [Text]) -> Text {
    if fragments.isEmpty { return Text("") }
    var level = fragments
    while level.count > 1 {
      var next: [Text] = []
      next.reserveCapacity((level.count + 1) / 2)
      var index = 0
      while index < level.count {
        if index + 1 < level.count {
          next.append(level[index] + level[index + 1])
        } else {
          next.append(level[index])
        }
        index += 2
      }
      level = next
    }
    return level[0]
  }

  private let baseURL: URL?
  private let textStyles: InlineTextStyles
  private let images: [String: Image]
  private let softBreakMode: SoftBreak.Mode
  private let attributes: AttributeContainer
  private var shouldSkipNextWhitespace = false

  init(
    baseURL: URL?,
    textStyles: InlineTextStyles,
    images: [String: Image],
    softBreakMode: SoftBreak.Mode,
    attributes: AttributeContainer
  ) {
    self.baseURL = baseURL
    self.textStyles = textStyles
    self.images = images
    self.softBreakMode = softBreakMode
    self.attributes = attributes
  }

  mutating func render<S: Sequence>(_ inlines: S) where S.Element == InlineNode {
    for inline in inlines {
      self.render(inline)
    }
  }

  private mutating func render(_ inline: InlineNode) {
    switch inline {
    case .text(let content):
      self.renderText(content)
    case .softBreak:
      self.renderSoftBreak()
    case .html(let content):
      self.renderHTML(content)
    case .image(let source, _):
      self.renderImage(source)
    case .code:
      self.renderCode(inline)
    default:
      self.defaultRender(inline)
    }
  }

  private mutating func renderText(_ text: String) {
    var text = text

    if self.shouldSkipNextWhitespace {
      self.shouldSkipNextWhitespace = false
      text = text.replacingOccurrences(of: "^\\s+", with: "", options: .regularExpression)
    }

    self.defaultRender(.text(text))
  }

  private mutating func renderSoftBreak() {
    switch self.softBreakMode {
    case .space where self.shouldSkipNextWhitespace:
      self.shouldSkipNextWhitespace = false
    case .space:
      self.defaultRender(.softBreak)
    case .lineBreak:
      self.shouldSkipNextWhitespace = true
      self.defaultRender(.lineBreak)
    }
  }

  private mutating func renderHTML(_ html: String) {
    let tag = HTMLTag(html)

    switch tag?.name.lowercased() {
    case "br":
      self.defaultRender(.lineBreak)
      self.shouldSkipNextWhitespace = true
    default:
      self.defaultRender(.html(html))
    }
  }

  private mutating func renderImage(_ source: String) {
    if let image = self.images[source] {
      self.fragments.append(Text(image))
    }
  }

  private mutating func renderCode(_ inline: InlineNode) {
    let text = Text(
      inline.renderAttributedString(
        baseURL: self.baseURL,
        textStyles: self.textStyles,
        softBreakMode: self.softBreakMode,
        attributes: self.attributes
      )
    )
    if #available(iOS 18.0, macOS 15.0, tvOS 18.0, watchOS 11.0, *) {
      self.fragments.append(text.customAttribute(MarkdownInlineCodeAttribute()))
    } else {
      self.fragments.append(text)
    }
  }

  private mutating func defaultRender(_ inline: InlineNode) {
    self.fragments.append(
      Text(
        inline.renderAttributedString(
          baseURL: self.baseURL,
          textStyles: self.textStyles,
          softBreakMode: self.softBreakMode,
          attributes: self.attributes
        )
      )
    )
  }
}
