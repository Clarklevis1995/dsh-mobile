import SwiftUI
import DeepSeekHarnessShared
import UIKit
import CoreText

private func changesColor(_ argb: Int64) -> Color {
    Color(red: Double((argb >> 16) & 255) / 255,
          green: Double((argb >> 8) & 255) / 255,
          blue: Double(argb & 255) / 255)
}

/// 文件菜单与审阅正文使用同一套跨端字体及颜色，不影响应用的其他页面。
private enum ReviewTheme {
    static let style = WorkspaceChangesStyle.shared
    static func font(_ size: CGFloat, weight: Font.Weight = .regular) -> Font {
        Font(UIFontMetrics(forTextStyle: .caption1).scaledFont(for: uiFont(size, weight: weight)))
    }
    static func uiFont(_ size: CGFloat, weight: Font.Weight = .regular) -> UIFont {
        let value: Double = weight == .semibold ? 600 : weight == .medium ? 500 : 400
        let descriptor = UIFontDescriptor(name: "NotoSansSC-Thin", size: size).addingAttributes([
            UIFontDescriptor.AttributeName(rawValue: kCTFontVariationAttribute as String): [NSNumber(value: 0x77676874): value]
        ])
        return UIFont(descriptor: descriptor, size: size * AppFontScale.current)
    }
    static func color(_ light: Int64, dark: UInt32) -> Color {
        Color(uiColor: UIColor { trait in
            let rgb = trait.userInterfaceStyle == .dark ? Int64(dark) : light
            return UIColor(red: CGFloat((rgb >> 16) & 255) / 255, green: CGFloat((rgb >> 8) & 255) / 255,
                           blue: CGFloat(rgb & 255) / 255, alpha: 1)
        })
    }
    static let ink = color(style.REVIEW_INK, dark: 0xE9EDF3)
    static let surface = color(0xFFFFFFFF, dark: 0x1C1C1E)
    static let secondary = color(style.REVIEW_SECONDARY, dark: 0xA5AEBA)
    static let muted = color(style.REVIEW_MUTED, dark: 0x8B95A3)
    static let line = color(style.REVIEW_LINE, dark: 0x343A44)
    static let subtle = color(style.REVIEW_SUBTLE, dark: 0x22262D)
    static let blue = color(style.REVIEW_BLUE, dark: 0x8DB1FF)
    static let blueSoft = color(style.REVIEW_BLUE_SOFT, dark: 0x243553)
    static let added = color(style.REVIEW_ADDED_INK, dark: 0x8AD5A7)
    static let deleted = color(style.REVIEW_DELETED_INK, dark: 0xF4A0AF)
    static let addedBG = color(style.REVIEW_ADDED_BG, dark: 0x20372B)
    static let deletedBG = color(style.REVIEW_DELETED_BG, dark: 0x40282F)
    static let hunkBG = color(style.REVIEW_HUNK_BG, dark: 0x2A3039)
}

struct WorkspaceChangesSheet: View {
    let state: SharedWorkspaceChangesSnapshot
    let selectFile: (Int) -> Void
    let retrySummary: () -> Void
    var workspaceName = "工作区"
    var workspaceRoot: String? = nil
    var openFile: ((String) -> Void)? = nil
    @Environment(\.dismiss) private var dismiss
    @State private var selected = 0
    @State private var wrapLines = true

    private var files: [WorkspaceChangedFile] { state.summary?.files ?? [] }
    private var file: WorkspaceChangedFile? { files.indices.contains(selected) ? files[selected] : nil }
    private var coordinate: String { "\(state.sessionId ?? ""):\(state.seq?.intValue ?? -1)" }
    private var filePath: String? {
        file.map { WorkspaceReviewPresentation.shared.absolutePath(path: $0.path, workspaceRoot: workspaceRoot) ?? $0.display }
    }

