import Combine
import Foundation

enum WorkspaceSessionScope {
    static let all = "__all_sessions__"
    static let ungrouped = "__ungrouped__"
}

enum WorkspaceSessionCopy {
    static var creationPrompt: String { String(localized: "请先选择一个具体工作区，再创建新会话。") }

    static func count(_ count: Int) -> String {
        count == 1 ? String(localized: "workspace.session.count.one", defaultValue: "1 个会话")
            : String(localized: "workspace.section.count", defaultValue: "\(count) 个会话")
    }

    static func expand(_ count: Int) -> String {
        count == 1 ? String(localized: "workspace.section.expand.one", defaultValue: "展开其余 1 个会话")
            : String(localized: "workspace.section.expand", defaultValue: "展开其余 \(count) 个会话")
    }

    static func sectionAccessibility(title: String, count: Int) -> String {
        count == 1 ? String(localized: "workspace.section.named-accessibility.one", defaultValue: "工作区 \(title)，1 个会话")
            : String(localized: "workspace.section.named-accessibility", defaultValue: "工作区 \(title)，\(count) 个会话")
    }
}

/// 主线程只交接值快照；筛选、索引、排序和分组均在后台完成。
struct WorkspaceSessionListInput: Equatable, Sendable {
    static let collapsedSectionLimit = 5

    var sessions: [SessionSummary] = []
    var workspaces: [GatewayWorkspace] = []
    var archivedIDs: Set<String> = []
    var selection: String?
    var searchResults: [GatewaySearchItem] = []
    var routingJSON = "[]"
    var query = ""
    var expandedSectionIDs: Set<String> = []

    func project() -> WorkspaceSessionListSnapshot {
        guard !Task.isCancelled else { return .init() }
        struct Target: Decodable {
            let sessionId: String
            let parentSessionId: String?
        }
        let targets = (try? JSONDecoder().decode([Target].self, from: Data(routingJSON.utf8))) ?? []
        let children = Set(targets.filter { $0.parentSessionId != nil }.map(\.sessionId))
        let visible = sessions.filter {
            $0.isVisibleInHistory && !children.contains($0.id) && !archivedIDs.contains($0.id)
        }
        let ungroupedID = WorkspaceSessionScope.ungrouped
        let isAll = selection == WorkspaceSessionScope.all
        // 先建立成员索引，避免对每个会话遍历全部工作区。重复归属取第一个工作区。
        var owners: [String: String] = [:]
        var workspaceTitles: [String: String] = [:]
        for workspace in workspaces {
            guard !Task.isCancelled else { return .init() }
            let title = workspace.title.trimmingCharacters(in: .whitespacesAndNewlines)
            workspaceTitles[workspace.id] = title.isEmpty
                ? workspace.path.split(separator: "/").last.map(String.init) ?? String(localized: "工作区")
                : title
            for id in workspace.sessionIds where owners[id] == nil { owners[id] = workspace.id }
        }
        let selected = workspaces.first { $0.id == selection } ?? workspaces.first
        let selectedIDs = Set(selected?.sessionIds ?? [])
        var scoped = visible.filter {
            if isAll { return true }
            if selection == ungroupedID { return owners[$0.id] == nil }
            guard selected != nil else { return true }
            return selectedIDs.contains($0.id)
        }
        let query = query.trimmingCharacters(in: .whitespacesAndNewlines)
        var hits: [String: String] = [:]
        for hit in searchResults where hits[hit.sessionId] == nil { hits[hit.sessionId] = hit.snippet }
        if !query.isEmpty {
            let knownIDs = Set(sessions.map(\.id))
            var inserted = Set<String>()
            // 保留只有缓存正文、尚无列表元数据的结果；不把其他已知工作区的会话混入当前分组。
            for hit in searchResults where !knownIDs.contains(hit.sessionId)
                && !children.contains(hit.sessionId) && !archivedIDs.contains(hit.sessionId)
                && inserted.insert(hit.sessionId).inserted {
                let owner = owners[hit.sessionId]
                if isAll || (selection == ungroupedID ? owner == nil : owner == selected?.id) {
                    scoped.append(SessionSummary(id: hit.sessionId, title: String(localized: "已缓存聊天"),
                        lastActivity: .distantPast, isRunning: false, hasUnread: false))
                }
            }
            scoped = scoped.filter {
                hits[$0.id] != nil || $0.title.localizedCaseInsensitiveContains(query)
                    || $0.id.localizedCaseInsensitiveContains(query)
            }
        }
        scoped.sort {
            $0.lastActivity == $1.lastActivity ? $0.id < $1.id : $0.lastActivity > $1.lastActivity
        }
        let count = scoped.count
        if !isAll { scoped = Array(scoped.prefix(query.isEmpty ? 12 : 100)) }
        var entries: [WorkspaceSessionListEntry] = []
        entries.reserveCapacity(scoped.count + workspaces.count + 1)
        if isAll {
            guard !Task.isCancelled else { return .init() }
            let groups = Dictionary(grouping: scoped) { owners[$0.id] ?? ungroupedID }
            // 分区按最近活动排序，相同时间按稳定 ID 排序；区内仍按时间倒序。
            let ordered = groups.keys.sorted {
                let lhs = groups[$0]?.first?.lastActivity ?? .distantPast
                let rhs = groups[$1]?.first?.lastActivity ?? .distantPast
                return lhs == rhs ? $0 < $1 : lhs > rhs
            }
            for (index, id) in ordered.enumerated() {
                guard !Task.isCancelled else { return .init() }
                let rows = groups[id] ?? []
                let isExpanded = expandedSectionIDs.contains(id)
                let displayedRows = isExpanded ? rows[...] : rows.prefix(Self.collapsedSectionLimit)
                entries.append(.section(id: id, title: workspaceTitles[id] ?? String(localized: "未分组"),
                    number: index + 1, count: rows.count))
                entries.append(contentsOf: displayedRows.map { .session($0, snippet: query.isEmpty ? nil : hits[$0.id]) })
                if rows.count > Self.collapsedSectionLimit {
                    entries.append(.sectionToggle(id: id,
                        remainingCount: rows.count - Self.collapsedSectionLimit, isExpanded: isExpanded))
                }
            }
        } else {
            entries = scoped.map { .session($0, snippet: query.isEmpty ? nil : hits[$0.id]) }
        }
        return WorkspaceSessionListSnapshot(entries: entries, matchingCount: count, visibleCount: visible.count)
    }
}

