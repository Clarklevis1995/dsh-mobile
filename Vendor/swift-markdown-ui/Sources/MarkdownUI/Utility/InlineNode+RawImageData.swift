import Foundation

struct RawImageData: Hashable {
  var source: String
  var alt: String
  var destination: String?
}

extension InlineNode {
  /// 包含粗体、斜体和链接里的公式图片，不把图片的替代文本作为后代继续遍历。
  var inlineImages: [RawImageData] {
    if let imageData { return [imageData] }
    return children.flatMap(\.inlineImages).filter { $0.source.hasPrefix("dsh-math:") }
  }

  var imageData: RawImageData? {
    switch self {
    case .image(let source, let children):
      return .init(source: source, alt: children.renderPlainText())
    case .link(let destination, let children) where children.count == 1:
      guard var imageData = children.first?.imageData else { return nil }
      imageData.destination = destination
      return imageData
    default:
      return nil
    }
  }
}