    var body: some View {
        GeometryReader { geometry in
            let wide = geometry.size.width >= ReviewTheme.style.REVIEW_BREAKPOINT
            VStack(spacing: 0) {
                Capsule().fill(ReviewTheme.line).frame(width: 36, height: 4).frame(height: 20)
                header(wide: wide)
                if !wide { summary.padding(.horizontal, 16).frame(minHeight: 36, alignment: .topLeading) }
                if wide {
                    Rectangle().fill(ReviewTheme.line).frame(height: 1)
                    HStack(spacing: 0) {
                        sidebar(width: geometry.size.width >= ReviewTheme.style.REVIEW_WIDE_BREAKPOINT ? 260 : 248)
                        Rectangle().fill(ReviewTheme.line).frame(width: 1)
                        review(wide: true)
                    }
                } else {
                    review(wide: false)
                    navigation
                }
            }
            .background(ReviewTheme.surface)

        }
        .font(ReviewTheme.font(12))
        .foregroundStyle(ReviewTheme.ink)
        .task(id: coordinate) {
            selected = 0
            if !files.isEmpty { selectFile(0) }
        }
        .onChangeCompat(of: files.count) { _, count in
            if count > 0 && state.selectedIndex == nil { choose(0) }
            if selected >= count { selected = 0 }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("workspace-changes-content")
    }

    private func header(wide: Bool) -> some View {
        HStack(spacing: 20) {
            VStack(alignment: .leading, spacing: 2) {
                Text("文件改动").font(ReviewTheme.font(20, weight: .medium))
                Text("\(workspaceName) · 本轮改动").foregroundStyle(ReviewTheme.secondary).lineLimit(1)
            }
            Spacer(minLength: 8)
            if wide { summary.fixedSize() }
            Button { dismiss() } label: {
                Image("WorkspaceReviewClose").renderingMode(.template).resizable().scaledToFit().frame(width: 20, height: 20)
                    .foregroundStyle(ReviewTheme.secondary).frame(width: 44, height: 44).contentShape(Rectangle())
            }.buttonStyle(.plain).accessibilityLabel("关闭文件改动")
        }.padding(.leading, wide ? 24 : 16).padding(.trailing, 8)
            .frame(minHeight: wide ? 60 : 64).accessibilityElement(children: .contain)
            .accessibilityIdentifier("workspace-changes-header")
    }

    private var summary: some View {
        HStack(spacing: 10) {
            let count = state.summary?.total ?? 0
            Text(files.count < count ? "已列出 \(files.count) / \(count) 个文件" : "\(count) 个文件")
                .foregroundStyle(ReviewTheme.secondary)
            Spacer(minLength: 8)
            if let summary = state.summary {
                Text("+\(summary.added)").font(ReviewTheme.font(14, weight: .semibold)).foregroundStyle(ReviewTheme.added)
                Text("−\(summary.deleted)").font(ReviewTheme.font(14, weight: .semibold)).foregroundStyle(ReviewTheme.deleted)
            }
        }.frame(minHeight: 24).monospacedDigit()
    }

    private func review(wide: Bool) -> some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                if wide {
                    Button { if let file { openFile?(file.path) } } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            HStack(spacing: 12) {
                                Text(file.map { ($0.display as NSString).lastPathComponent } ?? "文件改动")
                                    .font(ReviewTheme.font(14, weight: .semibold)).foregroundStyle(ReviewTheme.ink)
                                    .lineLimit(1).truncationMode(.middle)
                                if let file { Text("+\(file.added)  −\(file.deleted)").foregroundStyle(ReviewTheme.secondary).fixedSize() }
                            }
                            if let filePath { Text(filePath).foregroundStyle(ReviewTheme.blue).lineLimit(1).truncationMode(.middle) }
                        }.frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle())
                    }.buttonStyle(.plain).disabled(file == nil || openFile == nil)
                        .accessibilityIdentifier("workspace-open-file")
                        .accessibilityLabel("查看完整文件")
                        .accessibilityValue(filePath ?? "")
                } else {
                    Menu {
                        Picker("本轮改动文件", selection: Binding(get: { selected }, set: { choose($0) })) {
                            ForEach(Array(files.enumerated()), id: \.offset) { index, entry in
                                Label {
                                    Text(entry.display)
                                    Text("+\(entry.added)  −\(entry.deleted)")
                                } icon: {
                                    Image("FileIcon_\(FileIconCatalog.shared.resolve(name: entry.display, mediaType: nil).assetKey)")
                                        .renderingMode(.original)
                                }.tag(index)
                            }
                        }.pickerStyle(.inline)
                    } label: {
                        HStack(spacing: 10) {
                            Text(file.map { ($0.display as NSString).lastPathComponent } ?? "选择文件")
                                .font(ReviewTheme.font(14, weight: .semibold)).lineLimit(1).truncationMode(.middle)
                            Image("WorkspaceReviewChevron").renderingMode(.template).resizable().scaledToFit()
                                .frame(width: 16, height: 16).foregroundStyle(ReviewTheme.secondary)
                        }.padding(.leading, 12).padding(.trailing, 8).frame(minHeight: 40)
                            .background(ReviewTheme.subtle, in: RoundedRectangle(cornerRadius: 8))
                    }.buttonStyle(.plain).disabled(files.isEmpty)
                        .accessibilityIdentifier("workspace-file-selector")

                }
                Spacer(minLength: 4)
                Button { wrapLines.toggle() } label: {
                    Image(wrapLines ? "WorkspaceReviewWrap" : "WorkspaceReviewNoWrap")
                        .renderingMode(.template).resizable().scaledToFit().frame(width: 24, height: 24)
                        .foregroundStyle(wrapLines ? ReviewTheme.blue : ReviewTheme.secondary)
                        .frame(width: 44, height: 44).contentShape(Rectangle())
                }.buttonStyle(.plain).fixedSize().accessibilityIdentifier("workspace-wrap-lines")
                    .accessibilityLabel("自动换行")
                    .accessibilityValue(wrapLines ? "开启" : "关闭")
                    .accessibilityHint(wrapLines ? "切换为不自动换行" : "切换为自动换行")
            }.padding(.horizontal, wide ? 24 : 16).frame(height: wide ? 64 : 56)
            if !wide, let file, let filePath {
                Button { openFile?(file.path) } label: {
                    Text(filePath).foregroundStyle(ReviewTheme.blue).lineLimit(1).truncationMode(.middle)
                        .multilineTextAlignment(.leading).frame(maxWidth: .infinity, minHeight: 32, alignment: .leading)
                        .padding(.horizontal, 16).padding(.bottom, 8).contentShape(Rectangle())
                }.buttonStyle(.plain).disabled(openFile == nil)
                    .accessibilityIdentifier("workspace-open-file")
                    .accessibilityLabel("查看完整文件").accessibilityValue(filePath)
            }
            Rectangle().fill(ReviewTheme.line).frame(height: 1)
            // 换行只更新正文布局，保留滚动容器身份；切换文件才重置位置。
            diffContent(wide: wide).id("\(coordinate):\(selected)")
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            if wide {
                Text("删除行显示旧行号，其余显示新行号").foregroundStyle(ReviewTheme.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 24).frame(height: 40)
                    .background(ReviewTheme.subtle)
            }
        }
    }

    private func sidebar(width: CGFloat) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 4) {
                Text("本轮文件").foregroundStyle(ReviewTheme.secondary).padding(.bottom, 4)
                ForEach(Array(files.enumerated()), id: \.offset) { index, file in fileOption(file, index: index, iconSize: 28) }
            }.padding(.horizontal, 12).padding(.top, 16).padding(.bottom, 20)
        }.frame(width: width).background(ReviewTheme.subtle)
    }

    private func fileOption(_ file: WorkspaceChangedFile, index: Int, iconSize: CGFloat = 32) -> some View {
        Button { choose(index) } label: {
            HStack(spacing: 12) {
                FileTypeIcon(kind: FileIconCatalog.shared.resolve(name: file.display, mediaType: nil), size: iconSize)
                VStack(alignment: .leading, spacing: 2) {
                    Text((file.display as NSString).lastPathComponent).font(ReviewTheme.font(14, weight: .semibold))
                        .foregroundStyle(index == selected ? ReviewTheme.blue : ReviewTheme.ink)
                        .lineLimit(1).truncationMode(.middle)
                    Text("+\(file.added)  −\(file.deleted)").foregroundStyle(ReviewTheme.secondary)
                    if file.display.contains("/") {
                        Text((file.display as NSString).deletingLastPathComponent).foregroundStyle(ReviewTheme.secondary)
                            .lineLimit(1).truncationMode(.middle)
                    }
                }.frame(maxWidth: .infinity, alignment: .leading)
            }.padding(12).frame(maxWidth: .infinity, minHeight: 64, alignment: .leading)
                .background(index == selected ? ReviewTheme.blueSoft : ReviewTheme.surface, in: RoundedRectangle(cornerRadius: 10))
                .contentShape(Rectangle())
        }.buttonStyle(.plain).accessibilityIdentifier("workspace-file-\(index)")
            .accessibilityAddTraits(index == selected ? [.isSelected] : [])
    }

    private var navigation: some View {
        VStack(spacing: 0) {
            Rectangle().fill(ReviewTheme.line).frame(height: 1)
            HStack(spacing: 0) {
                Button("上一个文件") { choose(selected - 1) }
                    .disabled(selected == 0 || files.isEmpty)
                    .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                    .accessibilityIdentifier("workspace-file-previous")
                Text(files.isEmpty ? "0 / 0" : "\(selected + 1) / \(files.count)")
                    .font(ReviewTheme.font(14)).monospacedDigit().foregroundStyle(ReviewTheme.secondary)
                    .frame(maxWidth: .infinity, alignment: .center)
                    .accessibilityIdentifier("workspace-file-page")
                Button("下一个文件") { choose(selected + 1) }
                    .disabled(selected + 1 >= files.count)
                    .frame(maxWidth: .infinity, minHeight: 44, alignment: .trailing)
                    .accessibilityIdentifier("workspace-file-next")
            }.font(ReviewTheme.font(16)).lineLimit(1).minimumScaleFactor(0.75)
                .buttonStyle(.plain).foregroundStyle(ReviewTheme.blue)
                .padding(.horizontal, 16).frame(minHeight: 56)
        }
    }

    private func choose(_ index: Int) {
        guard files.indices.contains(index) else { return }
        guard selected != index || state.selectedIndex?.intValue != index || state.diffError != nil else { return }
        selected = index; selectFile(index)
    }

    @ViewBuilder private func diffContent(wide: Bool) -> some View {
        if state.loading { ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity) }
        else if let error = state.error { failure(error, retry: retrySummary) }
        else if files.isEmpty { notice("本轮没有可预览的文件改动。") }
        else if state.selectedIndex?.intValue != selected {
            if !state.connected { failure("disconnected") { selectFile(selected) } }
            else { ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity) }
        } else if state.diffLoading { ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity) }
        else if let error = state.diffError { failure(error) { selectFile(selected) } }
        else if let diff = state.diff {
            let rows = WorkspaceReviewPresentation.shared.rows(diff: diff)
            ScrollView(.vertical) {
                VStack(alignment: .leading, spacing: 0) {
                    if diff.kind == "binary" { notice("二进制文件已改动，无法逐行对比。") }
                    else if diff.kind == "oversized" { notice("文件过大，无法逐行对比。") }
                    else {
                        if diff.coarse { notice("比较超时，按整段替换展示。") }
                        if diff.before?.boolValue == false { notice("新建文件") }
                        if diff.after?.boolValue == false { notice("已删除文件") }
                        WorkspaceDiffBody(rows: rows, wrapLines: wrapLines, wide: wide)
                        if rows.isEmpty { notice("此文件没有可显示的文本差异。") }
                    }
                }.frame(maxWidth: ReviewTheme.style.REVIEW_CODE_MAX_WIDTH, alignment: .leading)
                    .frame(maxWidth: .infinity, alignment: .top)
                    .padding(.horizontal, wide ? 24 : 16).padding(.top, wide ? 20 : 12).padding(.bottom, 24)
            }.accessibilityIdentifier("workspace-changes-list")
        } else { notice("正在读取文件改动…") }
    }

    private func notice(_ message: String) -> some View {
        Text(message).foregroundStyle(ReviewTheme.secondary).padding(16).frame(maxWidth: .infinity, alignment: .leading)
    }
    private func failure(_ code: String, retry: @escaping () -> Void) -> some View {
        VStack(alignment: .leading, spacing: 12) { Text(changesError(code)); Button("重试", action: retry).foregroundStyle(ReviewTheme.blue) }
            .padding(20).frame(maxWidth: .infinity, alignment: .leading)
    }
    private func changesError(_ code: String) -> String {
        switch code {
        case "workspace-changes-missing": return "该次改动记录已不可用，Host 重启后可能无法查看历史改动。"
        case "disconnected": return "连接已断开，请重连后重试。"
        case "workspace-changes-unavailable": return "当前网关暂不支持文件改动查询。"
        case "workspace-changes-response-too-large": return "改动内容超过响应大小限制，无法完整展示。"
        case "unauthorized", "forbidden", "auth-required": return "没有权限读取该次改动，请检查连接认证。"
        default: return "无法读取改动内容，请重试。"
        }
    }
}

