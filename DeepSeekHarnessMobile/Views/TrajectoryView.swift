import SwiftUI

struct TrajectoryView: View {
    let sessionId: String?
    let events: [SessionEvent]
    let isActive: Bool
    @Environment(\.colorScheme) private var colorScheme
    @State private var selected: TrajectoryNode?
    @State private var highlightedID: String?
    @State private var nodes: [TrajectoryNode] = []
    @State private var projectedSessionId: String?
    @State private var isProjecting = false
    @State private var duration: TimeInterval = 0
    @State private var turnCount = 0
    @State private var toolCount = 0

    var body: some View {
        ScrollViewReader { proxy in
            VStack(spacing: 0) {
                overview { node in
                    highlightedID = node.id
                    withAnimation(.snappy) { proxy.scrollTo(node.id, anchor: .center) }
                    Task {
                        try? await Task.sleep(for: .seconds(1.2))
                        if highlightedID == node.id { highlightedID = nil }
                    }
                }
                ScrollView {
                    LazyVStack(spacing: 0) {
                        if isProjecting {
                            HStack(spacing: 9) {
                                ProgressView().tint(DSHColor.ocean)
                                Text(nodes.isEmpty ? "正在生成轨迹…" : "正在补齐较早的轨迹记录…")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                Spacer()
                            }
                            .padding(.horizontal, 8)
                            .padding(.vertical, 12)
                        }
                        ForEach(displayRows) { row in
                            if row.startsTurn, let turn = row.turn {
                                TrajectoryTurnHeader(turn: turn)
                            }
                            TrajectoryRow(
                                node: row.node,
                                requestNode: row.request,
                                highlighted: highlightedID == row.node.id,
                                onSelect: { selected = row.node },
                                onRequest: { request in selected = request }
                            )
                            .id(row.node.id)
                        }
                        Color.clear.frame(height: 90)
                    }
                    .padding(.horizontal, 18)
                }
            }
        }
        .sheet(item: $selected) { EventDetailSheet(node: $0) }
        .task(id: projectionVersion) {
            guard isActive else { return }
            await projectTrajectory()
        }
    }

    private func overview(onSelect: @escaping (TrajectoryNode) -> Void) -> some View {
        VStack(alignment: .leading, spacing: 9) {
            HStack {
                Label("Duration", systemImage: "clock")
                Text("\(turnCount) Turns")
                Text("\(toolCount) Calls")
                Spacer()
                Text(durationText).monospacedDigit()
            }
            .font(.caption)
            .foregroundStyle(.primary)

            HStack(spacing: 8) {
                VStack(alignment: .leading, spacing: 7) {
                    Text("Input")
                    Text("Model")
                    Text("Tools")
                }
                .font(.caption2).foregroundStyle(.secondary).frame(width: 34, alignment: .leading)

                TimelineOverviewCanvas(nodes: visibleNodes, onSelect: onSelect)
                .frame(height: 52)
            }
            HStack { Text("0s"); Spacer(); Text(durationText) }
                .font(.caption2.monospacedDigit()).foregroundStyle(.secondary)
        }
        .padding(.horizontal, 20).padding(.vertical, 12)
        .background(Color(uiColor: .secondarySystemBackground))
        .overlay(alignment: .bottom) {
            Rectangle()
                .fill(overviewSeparator)
                .frame(height: 0.7)
        }
    }

    private var overviewSeparator: Color {
        colorScheme == .dark ? .white.opacity(0.14) : .black.opacity(0.10)
    }

    private var durationText: String { String(format: "%.2f s", duration) }
    private var visibleNodes: [TrajectoryNode] { nodes.filter { $0.kind != .request } }

    private var displayRows: [TrajectoryDisplayRow] {
        let visible = visibleNodes
        let requestsByStep = Dictionary(
            nodes.compactMap { node -> (String, TrajectoryNode)? in
                guard node.kind == .request,
                      let turn = node.request?.turn,
                      let step = node.request?.step else { return nil }
                return ("\(turn)-\(step)", node)
            },
            uniquingKeysWith: { _, latest in latest }
        )
        var previousTurn: Int?
        return visible.enumerated().map { index, node in
            let directTurn = node.records.lazy.compactMap(\.event.turn).first
            let turn = directTurn ?? visible[index...].lazy
                .flatMap(\.records)
                .compactMap(\.event.turn)
                .first
            let startsTurn = turn != nil && turn != previousTurn
            if let turn { previousTurn = turn }
            let step = node.records.lazy.compactMap(\.event.step).first
            let request: TrajectoryNode? = if node.kind == .assistant, let turn, let step {
                requestsByStep["\(turn)-\(step)"]
            } else {
                nil
            }
            return TrajectoryDisplayRow(
                node: node,
                request: request,
                turn: turn,
                startsTurn: startsTurn
            )
        }
    }
    private var projectionVersion: String {
        guard isActive else { return "inactive-\(sessionId ?? "none")" }
        return "\(sessionId ?? "none")-\(events.count)-\(events.last?.seq ?? -1)"
    }

    @MainActor
    private func projectTrajectory() async {
        let targetSessionId = sessionId
        if projectedSessionId != targetSessionId {
            projectedSessionId = targetSessionId
            nodes = []
        }
        isProjecting = true
        let source = events
        // Coalesce bursty assistant chunks before starting another full
        // projection, and cancel the detached worker when this task is replaced.
        try? await Task.sleep(for: .milliseconds(nodes.isEmpty ? 20 : 60))
        guard !Task.isCancelled else { return }

        let worker = Task.detached(priority: .userInitiated) {
            let nodes = TrajectoryProjection.make(from: source)
            // Match WebUI's compressed execution timeline: idle wall-clock gaps
            // between frames do not contribute to the displayed duration.
            let duration = nodes.lazy.filter { $0.kind != .request }.reduce(0) { partial, node in
                partial + max(0, node.end.timeIntervalSince(node.start))
            }
            return TrajectoryProjectionResult(
                nodes: nodes,
                duration: duration,
                turnCount: Set(source.compactMap(\.event.turn)).count,
                toolCount: nodes.lazy.filter { $0.kind == .tool || $0.kind == .subtool }.count
            )
        }
        let projection = await withTaskCancellationHandler {
            await worker.value
        } onCancel: {
            worker.cancel()
        }
        guard !Task.isCancelled, projectedSessionId == targetSessionId else { return }

        duration = projection.duration
        turnCount = projection.turnCount
        toolCount = projection.toolCount

        var start = max(0, projection.nodes.count - 30)
        if projection.nodes.isEmpty {
            nodes = []
        } else {
            nodes = Array(projection.nodes[start..<projection.nodes.count])
        }
        await Task.yield()

        while start > 0 {
            guard !Task.isCancelled, projectedSessionId == targetSessionId else { return }
            start = max(0, start - 120)
            nodes = Array(projection.nodes[start..<projection.nodes.count])
            try? await Task.sleep(for: .milliseconds(12))
        }
        isProjecting = false
    }
}

