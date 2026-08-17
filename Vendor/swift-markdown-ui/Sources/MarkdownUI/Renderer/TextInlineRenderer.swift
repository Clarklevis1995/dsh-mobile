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
  var result = Text("")

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
      self.result = self.result + Text(image)
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
      self.result = self.result + text.customAttribute(MarkdownInlineCodeAttribute())
    } else {
      self.result = self.result + text
    }
  }

  private mutating func defaultRender(_ inline: InlineNode) {
    self.result =
      self.result
      + Text(
        inline.renderAttributedString(
          baseURL: self.baseURL,
          textStyles: self.textStyles,
          softBreakMode: self.softBreakMode,
          attributes: self.attributes
        )
      )
  }
}