private struct WorkspaceDiffBody: View {
    let rows: [WorkspaceReviewRow]
    let wrapLines: Bool
    let wide: Bool
    @ScaledMetric(relativeTo: .caption) private var phoneLine: CGFloat = 20
    @ScaledMetric(relativeTo: .caption) private var largeLine: CGFloat = 22
    private var lineHeight: CGFloat { (wide ? largeLine : phoneLine) * AppFontScale.current }
    private var gutter: CGFloat { max(wide ? 40 : 32, CGFloat(String(rows.compactMap { $0.lineNumber?.intValue }.max() ?? 0).count) * 8 * phoneLine / 20 * AppFontScale.current + 8) }
    @State private var viewportWidth: CGFloat = 0

    var body: some View {
        if wrapLines {
            LazyVStack(alignment: .leading, spacing: 0) {
                ForEach(Array(rows.enumerated()), id: \.offset) { index, row in
                    Group {
                        if row.kind == "hunk" || row.kind == "omitted" { section(row) }
                        else {
                            HStack(alignment: .top, spacing: 0) {
                                number(row)
                                code(row, wraps: true).frame(maxWidth: .infinity, alignment: .leading)
                            }.padding(.vertical, 3).background(background(row))
                        }
                    }.clipShape(blockShape(index))
                }
            }.textSelection(.enabled)
        } else {
            // 整个文件只有一个横向滚动容器；行号列固定，不随代码横移。
            HStack(alignment: .top, spacing: 0) {
                LazyVStack(spacing: 0) {
                    ForEach(Array(rows.enumerated()), id: \.offset) { index, row in
                        number(row).padding(.top, row.lineNumber == nil ? 0 : 3)
                            .frame(height: height(row), alignment: .top).background(background(row))
                            .clipShape(blockShape(index, right: false))
                    }
                }.frame(width: gutter)
                ScrollView(.horizontal) {
                    VStack(alignment: .leading, spacing: 0) {
                        ForEach(Array(rows.enumerated()), id: \.offset) { index, row in
                            Group {
                                if row.kind == "hunk" || row.kind == "omitted" { section(row) }
                                else { code(row, wraps: false) }
                            }.frame(maxWidth: .infinity, minHeight: height(row), maxHeight: height(row), alignment: .leading)
                                .background(background(row))
                                .clipShape(blockShape(index, left: false))
                        }
                    }.fixedSize(horizontal: true, vertical: false)
                        .frame(minWidth: max(0, viewportWidth - gutter), alignment: .leading)
                }.textSelection(.enabled)
            }
            .background(GeometryReader { geometry in
                Color.clear.onAppear { viewportWidth = geometry.size.width }
                    .onChangeCompat(of: geometry.size.width) { _, width in viewportWidth = width }
            })
        }
    }

