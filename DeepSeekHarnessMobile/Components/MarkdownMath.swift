import Foundation
import MarkdownUI
import SwiftMath
import SwiftUI
import UIKit
import ImageIO
import UniformTypeIdentifiers

/// 在 CommonMark 吞掉 \(、\[ 的转义符之前，保护数学内容；原消息仍用于复制和选择文字。
enum MarkdownMath {
    struct Formula: Equatable {
        let latex: String
        let display: Bool

        var url: URL {
            let payload = Data(latex.utf8).base64EncodedString()
                .replacingOccurrences(of: "+", with: "-")
                .replacingOccurrences(of: "/", with: "_")
                .replacingOccurrences(of: "=", with: "")
            return URL(string: "dsh-math://\(display ? "display" : "inline")/\(payload)")!
        }

        init(latex: String, display: Bool) {
            self.latex = latex
            self.display = display
        }

        init?(url: URL?) {
            guard let url, url.scheme == "dsh-math",
                  url.host == "display" || url.host == "inline" else { return nil }
            var payload = String(url.path.dropFirst()).replacingOccurrences(of: "-", with: "+")
                .replacingOccurrences(of: "_", with: "/")
            payload += String(repeating: "=", count: (4 - payload.count % 4) % 4)
            guard let data = Data(base64Encoded: payload),
                  let latex = String(data: data, encoding: .utf8) else { return nil }
            self.init(latex: latex, display: url.host == "display")
        }
    }

