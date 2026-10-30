import SwiftUI
import MarkdownUI
import DeepSeekHarnessShared
import UniformTypeIdentifiers

struct MarkdownTableModel {
    let cells: [[MarkdownUI.MarkdownContent]]
    let plainRows: [[String]]
    let alignments: [TextTableColumnAlignment]

    init(content: MarkdownUI.MarkdownContent) {
        cells = content.tableCells ?? []
        plainRows = content.tablePlainTextRows
        alignments = content.tableColumnAlignments
    }

    func widths(fontSize: CGFloat, viewportWidth: CGFloat = 0) -> [CGFloat] {
        let font = UIFont.systemFont(ofSize: fontSize)
        let naturalWidths = alignments.indices.map { column in
            let natural = plainRows.flatMap { row in
                (row.indices.contains(column) ? row[column] : "").components(separatedBy: .newlines)
            }.map { ($0 as NSString).size(withAttributes: [.font: font]).width }.max() ?? 0
            return min(260, max(96, ceil(natural) + 24))
        }
        let total = naturalWidths.reduce(0, +)
        // 少列表格按原列宽比例铺满容器；宽表保持原宽度，通过横向滚动浏览。
        guard total > 0, viewportWidth.isFinite, viewportWidth > total else { return naturalWidths }
        return naturalWidths.map { $0 * viewportWidth / total }
    }
}

/// 表格宽度由列内容决定，聊天视口只负责横向滚动；全屏使用同一份单元格快照。
struct MarkdownTableCard: View {
    let model: MarkdownTableModel
    let fontSize: CGFloat
    @State private var fullscreen = false
    @State private var fullscreenModel: MarkdownTableModel?

    init(content: MarkdownUI.MarkdownContent, fontSize: CGFloat) {
        model = MarkdownTableModel(content: content)
        self.fontSize = fontSize
    }

    var body: some View {
        VStack(spacing: 0) {
            MarkdownTableActions(model: model, onExpand: { fullscreenModel = model; fullscreen = true })
            MarkdownTableViewport(model: model, fontSize: fontSize, roundTopCorners: false)
        }
        .background { Rectangle().fill(DocumentPageAppearance.toolbar) }
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .accessibilityIdentifier("markdown-table-card")
        .fullScreenCover(isPresented: $fullscreen) {
            MarkdownTablePage(model: fullscreenModel ?? model, fontSize: fontSize)
        }
    }
}

@MainActor
struct MarkdownTablePage: View {
    @Environment(\.dismiss) private var dismiss
    @StateObject private var rotation: DocumentPreviewRotation
    let model: MarkdownTableModel
    let fontSize: CGFloat

    init(model: MarkdownTableModel, fontSize: CGFloat, rotation: DocumentPreviewRotation? = nil) {
        self.model = model
        self.fontSize = fontSize
        _rotation = StateObject(wrappedValue: rotation ?? DocumentPreviewRotation())
    }

    var body: some View {
        DocumentRotatingLayout(isRotated: rotation.isRotated) {
            VStack(spacing: 0) {
                MarkdownTableActions(model: model, onClose: { dismiss() }, onRotate: rotation.toggle)
                ScrollView(.vertical, showsIndicators: true) {
                    MarkdownTableViewport(model: model, fontSize: fontSize)
                }
                .padding(.horizontal, 20)
                .padding(.top, 12)
                .padding(.bottom, 20)
            }
        }
        .background { Rectangle().fill(DocumentPageAppearance.background).ignoresSafeArea() }
        .accessibilityIdentifier("markdown-table-fullscreen")
    }
}

private struct MarkdownTableViewportWidth: PreferenceKey {
    static let defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}

/// 读取实际容器宽度，适配 iPad 分屏和侧栏；只测宽，不让 GeometryReader 占据正文高度。
private struct MarkdownTableViewport: View {
    let model: MarkdownTableModel
    let fontSize: CGFloat
    var roundTopCorners = true
    @State private var viewportWidth: CGFloat = 0

    var body: some View {
        let outline = MarkdownTableOutline(roundTopCorners: roundTopCorners)
        ScrollView(.horizontal, showsIndicators: true) {
            MarkdownTableGrid(model: model, fontSize: fontSize,
                widths: model.widths(fontSize: fontSize, viewportWidth: viewportWidth))
        }
        .fixedSize(horizontal: false, vertical: true)
        .clipShape(outline)
        .overlay(outline.strokeBorder(DocumentPageAppearance.border, lineWidth: 0.5).allowsHitTesting(false))
        .background {
            GeometryReader { geometry in
                Color.clear.preference(key: MarkdownTableViewportWidth.self, value: geometry.size.width)
            }
        }
        .onPreferenceChange(MarkdownTableViewportWidth.self) { width in
            if width.isFinite, width > 0, abs(viewportWidth - width) > 0.5 { viewportWidth = width }
        }
    }
}

private struct MarkdownTableGrid: View {
    let model: MarkdownTableModel
    let fontSize: CGFloat
    let widths: [CGFloat]