private struct TimelineOverviewCanvas: View {
    let nodes: [TrajectoryNode]
    let onSelect: (TrajectoryNode) -> Void
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        GeometryReader { geometry in
            Canvas { context, size in
                for laneY in [CGFloat(6), 25, 44] {
                    let guide = CGRect(x: 0, y: laneY, width: size.width, height: 0.7)
                    context.fill(Path(guide), with: .color(laneGuideColor))
                }
                for entry in layoutEntries {
                    let node = entry.node
                    let height: CGFloat = node.kind == .assistant ? 9 : 7
                    let startX = entry.startFraction * size.width
                    let naturalWidth = (entry.endFraction - entry.startFraction) * size.width
                    let barWidth = max(node.kind == .input ? 2 : 1.5, naturalWidth)
                    let rect = CGRect(
                        x: max(0, min(size.width - barWidth, startX)),
                        y: node.kind.laneY - height / 2,
                        width: barWidth,
                        height: height
                    )
                    context.fill(
                        Path(roundedRect: rect, cornerRadius: 1),
                        with: .color(node.kind.color)
                    )
                }
            }
            .contentShape(Rectangle())
            .gesture(
                SpatialTapGesture().onEnded { value in
                    guard !nodes.isEmpty, geometry.size.width > 0 else { return }
                    let fraction = min(0.999_999, max(0, value.location.x / geometry.size.width))
                    let targetKind: TrajectoryNode.Kind = if value.location.y < 15 {
                        .input
                    } else if value.location.y < 35 {
                        .assistant
                    } else {
                        .tool
                    }
                    let laneEntries = layoutEntries.filter { entry in
                        if targetKind == .assistant {
                            return entry.node.kind == .assistant || entry.node.kind == .context
                        }
                        return entry.node.kind == targetKind
                    }
                    let candidates = laneEntries.isEmpty ? layoutEntries : laneEntries
                    if let nearest = candidates.min(by: {
                        layoutDistance(from: fraction, to: $0) < layoutDistance(from: fraction, to: $1)
                    }) {
                        onSelect(nearest.node)
                    }
                }
            )
        }
    }

    private var laneGuideColor: Color {
        colorScheme == .dark ? .white.opacity(0.10) : .black.opacity(0.07)
    }

    private struct LayoutEntry {
        let node: TrajectoryNode
        let startFraction: CGFloat
        let endFraction: CGFloat
    }

    /// Every frame keeps its real duration, but frames are laid out back-to-back
    /// so idle time between turns cannot compress active work into tiny marks.
    private var layoutEntries: [LayoutEntry] {
        guard !nodes.isEmpty else { return [] }
        let durations = nodes.map { max(0, $0.end.timeIntervalSince($0.start)) }
        let activeDuration = durations.reduce(0, +)
        let minimumWeight = max(0.001, activeDuration / 500)
        let weights = durations.map { max($0, minimumWeight) }
        let totalWeight = max(0.001, weights.reduce(0, +))
        var cursor: TimeInterval = 0
        return zip(nodes, weights).map { node, weight in
            let start = cursor / totalWeight
            cursor += weight
            return LayoutEntry(
                node: node,
                startFraction: CGFloat(start),
                endFraction: CGFloat(cursor / totalWeight)
            )
        }
    }

    private func layoutDistance(from fraction: CGFloat, to entry: LayoutEntry) -> CGFloat {
        if fraction < entry.startFraction { return entry.startFraction - fraction }
        if fraction > entry.endFraction { return fraction - entry.endFraction }
        return 0
    }
}

private struct TrajectoryProjectionResult: Sendable {
    let nodes: [TrajectoryNode]
    let duration: TimeInterval
    let turnCount: Int
    let toolCount: Int
}

private struct TrajectoryDisplayRow: Identifiable {
    let node: TrajectoryNode
    let request: TrajectoryNode?
    let turn: Int?
    let startsTurn: Bool

    var id: String { node.id }
}

struct TrajectoryNode: Identifiable, Sendable {
    enum Kind: Hashable, Sendable {
        case input, context, request, assistant, tool, subtool

        var color: Color {
            switch self {
            case .input: DSHColor.ocean
            case .context: .green
            case .request: Color(uiColor: .secondaryLabel)
            case .assistant: DSHColor.purple
            case .tool, .subtool: DSHColor.orange
            }
        }
        var laneY: CGFloat {
            switch self { case .input: 6; case .context, .request, .assistant: 25; case .tool, .subtool: 44 }
        }
        var label: String {
            switch self { case .input: "USER"; case .context: "CONTEXT"; case .request: "REQUEST"; case .assistant: "ASSISTANT"; case .tool: "TOOL"; case .subtool: "SUBTOOL" }
        }
    }

    var id: String
    var kind: Kind
    var title: String
    var subtitle: String
    var startSeq: Int
    var endSeq: Int
    var start: Date
    var end: Date
    var records: [SessionEvent]
    var request: TrajectoryRequest?
    var tool: TrajectoryTool?
}

struct TrajectoryTool: Sendable {
    var hierarchy: String
    var schema: JSONValue?
}

struct TrajectoryRequest: Sendable {
    var number: Int
    var turn: Int?
    var step: Int?
    var provider: String?
    var model: String?
    var options: JSONValue?
    var usage: RequestTokenUsage
    var cumulativeUsage: RequestTokenUsage
    var toolCalls: Int
    var subtoolCalls: Int
}