    private func blockShape(_ index: Int, left: Bool = true, right: Bool = true) -> ReviewBlockCorners {
        let endsBlock = rows[index].kind != "omitted" &&
            (index == rows.count - 1 || rows[index + 1].kind == "omitted" || rows[index + 1].kind == "hunk")
        var corners: UIRectCorner = []
        if rows[index].kind == "hunk" {
            if left { corners.insert(.topLeft) }
            if right { corners.insert(.topRight) }
        }
        if endsBlock {
            if left { corners.insert(.bottomLeft) }
            if right { corners.insert(.bottomRight) }
        }
        return ReviewBlockCorners(corners: corners)
    }

    private func height(_ row: WorkspaceReviewRow) -> CGFloat { row.kind == "hunk" || row.kind == "omitted" ? 32 : lineHeight + 6 }
    private func background(_ row: WorkspaceReviewRow) -> Color {
        switch row.kind { case "added": return ReviewTheme.addedBG; case "deleted": return ReviewTheme.deletedBG; case "hunk": return ReviewTheme.hunkBG; default: return ReviewTheme.surface }
    }
    private func number(_ row: WorkspaceReviewRow) -> some View {
        Text(row.lineNumber.map { "\($0.intValue)" } ?? "")
            .font(.custom("JetBrainsMono-Regular", size: 12 * AppFontScale.current, relativeTo: .caption))
            .foregroundStyle(ReviewTheme.muted).frame(width: gutter, height: lineHeight, alignment: .topTrailing)
    }
    private func section(_ row: WorkspaceReviewRow) -> some View {
        Text(row.text).font(ReviewTheme.font(12)).foregroundStyle(row.kind == "omitted" ? ReviewTheme.muted : ReviewTheme.secondary)
            .padding(.horizontal, 12).frame(maxWidth: .infinity, minHeight: 32, alignment: row.kind == "omitted" ? .center : .leading)
            .background(background(row))
    }
    private func code(_ row: WorkspaceReviewRow, wraps: Bool) -> some View {
        HStack(alignment: .top, spacing: 0) {
            Text(row.kind == "added" ? "+" : row.kind == "deleted" ? "−" : " ")
                .foregroundStyle(row.kind == "added" ? ReviewTheme.added : row.kind == "deleted" ? ReviewTheme.deleted : ReviewTheme.muted)
                .frame(width: 24, height: lineHeight, alignment: .top)
            ReviewCodeLabel(text: row.text, size: wide ? 13 : 12, lineHeight: lineHeight, wraps: wraps,
                            color: UIColor(row.kind == "annotation" ? ReviewTheme.secondary : ReviewTheme.ink))
                .fixedSize(horizontal: !wraps, vertical: true)
                .frame(minHeight: lineHeight, alignment: .topLeading).padding(.trailing, 12)
        }.font(.custom("JetBrainsMono-Regular", size: (wide ? 13 : 12) * AppFontScale.current, relativeTo: .caption))
    }
}