    static func prepare(_ source: String, forSelection: Bool = false) -> String {
        guard source.contains("$") || source.contains("\\(") || source.contains("\\[") else { return source }
        let characters = Array(source)
        var result = ""
        var index = 0
        var fence: (Character, Int)?
        var lineStart = true

        func matches(_ token: String, at offset: Int) -> Bool {
            let token = Array(token)
            return offset + token.count <= characters.count
                && characters[offset..<(offset + token.count)].elementsEqual(token)
        }
        func runLength(at offset: Int) -> Int {
            var end = offset
            while end < characters.count, characters[end] == characters[offset] { end += 1 }
            return end - offset
        }
        func appendThrough(_ end: Int) {
            result += String(characters[index..<end])
            lineStart = end > index && characters[end - 1] == "\n"
            index = end
        }

        while index < characters.count {
            if lineStart {
                let end = characters[index...].firstIndex(of: "\n").map { $0 + 1 } ?? characters.count
                var marker = index
                // 引用和列表中的围栏也必须保持源码，不能把示例当成公式。
                while marker < end, characters[marker] == " " || characters[marker] == ">" { marker += 1 }
                if marker < end, characters[marker] == "[",
                   String(characters[marker..<end]).range(of: #"^\[[^\]]+\]:"#, options: .regularExpression) != nil {
                    appendThrough(end)
                    continue
                }
                if marker < end, characters[marker] == "`" || characters[marker] == "~" {
                    let count = runLength(at: marker)
                    if count >= 3 {
                        if let active = fence {
                            let tail = String(characters[(marker + count)..<end]).trimmingCharacters(in: .whitespacesAndNewlines)
                            if active.0 == characters[marker], count >= active.1, tail.isEmpty { fence = nil }
                        } else {
                            fence = (characters[marker], count)
                        }
                        appendThrough(end)
                        continue
                    }
                }
                if fence != nil || matches("    ", at: index) || characters[index] == "\t" {
                    appendThrough(end)
                    continue
                }
            }
            lineStart = false
            let character = characters[index]
            if matches("![", at: index) {
                var end = index + 2
                var depth = 1
                while end < characters.count, depth > 0 {
                    if characters[end] == "\\" { end = min(end + 2, characters.count); continue }
                    if characters[end] == "[" { depth += 1 }
                    if characters[end] == "]" { depth -= 1 }
                    end += 1
                }
                // 留下 ] 给下方的链接目的地分支处理。
                appendThrough(depth == 0 ? end - 1 : end)
                continue
            }
            if character == "`" {
                let count = runLength(at: index)
                var end = index + count
                var closing: Int?
                while end < characters.count {
                    if characters[end] == "`" {
                        let length = runLength(at: end)
                        if length == count { closing = end + length; break }
                        end += length
                    } else { end += 1 }
                }
                appendThrough(closing ?? index + count)
                continue
            }
            // 跳过链接目的地、自动链接及 HTML 标签中的美元和反斜杠。
            if matches("](", at: index) {
                var end = index + 2
                var depth = 1
                while end < characters.count, depth > 0 {
                    if characters[end] == "\\" { end = min(end + 2, characters.count); continue }
                    if characters[end] == "(" { depth += 1 }
                    if characters[end] == ")" { depth -= 1 }
                    end += 1
                }
                appendThrough(end)
                continue
            }
            if matches("https://", at: index) || matches("http://", at: index) {
                let end = characters[index...].firstIndex(where: \.isWhitespace) ?? characters.count
                appendThrough(end)
                continue
            }
            if character == "<", index + 1 < characters.count,
               characters[index + 1].isLetter || "/!?".contains(characters[index + 1]),
               let end = characters[index...].firstIndex(of: ">") {
                appendThrough(end + 1)
                continue
            }

            let opening: String?
            let closing: String
            let display: Bool
            if matches("\\[", at: index) { opening = "\\["; closing = "\\]"; display = true }
            else if matches("\\(", at: index) { opening = "\\("; closing = "\\)"; display = false }
            else if matches("$$", at: index) { opening = "$$"; closing = "$$"; display = true }
            else if character == "$" { opening = "$"; closing = "$"; display = false }
            else { opening = nil; closing = ""; display = false }

            if let opening {
                let start = index + opening.count
                var end = start
                var found: Int?
                let validStart = opening != "$" || (start < characters.count && !characters[start].isWhitespace)
                while validStart, end < characters.count {
                    if !display, characters[end] == "\n" { break }
                    if matches(closing, at: end) {
                        let after = end + closing.count
                        let validEnd = opening != "$" || (end > start && !characters[end - 1].isWhitespace
                            && (after == characters.count || (!characters[after].isNumber && characters[after] != "$")))
                        if validEnd { found = end; break }
                    }
                    if characters[end] == "\\" { end = min(end + 2, characters.count) }
                    else { end += 1 }
                }
                if let end = found, end > start {
                    if forSelection {
                        // 实体解码发生在 Markdown 转义解析之后，原始反斜杠、换行和分隔符不会丢失。
                        result += String(characters[index..<(end + closing.count)]).unicodeScalars
                            .map { "&#\($0.value);" }.joined()
                        index = end + closing.count
                        continue
                    }
                    var latex = String(characters[start..<end])
                    let lineBegin = characters[..<index].lastIndex(of: "\n").map { $0 + 1 } ?? 0
                    let prefix = String(characters[lineBegin..<index])
                    if display, prefix.range(of: #"^(?:[ \t]*>[ \t]?)+[ \t]*$"#, options: .regularExpression) != nil {
                        latex = latex.components(separatedBy: "\n").map {
                            $0.replacingOccurrences(of: #"^[ \t]*(?:>[ \t]?)+"#, with: "", options: .regularExpression)
                        }.joined(separator: "\n")
                    }
                    latex = latex.trimmingCharacters(in: .whitespacesAndNewlines)
                    if !latex.isEmpty {
                        let formula = Formula(latex: latex, display: display)
                        let alt = latex.reduce(into: "") { value, character in
                            if "\\[]`*_<>|".contains(character) { value.append("\\") }
                            value.append(character == "\n" ? " " : character)
                        }
                        result += "![\(alt)](\(formula.url.absoluteString))"
                        index = end + closing.count
                        continue
                    }
                }
                // 未闭合的 $$ 不能回头把第二个 $ 当成行内公式的起点。
                appendThrough(start)
                continue
            }
            if character == "\\" { appendThrough(min(index + 2, characters.count)) }
            else { appendThrough(index + 1) }
        }
        return result
    }
}

@MainActor
enum MarkdownMathRenderer {
    private static let cache = MarkdownRenderProductCache<String, UIImage?>(
        countLimit: 128, costLimit: 16 * 1_024 * 1_024
    )

