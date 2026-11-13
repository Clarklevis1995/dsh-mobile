import SwiftUI
import DeepSeekHarnessShared

private let changesGreen = Color(red: 0.16, green: 0.52, blue: 0.23)
private let changesRed = Color(red: 0.77, green: 0.29, blue: 0.32)

struct WorkspaceChangesDock: View {
    @ObservedObject var store: AppStore
    @State private var expanded = false

    var body: some View {
        Group {
            if store.workspaceChanges.sessionId == store.selectedSessionId && store.workspaceChanges.visible {
                Button { expanded = true } label: {
                    HStack(spacing: 8) {
                        if let summary = store.workspaceChanges.summary {
                            Text("\(summary.total) 个文件").foregroundStyle(.secondary)
                            Text("+\(summary.added)").foregroundStyle(changesGreen)
                            Text("−\(summary.deleted)").foregroundStyle(changesRed)
                        } else {
                            Text("文件改动").foregroundStyle(.secondary)
                            if store.workspaceChanges.loading { ProgressView().controlSize(.mini) }
                        }
                    }
                    .font(.subheadline)
                    .monospacedDigit()
                    .padding(.horizontal, 14).padding(.vertical, 8)
                    .background(.regularMaterial, in: Capsule())
                    .overlay { Capsule().strokeBorder(.primary.opacity(0.12), lineWidth: 0.5) }
                }
                .buttonStyle(.plain)
                .accessibilityLabel("查看本轮文件改动")
                .padding(.bottom, 8)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 14)
        .task(id: store.selectedSessionId) { store.refreshWorkspaceChanges() }
        .onChangeCompat(of: store.selectedSessionId) { _, _ in expanded = false; store.closeChangesDiff() }
        .sheet(isPresented: $expanded, onDismiss: { store.closeChangesDiff() }) {
            WorkspaceChangesSheet(state: store.workspaceChanges, selectFile: store.selectChangedFile, retrySummary: store.retryWorkspaceChanges)
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
        }
    }
}

struct WorkspaceChangesSheet: View {
    let state: SharedWorkspaceChangesSnapshot
    let selectFile: (Int) -> Void
    let retrySummary: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var opened: Set<Int> = []

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 10) {
                    Text("本轮改动").foregroundStyle(.secondary)
                    if let summary = state.summary {
                        Text("+\(summary.added)").foregroundStyle(changesGreen)
                        Text("−\(summary.deleted)").foregroundStyle(changesRed)
                    }
                }.font(.subheadline).padding(.horizontal, 20).padding(.vertical, 12)
                if let summary = state.summary {
                    Text("已列出 \(summary.files.count) / 总计 \(summary.total) 个文件")
                        .font(.footnote).foregroundStyle(.secondary).padding(.horizontal, 20).padding(.bottom, 8)
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 0) {
                            ForEach(Array(summary.files.enumerated()), id: \.offset) { index, file in
                                fileHeader(file, index: index).padding(.top, 16)
                                if opened.contains(index) { fileDiff(index) }
                            }
                        }.padding(.horizontal, 16).padding(.bottom, 24)
                    }
                }
                if state.loading { ProgressView().padding(24) }
                if let error = state.error { failure(error, retry: retrySummary) }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .background(Color(.systemBackground))
            .navigationTitle("文件改动")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("完成") { dismiss() } } }
            .task(id: state.summary?.files.count) {
                if state.summary?.files.isEmpty == false && opened.isEmpty {
                    opened.insert(0)
                    selectFile(0)
                }
            }
            .onChangeCompat(of: state.seq) { _, _ in opened = [] }
        }
    }

    private func fileHeader(_ file: WorkspaceChangedFile, index: Int) -> some View {
        Button {
            if opened.contains(index) { opened.remove(index) }
            else { opened.insert(index); selectFile(index) }
        } label: {
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 8) {
                    Text((file.display as NSString).lastPathComponent).font(.headline).foregroundStyle(.primary)
                    Spacer(minLength: 0)
                    Text("+\(file.added)").foregroundStyle(changesGreen)
                    Text("−\(file.deleted)").foregroundStyle(changesRed)
                }
                if file.display.contains("/") {
                    Text((file.display as NSString).deletingLastPathComponent).font(.caption).foregroundStyle(.secondary)
                }
                Label(opened.contains(index) ? "收起改动" : "展开改动", systemImage: opened.contains(index) ? "chevron.up" : "chevron.down")
                    .font(.caption).foregroundStyle(.secondary)
            }.padding(16).frame(maxWidth: .infinity, alignment: .leading)
                .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 16))
                .contentShape(Rectangle())
        }.buttonStyle(.plain)
    }

    @ViewBuilder private func fileDiff(_ index: Int) -> some View {
        if state.selectedIndex?.intValue == index && state.diffLoading { ProgressView().padding(20) }
        if state.selectedIndex?.intValue == index, let error = state.diffError {
            failure(error) { selectFile(index) }
        }
        if let preview = state.previews.first(where: { $0.index == Int32(index) }) {
            let diff = preview.diff
            if diff.kind == "binary" { notice("二进制文件已改动，无法逐行对比。") }
            else if diff.kind == "oversized" { notice("文件过大，无法逐行对比。") }
            else if diff.kind == "text" {
                if diff.coarse { notice("比较超时，按整段替换展示。") }
                if diff.before?.boolValue == false && diff.after?.boolValue == true { notice("新建文件") }
                if diff.before?.boolValue == true && diff.after?.boolValue == false { notice("已删除文件") }
                ForEach(Array(preview.rows.enumerated()), id: \.offset) { _, row in
                    WorkspaceDiffLineViewport(row: row)
                }
            }
        } else if state.selectedIndex?.intValue != index {
            Button("加载改动内容") { selectFile(index) }.padding(16)
        }
    }

    private func notice(_ text: String) -> some View {
        Text(text).font(.footnote).foregroundStyle(.secondary).padding(16)
    }

    private func failure(_ code: String, retry: @escaping () -> Void) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(changesError(code))
            Button("重试", action: retry)
        }.font(.subheadline).padding(20)
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

private struct WorkspaceDiffLineViewport: View {
    let row: WorkspaceDiffRow
    @ScaledMetric(relativeTo: .caption) private var lineHeight: CGFloat = 23
    var body: some View {
        GeometryReader { geometry in
            ScrollView(.horizontal) {
                WorkspaceDiffLine(row: row).frame(minWidth: geometry.size.width, alignment: .leading)
            }.textSelection(.enabled)
        }.frame(height: lineHeight)
            .background(row.kind == "added" ? changesGreen.opacity(0.14) : row.kind == "deleted" ? changesRed.opacity(0.14) : Color.secondary.opacity(0.025))
    }
}

private struct WorkspaceDiffLine: View {
    let row: WorkspaceDiffRow
    private var tint: Color {
        switch row.kind {
        case "added": return changesGreen
        case "deleted": return changesRed
        case "context": return .primary
        default: return .secondary
        }
    }
    var body: some View {
        HStack(alignment: .top, spacing: 0) {
            Text(row.oldLine.map { "\($0.intValue)" } ?? "").frame(width: 46, alignment: .trailing).padding(.trailing, 8)
            Text(row.newLine.map { "\($0.intValue)" } ?? "").frame(width: 46, alignment: .trailing).padding(.trailing, 12)
            Text(verbatim: row.text).foregroundStyle(tint).fixedSize(horizontal: true, vertical: true).padding(.trailing, 20)
            Spacer(minLength: 0)
        }
        .font(.system(.caption, design: .monospaced))
        .foregroundStyle(.secondary)
        .padding(.vertical, 3)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