/// 片段只在首尾裁剪外侧圆角，行号与正文之间保持无缝连接，兼容 iOS 16。
private struct ReviewBlockCorners: Shape {
    let corners: UIRectCorner

    func path(in rect: CGRect) -> Path {
        let radius = WorkspaceChangesStyle.shared.REVIEW_BLOCK_RADIUS
        return Path(UIBezierPath(roundedRect: rect, byRoundingCorners: corners,
                                 cornerRadii: CGSize(width: radius, height: radius)).cgPath)
    }
}

private enum ReviewCodeText {
    private static let cjk = try? NSRegularExpression(pattern: "[\\u2E80-\\uFFFF]+")
    static func attributed(_ text: String, size: CGFloat, lineHeight: CGFloat, color: UIColor) -> NSAttributedString {
        let text = text.isEmpty ? " " : text
        let metrics = UIFontMetrics(forTextStyle: .caption1)
        let mono = metrics.scaledFont(for: UIFont(name: "JetBrainsMono-Regular", size: size * AppFontScale.current) ?? .monospacedSystemFont(ofSize: size, weight: .regular))
        let paragraph = NSMutableParagraphStyle()
        paragraph.minimumLineHeight = lineHeight
        paragraph.maximumLineHeight = lineHeight
        paragraph.lineBreakMode = .byWordWrapping
        let value = NSMutableAttributedString(string: text, attributes: [.font: mono, .paragraphStyle: paragraph, .foregroundColor: color])
        // 显式指定中文字体，避免 iOS/Android 的默认字形回退导致换行位置不同。
        cjk?.enumerateMatches(in: text, range: NSRange(location: 0, length: value.length)) { match, _, _ in
            if let match { value.addAttribute(.font, value: metrics.scaledFont(for: ReviewTheme.uiFont(size)), range: match.range) }
        }
        return value
    }
}