    static func image(for formula: MarkdownMath.Formula, fontSize: CGFloat, color: UIColor) -> UIImage? {
        let key = "\(formula.url.absoluteString)|\(fontSize)|\(color.description)"
        return cache.value(for: key) {
            guard formula.latex.count <= 8_000 else { return (nil, 0) }
            // SwiftMath 1.7 不支持显式括号尺寸和行间距参数；只移除排版提示，保留运算内容。
            let latex = formula.latex
                .replacingOccurrences(of: #"\\(?:big|Big|bigg|Bigg)[lrm]?\b"#, with: "", options: .regularExpression)
                .replacingOccurrences(of: #"(\\\\)\s*\[-?\d+(?:\.\d+)?(?:pt|em|ex)\]"#, with: "$1", options: .regularExpression)
            let label = MTMathUILabel()
            label.fontSize = fontSize
            label.labelMode = formula.display ? .display : .text
            label.textColor = color
            label.textAlignment = .left
            label.latex = latex
            guard label.error == nil else { return (nil, 0) }
            let size = label.intrinsicContentSize
            guard size.width.isFinite, size.height.isFinite, size.width > 0, size.height > 0,
                  size.width <= 8_192, size.height <= 2_048 else { return (nil, 0) }
            label.frame = CGRect(origin: .zero, size: CGSize(width: ceil(size.width) + 2, height: ceil(size.height) + 2))
            label.layoutIfNeeded()
            let image = UIGraphicsImageRenderer(size: label.bounds.size).image { context in
                // 数学字形采用左下角原点，不能直接快照 UIKit 的翻转图层。
                context.cgContext.translateBy(x: 0, y: label.bounds.height)
                context.cgContext.scaleBy(x: 1, y: -1)
                label.displayList?.draw(context.cgContext)
            }.withBaselineOffset(fromBottom: (label.displayList?.descent ?? 0) + 1)
            let cost = (image.cgImage?.bytesPerRow ?? 0) * (image.cgImage?.height ?? 0)
            return (image, cost)
        }
    }
}

/// 会话身份随渲染树传递，避免图片请求误用当前选中的其他会话。
struct WorkspaceMarkdownImageContext: Sendable {
    let id: String
    var dimensions: @Sendable (String) -> GatewayImageDimensions? = { _ in nil }
    let load: @MainActor @Sendable (String) async throws -> Data

    /// 文件里的图片相对文档目录解析；工作区根目录仍由原加载器和网关校验。
    func relative(to documentPath: String) -> WorkspaceMarkdownImageContext {
        WorkspaceMarkdownImageContext(id: "\(id):\(documentPath.utf8.count):\(documentPath)", dimensions: { imagePath in
            guard let path = try? Self.resolve(imagePath, relativeTo: documentPath) else { return nil }
            return dimensions(path)
        }) { imagePath in
            try await load(Self.resolve(imagePath, relativeTo: documentPath))
        }
    }

    private static func resolve(_ imagePath: String, relativeTo documentPath: String) throws -> String {
        if imagePath.hasPrefix("/") { return imagePath }
        let directory = documentPath.split(separator: "/", omittingEmptySubsequences: false).dropLast().joined(separator: "/")
        let joined = directory.isEmpty ? imagePath : directory + "/" + imagePath
        var components: [Substring] = []
        for component in joined.split(separator: "/") {
            switch component {
            case ".": continue
            case "..":
                guard !components.isEmpty else { throw URLError(.badURL) }
                components.removeLast()
            default: components.append(component)
            }
        }
        guard !components.isEmpty else { throw URLError(.badURL) }
        return (documentPath.hasPrefix("/") ? "/" : "") + components.joined(separator: "/")
    }

    static func path(for url: URL) -> String? {
        guard url.scheme == nil || url.isFileURL, !url.path.isEmpty else { return nil }
        return url.path
    }
}

private struct WorkspaceMarkdownImageContextKey: EnvironmentKey {
    static let defaultValue: WorkspaceMarkdownImageContext? = nil
}

extension EnvironmentValues {
    var workspaceMarkdownImageContext: WorkspaceMarkdownImageContext? {
        get { self[WorkspaceMarkdownImageContextKey.self] }
        set { self[WorkspaceMarkdownImageContextKey.self] = newValue }
    }
}

/// 预览和导出复用下载到的原始字节，保留 PNG/JPEG 等原始格式。
struct MarkdownImageAsset: FileDocument, Identifiable {
    static var readableContentTypes: [UTType] { [.image] }
    let id: String
    let data: Data
    let contentType: UTType
    let name: String
    let attachment: GatewayImageAttachment

    init(data: Data, url: URL) throws {
        guard data.count <= 32 * 1024 * 1024,
              let source = CGImageSourceCreateWithData(data as CFData, nil),
              let type = CGImageSourceGetType(source),
              let dimensions = GatewayImageInspector.dimensions(of: data),
              dimensions.longestSide <= 16_384,
              dimensions.width * dimensions.height <= 80_000_000 else {
            throw URLError(.cannotDecodeContentData)
        }
        id = url.absoluteString
        self.data = data
        contentType = UTType(type as String) ?? .image
        let stem = url.deletingPathExtension().lastPathComponent
        name = (stem.isEmpty ? "image" : stem) + "." + (contentType.preferredFilenameExtension ?? "png")
        attachment = GatewayImageAttachment(attachmentId: id, mediaType: contentType.preferredMIMEType ?? "image/png",
            bytes: data.count, width: dimensions.width, height: dimensions.height, name: name)
    }

    init(configuration: ReadConfiguration) throws {
        guard let data = configuration.file.regularFileContents else { throw CocoaError(.fileReadCorruptFile) }
        try self.init(data: data, url: URL(fileURLWithPath: configuration.file.preferredFilename ?? "image"))
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }

    static func load(url: URL, workspace: WorkspaceMarkdownImageContext?) async throws -> MarkdownImageAsset {
        let data: Data
        if let path = WorkspaceMarkdownImageContext.path(for: url), let workspace {
            data = try await workspace.load(path)
        } else {
            guard ["http", "https"].contains(url.scheme?.lowercased() ?? "") else { throw URLError(.unsupportedURL) }
            let (downloaded, response) = try await URLSession.shared.data(from: url)
            guard let response = response as? HTTPURLResponse, (200..<300).contains(response.statusCode) else {
                throw URLError(.badServerResponse)
            }
            data = downloaded
        }
        return try MarkdownImageAsset(data: data, url: url)
    }
}

/// 仅使用插件明确给出的文件路径与附件关联，不能按文件名猜测其他目录中的图片。
func markdownGeneratedImageReferences(in records: [SessionEvent]) -> [String: GatewayImageAttachment] {
    var result: [String: GatewayImageAttachment] = [:]
    func visit(_ value: JSONValue?, depth: Int = 0) {
        guard let value, depth <= 16 else { return }
        if value["kind"]?.stringValue == "dsh-image-gen",
           let path = value["savedTo"]?.stringValue, !path.isEmpty,
           let image = value["attachment"]?.decode(GatewayImageAttachment.self), image.isValidToolImage {
            result[path] = image
        }
        for element in value.arrayValue ?? [] { visit(element, depth: depth + 1) }
        for key in ["meta", "message", "content"] { visit(value[key], depth: depth + 1) }
        if value["kind"]?.stringValue == "dsh-image-gen-batch" { visit(value["images"], depth: depth + 1) }
    }
    for record in records { visit(record.event.raw) }
    return result
}

/// 位图缓存与轻量尺寸缓存分离；释放位图后重新进入视口也能保持原来的占位高度。
final class MarkdownImageRenderCache: @unchecked Sendable {
    final class Entry: @unchecked Sendable {
        let asset: MarkdownImageAsset
        let image: UIImage
        init(asset: MarkdownImageAsset, image: UIImage) { self.asset = asset; self.image = image }
    }
    private final class SizeBox: NSObject {
        let size: GatewayImageDimensions
        init(_ size: GatewayImageDimensions) { self.size = size }
    }
    static let shared = MarkdownImageRenderCache()
    private let images = NSCache<NSString, Entry>()
    private let sizes = NSCache<NSString, SizeBox>()
    @MainActor private var pending: [String: Task<Entry, Error>] = [:]

    init() {
        images.totalCostLimit = 64 * 1024 * 1024
        images.countLimit = 32
        sizes.countLimit = 512
    }

    static func key(url: URL, context: WorkspaceMarkdownImageContext?) -> String {
        "\(context?.id ?? "network"):\(url.absoluteString)"
    }
    func cached(_ key: String) -> Entry? { images.object(forKey: key as NSString) }
    func dimensions(_ key: String) -> GatewayImageDimensions? { sizes.object(forKey: key as NSString)?.size }

    @MainActor
    func load(url: URL, context: WorkspaceMarkdownImageContext?) async throws -> Entry {
        let key = Self.key(url: url, context: context)
        if let entry = cached(key) { return entry }
        if let task = pending[key] { return try await task.value }
        let task = Task { () throws -> Entry in
            let asset = try await MarkdownImageAsset.load(url: url, workspace: context)
            // ImageIO 在后台缩采样并立即解码，滚动主线程不再触发原图解压。
            return try await Task.detached(priority: .userInitiated) {
                guard let source = CGImageSourceCreateWithData(asset.data as CFData,
                    [kCGImageSourceShouldCache: false] as CFDictionary),
                      let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                        kCGImageSourceCreateThumbnailFromImageAlways: true,
                        kCGImageSourceCreateThumbnailWithTransform: true,
                        kCGImageSourceThumbnailMaxPixelSize: 1200,
                        kCGImageSourceShouldCacheImmediately: true
                      ] as CFDictionary) else { throw URLError(.cannotDecodeContentData) }
                return Entry(asset: asset, image: UIImage(cgImage: cgImage))
            }.value
        }
        pending[key] = task
        defer { pending[key] = nil }
        let entry = try await task.value
        images.setObject(entry, forKey: key as NSString,
            cost: entry.asset.data.count + (entry.image.cgImage.map { $0.bytesPerRow * $0.height } ?? 0))
        sizes.setObject(SizeBox(GatewayImageDimensions(width: entry.asset.attachment.width,
            height: entry.asset.attachment.height)), forKey: key as NSString)
        return entry
    }
}

/// 只用元数据决定尺寸，加载状态和子视图理想高度不参与测量。
struct MarkdownImageSlot: Layout {
    let dimensions: GatewayImageDimensions
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = max(0, proposal.width ?? 320)
        return CGSize(width: width, height: width * CGFloat(max(1, dimensions.height)) / CGFloat(max(1, dimensions.width)))
    }
    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        for view in subviews {
            view.place(at: bounds.origin, anchor: .topLeading,
                       proposal: ProposedViewSize(width: bounds.width, height: bounds.height))
        }
    }
}

