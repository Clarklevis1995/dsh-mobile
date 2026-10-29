import SwiftUI

struct InlineText: View {
  private let inlines: [InlineNode]
  private let hasInlineImages: Bool
  let hasInlineCode: Bool

  init(_ inlines: [InlineNode]) {
    self.inlines = inlines
    func containsCode(_ node: InlineNode) -> Bool {
      if case .code = node { return true }
      return node.children.contains(where: containsCode)
    }
    self.hasInlineCode = inlines.contains(where: containsCode)
    self.hasInlineImages = inlines.contains { !$0.inlineImages.isEmpty }
  }

  var body: some View {
    if self.hasInlineImages {
      InlineTextWithImages(inlines: self.inlines, hasInlineCode: self.hasInlineCode)
    } else {
      InlineTextContent(inlines: self.inlines, hasInlineCode: self.hasInlineCode, images: [:])
    }
  }
}

// 所有 Text 构造和 renderer 选择只维护一份；无图分支没有 State 或 task。
private struct InlineTextContent: View {
  let inlines: [InlineNode]
  let hasInlineCode: Bool
  let images: [String: Image]
  @Environment(\.baseURL) private var baseURL
  @Environment(\.softBreakMode) private var softBreakMode
  @Environment(\.theme) private var theme

  var body: some View {
    TextStyleAttributesReader { attributes in
      let text = self.inlines.renderText(
        baseURL: self.baseURL,
        textStyles: .init(
          code: self.theme.code,
          emphasis: self.theme.emphasis,
          strong: self.theme.strong,
          strikethrough: self.theme.strikethrough,
          link: self.theme.link
        ),
        images: self.images,
        softBreakMode: self.softBreakMode,
        attributes: attributes
      )
      if #available(iOS 18.0, macOS 15.0, tvOS 18.0, watchOS 11.0, *), self.hasInlineCode {
        text.textRenderer(MarkdownInlineCodeBackgroundRenderer())
      } else {
        text
      }
    }
  }
}

private struct InlineTextWithImages: View {
  let inlines: [InlineNode]
  let hasInlineCode: Bool
  @Environment(\.inlineImageProvider) private var inlineImageProvider
  @Environment(\.imageBaseURL) private var imageBaseURL
  @State private var loaded: LoadedImages?

  private struct LoadedImages {
    let inlines: [InlineNode]
    let images: [String: Image]
  }

  private var currentImages: [String: Image] {
    guard let loaded = self.loaded, loaded.inlines == self.inlines else { return [:] }
    return loaded.images
  }

  var body: some View {
    InlineTextContent(
      inlines: self.inlines,
      hasInlineCode: self.hasInlineCode,
      images: self.currentImages
    )
    .task(id: self.inlines) {
      let images = (try? await self.loadInlineImages()) ?? [:]
      // provider 即使忽略取消也不能把旧 A 结果写回 A→B 或已移除的分支。
      guard !Task.isCancelled else { return }
      self.loaded = LoadedImages(inlines: self.inlines, images: images)
    }
  }

  private func loadInlineImages() async throws -> [String: Image] {
    let images = Set(self.inlines.flatMap(\.inlineImages))
    guard !images.isEmpty else { return [:] }

    return await withTaskGroup(of: (String, Image?).self) { taskGroup in
      for image in images {
        guard let url = URL(string: image.source, relativeTo: self.imageBaseURL) else {
          continue
        }

        taskGroup.addTask {
          (image.source, try? await self.inlineImageProvider.image(with: url, label: image.alt))
        }
      }

      var inlineImages: [String: Image] = [:]

      for await result in taskGroup {
        inlineImages[result.0] = result.1
      }

      return inlineImages
    }
  }
}