enum WorkspaceSessionListEntry: Identifiable, Equatable, Sendable {
    case section(id: String, title: String, number: Int, count: Int)
    case session(SessionSummary, snippet: String?)
    case sectionToggle(id: String, remainingCount: Int, isExpanded: Bool)

    var id: String {
        switch self {
        case .section(let id, _, _, _): return "section-\(id)"
        case .session(let session, _): return "session-\(session.id)"
        case .sectionToggle(let id, _, _): return "toggle-\(id)"
        }
    }
}

struct WorkspaceSessionListSnapshot: Equatable, Sendable {
    var entries: [WorkspaceSessionListEntry] = []
    var matchingCount = 0
    var visibleCount = 0
}

@MainActor
final class WorkspaceSessionListModel: ObservableObject {
    @Published private(set) var snapshot = WorkspaceSessionListSnapshot()
    @Published private(set) var isLoading = true
    private let query = CurrentValueSubject<String, Never>("")
    private let expandedSectionIDs = CurrentValueSubject<Set<String>, Never>([])
    private var observation: AnyCancellable?
    private var projection: Task<Void, Never>?
    private var isScrolling = false
    private var pendingInput: WorkspaceSessionListInput?

    func bind(to store: AppStore) {
        guard observation == nil else { return }
        let catalog = Publishers.CombineLatest4(store.$sessions, store.$workspaces,
            store.$archivedSessionIds, store.$selectedWorkspaceId)
        let presentation = Publishers.CombineLatest(query, expandedSectionIDs)
        observation = Publishers.CombineLatest4(catalog, store.$searchResults, store.$persistedTeamRouting, presentation)
            .map { catalog, hits, routing, presentation in
                WorkspaceSessionListInput(sessions: catalog.0, workspaces: catalog.1,
                    archivedIDs: catalog.2, selection: catalog.3, searchResults: hits,
                    routingJSON: routing, query: presentation.0, expandedSectionIDs: presentation.1)
            }
            .removeDuplicates()
            // 持续状态更新也必须有进展，避免 debounce 在运行中会话上无限推迟结果。
            .throttle(for: .milliseconds(100), scheduler: DispatchQueue.main, latest: true)
            .sink { [weak self] input in self?.receive(input) }
    }

    func search(_ value: String) { query.send(value) }

    func toggleSection(_ id: String) {
        var expanded = expandedSectionIDs.value
        if !expanded.insert(id).inserted { expanded.remove(id) }
        expandedSectionIDs.send(expanded)
    }

    /// 拖动和惯性滑动期间只保留最新输入，避免后台状态刷新引发列表 diff 或分区重排。
    func setScrolling(_ scrolling: Bool) {
        guard isScrolling != scrolling else { return }
        isScrolling = scrolling
        if !scrolling, let input = pendingInput {
            pendingInput = nil
            update(input)
        }
    }

    func unbind() {
        observation = nil
        projection?.cancel()
        projection = nil
        pendingInput = nil
        isScrolling = false
    }

    private func receive(_ input: WorkspaceSessionListInput) {
        if isScrolling { pendingInput = input }
        else { update(input) }
    }

    private func update(_ input: WorkspaceSessionListInput) {
        projection?.cancel()
        let worker = Task.detached(priority: .userInitiated) { input.project() }
        projection = Task { [weak self] in
            let result = await withTaskCancellationHandler {
                await worker.value
            } onCancel: { worker.cancel() }
            guard !Task.isCancelled, let self else { return }
            guard !isScrolling else {
                if pendingInput == nil { pendingInput = input }
                return
            }
            if snapshot != result { snapshot = result }
            if isLoading { isLoading = false }
        }
    }

    deinit { projection?.cancel() }
}