struct RequestTokenUsage: Sendable {
    var uncachedInput = 0
    var cachedInput = 0
    var output = 0
    var reasoning = 0

    var totalInput: Int { uncachedInput + cachedInput }
    var content: Int { max(0, output - reasoning) }

    static func + (lhs: Self, rhs: Self) -> Self {
        Self(
            uncachedInput: lhs.uncachedInput + rhs.uncachedInput,
            cachedInput: lhs.cachedInput + rhs.cachedInput,
            output: lhs.output + rhs.output,
            reasoning: lhs.reasoning + rhs.reasoning
        )
    }
}

enum TrajectoryProjection {
    static func make(from source: [SessionEvent]) -> [TrajectoryNode] {
        let events = source.sorted { $0.seq < $1.seq }
        let completedSteps = Set(events.filter { $0.event.type == "assistant/message" }.map(stepKey))
        let assistantChunks = Dictionary(grouping: events.filter {
            $0.event.type == "assistant/chunk" &&
            ["reasoning-delta", "text-delta", "tool-call-delta", "block-start", "block-end", "usage", "finish"].contains($0.event.chunkType)
        }, by: stepKey)
        var nodes: [TrajectoryNode] = []
        var assistantIndexes: [String: Int] = [:]
        var requestIndexes: [String: Int] = [:]
        var toolIndexes: [String: Int] = [:]
        var subtoolIndexes: [String: Int] = [:]
        let requestMetadataRecords = events.filter { ["request/header", "request/context"].contains($0.event.type) }
        let requestOptions = requestMetadataRecords.first { $0.event.type == "request/header" }?.event.raw?["header"]?["config"]
        let requestContext = requestMetadataRecords.first { $0.event.type == "request/context" }?.event.raw
        let toolDefinitions = requestMetadataRecords.first { $0.event.type == "request/header" }?.event.raw?["header"]?["tools"]?.arrayValue ?? []
        var requestNumber = 0
        var cumulativeUsage = RequestTokenUsage()

        for record in events {
            if Task.isCancelled { return [] }
            let event = record.event
            let key = stepKey(record)
            switch event.type {
            case "user/message" where event.source == nil || event.source == "user":
                guard let text = event.text, !text.isEmpty else { continue }
                nodes.append(node(id: "input-\(record.id)", kind: .input, title: "User", subtitle: text, record: record))

            case "user/message":
                guard let text = event.text, !text.isEmpty else { continue }
                nodes.append(node(
                    id: "context-\(record.id)",
                    kind: .context,
                    title: contextSourceName(event),
                    subtitle: text,
                    record: record
                ))

            case "assistant/chunk" where !completedSteps.contains(key):
                guard ["reasoning-delta", "text-delta"].contains(event.chunkType), let text = event.text, !text.isEmpty else { continue }
                if requestIndexes[key] == nil {
                    requestNumber += 1
                    let usage = requestUsage(from: event.usage)
                    cumulativeUsage = cumulativeUsage + usage
                    requestIndexes[key] = nodes.count
                    nodes.append(requestNode(
                        number: requestNumber,
                        key: key,
                        startRecord: record,
                        endRecord: record,
                        records: requestMetadataRecords + [record],
                        options: requestOptions,
                        context: requestContext,
                        usage: usage,
                        cumulativeUsage: cumulativeUsage,
                        finalEvent: nil,
                        allEvents: events
                    ))
                } else if let requestIndex = requestIndexes[key] {
                    nodes[requestIndex].endSeq = record.seq
                    nodes[requestIndex].end = record.date
                    nodes[requestIndex].records.append(record)
                }
                if let index = assistantIndexes[key] {
                    nodes[index].subtitle += text
                    nodes[index].endSeq = record.seq
                    nodes[index].end = record.date
                    nodes[index].records.append(record)
                } else {
                    assistantIndexes[key] = nodes.count
                    nodes.append(node(id: "assistant-stream-\(key)", kind: .assistant, title: "Assistant", subtitle: text, record: record))
                }

            case "assistant/message":
                let chunks = assistantChunks[key] ?? []
                let startRecord = chunks.first ?? record
                let usage = requestUsage(from: event.usage ?? event.raw?["usage"])
                if let requestIndex = requestIndexes[key] {
                    let oldUsage = nodes[requestIndex].request?.usage ?? RequestTokenUsage()
                    cumulativeUsage = cumulativeUsage + usage + RequestTokenUsage(
                        uncachedInput: -oldUsage.uncachedInput,
                        cachedInput: -oldUsage.cachedInput,
                        output: -oldUsage.output,
                        reasoning: -oldUsage.reasoning
                    )
                    nodes[requestIndex] = requestNode(
                        number: nodes[requestIndex].request?.number ?? requestNumber,
                        key: key,
                        startRecord: startRecord,
                        endRecord: record,
                        records: requestMetadataRecords + chunks + [record],
                        options: requestOptions,
                        context: requestContext,
                        usage: usage,
                        cumulativeUsage: cumulativeUsage,
                        finalEvent: event,
                        allEvents: events
                    )
                } else {
                    requestNumber += 1
                    cumulativeUsage = cumulativeUsage + usage
                    requestIndexes[key] = nodes.count
                    nodes.append(requestNode(
                        number: requestNumber,
                        key: key,
                        startRecord: startRecord,
                        endRecord: record,
                        records: requestMetadataRecords + chunks + [record],
                        options: requestOptions,
                        context: requestContext,
                        usage: usage,
                        cumulativeUsage: cumulativeUsage,
                        finalEvent: event,
                        allEvents: events
                    ))
                }
                let subtitle = nonEmpty(event.reasoning) ?? nonEmpty(event.text) ?? "(tool call only)"
                nodes.append(TrajectoryNode(
                    id: "assistant-\(record.id)", kind: .assistant, title: "Assistant", subtitle: subtitle,
                    startSeq: startRecord.seq, endSeq: record.seq, start: startRecord.date, end: record.date,
                    records: chunks + [record], request: nil, tool: nil
                ))

            case "tool/call":
                let callKey = event.callId ?? record.id
                let arguments = event.arguments?.jsonDisplayText.replacingOccurrences(of: "\n", with: " ") ?? ""
                toolIndexes[callKey] = nodes.count
                var toolNode = node(id: "tool-\(callKey)", kind: .tool, title: event.name?.lowercased() ?? "tool", subtitle: arguments, record: record)
                toolNode.tool = TrajectoryTool(
                    hierarchy: "Assistant Message",
                    schema: toolDefinitions.first { $0["name"]?.stringValue == event.name }
                )
                nodes.append(toolNode)

            case "tool/result":
                let callKey = event.callId ?? record.id
                if let index = toolIndexes[callKey] {
                    let result = event.preview ?? ""
                    if !result.isEmpty {
                        nodes[index].subtitle += (nodes[index].subtitle.isEmpty ? "" : "  →  ") + result.replacingOccurrences(of: "\n", with: " ")
                    }
                    nodes[index].endSeq = record.seq
                    nodes[index].end = record.date
                    nodes[index].records.append(record)
                } else {
                    nodes.append(node(id: "tool-result-\(record.id)", kind: .tool, title: event.isError == true ? "tool error" : "tool result", subtitle: event.preview ?? "", record: record))
                }

            case "tool/code-dispatch-start":
                let callKey = event.subCallId ?? record.id
                let arguments = event.arguments?.jsonDisplayText.replacingOccurrences(of: "\n", with: " ") ?? ""
                let parentTitle = event.parentCallId.flatMap { parentId in
                    toolIndexes[parentId].map { nodes[$0].title }
                } ?? "Tool"
                subtoolIndexes[callKey] = nodes.count
                var subtoolNode = node(
                    id: "subtool-\(callKey)",
                    kind: .subtool,
                    title: event.name?.lowercased() ?? "subtool",
                    subtitle: arguments,
                    record: record
                )
                subtoolNode.tool = TrajectoryTool(hierarchy: parentTitle, schema: nil)
                nodes.append(subtoolNode)

            case "tool/code-dispatch":
                let callKey = event.subCallId ?? record.id
                if let index = subtoolIndexes[callKey] {
                    let result = event.preview ?? ""
                    if !result.isEmpty {
                        nodes[index].subtitle += (nodes[index].subtitle.isEmpty ? "" : "  →  ") + result.replacingOccurrences(of: "\n", with: " ")
                    }
                    nodes[index].endSeq = record.seq
                    nodes[index].end = record.date
                    nodes[index].records.append(record)
                } else {
                    var subtoolNode = node(
                        id: "subtool-result-\(record.id)",
                        kind: .subtool,
                        title: event.name?.lowercased() ?? "subtool",
                        subtitle: event.preview ?? "",
                        record: record
                    )
                    subtoolNode.tool = TrajectoryTool(hierarchy: "Tool", schema: nil)
                    nodes.append(subtoolNode)
                }

            default:
                continue
            }
        }
        return nodes.sorted {
            if $0.startSeq != $1.startSeq { return $0.startSeq < $1.startSeq }
            return sortPriority($0.kind) < sortPriority($1.kind)
        }
    }