private struct MarkdownAttachmentImage: View {
    let url: URL
    let context: WorkspaceMarkdownImageContext?
    @State private var image: UIImage?
    @State private var asset: MarkdownImageAsset?
    @State private var preview: MarkdownImageAsset?
    @State private var exporting = false
    @State private var exportError: String?
    @State private var failed = false
    @State private var attempt = 0
    @State private var layoutDimensions: GatewayImageDimensions?
    @State private var loadedKey: String?

    init(url: URL, context: WorkspaceMarkdownImageContext?) {
        self.url = url
        self.context = context
        let key = MarkdownImageRenderCache.key(url: url, context: context)
        let cached = MarkdownImageRenderCache.shared.cached(key)
        _image = State(initialValue: cached?.image)
        _asset = State(initialValue: cached?.asset)
        _loadedKey = State(initialValue: cached == nil ? nil : key)
        _layoutDimensions = State(initialValue: Self.knownDimensions(url: url, context: context))
    }

    private static func knownDimensions(url: URL, context: WorkspaceMarkdownImageContext?) -> GatewayImageDimensions? {
        if let path = WorkspaceMarkdownImageContext.path(for: url), let dimensions = context?.dimensions(path) {
            return dimensions
        }
        return MarkdownImageRenderCache.shared.dimensions(MarkdownImageRenderCache.key(url: url, context: context))
    }