/// TextKit 按可用宽度返回真实段落高度；没有行级横向或纵向滚动。
private struct ReviewCodeLabel: UIViewRepresentable {
    let text: String
    let size: CGFloat
    let lineHeight: CGFloat
    let wraps: Bool
    let color: UIColor
    func makeUIView(context: Context) -> UITextView {
        let view = UITextView()
        view.isEditable = false; view.isSelectable = true; view.isScrollEnabled = false
        view.showsHorizontalScrollIndicator = false; view.showsVerticalScrollIndicator = false
        view.bounces = false
        view.backgroundColor = .clear
        view.textContainerInset = .zero; view.textContainer.lineFragmentPadding = 0
        view.layoutManager.usesFontLeading = false
        view.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        return view
    }
    func updateUIView(_ view: UITextView, context: Context) {
        view.attributedText = ReviewCodeText.attributed(text, size: size, lineHeight: lineHeight, color: color)
        view.textContainer.maximumNumberOfLines = wraps ? 0 : 1
        view.accessibilityLabel = text
    }
    func sizeThatFits(_ proposal: ProposedViewSize, uiView: UITextView, context: Context) -> CGSize? {
        if wraps {
            let width = max(1, proposal.width ?? 300)
            let height = uiView.sizeThatFits(CGSize(width: width, height: .greatestFiniteMagnitude)).height
            return CGSize(width: width, height: max(lineHeight, ceil(height)))
        }
        let width = uiView.attributedText.boundingRect(with: CGSize(width: CGFloat.greatestFiniteMagnitude, height: lineHeight),
            options: [.usesLineFragmentOrigin], context: nil).width
        return CGSize(width: ceil(width) + 1, height: lineHeight)
    }
}

