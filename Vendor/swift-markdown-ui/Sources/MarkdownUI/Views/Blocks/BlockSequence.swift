import SwiftUI

struct BlockSequence<Data, Content>: View
where
  Data: Sequence,
  Data.Element: Hashable,
  Content: View
{
  @Environment(\.multilineTextAlignment) private var textAlignment
  @Environment(\.tightSpacingEnabled) private var tightSpacingEnabled

  @State private var blockMargins: [Int: BlockMargin] = [:]

  private let data: [Indexed<Data.Element>]
  private let content: (Int, Data.Element) -> Content

  init(
    _ data: Data,
    @ViewBuilder content: @escaping (_ index: Int, _ element: Data.Element) -> Content
  ) {
    self.data = data.indexed()
    self.content = content
  }

  var body: some View {
    VStack(alignment: self.textAlignment.alignment.horizontal, spacing: 0) {
      // 内容增长不能改变块身份，否则流式代码块会丢失横向滚动位置，
      // blockMargins 也会为每个 token 留下一份过期的哈希键。
      ForEach(self.data, id: \.index) { element in
        self.content(element.index, element.value)
          .onPreferenceChange(BlockMarginsPreference.self) { value in
            self.blockMargins[element.index] = value
          }
          .padding(.top, self.topPaddingLength(for: element))
      }
    }
    .onChange(of: self.data.count) { count in
      self.blockMargins = self.blockMargins.filter { $0.key < count }
    }
  }

  private func topPaddingLength(for element: Indexed<Data.Element>) -> CGFloat? {
    guard element.index > 0 else {
      return 0
    }

    let topSpacing = self.blockMargins[element.index]?.top
    let predecessor = self.data[element.index - 1]
    let predecessorBottomSpacing =
      self.tightSpacingEnabled ? 0 : self.blockMargins[predecessor.index]?.bottom

    return [topSpacing, predecessorBottomSpacing]
      .compactMap { $0 }
      .max()
  }
}

extension BlockSequence where Data == [BlockNode], Content == BlockNode {
  init(_ blocks: [BlockNode]) {
    self.init(blocks) { $1 }
  }
}

extension TextAlignment {
  fileprivate var alignment: Alignment {
    switch self {
    case .leading:
      return .leading
    case .center:
      return .center
    case .trailing:
      return .trailing
    }
  }
}