    private static func node(id: String, kind: TrajectoryNode.Kind, title: String, subtitle: String, record: SessionEvent) -> TrajectoryNode {
        TrajectoryNode(id: id, kind: kind, title: title, subtitle: subtitle, startSeq: record.seq, endSeq: record.seq, start: record.date, end: record.date, records: [record], request: nil, tool: nil)
    }

    private static func requestNode(
        number: Int,
        key: String,
        startRecord: SessionEvent,
        endRecord: SessionEvent,
        records: [SessionEvent],
        options: JSONValue?,
        context: JSONValue?,
        usage: RequestTokenUsage,
        cumulativeUsage: RequestTokenUsage,
        finalEvent: GatewayEvent?,
        allEvents: [SessionEvent]
    ) -> TrajectoryNode {
        let source = finalEvent?.raw?["message"]?["source"]
        let provider = source?["provider"]?.stringValue ?? context?["provider"]?.stringValue ?? options?["provider"]?.stringValue
        let model = source?["model"]?.stringValue ?? context?["model"]?.stringValue ?? options?["model"]?.stringValue
        let turn = finalEvent?.turn ?? startRecord.event.turn
        let step = finalEvent?.step ?? startRecord.event.step
        let toolCalls = finalEvent?.toolCalls?.count ?? 0
        let callIds = Set(finalEvent?.toolCalls?.map(\.id) ?? [])
        let subtoolCalls = allEvents.filter {
            $0.event.type == "tool/code-dispatch" &&
            ($0.event.rootCallId.map(callIds.contains) == true || $0.event.parentCallId.map(callIds.contains) == true)
        }.count
        let subtitle = [
            turn.map { "Turn \($0)" },
            step.map { "Step \($0)" },
            provider,
            model
        ].compactMap { $0 }.joined(separator: " · ")
        return TrajectoryNode(
            id: "request-\(key)",
            kind: .request,
            title: "Request #\(number)",
            subtitle: subtitle,
            startSeq: startRecord.seq,
            endSeq: endRecord.seq,
            start: startRecord.date,
            end: endRecord.date,
            records: records,
            request: TrajectoryRequest(
                number: number,
                turn: turn,
                step: step,
                provider: provider,
                model: model,
                options: options,
                usage: usage,
                cumulativeUsage: cumulativeUsage,
                toolCalls: toolCalls,
                subtoolCalls: subtoolCalls
            ),
            tool: nil
        )
    }

    private static func requestUsage(from value: JSONValue?) -> RequestTokenUsage {
        RequestTokenUsage(
            uncachedInput: value?.firstInteger(for: ["inputTokens", "input_tokens", "uncachedInputTokens"]) ?? 0,
            cachedInput: value?.firstInteger(for: ["cacheReadTokens", "cachedInputTokens", "cached_tokens"]) ?? 0,
            output: value?.firstInteger(for: ["outputTokens", "output_tokens", "completionTokens"]) ?? 0,
            reasoning: value?.firstInteger(for: ["reasoningTokens", "reasoning_tokens"]) ?? 0
        )
    }
    private static func stepKey(_ record: SessionEvent) -> String { "\(record.event.turn ?? -1)-\(record.event.step ?? -1)" }
    private static func sortPriority(_ kind: TrajectoryNode.Kind) -> Int {
        switch kind {
        case .input: 0
        case .context: 1
        case .request: 2
        case .assistant: 3
        case .tool: 4
        case .subtool: 5
        }
    }
    private static func contextSourceName(_ event: GatewayEvent) -> String {
        if let plugin = event.raw?["source"]?["plugin"]?.stringValue, !plugin.isEmpty {
            return plugin
        }
        return event.source ?? "context"
    }
    private static func nonEmpty(_ value: String?) -> String? {
        guard let value, !value.isEmpty else { return nil }
        return value
    }
}