#if DEBUG
/// 通过正式状态机提供可重复的 UI 回归数据，不修改网关、连接配置或设备身份。
@MainActor private final class ReviewUITestModel: ObservableObject {
    private let shared = SharedWorkspaceChangesStore()
    @Published var snapshot = SharedWorkspaceChangesStore().snapshot()
    init() {
        _ = shared.open(sessionId: "review-ui-test", supported: true, connected: true)
        let announcement = shared.acceptJson(json: """
        {"kind":"event","sessionId":"review-ui-test","seq":1,"event":{"type":"workspace/changes","turn":1}}
        """)
        let id = announcement.requests.first?.correlationId ?? ""
        _ = shared.acceptJson(json: """
        {"kind":"workspace-changes-summary","sessionId":"review-ui-test","seq":1,"requestId":"\(id)","summary":{"turn":1,"total":4,"added":15,"deleted":2,"files":[
        {"path":"README.md","display":"README.md","added":3,"deleted":1},
        {"path":"app.js","display":"app.js","added":4,"deleted":0},
        {"path":"index.html","display":"index.html","added":2,"deleted":1},
        {"path":"style.css","display":"style.css","added":6,"deleted":0}]}}
        """)
        select(0)
    }
    func select(_ index: Int) {
        let transition = shared.selectFile(index: Int32(index))
        if let request = transition.requests.first {
            let names = ["README.md", "app.js", "index.html", "style.css"]
            let lines = index == 0 ? ["-# 小待办", "+# 今日", " ", " 一个极简的中文待办清单，纯 HTML / CSS / JavaScript，无依赖。", " "] :
                (1...160).map { $0 == 1 ? "+  const remaining = todos.filter(todo => !todo.done).length;" : "+  const line\($0) = '保留中文与缩进，并验证较长代码不会遮挡相邻行';" }
            let hunks: [[String: Any]] = index == 0 ? [
                ["oldStart": 1, "oldLines": 4, "newStart": 1, "newLines": 4, "lines": lines],
                ["oldStart": 7, "oldLines": 6, "newStart": 7, "newLines": 8, "lines": [" - 添加待办", " - 点击文字标记完成 / 取消完成", " - 点击「删除」移除待办", "+- 顶部显示剩余未完成数量", "+- 蓝色主题", " - 数据保存在浏览器 localStorage，刷新不会丢失。", " ", " ## 使用"]]
            ] : [["oldStart": 1, "oldLines": 0, "newStart": 1, "newLines": 160, "lines": lines]]
            let payload: [String: Any] = ["kind": "workspace-changes-diff", "requestId": request.correlationId ?? "", "sessionId": "review-ui-test", "seq": 1, "index": index,
                "diff": ["kind": "text", "path": names[index], "display": names[index], "before": true, "after": true, "hunks": hunks]]
            if let data = try? JSONSerialization.data(withJSONObject: payload) { _ = shared.acceptJson(json: String(decoding: data, as: UTF8.self)) }
        }
        snapshot = shared.snapshot()
    }
}

struct WorkspaceReviewUITestHarness: View {
    @StateObject private var model = ReviewUITestModel()
    @State private var presented = true
    var body: some View {
        Color(.systemGroupedBackground).sheet(isPresented: $presented) {
            WorkspaceChangesSheet(state: model.snapshot, selectFile: model.select, retrySummary: {}, workspaceName: "file-changes-demo",
                                  workspaceRoot: "/Users/demo/file-changes-demo")
                .presentationDetents([.large]).presentationDragIndicator(.hidden).modifier(ReviewPageSizing())
        }
    }
}
#endif

private let changesLabel = changesColor(WorkspaceChangesStyle.shared.LABEL_COLOR_ARGB)
private let changesGreen = changesColor(WorkspaceChangesStyle.shared.ADDED_COLOR_ARGB)
private let changesRed = changesColor(WorkspaceChangesStyle.shared.DELETED_COLOR_ARGB)

