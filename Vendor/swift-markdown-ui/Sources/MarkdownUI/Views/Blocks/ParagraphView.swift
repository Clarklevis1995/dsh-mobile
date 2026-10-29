import SwiftUI

struct ParagraphView: View {
  @Environment(\.theme.paragraph) private var paragraph

  private let content: [InlineNode]

  init(content: String) {
    self.init(
      content: [
        .text(content.hasSuffix("\n") ? String(content.dropLast()) : content)
      ]
    )
  }

  init(content: [InlineNode]) {
    self.content = content
  }

  var body: some View {
    self.paragraph.makeBody(
      configuration: .init(
        label: .init(self.label),
        content: .init(block: .paragraph(content: self.content))
      )
    )
  }

  @ViewBuilder private var label: some View {
    if let sections = self.displayMathSections {
      VStack(alignment: .leading, spacing: 8) {
        ForEach(sections.indices, id: \.self) { index in
          self.inlineLabel(sections[index])
        }
      }
    } else {
      self.inlineLabel(self.content)
    }
  }

  /// 独立公式即使与相邻正文之间没有空行，也应保持块级布局和横向滚动。
  private var displayMathSections: [[InlineNode]]? {
    guard self.content.count > 1, self.content.contains(where: Self.isDisplayMath) else { return nil }
    var sections: [[InlineNode]] = []
    var pending: [InlineNode] = []
    func flush() {
      while pending.first == .softBreak { pending.removeFirst() }
      while pending.last == .softBreak { pending.removeLast() }
      if !pending.isEmpty { sections.append(pending) }
      pending = []
    }
    for inline in self.content {
      if Self.isDisplayMath(inline) {
        flush()
        sections.append([inline])
      } else { pending.append(inline) }
    }
    flush()
    return sections
  }

  private static func isDisplayMath(_ inline: InlineNode) -> Bool {
    guard case .image(let source, _) = inline else { return false }
    return source.hasPrefix("dsh-math://display/")
  }

  @ViewBuilder private func inlineLabel(_ content: [InlineNode]) -> some View {
    if let imageView = ImageView(content) {
      imageView
    } else if #available(iOS 16.0, macOS 13.0, tvOS 16.0, watchOS 9.0, *),
      let imageFlow = ImageFlow(content)
    {
      imageFlow
    } else {
      InlineText(content)
    }
  }
}