    var body: some View {
        MarkdownImageSlot(dimensions: layoutDimensions ?? GatewayImageDimensions(width: 1, height: 1)) {
            Color.clear.overlay {
                content
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .task(id: "\(context?.id ?? "network"):\(url.absoluteString):\(attempt)") {
            let key = MarkdownImageRenderCache.key(url: url, context: context)
            if loadedKey == key, image != nil, asset != nil { return }
            loadedKey = nil
            image = nil
            asset = nil
            layoutDimensions = Self.knownDimensions(url: url, context: context)
            failed = false
            do {
                let loaded = try await MarkdownImageRenderCache.shared.load(url: url, context: context)
                try Task.checkCancellation()
                if layoutDimensions == nil {
                    layoutDimensions = GatewayImageDimensions(width: loaded.asset.attachment.width, height: loaded.asset.attachment.height)
                }
                asset = loaded.asset
                image = loaded.image
                loadedKey = key
            } catch {
                if !Task.isCancelled { failed = true }
            }
        }
        .fullScreenCover(item: $preview) { asset in
            AttachmentGallery(attachments: [asset.attachment], initialID: asset.id,
                imageData: { _ in asset.data }, imageError: { _ in nil }, onRetry: { _ in }, exportDocument: asset)
        }
        .fileExporter(isPresented: $exporting, document: asset, contentType: asset?.contentType ?? .image,
                      defaultFilename: asset?.name) { result in
            if case .failure(let error) = result { exportError = error.localizedDescription }
        }
        .alert("保存失败", isPresented: Binding(get: { exportError != nil }, set: { if !$0 { exportError = nil } })) {
            Button("关闭", role: .cancel) { exportError = nil }
        } message: { Text(exportError ?? "") }
    }

    private var content: some View {
        Group {
            if let image, let asset {
                Button { preview = asset } label: {
                    Image(uiImage: image).resizable().scaledToFit()
                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
                .buttonStyle(.plain)
                .accessibilityLabel("预览")
                .accessibilityIdentifier("markdown-image-preview-button")
                .contextMenu {
                    Button { preview = asset } label: { Label("预览", systemImage: "arrow.up.left.and.arrow.down.right") }
                    Button { exporting = true } label: {
                        Label(String(localized: "markdown.image.download", defaultValue: "下载图片"), systemImage: "square.and.arrow.down")
                    }
                }
            } else if failed {
                Button { attempt += 1 } label: {
                    VStack(spacing: 8) {
                        Image(systemName: "photo")
                        Text("重试")
                    }.frame(maxWidth: .infinity, minHeight: 100)
                }.buttonStyle(.plain)
            } else {
                ProgressView().frame(maxWidth: .infinity, minHeight: 100)
            }
        }
    }
}

struct MathMarkdownImageProvider: ImageProvider {
    let fontSize: CGFloat
    var workspace: WorkspaceMarkdownImageContext? = nil

    func makeImage(url: URL?) -> some View {
        if let formula = MarkdownMath.Formula(url: url) {
            MarkdownFormulaImage(formula: formula, fontSize: fontSize)
        } else if let url {
            MarkdownAttachmentImage(url: url, context: workspace)
        }
    }
}

private struct MarkdownFormulaImage: View {
    @Environment(\.colorScheme) private var colorScheme
    let formula: MarkdownMath.Formula
    let fontSize: CGFloat

    var body: some View {
        let color = UIColor.label.resolvedColor(with: UITraitCollection(userInterfaceStyle: colorScheme == .dark ? .dark : .light))
        if let image = MarkdownMathRenderer.image(for: formula, fontSize: fontSize, color: color) {
            ScrollView(.horizontal) {
                Image(uiImage: image)
                    .accessibilityLabel(formula.latex)
                    .padding(.vertical, 4)
            }
            .frame(height: image.size.height + 8)
        } else {
            Text(formula.latex).font(.system(size: fontSize, design: .monospaced))
        }
    }
}

struct MathMarkdownInlineImageProvider: InlineImageProvider {
    let fontSize: CGFloat
    let dark: Bool
    var workspace: WorkspaceMarkdownImageContext? = nil

    func baselineOffset(with url: URL, label: String) async -> CGFloat {
        guard let formula = MarkdownMath.Formula(url: url) else { return 0 }
        return await MainActor.run {
            let color = UIColor.label.resolvedColor(with: UITraitCollection(userInterfaceStyle: dark ? .dark : .light))
            guard let image = MarkdownMathRenderer.image(for: formula, fontSize: fontSize, color: color) else { return 0 }
            // SwiftUI 的 Text(Image) 不采用 UIImage 的基线信息，必须显式下移数学 descent。
            return -(image.baselineOffsetFromBottom ?? 0)
        }
    }

    func image(with url: URL, label: String) async throws -> Image {
        guard let formula = MarkdownMath.Formula(url: url) else {
            if let workspace, let path = WorkspaceMarkdownImageContext.path(for: url) {
                let data = try await workspace.load(path)
                guard let image = UIImage(data: data) else { throw URLError(.cannotDecodeContentData) }
                return Image(uiImage: image)
            }
            return try await DefaultInlineImageProvider.default.image(with: url, label: label)
        }
        return try await MainActor.run {
            let color = UIColor.label.resolvedColor(with: UITraitCollection(userInterfaceStyle: dark ? .dark : .light))
            guard let image = MarkdownMathRenderer.image(for: formula, fontSize: fontSize, color: color) else {
                throw CocoaError(.coderInvalidValue)
            }
            return Image(uiImage: image)
        }
    }
}