struct WorkspaceChangesDock: View {
    @ObservedObject var store: AppStore
    @State private var expanded = false
    @State private var filePreviewID: UUID?
    @ScaledMetric(relativeTo: .subheadline) private var lineHeight = WorkspaceChangesStyle.shared.LINE_HEIGHT
    private let style = WorkspaceChangesStyle.shared

    var body: some View {
        Group {
            if store.workspaceChanges.sessionId == store.selectedSessionId && store.workspaceChanges.visible {
                Button { expanded = true } label: {
                    HStack(spacing: style.ITEM_SPACING) {
                        if let summary = store.workspaceChanges.summary {
                            Text("\(summary.total) 个文件").foregroundStyle(changesLabel)
                            Text("+\(summary.added)").foregroundStyle(changesGreen)
                            Text("−\(summary.deleted)").foregroundStyle(changesRed)
                        } else {
                            Text("文件改动").foregroundStyle(changesLabel)
                            if store.workspaceChanges.loading {
                                ProgressView().controlSize(.mini)
                                    .frame(width: style.PROGRESS_SIZE, height: style.PROGRESS_SIZE)
                            }
                            if store.workspaceChanges.error != nil { Text("查看").foregroundStyle(changesLabel) }
                        }
                    }
                    .appFont(.system(size: style.FONT_SIZE))
                    .monospacedDigit()
                    .frame(minHeight: lineHeight)
                    .padding(.horizontal, style.HORIZONTAL_PADDING)
                    .padding(.vertical, style.VERTICAL_PADDING)
                    .composerGlassSurface(radius: 1000)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("查看本轮文件改动")
                .padding(.bottom, style.COMPOSER_SPACING)
            }
        }
        .frame(maxWidth: .infinity, alignment: .trailing)
        .padding(.horizontal, style.HORIZONTAL_PADDING)
        .task(id: store.selectedSessionId) { store.refreshWorkspaceChanges() }
        .onChangeCompat(of: store.selectedSessionId) { _, _ in expanded = false; store.closeChangesDiff() }
        .sheet(isPresented: $expanded, onDismiss: { closeFilePreview(); store.closeChangesDiff() }) {
            WorkspaceChangesSheet(state: store.workspaceChanges, selectFile: store.selectChangedFile, retrySummary: store.retryWorkspaceChanges,
                                  workspaceName: store.activeWorkspace?.title ?? "工作区",
                                  workspaceRoot: store.workspaces.first { $0.sessionIds.contains(store.workspaceChanges.sessionId ?? "") }?.path,
                                  openFile: openFile)
                .presentationDetents([.large])
                .presentationDragIndicator(.hidden)
                .modifier(ReviewPageSizing())
                .sheet(item: filePreviewBinding) { request in
                    WorkspaceFilePreviewLoadingSheet(store: store, request: request)
                }
        }
    }

    private var filePreviewBinding: Binding<WorkspaceFilePreviewRequest?> {
        Binding(get: {
            guard store.workspaceFilePreviewRequest?.id == filePreviewID else { return nil }
            return store.workspaceFilePreviewRequest
        }, set: { if $0 == nil { closeFilePreview() } })
    }

    private func openFile(_ path: String) {
        guard store.selectedSessionId == store.workspaceChanges.sessionId,
              store.workspaceFilePreviewRequest == nil, !store.conversationFileIsOpening else { return }
        // 文件列表入口不会触发聊天底层的 Sheet；完整预览由当前审阅面板原生呈现。
        store.openWorkspaceFile(GatewayDirectoryItem(name: (path as NSString).lastPathComponent, path: path, kind: "file"), purpose: "preview")
        filePreviewID = store.workspaceFilePreviewRequest?.id
    }

    private func closeFilePreview() {
        if let filePreviewID, store.workspaceFilePreviewRequest?.id == filePreviewID { store.cancelWorkspaceFileDownload() }
        filePreviewID = nil
    }
}

private struct ReviewPageSizing: ViewModifier {
    func body(content: Content) -> some View {
        if #available(iOS 18.0, *) { content.presentationSizing(.page).presentationCornerRadius(28) }
        else if #available(iOS 16.4, *) { content.presentationCornerRadius(28) }
        else { content }
    }
}