private struct TrajectoryRow: View {
    let node: TrajectoryNode
    let requestNode: TrajectoryNode?
    let highlighted: Bool
    let onSelect: () -> Void
    let onRequest: (TrajectoryNode) -> Void
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        HStack(alignment: .top, spacing: 9) {
            VStack(spacing: 0) {
                if let requestNode {
                    Circle()
                        .fill(Color(uiColor: .secondaryLabel))
                        .frame(width: 10, height: 10)
                        .frame(width: 10, height: 20)
                        .contentShape(Rectangle())
                        .movementQualifiedTap { onRequest(requestNode) }
                        .accessibilityLabel("打开 Request #\(requestNode.request?.number ?? 0)")
                } else {
                    Circle()
                        .fill(node.kind.color)
                        .frame(width: 7, height: 7)
                        .frame(width: 10, height: 20)
                }
                Rectangle()
                    .fill(timelineLineColor)
                    .frame(width: colorScheme == .dark ? 1.5 : 1, height: 22)
            }
            HStack(spacing: 8) {
                Text(node.kind.label)
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(node.kind.color)
                    .padding(.horizontal, 6).padding(.vertical, 3)
                    .background(tagFill, in: RoundedRectangle(cornerRadius: 6, style: .continuous))
                    .overlay {
                        RoundedRectangle(cornerRadius: 6, style: .continuous)
                            .stroke(tagEdge, lineWidth: 0.7)
                    }
                if node.kind != .input && node.kind != .assistant {
                    Text(node.title)
                        .font(.subheadline.weight(.medium))
                        .lineLimit(1)
                        .layoutPriority(1)
                }
                if !node.subtitle.isEmpty {
                    Text(node.subtitle)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                Spacer()
                Text("#\(node.startSeq)").font(.caption2.monospaced()).foregroundStyle(.secondary)
                Image(systemName: "chevron.right").font(.caption2).foregroundStyle(.tertiary)
            }
            .frame(height: 20)
            .contentShape(Rectangle())
            .movementQualifiedTap(action: onSelect)
            .accessibilityLabel("打开 \(node.kind.label) #\(node.startSeq)")
        }
        .padding(.horizontal, 8)
        .frame(height: 42)
        .background(highlighted ? DSHColor.ocean.opacity(0.12) : .clear, in: RoundedRectangle(cornerRadius: 10))
        .animation(.easeInOut(duration: 0.18), value: highlighted)
    }

    private var tagFill: Color {
        node.kind.color.opacity(colorScheme == .dark ? 0.24 : 0.10)
    }

    private var tagEdge: Color {
        node.kind.color.opacity(colorScheme == .dark ? 0.38 : 0.14)
    }

    private var timelineLineColor: Color {
        colorScheme == .dark ? .white.opacity(0.22) : .black.opacity(0.14)
    }
}

/// A row tap that is rejected whenever the finger actually travelled. Using
/// global coordinates keeps the measurement independent of the page moving
/// underneath the touch during a horizontal swipe.
private struct MovementQualifiedTapModifier: ViewModifier {
    let action: () -> Void
    private let tapTolerance: CGFloat = 10

    func body(content: Content) -> some View {
        content
            .simultaneousGesture(
                DragGesture(minimumDistance: 0, coordinateSpace: .global)
                    .onEnded { value in
                        let travelled = hypot(value.translation.width, value.translation.height)
                        let predicted = hypot(
                            value.predictedEndTranslation.width,
                            value.predictedEndTranslation.height
                        )
                        guard max(travelled, predicted) <= tapTolerance else { return }
                        action()
                    }
            )
            .accessibilityAddTraits(.isButton)
            .accessibilityAction(named: Text("打开"), action)
    }
}

private extension View {
    func movementQualifiedTap(action: @escaping () -> Void) -> some View {
        modifier(MovementQualifiedTapModifier(action: action))
    }
}

private struct TrajectoryTurnHeader: View {
    let turn: Int
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        HStack(spacing: 8) {
            Text("Turn \(turn)")
                .font(.caption2.monospaced())
                .foregroundStyle(.secondary)
            Rectangle()
                .fill(turnDividerColor)
                .frame(height: 1)
        }
        .padding(.horizontal, 8)
        .frame(height: 18)
    }

    private var turnDividerColor: Color {
        colorScheme == .dark ? .white.opacity(0.16) : .black.opacity(0.10)
    }
}

private struct EventDetailSheet: View {
    private enum Tab: Int, CaseIterable {
        case summary, preview, raw

        var title: String {
            switch self { case .summary: "摘要"; case .preview: "预览"; case .raw: "原始" }
        }
    }

    private enum RequestTab: Int, CaseIterable {
        case summary, options, usage, timing
        var title: String {
            switch self { case .summary: "摘要"; case .options: "选项"; case .usage: "用量"; case .timing: "耗时" }
        }
    }

    private enum ToolTab: Int, CaseIterable {
        case summary, payload, result, schema, timing
        var title: String {
            switch self { case .summary: "摘要"; case .payload: "参数"; case .result: "结果"; case .schema: "Schema"; case .timing: "耗时" }
        }
    }