    var body: some View {
        Grid(horizontalSpacing: 0, verticalSpacing: 0) {
            ForEach(model.cells.indices, id: \.self) { row in
                GridRow(alignment: .top) {
                    ForEach(widths.indices, id: \.self) { column in
                        let alignment = alignment(column)
                        Group {
                            if model.cells[row].indices.contains(column) {
                                Markdown(model.cells[row][column])
                                    .markdownTheme(.basic.text {
                                        FontSize(fontSize)
                                        ForegroundColor(DocumentPageAppearance.foreground)
                                        BackgroundColor(nil)
                                        FontWeight(row == 0 ? .semibold : .regular)
                                    }.paragraph { configuration in
                                        configuration.label
                                            .fixedSize(horizontal: false, vertical: true)
                                            .relativeLineSpacing(.em(0.18))
                                    })
                                    .dynamicTypeSize(.large)
                                    .multilineTextAlignment(textAlignment(column))
                            } else { Text(verbatim: " ") }
                        }
                        .padding(12)
                        .frame(width: widths[column], alignment: alignment)
                        .frame(maxHeight: .infinity, alignment: alignment)
                        .background {
                            Rectangle().fill(row == 0 ? DocumentPageAppearance.headerFill
                                : row.isMultiple(of: 2) ? DocumentPageAppearance.alternateFill : DocumentPageAppearance.background)
                        }
                        .overlay(Rectangle().strokeBorder(DocumentPageAppearance.border, lineWidth: 0.5))
                    }
                }
            }
        }
        .fixedSize(horizontal: true, vertical: true)
        .accessibilityIdentifier("markdown-table-grid")
    }

    private func textAlignment(_ column: Int) -> TextAlignment {
        switch model.alignments[column] {
        case .center: return .center
        case .trailing: return .trailing
        default: return .leading
        }
    }

    private func alignment(_ column: Int) -> Alignment {
        switch model.alignments[column] {
        case .center: return .top
        case .trailing: return .topTrailing
        default: return .topLeading
        }
    }
}

/// 圆角和弧形封边只属于整张表格；卡片内的表头直接衔接上方操作栏。
private struct MarkdownTableOutline: InsettableShape {
    let roundTopCorners: Bool
    var insetAmount: CGFloat = 0

    func path(in rect: CGRect) -> Path {
        var corners: UIRectCorner = [.bottomLeft, .bottomRight]
        if roundTopCorners { corners.formUnion([.topLeft, .topRight]) }
        let radius = max(0, 12 - insetAmount)
        return Path(UIBezierPath(roundedRect: rect.insetBy(dx: insetAmount, dy: insetAmount),
                                 byRoundingCorners: corners,
                                 cornerRadii: CGSize(width: radius, height: radius)).cgPath)
    }

    func inset(by amount: CGFloat) -> Self {
        var shape = self
        shape.insetAmount += amount
        return shape
    }
}

private struct MarkdownTableActions: View {
    let model: MarkdownTableModel
    var onExpand: (() -> Void)?
    var onClose: (() -> Void)?
    var onRotate: (() -> Void)?
    @State private var copied = false
    @State private var exporting = false
    @State private var exportDocument: MarkdownTableCSVDocument?
    @State private var exportError: String?

    var body: some View {
        Group {
            if let onClose {
                DocumentPageHeader(title: "表格", closeIdentifier: "markdown-table-close", onClose: onClose) {
                    buttons
                }
            } else {
                HStack(spacing: 0) {
                    Text("表格").appFont(.system(size: 12)).foregroundStyle(DocumentPageAppearance.icon).padding(.leading, 12)
                    Spacer(minLength: 0)
                    buttons
                }
                .frame(minHeight: 44)
            }
        }
        .task(id: copied) {
            if copied {
                try? await Task.sleep(for: .milliseconds(1800))
                if !Task.isCancelled { copied = false }
            }
        }
        .fileExporter(isPresented: $exporting, document: exportDocument, contentType: .commaSeparatedText, defaultFilename: "table.csv") { result in
            if case .failure(let error) = result { exportError = error.localizedDescription }
            exportDocument = nil
        }
        .alert("保存失败", isPresented: Binding(get: { exportError != nil }, set: { if !$0 { exportError = nil } })) {
            Button("关闭", role: .cancel) { exportError = nil }
        } message: { Text(exportError ?? "") }
    }

    private var buttons: some View {
        HStack(spacing: 0) {
            if copied && onClose == nil { Text("已复制").appFont(.system(size: 12)).foregroundStyle(DocumentPageAppearance.icon) }
            if let onRotate {
                action("TableRotate", label: "旋转屏幕", onRotate)
                    .accessibilityIdentifier("markdown-table-rotate")
            }
            action("TableCopy", label: "复制表格") {
                UIPasteboard.general.string = MarkdownTableExport.shared.plainText(rows: model.plainRows)
                copied = true
            }
            action("TableDownload", label: "下载 CSV") {
                exportDocument = MarkdownTableCSVDocument(data: Data(MarkdownTableExport.shared.csv(rows: model.plainRows).utf8))
                exporting = true
            }
            if let onExpand {
                action("TableExpand", label: "全屏浏览表格", onExpand)
                    .accessibilityIdentifier("markdown-table-expand")
            }
        }
    }

    private func action(_ symbol: String, label: LocalizedStringKey, _ callback: @escaping () -> Void) -> some View {
        Button(action: callback) {
            Image(symbol).renderingMode(.template).resizable().scaledToFit()
                .frame(width: 20, height: 20).foregroundStyle(DocumentPageAppearance.icon)
                .frame(width: 44, height: 44).contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(label))
    }
}

struct MarkdownTableCSVDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.commaSeparatedText] }
    let data: Data
    init(data: Data) { self.data = data }
    init(configuration: ReadConfiguration) throws { data = configuration.file.regularFileContents ?? Data() }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: data) }
}
