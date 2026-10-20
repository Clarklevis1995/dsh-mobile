import Foundation
import SwiftUI

#if canImport(UIKit)
import UIKit

/// Renders one inline run (a Markdown paragraph line) inside a real `UITextView`
/// so iOS shows the native selection handles and lets the user drag a partial
/// range. SwiftUI's `Text` + `.textSelection(.enabled)` only offers whole-view
/// selection and loses the drag gesture to the hosting scroll/collection view,
/// which is why long-press produced "Copy / Share" with no handles.
///
/// The incoming AttributedString is *unresolved*: it still carries MarkdownUI's
/// `fontProperties` instead of a SwiftUI `Font`, because `NSAttributedString`
/// cannot bridge SwiftUI fonts (that mismatch made text fall back to 12 pt).
struct SelectableInlineText: UIViewRepresentable {
  let attributed: AttributedString

  @Environment(\.openURL) private var openURL
  @Environment(\.colorScheme) private var colorScheme

  func makeUIView(context: Context) -> UITextView {
    let view = UITextView()
    view.isEditable = false
    view.isSelectable = true
    view.isScrollEnabled = false
    view.backgroundColor = .clear
    view.textContainerInset = .zero
    view.textContainer.lineFragmentPadding = 0
    view.textContainer.widthTracksTextView = true
    view.dataDetectorTypes = []
    view.adjustsFontForContentSizeCategory = false
    view.textColor = .label
    view.tintColor = .tintColor
    view.delegate = context.coordinator
    view.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
    view.setContentHuggingPriority(.defaultLow, for: .horizontal)
    return view
  }

  func updateUIView(_ view: UITextView, context: Context) {
    context.coordinator.openURL = self.openURL
    let rendered = Self.makeAttributedString(self.attributed)
    if view.attributedText != rendered {
      view.attributedText = rendered
    }
  }

  @available(iOS 16.0, macOS 13.0, tvOS 16.0, watchOS 9.0, *)
  func sizeThatFits(_ proposal: ProposedViewSize, uiView: UITextView, context: Context) -> CGSize? {
    let width = proposal.width ?? uiView.bounds.width
    guard width.isFinite, width > 0 else { return nil }
    let fitted = uiView.sizeThatFits(CGSize(width: width, height: .greatestFiniteMagnitude))
    return CGSize(width: width, height: ceil(fitted.height))
  }

  func makeCoordinator() -> Coordinator {
    Coordinator()
  }

  final class Coordinator: NSObject, UITextViewDelegate {
    var openURL: OpenURLAction?

    func textView(
      _ textView: UITextView,
      shouldInteractWith URL: URL,
      in characterRange: NSRange,
      interaction: UITextItemInteraction
    ) -> Bool {
      guard let openURL else { return true }
      Task { @MainActor in _ = openURL(URL) }
      return false
    }
  }

  // MARK: - AttributedString -> NSAttributedString

  static func makeAttributedString(_ source: AttributedString) -> NSAttributedString {
    let baseFont = UIFont.preferredFont(forTextStyle: .body)
    let paragraphStyle = NSMutableParagraphStyle()
    paragraphStyle.lineSpacing = (baseFont.lineHeight * 0.22).rounded()

    let output = NSMutableAttributedString()
    for run in source.runs {
      let slice = source[run.range]
      let text = String(slice.characters)
      guard !text.isEmpty else { continue }

      var attributes: [NSAttributedString.Key: Any] = [
        .paragraphStyle: paragraphStyle,
      ]

      if let properties = slice[FontPropertiesAttribute.self] {
        attributes[.font] = uiFont(from: properties)
      } else {
        attributes[.font] = baseFont
      }

      if let color = slice[AttributeScopes.SwiftUIAttributes.ForegroundColorAttribute.self] {
        attributes[.foregroundColor] = UIColor(color)
      } else {
        attributes[.foregroundColor] = UIColor.label
      }

      if let link = slice[AttributeScopes.FoundationAttributes.LinkAttribute.self] {
        attributes[.link] = link
      }

      output.append(NSAttributedString(string: text, attributes: attributes))
    }
    return output
  }

  private static func uiFont(from properties: FontProperties) -> UIFont {
    let size = properties.scaledSize
    let weight = uiWeight(properties.weight)

    var font: UIFont
    switch properties.family {
    case .system(let design):
      font = UIFont.systemFont(ofSize: size, weight: weight)
      if let systemDesign = uiDesign(design),
         let descriptor = font.fontDescriptor.withDesign(systemDesign) {
        font = UIFont(descriptor: descriptor, size: size)
      }
    case .custom(let name):
      font = UIFont(name: name, size: size) ?? UIFont.systemFont(ofSize: size, weight: weight)
    }

    if properties.familyVariant == .monospaced,
       let descriptor = font.fontDescriptor.withDesign(.monospaced) {
      font = UIFont(descriptor: descriptor, size: size)
    }

    if case .italic = properties.style,
       let descriptor = font.fontDescriptor.withSymbolicTraits(
         font.fontDescriptor.symbolicTraits.union(.traitItalic)
       ) {
      font = UIFont(descriptor: descriptor, size: size)
    }

    return font
  }

  private static func uiWeight(_ weight: Font.Weight) -> UIFont.Weight {
    switch weight {
    case .ultraLight: return .ultraLight
    case .thin: return .thin
    case .light: return .light
    case .medium: return .medium
    case .semibold: return .semibold
    case .bold: return .bold
    case .heavy: return .heavy
    case .black: return .black
    default: return .regular
    }
  }

  private static func uiDesign(_ design: Font.Design) -> UIFontDescriptor.SystemDesign? {
    switch design {
    case .monospaced: return .monospaced
    case .serif: return .serif
    case .rounded: return .rounded
    default: return nil
    }
  }
}

extension Array where Element == InlineNode {
  /// Array counterpart of `InlineNode.renderAttributedString`, used by
  /// `SelectableInlineText`. Inline images are not representable here; callers
  /// fall back to the SwiftUI renderer when images are present.
  func renderAttributedString(
    baseURL: URL?,
    textStyles: InlineTextStyles,
    softBreakMode: SoftBreak.Mode,
    attributes: AttributeContainer,
    resolveFonts: Bool = true
  ) -> AttributedString {
    self.reduce(into: AttributedString()) { result, inline in
      result.append(
        inline.renderAttributedString(
          baseURL: baseURL,
          textStyles: textStyles,
          softBreakMode: softBreakMode,
          attributes: attributes,
          resolveFonts: resolveFonts
        )
      )
    }
  }
}
#endif
