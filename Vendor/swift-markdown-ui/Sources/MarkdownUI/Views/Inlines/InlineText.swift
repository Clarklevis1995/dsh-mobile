import SwiftUI

struct InlineText: View {
  @Environment(\.inlineImageProvider) private var inlineImageProvider
  @Environment(\.baseURL) private var baseURL
  @Environment(\.imageBaseURL) private var imageBaseURL
  @Environment(\.softBreakMode) private var softBreakMode
  @Environment(\.theme) private var theme

  @State private var inlineImages: [String: Image] = [:]

  private let inlines: [InlineNode]

  init(_ inlines: [InlineNode]) {
    self.inlines = inlines
  }

  var body: some View {
    TextStyleAttributesReader { attributes in
      #if canImport(UIKit)
      if self.inlines.allSatisfy({ $0.imageData == nil }) {
        SelectableInlineText(
          attributed: self.inlines.renderAttributedString(
            baseURL: self.baseURL,
            textStyles: .init(
              code: self.theme.code,
              emphasis: self.theme.emphasis,
              strong: self.theme.strong,
              strikethrough: self.theme.strikethrough,
              link: self.theme.link
            ),
            softBreakMode: self.softBreakMode,
            attributes: attributes,
            resolveFonts: false
          )
        )
      } else {
        // Inline images need the SwiftUI renderer; those runs stay non-selectable.
        self.swiftUIText(attributes: attributes)
      }
      #else
      self.swiftUIText(attributes: attributes)
      #endif
    }
    .task(id: self.inlines) {
      self.inlineImages = (try? await self.loadInlineImages()) ?? [:]
    }
  }

  @ViewBuilder
  private func swiftUIText(attributes: AttributeContainer) -> some View {
    let text = self.inlines.renderText(
      baseURL: self.baseURL,
      textStyles: .init(
        code: self.theme.code,
        emphasis: self.theme.emphasis,
        strong: self.theme.strong,
        strikethrough: self.theme.strikethrough,
        link: self.theme.link
      ),
      images: self.inlineImages,
      softBreakMode: self.softBreakMode,
      attributes: attributes
    )
    if #available(iOS 18.0, macOS 15.0, tvOS 18.0, watchOS 11.0, *) {
      text.textRenderer(MarkdownInlineCodeBackgroundRenderer())
    } else {
      text
    }
  }

  private func loadInlineImages() async throws -> [String: Image] {
    let images = Set(self.inlines.compactMap(\.imageData))
    guard !images.isEmpty else { return [:] }

    return try await withThrowingTaskGroup(of: (String, Image).self) { taskGroup in
      for image in images {
        guard let url = URL(string: image.source, relativeTo: self.imageBaseURL) else {
          continue
        }

        taskGroup.addTask {
          (image.source, try await self.inlineImageProvider.image(with: url, label: image.alt))
        }
      }

      var inlineImages: [String: Image] = [:]

      for try await result in taskGroup {
        inlineImages[result.0] = result.1
      }

      return inlineImages
    }
  }
}