    let node: TrajectoryNode
    @Environment(\.dismiss) private var dismiss
    @State private var tab: Tab = .summary
    @State private var requestTab: RequestTab = .summary
    @State private var toolTab: ToolTab = .summary
    @State private var summaryThinkingExpanded = false
    @Namespace private var tabIndicator

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if node.kind == .request {
                    requestDetailContent
                } else if node.kind == .tool || node.kind == .subtool {
                    toolDetailContent
                } else {
                    detailTabs
                    Divider()
                    TabView(selection: $tab) {
                        scrollPage(summaryView).tag(Tab.summary)
                        scrollPage(previewView).tag(Tab.preview)
                        scrollPage(rawView).tag(Tab.raw)
                    }
                    .tabViewStyle(.page(indexDisplayMode: .never))
                }
            }
            .navigationTitle(node.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("完成") { dismiss() } } }
        }
        .presentationDetents([.medium, .large])
        .presentationBackground(.ultraThinMaterial)
    }

    private func scrollPage<Content: View>(_ content: Content) -> some View {
        ScrollView {
            content
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 22)
                .padding(.top, 22)
                .padding(.bottom, 52)
        }
        .scrollBounceBehavior(.basedOnSize)
        .mask {
            VStack(spacing: 0) {
                LinearGradient(
                    colors: [.clear, .black],
                    startPoint: .top,
                    endPoint: .bottom
                )
                .frame(height: 12)

                Rectangle().fill(.black)

                LinearGradient(
                    colors: [.black, .clear],
                    startPoint: .top,
                    endPoint: .bottom
                )
                .frame(height: 36)
            }
        }
    }

    private var requestDetailContent: some View {
        VStack(spacing: 0) {
            HStack(spacing: 0) {
                ForEach(RequestTab.allCases, id: \.self) { item in
                    sheetTabButton(item.title, selected: requestTab == item) {
                        withAnimation(.snappy(duration: 0.28)) { requestTab = item }
                    }
                }
            }
            .padding(.top, 8)
            Divider()
            TabView(selection: $requestTab) {
                scrollPage(requestSummaryView).tag(RequestTab.summary)
                scrollPage(requestOptionsView).tag(RequestTab.options)
                scrollPage(requestUsageView).tag(RequestTab.usage)
                scrollPage(requestTimingView).tag(RequestTab.timing)
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
        }
    }

    private var toolDetailContent: some View {
        let tabs = ToolTab.allCases.filter { node.kind == .tool || $0 != .schema }
        return VStack(spacing: 0) {
            HStack(spacing: 0) {
                ForEach(tabs, id: \.self) { item in
                    sheetTabButton(item.title, selected: toolTab == item) {
                        withAnimation(.snappy(duration: 0.28)) { toolTab = item }
                    }
                }
            }
            .padding(.top, 8)
            Divider()
            TabView(selection: $toolTab) {
                scrollPage(toolSummaryView).tag(ToolTab.summary)
                scrollPage(toolPayloadView).tag(ToolTab.payload)
                scrollPage(toolResultView).tag(ToolTab.result)
                if node.kind == .tool { scrollPage(toolSchemaView).tag(ToolTab.schema) }
                scrollPage(toolTimingView).tag(ToolTab.timing)
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
        }
    }

    private func sheetTabButton(_ title: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 8) {
                Text(title)
                    .font(.subheadline.weight(selected ? .semibold : .regular))
                    .foregroundStyle(selected ? DSHColor.ocean : .secondary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
                ZStack {
                    Color.clear.frame(height: 2)
                    if selected { Capsule().fill(DSHColor.ocean).frame(height: 2) }
                }
            }
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(.plain)
    }

    private var detailTabs: some View {
        HStack(spacing: 0) {
            ForEach(Tab.allCases, id: \.self) { item in
                Button {
                    withAnimation(.snappy(duration: 0.28)) { tab = item }
                } label: {
                    VStack(spacing: 8) {
                        Text(item.title)
                            .font(.subheadline.weight(tab == item ? .semibold : .regular))
                            .foregroundStyle(tab == item ? DSHColor.ocean : .secondary)
                        ZStack {
                            Color.clear.frame(height: 2)
                            if tab == item {
                                Capsule()
                                    .fill(DSHColor.ocean)
                                    .matchedGeometryEffect(id: "tab-indicator", in: tabIndicator)
                                    .frame(height: 2)
                            }
                        }
                    }
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.top, 8)
    }

    private var requestSummaryView: some View {
        VStack(alignment: .leading, spacing: 18) {
            detailRow("Status", statusText)
            detailRow("Provider", requestInfo?.provider ?? "—")
            detailRow("Model", requestInfo?.model ?? "—")
            detailRow("Tool calls", "\(requestInfo?.toolCalls ?? 0)")
            detailRow("Subtool calls", "\(requestInfo?.subtoolCalls ?? 0)")
            detailRow("Result", isCompleted ? "Assistant Message" : "—")
            Divider()
            summarySection("Options") { requestOptionsView }
            Divider()
            summarySection("Usage") { requestUsageView }
            Divider()
            summarySection("Timing") { requestTimingView }
        }
    }

    @ViewBuilder private var requestOptionsView: some View {
        if let options = requestInfo?.options {
            jsonCode(options)
        } else {
            unavailable("没有记录模型选项")
        }
    }

    private var requestUsageView: some View {
        VStack(alignment: .leading, spacing: 20) {
            tokenUsageSection("This request", usage: requestInfo?.usage)
            tokenUsageSection("Session cumulative", usage: requestInfo?.cumulativeUsage)
        }
    }

    private var requestTimingView: some View {
        VStack(alignment: .leading, spacing: 10) {
            detailRow("Started", startedText)
            detailRow("Total duration", durationText)
            if let ttftText { detailRow("TTFT", ttftText) }
            if let generationText { detailRow("Generation", generationText) }
            if let throughputText { detailRow("Throughput", throughputText) }
        }
    }

    private var toolSummaryView: some View {
        VStack(alignment: .leading, spacing: 18) {
            detailRow("Hierarchy", node.tool?.hierarchy ?? (node.kind == .subtool ? "Tool" : "Assistant Message"))
            detailRow("Status", statusText)
            Divider()
            summarySection("Payload") { toolPayloadView }
            Divider()
            summarySection("Result") { toolResultView }
            if node.kind == .tool {
                Divider()
                summarySection("Schema") { toolSchemaView }
            }
            Divider()
            summarySection("Timing") { toolTimingView }
        }
    }

    @ViewBuilder private var toolPayloadView: some View {
        if let arguments = toolArgumentValue {
            jsonCode(arguments)
        } else {
            unavailable("没有参数")
        }
    }

    @ViewBuilder private var toolResultView: some View {
        if !toolResult.isEmpty {
            Text(toolResult)
                .font(.callout.monospaced())
                .textSelection(.enabled)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
        } else {
            unavailable(isCompleted ? "(no output)" : "等待工具返回…")
        }
    }

    @ViewBuilder private var toolSchemaView: some View {
        if let schema = node.tool?.schema {
            VStack(alignment: .leading, spacing: 12) {
                Text(schema["name"]?.stringValue ?? node.title)
                    .font(.headline)
                if let description = schema["description"]?.stringValue, !description.isEmpty {
                    MarkdownContent(description, compact: true)
                        .fixedSize(horizontal: false, vertical: true)
                }
                if let parameters = schema["parameters"] {
                    Text("Parameters").font(.subheadline.weight(.semibold)).foregroundStyle(.secondary)
                    jsonCode(parameters)
                }
            }
        } else {
            unavailable("没有记录工具 Schema")
        }
    }

    private var toolTimingView: some View {
        VStack(alignment: .leading, spacing: 10) {
            detailRow("Started", startedText)
            detailRow("Duration", durationText)
            detailRow("Timing source", "Session timestamps")
        }
    }

    private func summarySection<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Label(title, systemImage: "chevron.right")
                .labelStyle(TrailingIconLabelStyle())
                .font(.headline)
                .foregroundStyle(.secondary)
            content()
        }
    }

    private func tokenUsageSection(_ title: String, usage: RequestTokenUsage?) -> some View {
        VStack(alignment: .leading, spacing: 9) {
            Text(title).font(.headline).foregroundStyle(.secondary)
            detailRow("Input", "\(usage?.totalInput ?? 0) tok")
            detailRow("Cached", "\(usage?.cachedInput ?? 0) tok", indented: true)
            detailRow("Other", "\(usage?.uncachedInput ?? 0) tok", indented: true)
            detailRow("Output", "\(usage?.output ?? 0) tok")
            detailRow("Reasoning", "\(usage?.reasoning ?? 0) tok", indented: true)
            detailRow("Content", "\(usage?.content ?? 0) tok", indented: true)
        }
    }

    private func jsonCode(_ value: JSONValue) -> some View {
        MarkdownContent("```json\n\(value.jsonDisplayText)\n```", compact: true)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func unavailable(_ text: String) -> some View {
        Text(text).font(.subheadline).foregroundStyle(.secondary)
    }

    private var summaryView: some View {
        VStack(alignment: .leading, spacing: 18) {
            detailRow("Source", sourceRequestText)
            detailRow("Status", statusText)
            if let usage = usageSummary {
                detailRow("Tokens", usage.output.map { "\($0) tok" } ?? "—")
                detailRow("Reasoning", usage.reasoning.map { "\($0) tok" } ?? "—", indented: true)
                detailRow("Content", usage.content.map { "\($0) tok" } ?? "—", indented: true)
            }
            Divider()
            summaryPreview
            Divider()
            VStack(alignment: .leading, spacing: 10) {
                Label("Request Timing", systemImage: "chevron.right")
                    .labelStyle(TrailingIconLabelStyle())
                    .font(.headline)
                    .foregroundStyle(.secondary)
                detailRow("Started", startedText)
                detailRow("Total duration", durationText)
                if let ttftText { detailRow("TTFT", ttftText) }
                if let generationText { detailRow("Generation", generationText) }
                if let throughputText { detailRow("Throughput", throughputText) }
            }
        }
    }

    @ViewBuilder private var summaryPreview: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("Preview", systemImage: "chevron.right")
                .labelStyle(TrailingIconLabelStyle())
                .font(.headline)
                .foregroundStyle(.secondary)
            if node.kind == .assistant && !reasoningPreview.isEmpty {
                Button {
                    withAnimation(.easeInOut(duration: 0.2)) {
                        summaryThinkingExpanded.toggle()
                    }
                } label: {
                    HStack {
                        Text("Thinking")
                        Spacer()
                        Image(systemName: "chevron.right")
                            .font(.caption)
                            .rotationEffect(.degrees(summaryThinkingExpanded ? 90 : 0))
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.secondary)

                if summaryThinkingExpanded {
                    MarkdownContent(reasoningPreview, compact: true)
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .transition(.opacity.combined(with: .move(edge: .top)))
                }
            }
            if !contentPreview.isEmpty {
                MarkdownContent(contentPreview)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
            } else if node.kind != .assistant {
                MarkdownContent(node.subtitle, compact: node.kind == .tool)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }

    @ViewBuilder private var previewView: some View {
        VStack(alignment: .leading, spacing: 20) {
            switch node.kind {
            case .input:
                previewSection("Message", text: node.subtitle)
            case .context:
                previewSection("Context", text: node.subtitle)
            case .request:
                requestSummaryView
            case .assistant:
                if !reasoningPreview.isEmpty { previewSection("Thinking", text: reasoningPreview) }
                if !contentPreview.isEmpty { previewSection("Content", text: contentPreview) }
                if reasoningPreview.isEmpty && contentPreview.isEmpty {
                    previewSection("Content", text: node.subtitle)
                }
            case .tool, .subtool:
                if !toolArguments.isEmpty { previewSection("Arguments", text: toolArguments, compact: true) }
                if !toolResult.isEmpty { previewSection("Result", text: toolResult, compact: true) }
                if toolArguments.isEmpty && toolResult.isEmpty {
                    previewSection("Tool", text: node.subtitle, compact: true)
                }
            }
        }
    }

    @ViewBuilder private var rawView: some View {
        VStack(alignment: .leading, spacing: 24) {
            switch node.kind {
            case .assistant:
                let blocks = assistantRawBlocks
                if blocks.isEmpty {
                    rawBlock(index: 1, type: "text", text: node.subtitle)
                } else {
                    ForEach(Array(blocks.enumerated()), id: \.offset) { index, block in
                        rawBlock(index: index + 1, type: block.type, text: block.text)
                    }
                }
            case .input:
                rawBlock(index: 1, type: "text", text: node.subtitle)
            case .context:
                rawBlock(
                    index: 1,
                    type: "context-event",
                    text: node.records.first?.event.raw?.jsonDisplayText ?? node.subtitle
                )
            case .request:
                if let options = requestInfo?.options { rawBlock(index: 1, type: "options", text: options.jsonDisplayText) }
            case .tool, .subtool:
                if !toolArguments.isEmpty { rawBlock(index: 1, type: "arguments", text: toolArguments) }
                if !toolResult.isEmpty { rawBlock(index: toolArguments.isEmpty ? 1 : 2, type: "result", text: toolResult) }
                if toolArguments.isEmpty && toolResult.isEmpty { rawBlock(index: 1, type: "tool", text: node.subtitle) }
            }
        }
    }

    private func rawBlock(index: Int, type: String, text: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Block #\(index) \(type)")
                .font(.subheadline.monospaced())
                .foregroundStyle(.secondary)
            Text(text)
                .font(.body.monospaced())
                .textSelection(.enabled)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private func previewSection(_ title: String, text: String, compact: Bool = false) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.headline).foregroundStyle(.secondary)
            MarkdownContent(text, compact: compact)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var isError: Bool { node.records.contains { $0.event.isError == true } }
    private var isCompleted: Bool {
        switch node.kind {
        case .input: true
        case .context: true
        case .request: node.records.contains { $0.event.type == "assistant/message" }
        case .assistant: node.records.contains { $0.event.type == "assistant/message" }
        case .tool: node.records.contains { $0.event.type == "tool/result" }
        case .subtool: node.records.contains { $0.event.type == "tool/code-dispatch" }
        }
    }
    private var statusText: String { isError ? "Failed" : (isCompleted ? "Completed" : "Running") }
    private var sourceRequestText: String { turn.map { "Request #\($0)" } ?? "#\(node.startSeq)" }
    private var durationText: String { String(format: "%.2f s", max(0, node.end.timeIntervalSince(node.start))) }
    private var turn: Int? { node.records.lazy.compactMap(\.event.turn).first }
    private var step: Int? { node.records.lazy.compactMap(\.event.step).first }
    private var requestInfo: TrajectoryRequest? { node.request }
    private var finalAssistant: GatewayEvent? { node.records.reversed().first { $0.event.type == "assistant/message" }?.event }
    private var reasoningPreview: String { finalAssistant?.reasoning ?? "" }
    private var contentPreview: String { finalAssistant?.text ?? "" }
    private var assistantRawBlocks: [(type: String, text: String)] {
        var blocks: [(String, String)] = []
        if !reasoningPreview.isEmpty { blocks.append(("thinking", reasoningPreview)) }
        if !contentPreview.isEmpty { blocks.append(("text", contentPreview)) }
        if let calls = finalAssistant?.toolCalls {
            for call in calls {
                let arguments = call.arguments?.jsonDisplayText ?? ""
                blocks.append(("tool-call \(call.name)", arguments))
            }
        }
        return blocks
    }
    private var toolArguments: String {
        toolArgumentValue?.jsonDisplayText ?? ""
    }
    private var toolArgumentValue: JSONValue? {
        node.records.first {
            $0.event.type == "tool/call" || $0.event.type == "tool/code-dispatch-start" || $0.event.type == "tool/code-dispatch"
        }?.event.arguments
    }
    private var toolResult: String {
        node.records.first {
            $0.event.type == "tool/result" || $0.event.type == "tool/code-dispatch"
        }?.event.preview ?? ""
    }
    private var usageSummary: TokenSummary? {
        let usage = node.records.reversed().compactMap { $0.event.usage ?? $0.event.raw?["usage"] }.first
        guard let usage else { return nil }
        let input = usage.firstInteger(for: ["inputTokens", "input_tokens", "uncachedInputTokens", "promptTokens"])
        let output = usage.firstInteger(for: ["outputTokens", "output_tokens", "completionTokens"])
        let reasoning = usage.firstInteger(for: ["reasoningTokens", "reasoning_tokens"])
        let content = output.map { max(0, $0 - (reasoning ?? 0)) }
        guard input != nil || output != nil || reasoning != nil else { return nil }
        return TokenSummary(input: input, output: output, reasoning: reasoning, content: content)
    }
    private var firstTokenDate: Date? {
        node.records.first {
            ["reasoning-delta", "text-delta"].contains($0.event.chunkType) && !($0.event.text ?? "").isEmpty
        }?.date
    }
    private var startedText: String {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd HH:mm:ss.SSS"
        return formatter.string(from: node.start)
    }
    private var ttft: TimeInterval? { firstTokenDate.map { max(0, $0.timeIntervalSince(node.start)) } }
    private var generation: TimeInterval? { firstTokenDate.map { max(0, node.end.timeIntervalSince($0)) } }
    private var ttftText: String? { ttft.map(formatInterval) }
    private var generationText: String? { generation.map(formatInterval) }
    private var throughputText: String? {
        let output = requestInfo?.usage.output ?? usageSummary?.output
        guard let output, let generation, generation > 0 else { return nil }
        return String(format: "%.1f tok/s", Double(output) / generation)
    }
    private func formatInterval(_ interval: TimeInterval) -> String {
        interval < 1 ? "\(Int((interval * 1000).rounded())) ms" : String(format: "%.2f s", interval)
    }
    private func detailRow(_ label: String, _ value: String, indented: Bool = false) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Text(label)
                .foregroundStyle(.secondary)
                .padding(.leading, indented ? 16 : 0)
                .frame(width: 158, alignment: .leading)
            Text(value)
                .foregroundStyle(.primary)
                .textSelection(.enabled)
            Spacer(minLength: 0)
        }
        .font(.subheadline)
    }
}

private struct TokenSummary {
    var input: Int?
    var output: Int?
    var reasoning: Int?
    var content: Int?
}

private struct TrailingIconLabelStyle: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 5) {
            configuration.title
            configuration.icon.font(.caption2)
        }
    }
}

private extension JSONValue {
    func firstInteger(for keys: [String]) -> Int? {
        if let object = objectValue {
            for key in keys {
                if let value = object[key]?.doubleValue { return Int(value) }
            }
            for value in object.values {
                if let match = value.firstInteger(for: keys) { return match }
            }
        }
        if let array = arrayValue {
            for value in array {
                if let match = value.firstInteger(for: keys) { return match }
            }
        }
        return nil
    }
}
