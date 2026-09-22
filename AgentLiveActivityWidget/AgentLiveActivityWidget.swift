import ActivityKit
import SwiftUI
import WidgetKit

@main
struct AgentLiveActivityWidgetBundle: WidgetBundle {
    var body: some Widget { AgentLiveActivityWidget() }
}

struct AgentLiveActivityWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: AgentActivityAttributes.self) { context in
            AgentActivityCard(context: context, surface: .lockScreen, showsHeader: true)
                .activityBackgroundTint(AgentActivityColors.lockBackground)
                .activitySystemActionForegroundColor(.white)
                .widgetURL(AgentActivityLink.session(context))
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    AgentDynamicIslandBrand()
                        .frame(height: AgentActivityMetrics.expandedStatusCapsuleHeight)
                        .padding(.leading, AgentActivityMetrics.expandedBrandLeadingInset)
                        .padding(.top, AgentActivityMetrics.expandedBrandTopInset)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    HStack(spacing: 1) {
                        Image(systemName: expandedStatusSymbol(context))
                            .foregroundStyle(expandedStatusColor(context))
                            .font(.system(size: 10, weight: .semibold))
                            .frame(width: 12)
                            .layoutPriority(1)
                        Text(expandedStatusText(context))
                            .foregroundStyle(expandedStatusColor(context))
                            .minimumScaleFactor(0.8)
                            .frame(width: 28, alignment: .leading)
                        AgentElapsedTime(context: context)
                            .foregroundStyle(AgentActivityColors.islandDimmed)
                            .monospacedDigit()
                            .minimumScaleFactor(0.75)
                            .frame(width: 36, alignment: .trailing)
                    }
                    .font(.system(size: 10, weight: .semibold))
                    .lineLimit(1)
                    .padding(.horizontal, 5)
                    .frame(
                        width: AgentActivityMetrics.expandedStatusCapsuleWidth,
                        height: AgentActivityMetrics.expandedStatusCapsuleHeight
                    )
                    .background(AgentActivityColors.islandTile, in: Capsule())
                    .padding(.trailing, AgentActivityMetrics.expandedTrailingInset)
                    .padding(.top, AgentActivityMetrics.expandedBrandTopInset)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    AgentDynamicIslandExpandedContent(context: context)
                }
            } compactLeading: {
                AgentBrandMark(compact: true)
                    .frame(width: 20, height: 20)
            } compactTrailing: {
                AgentCompactStatus(context: context)
            } minimal: {
                Image(systemName: AgentActivityPresentation.symbol(for: context.state.phase))
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(AgentActivityColors.status(for: context.state.phase))
            }
            .contentMargins(.top, AgentActivityMetrics.expandedTopInset, for: .expanded)
            .contentMargins(.horizontal, AgentActivityMetrics.expandedHorizontalInset, for: .expanded)
            .contentMargins(
                .bottom,
                AgentActivityMetrics.expandedBottomInset(
                    for: context.state,
                    isStale: context.isStale
                ),
                for: .expanded
            )
            .keylineTint(AgentActivityColors.status(for: context.state.phase))
            .widgetURL(AgentActivityLink.session(context))
        }
        .contentMarginsDisabled()
    }
}

private func expandedStatusSymbol(
    _ context: ActivityViewContext<AgentActivityAttributes>
) -> String {
    if context.isStale && !context.state.phase.isTerminal {
        return "exclamationmark.circle"
    }
    return AgentActivityPresentation.symbol(for: context.state.phase)
}

private func expandedStatusColor(
    _ context: ActivityViewContext<AgentActivityAttributes>
) -> Color {
    if context.isStale && !context.state.phase.isTerminal {
        return AgentActivityColors.failed
    }
    return AgentActivityColors.status(for: context.state.phase)
}

private func expandedStatusText(
    _ context: ActivityViewContext<AgentActivityAttributes>
) -> String {
    if context.isStale && !context.state.phase.isTerminal {
        return "待同步"
    }
    switch context.state.phase {
    case .running: return "执行中"
    case .awaitingChoice: return "待选择"
    case .awaitingApproval: return "待审批"
    case .submittingApproval: return "提交中"
    case .approved: return "已允许"
    case .rejected: return "已拒绝"
    case .failed: return "失败"
    case .completed: return "已完成"
    }
}

private enum AgentActivityMetrics {
    static let cardHorizontalInset: CGFloat = 14
    static let cardVerticalInset: CGFloat = 12
    // The expanded island is clipped by a much rounder mask near its lower
    // corners than its visible bounding box suggests. Keep the content inside
    // that safe shape instead of relying on the rectangular region bounds.
    static let expandedHorizontalInset: CGFloat = 23
    static let expandedTopInset: CGFloat = 5
    // 顶部左侧需要避开灵动岛圆角遮罩。只移动品牌整体，不缩放或裁剪鲸鱼。
    static let expandedBrandLeadingInset: CGFloat = 8
    static let expandedBrandTopInset: CGFloat = 8
    static let expandedTrailingInset: CGFloat = 12
    static let expandedStatusCapsuleWidth: CGFloat = 88
    static let expandedStatusCapsuleHeight: CGFloat = 24
    static let headerToStatusSpacing: CGFloat = 5
    static let operationMargin: CGFloat = 7
    static let actionSpacing: CGFloat = 8
    static let actionHeight: CGFloat = 36
    static let islandActionHeight: CGFloat = 30

    /// 展开岛的外形高度由内容和 content margin 一起决定。
    /// 带按钮的状态必须保留正的底部边距，否则系统会收缩外形并裁掉按钮。
    static func expandedBottomInset(
        for state: AgentActivityAttributes.ContentState,
        isStale: Bool
    ) -> CGFloat {
        let showsApprovalButtons = state.phase == .awaitingApproval && !isStale
        let showsFailureButtons = state.phase == .failed && state.command?.isEmpty == false
        return showsApprovalButtons || showsFailureButtons ? 16 : 12
    }
}

private struct AgentDynamicIslandBrand: View {
    var body: some View {
        HStack(spacing: 6) {
            AgentBrandMark(compact: false)
            Text("DshMobile")
                .font(.system(size: 11, weight: .semibold))
                .lineLimit(1)
        }
        .foregroundStyle(AgentActivityColors.islandText)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// 展开态只保留完成当前决策所需的信息。品牌位于传感器左侧，
/// 会话与执行内容全部从传感器下方开始，避免上下两端被系统遮罩裁切。
private struct AgentDynamicIslandExpandedContent: View {
    let context: ActivityViewContext<AgentActivityAttributes>

    private var state: AgentActivityAttributes.ContentState { context.state }

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                Text(displaySessionTitle)
                    .font(.system(size: 15, weight: .semibold))
                    .tracking(-0.2)
                    .lineLimit(1)
                Spacer(minLength: 8)
                Link(destination: AgentActivityLink.session(context)) {
                    HStack(spacing: 3) {
                        Text("详情")
                        Image(systemName: "chevron.right")
                            .font(.system(size: 10, weight: .semibold))
                    }
                    .font(.system(size: 11))
                    .foregroundStyle(AgentActivityColors.islandText)
                }
            }

            if state.phase == .awaitingApproval && !context.isStale {
                approvalOperation
                AgentApprovalButtons(
                    context: context,
                    foreground: AgentActivityColors.islandText,
                    tile: AgentActivityColors.islandTile,
                    height: AgentActivityMetrics.islandActionHeight
                )
                .padding(.top, 5)
            } else if state.phase == .submittingApproval {
                resultRow(symbol: "hourglass", text: stepTitle)
                compactFooter
            } else if state.phase == .failed && state.command?.isEmpty == false {
                approvalOperation
                failureActions
                    .padding(.top, 5)
            } else {
                resultRow(
                    symbol: AgentActivityPresentation.stepSymbol(for: state),
                    text: stepTitle
                )
                compactFooter
            }
        }
        .foregroundStyle(AgentActivityColors.islandText)
        .frame(maxWidth: .infinity, alignment: .top)
        .fixedSize(horizontal: false, vertical: true)
    }

    private var approvalOperation: some View {
        Text(state.command?.isEmpty == false ? state.command! : (state.detail ?? "等待操作详情"))
            .font(.system(size: 12, design: .monospaced))
            .tracking(-0.3)
            .lineSpacing(2)
            .lineLimit(2)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 9)
            .padding(.vertical, 5)
            .background(
                AgentActivityColors.islandTile,
                in: RoundedRectangle(cornerRadius: 9, style: .continuous)
            )
            .padding(.top, 5)
    }

    private func resultRow(symbol: String, text: String) -> some View {
        HStack(alignment: islandStepDetail == nil ? .center : .top, spacing: 6) {
            Image(systemName: symbol)
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(AgentActivityColors.status(for: state.phase))
                .frame(width: 16, height: 16, alignment: .center)
                .padding(.top, islandStepDetail == nil ? 0 : 1)
            VStack(alignment: .leading, spacing: 3) {
                Text(text)
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(AgentActivityColors.islandText)
                    .lineLimit(1)
                if let islandStepDetail {
                    Text(islandStepDetail)
                        .font(.system(size: 11))
                        .lineSpacing(2)
                        .foregroundStyle(AgentActivityColors.islandDimmed)
                        .lineLimit(2)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.top, 5)
        .padding(.bottom, 5)
    }

    /// 运行中的具体步骤对判断实时进展有用；终态和过期状态保持精简，
    /// 避免重复结果信息占用展开岛高度。
    private var islandStepDetail: String? {
        guard state.phase == .running || state.phase == .awaitingChoice,
              !context.isStale,
              let detail = state.secondaryDetail?.trimmingCharacters(in: .whitespacesAndNewlines),
              !detail.isEmpty else {
            return nil
        }
        return detail
    }

    /// 简单状态使用底部信息行占满展开卡片的安全区域。它既补足运行、允许、
    /// 拒绝等状态下的下半部空白，也把所有内容限制在底部圆角开始收窄之前。
    private var compactFooter: some View {
        Text(state.sourceLabel?.isEmpty == false ? state.sourceLabel! : "DeepSeek Harness")
            .lineLimit(1)
            .frame(maxWidth: .infinity, alignment: .leading)
            .font(.system(size: 10))
            .foregroundStyle(AgentActivityColors.islandDimmed)
            .padding(.bottom, 2)
            .frame(height: 18, alignment: .bottom)
            .padding(.horizontal, 8)
            .overlay(alignment: .top) {
                Rectangle()
                    .fill(AgentActivityColors.islandDivider)
                    .frame(height: 0.5)
                    .padding(.top, 2)
            }
    }

    private var failureActions: some View {
        HStack(spacing: 7) {
            Link(destination: AgentActivityLink.session(context)) {
                Text("查看详情")
                    .frame(maxWidth: .infinity, minHeight: AgentActivityMetrics.islandActionHeight)
                    .background(AgentActivityColors.islandTile, in: Capsule())
            }
            Link(destination: AgentActivityLink.session(context)) {
                Label("检查结果", systemImage: "arrow.clockwise")
                    .frame(maxWidth: .infinity, minHeight: AgentActivityMetrics.islandActionHeight)
                    .foregroundStyle(Color(red: 0.09, green: 0.16, blue: 0.19))
                    .background(AgentActivityColors.accent, in: Capsule())
            }
        }
        .font(.system(size: 12, weight: .medium))
        .foregroundStyle(AgentActivityColors.islandText)
    }

    private var displaySessionTitle: String {
        let updatedTitle = state.sessionTitle?.trimmingCharacters(in: .whitespacesAndNewlines)
        return updatedTitle?.isEmpty == false ? updatedTitle! : context.attributes.sessionTitle
    }

    private var stepTitle: String {
        if context.isStale && !state.phase.isTerminal { return "打开 App 获取最新进度" }
        return state.detail?.isEmpty == false
            ? state.detail!
            : AgentActivityPresentation.stepTitle(for: state.phase)
    }
}

private enum AgentActivitySurface {
    case lockScreen
    case dynamicIsland
}

private struct AgentActivityCard: View {
    let context: ActivityViewContext<AgentActivityAttributes>
    let surface: AgentActivitySurface
    let showsHeader: Bool

    private var state: AgentActivityAttributes.ContentState { context.state }
    private var foreground: Color {
        surface == .dynamicIsland ? AgentActivityColors.islandText : AgentActivityColors.lockText
    }
    private var dimmed: Color {
        surface == .dynamicIsland ? AgentActivityColors.islandDimmed : AgentActivityColors.lockDimmed
    }
    private var tile: Color {
        surface == .dynamicIsland ? AgentActivityColors.islandTile : AgentActivityColors.lockTile
    }
    private var divider: Color {
        surface == .dynamicIsland ? AgentActivityColors.islandDivider : AgentActivityColors.lockDivider
    }

    var body: some View {
        VStack(spacing: 0) {
            if showsHeader {
                header.padding(.bottom, AgentActivityMetrics.headerToStatusSpacing)
            }
            statusRow
            if showsOperation { operation } else { progress }

            if state.phase == .awaitingApproval && !context.isStale {
                AgentApprovalButtons(context: context, foreground: foreground, tile: tile)
                    .padding(.top, AgentActivityMetrics.operationMargin)
            } else if state.phase == .submittingApproval {
                submitting.padding(.top, AgentActivityMetrics.operationMargin)
            } else if state.phase == .failed && state.command?.isEmpty == false {
                failureActions.padding(.top, AgentActivityMetrics.operationMargin)
            } else {
                footer
            }
        }
        .padding(.horizontal, surface == .dynamicIsland ? 0 : AgentActivityMetrics.cardHorizontalInset)
        .padding(.vertical, surface == .dynamicIsland ? 0 : AgentActivityMetrics.cardVerticalInset)
        .foregroundStyle(foreground)
    }

    private var showsOperation: Bool {
        switch state.phase {
        case .awaitingApproval, .submittingApproval:
            true
        case .failed:
            state.command?.isEmpty == false
        default:
            false
        }
    }

    private var header: some View {
        HStack(spacing: 8) {
            AgentBrandMark(compact: false)
            Text(displaySessionTitle)
                .font(.system(size: 14, weight: .medium))
                .tracking(-0.25)
                .lineLimit(1)
            Spacer(minLength: 8)
            Link(destination: AgentActivityLink.session(context)) {
                HStack(spacing: 3) {
                    Text("详情")
                    Image(systemName: "chevron.right")
                        .font(.system(size: 10, weight: .semibold))
                }
                .font(.system(size: 11))
                .foregroundStyle(foreground)
                .frame(minHeight: 26)
            }
        }
    }

    private var statusRow: some View {
        HStack(spacing: 4) {
            HStack(spacing: 6) {
                Image(systemName: statusSymbol)
                    .font(.system(size: 13, weight: .medium))
                    .frame(width: 14, height: 14)
                Text(statusText).lineLimit(1)
            }
            .font(.system(size: 12))
            .foregroundStyle(AgentActivityColors.status(for: state.phase))
            Spacer(minLength: 6)
            AgentElapsedTime(context: context)
                .font(.system(size: 12).monospacedDigit())
                .foregroundStyle(dimmed)
        }
    }

    private var operation: some View {
        Text(state.command?.isEmpty == false ? state.command! : "等待操作详情")
            .font(.system(size: 11, design: .monospaced))
            .tracking(-0.4)
            .lineLimit(2)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 9)
            .padding(.vertical, 6)
            .background(tile, in: RoundedRectangle(cornerRadius: 9, style: .continuous))
            .padding(.vertical, AgentActivityMetrics.operationMargin)
    }

    private var progress: some View {
        HStack(spacing: 8) {
            Image(systemName: stepSymbol)
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(AgentActivityColors.status(for: state.phase))
                .frame(width: 20, height: 20, alignment: .leading)
            VStack(alignment: .leading, spacing: 2) {
                Text(stepTitle)
                    .font(.system(size: 13))
                    .foregroundStyle(foreground)
                    .lineLimit(1)
                Text(stepDetail)
                    .font(.system(size: 11))
                    .foregroundStyle(dimmed)
                    .lineLimit(1)
            }
            Spacer(minLength: 0)
        }
        .padding(.top, surface == .dynamicIsland ? 9 : 12)
        .padding(.bottom, surface == .dynamicIsland ? 7 : 8)
    }

    private var footer: some View {
        HStack(spacing: 7) {
            Text(state.sourceLabel?.isEmpty == false ? state.sourceLabel! : "DeepSeek Harness")
                .lineLimit(1)
            Spacer(minLength: 12)
            Link(destination: AgentActivityLink.session(context)) {
                HStack(spacing: 3) {
                    Text("查看会话")
                    Image(systemName: "chevron.right")
                        .font(.system(size: 10, weight: .semibold))
                }
                .foregroundStyle(dimmed)
            }
        }
        .font(.system(size: 11))
        .foregroundStyle(dimmed)
        .padding(.top, 10)
        .padding(.horizontal, surface == .dynamicIsland ? 10 : 0)
        .overlay(alignment: .top) {
            Rectangle().fill(divider).frame(height: 0.5)
        }
    }

    private var submitting: some View {
        HStack(spacing: 7) {
            ProgressView().controlSize(.small).tint(dimmed)
            Text(state.detail?.isEmpty == false ? state.detail! : "正在等待主机确认")
                .lineLimit(1)
        }
        .font(.system(size: 12))
        .foregroundStyle(dimmed)
        .frame(maxWidth: .infinity, minHeight: AgentActivityMetrics.actionHeight)
        .background(tile, in: Capsule())
    }

    private var failureActions: some View {
        HStack(spacing: AgentActivityMetrics.actionSpacing) {
            Link(destination: AgentActivityLink.session(context)) {
                Text("查看详情")
                    .frame(maxWidth: .infinity, minHeight: AgentActivityMetrics.actionHeight)
                    .background(tile, in: Capsule())
            }
            Link(destination: AgentActivityLink.session(context)) {
                Label("检查结果", systemImage: "arrow.clockwise")
                    .frame(maxWidth: .infinity, minHeight: AgentActivityMetrics.actionHeight)
                    .foregroundStyle(Color(red: 0.09, green: 0.16, blue: 0.19))
                    .background(AgentActivityColors.accent, in: Capsule())
            }
        }
        .font(.system(size: 13, weight: .medium))
        .foregroundStyle(foreground)
    }

    private var statusText: String {
        context.isStale && !state.phase.isTerminal ? "等待状态同步" : state.status
    }
    private var statusSymbol: String {
        context.isStale && !state.phase.isTerminal
            ? "exclamationmark.circle"
            : AgentActivityPresentation.symbol(for: state.phase)
    }
    private var stepSymbol: String {
        AgentActivityPresentation.stepSymbol(for: state)
    }
    private var stepTitle: String {
        if context.isStale && !state.phase.isTerminal { return "打开 App 获取最新进度" }
        return state.detail?.isEmpty == false ? state.detail! : AgentActivityPresentation.stepTitle(for: state.phase)
    }
    private var stepDetail: String {
        state.secondaryDetail?.isEmpty == false
            ? state.secondaryDetail!
            : AgentActivityPresentation.stepDetail(for: state.phase)
    }

    private var displaySessionTitle: String {
        let updatedTitle = state.sessionTitle?.trimmingCharacters(in: .whitespacesAndNewlines)
        return updatedTitle?.isEmpty == false ? updatedTitle! : context.attributes.sessionTitle
    }
}

private struct AgentApprovalButtons: View {
    let context: ActivityViewContext<AgentActivityAttributes>
    let foreground: Color
    let tile: Color
    let height: CGFloat

    init(
        context: ActivityViewContext<AgentActivityAttributes>,
        foreground: Color,
        tile: Color,
        height: CGFloat = AgentActivityMetrics.actionHeight
    ) {
        self.context = context
        self.foreground = foreground
        self.tile = tile
        self.height = height
    }

    var body: some View {
        if let rpcID = context.state.rpcID, let approvalID = context.state.approvalID {
            HStack(spacing: AgentActivityMetrics.actionSpacing) {
                Button(intent: RejectAgentActionIntent(
                    gatewayID: context.attributes.gatewayID,
                    sessionID: context.attributes.sessionID,
                    rpcID: rpcID,
                    approvalID: approvalID
                )) {
                    Label("拒绝", systemImage: "xmark")
                        .frame(maxWidth: .infinity, minHeight: height)
                        .foregroundStyle(foreground)
                        .background(tile, in: Capsule())
                }
                .buttonStyle(.plain)
                Button(intent: AllowAgentActionIntent(
                    gatewayID: context.attributes.gatewayID,
                    sessionID: context.attributes.sessionID,
                    rpcID: rpcID,
                    approvalID: approvalID
                )) {
                    Label("允许一次", systemImage: "checkmark")
                        .frame(maxWidth: .infinity, minHeight: height)
                        .foregroundStyle(Color(red: 0.09, green: 0.16, blue: 0.19))
                        .background(AgentActivityColors.accent, in: Capsule())
                }
                .buttonStyle(.plain)
            }
            .font(.system(size: 13, weight: .medium))
        }
    }
}

private struct AgentCompactStatus: View {
    let context: ActivityViewContext<AgentActivityAttributes>

    var body: some View {
        if context.isStale && !context.state.phase.isTerminal {
            Image(systemName: "exclamationmark.circle")
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(AgentActivityColors.failed)
        } else if context.state.phase == .running {
            AgentElapsedTime(context: context)
                .font(.system(size: 12, weight: .medium).monospacedDigit())
                .frame(width: 38, alignment: .trailing)
                .foregroundStyle(AgentActivityColors.accent)
        } else {
            HStack(spacing: 4) {
                Image(systemName: AgentActivityPresentation.symbol(for: context.state.phase))
                    .font(.system(size: 12, weight: .medium))
                Text(AgentActivityPresentation.compactText(for: context.state.phase, status: context.state.status))
                    .font(.system(size: 11, weight: .medium))
                    .lineLimit(1)
            }
            .foregroundStyle(AgentActivityColors.status(for: context.state.phase))
        }
    }
}

private struct AgentElapsedTime: View {
    let context: ActivityViewContext<AgentActivityAttributes>

    var body: some View {
        if context.state.phase.isTerminal {
            Text(AgentActivityPresentation.duration(from: context.attributes.startedAt, to: context.state.updatedAt))
        } else {
            Text(context.attributes.startedAt, style: .timer)
                .multilineTextAlignment(.trailing)
        }
    }
}

private struct AgentBrandMark: View {
    let compact: Bool

    private var width: CGFloat { compact ? 20 : 21 }
    private var height: CGFloat { width * 0.743 }

    var body: some View {
        Image("DeepSeekWhale")
            .renderingMode(.template)
            .resizable()
            .scaledToFit()
            .foregroundStyle(AgentActivityColors.accent)
            .frame(width: width, height: height)
            .frame(width: width, height: 20, alignment: .center)
            .accessibilityHidden(true)
    }
}

private enum AgentActivityColors {
    static let accent = Color(red: 0.596, green: 0.851, blue: 0.925)
    static let pending = Color(red: 0.937, green: 0.761, blue: 0.506)
    static let approved = Color(red: 0.612, green: 0.871, blue: 0.757)
    static let rejected = Color(red: 0.867, green: 0.741, blue: 0.729)
    static let failed = Color(red: 1, green: 0.718, blue: 0.643)
    static let lockBackground = Color(red: 0.125, green: 0.208, blue: 0.255)
    static let lockText = Color(red: 0.929, green: 0.961, blue: 0.969)
    static let lockDimmed = Color(red: 0.702, green: 0.776, blue: 0.812)
    static let lockTile = Color(red: 0.082, green: 0.165, blue: 0.212)
    static let lockDivider = Color(red: 0.231, green: 0.318, blue: 0.365)
    static let islandText = Color(red: 0.965, green: 0.969, blue: 0.973)
    static let islandDimmed = Color(red: 0.655, green: 0.671, blue: 0.694)
    static let islandTile = Color(red: 0.125, green: 0.133, blue: 0.149)
    static let islandDivider = Color(red: 0.169, green: 0.180, blue: 0.200)
    static let brandTile = Color(red: 0.227, green: 0.380, blue: 0.447).opacity(0.35)

    static func status(for phase: AgentActivityPhase) -> Color {
        switch phase {
        case .running: accent
        case .awaitingChoice, .awaitingApproval, .submittingApproval: pending
        case .approved, .completed: approved
        case .rejected: rejected
        case .failed: failed
        }
    }
}

private enum AgentActivityPresentation {
    static func stepSymbol(for state: AgentActivityAttributes.ContentState) -> String {
        guard state.phase == .running else { return symbol(for: state.phase) }
        switch state.stepKind {
        case .preparing: return "sparkles"
        case .context: return "tray.and.arrow.down"
        case .reasoning: return "brain"
        case .writing: return "square.and.pencil"
        case .editing: return "pencil.and.outline"
        case .reading: return "doc.text.magnifyingglass"
        case .command: return "terminal"
        case .searching: return "magnifyingglass"
        case .tool: return "wrench.and.screwdriver"
        case .result: return "checkmark.circle"
        case .message: return "text.bubble"
        case .delivery: return "shippingbox"
        case nil: return "doc.text.magnifyingglass"
        }
    }

    static func symbol(for phase: AgentActivityPhase) -> String {
        switch phase {
        case .running: "waveform.path.ecg"
        case .awaitingChoice: "questionmark.bubble"
        case .awaitingApproval: "exclamationmark.shield"
        case .submittingApproval: "arrow.triangle.2.circlepath"
        case .approved: "checkmark"
        case .rejected: "xmark.shield"
        case .failed: "wifi.slash"
        case .completed: "checkmark.circle"
        }
    }

    static func compactText(for phase: AgentActivityPhase, status: String) -> String {
        switch phase {
        case .running: ""
        case .awaitingChoice: "待选择"
        case .awaitingApproval: "待审批"
        case .submittingApproval: "提交中"
        case .approved: "已允许"
        case .rejected: "已拒绝"
        case .failed: status == "执行失败" ? "失败" : "待确认"
        case .completed: "已完成"
        }
    }

    static func stepTitle(for phase: AgentActivityPhase) -> String {
        switch phase {
        case .running: "Agent 正在处理"
        case .awaitingChoice: "请打开 App 完成选择"
        case .approved: "批准已送达"
        case .rejected: "本次命令未执行"
        case .completed: "任务已完成"
        case .failed: "执行未完成"
        case .awaitingApproval, .submittingApproval: "等待操作"
        }
    }

    static func stepDetail(for phase: AgentActivityPhase) -> String {
        switch phase {
        case .running: "正在等待下一步"
        case .awaitingChoice: "Agent 正在等待你的回答"
        case .approved: "等待 Agent 更新下一步"
        case .rejected: "决定已送达，等待 Agent 后续状态"
        case .completed: "Agent 已完成本次任务"
        case .failed: "打开 App 查看详情"
        case .awaitingApproval, .submittingApproval: "仅授权本次操作"
        }
    }

    static func duration(from start: Date, to end: Date) -> String {
        let totalSeconds = max(0, Int(end.timeIntervalSince(start)))
        let hours = totalSeconds / 3_600
        let minutes = (totalSeconds % 3_600) / 60
        let seconds = totalSeconds % 60
        if hours > 0 { return String(format: "%d:%02d:%02d", hours, minutes, seconds) }
        return String(format: "%02d:%02d", minutes, seconds)
    }
}

private enum AgentActivityLink {
    static func session(_ context: ActivityViewContext<AgentActivityAttributes>) -> URL {
        var components = URLComponents()
        components.scheme = "dshmobile"
        components.host = "session"
        components.queryItems = [
            URLQueryItem(name: "gateway", value: context.attributes.gatewayID),
            URLQueryItem(name: "id", value: context.attributes.sessionID)
        ]
        return components.url ?? URL(string: "dshmobile://session")!
    }
}

#if DEBUG
private let previewAttributes = AgentActivityAttributes(
    gatewayID: "preview-gateway",
    sessionID: "preview-session",
    sessionTitle: "修复 Android 构建",
    startedAt: Date().addingTimeInterval(-168)
)

private func previewState(
    phase: AgentActivityPhase,
    status: String,
    detail: String,
    secondaryDetail: String? = nil,
    command: String? = nil,
    stepKind: AgentActivityStepKind? = nil
) -> AgentActivityAttributes.ContentState {
    AgentActivityAttributes.ContentState(
        sessionTitle: "修复 Android 构建",
        phase: phase,
        status: status,
        detail: detail,
        secondaryDetail: secondaryDetail,
        sourceLabel: "Mac Studio · dsh-mobile",
        command: command,
        rpcID: command == nil ? nil : "preview-rpc",
        approvalID: command == nil ? nil : "preview-approval",
        toolName: command == nil ? nil : "Bash",
        stepKind: stepKind,
        updatedAt: .now
    )
}

#Preview("运行中 · 紧凑", as: .dynamicIsland(.compact), using: previewAttributes) {
    AgentLiveActivityWidget()
} contentStates: {
    previewState(
        phase: .running,
        status: "正在执行",
        detail: "正在分析构建日志",
        stepKind: .reading
    )
}

#Preview("运行中 · 展开", as: .dynamicIsland(.expanded), using: previewAttributes) {
    AgentLiveActivityWidget()
} contentStates: {
    previewState(
        phase: .running,
        status: "正在执行",
        detail: "正在分析构建日志",
        stepKind: .reading
    )
}

#Preview("等待审批 · 展开", as: .dynamicIsland(.expanded), using: previewAttributes) {
    AgentLiveActivityWidget()
} contentStates: {
    previewState(
        phase: .awaitingApproval,
        status: "需要批准 · 仅限本次",
        detail: "运行构建命令",
        command: "./gradlew :androidApp:assembleDebug"
    )
}

#Preview("等待选择 · 展开", as: .dynamicIsland(.expanded), using: previewAttributes) {
    AgentLiveActivityWidget()
} contentStates: {
    previewState(
        phase: .awaitingChoice,
        status: "需要选择",
        detail: "请打开 App 完成选择",
        secondaryDetail: "请选择希望 Agent 接下来处理的方向"
    )
}

#Preview("等待选择 · 锁屏", as: .content, using: previewAttributes) {
    AgentLiveActivityWidget()
} contentStates: {
    previewState(
        phase: .awaitingChoice,
        status: "需要选择",
        detail: "请打开 App 完成选择",
        secondaryDetail: "请选择希望 Agent 接下来处理的方向"
    )
}

#Preview("已允许 · 展开", as: .dynamicIsland(.expanded), using: previewAttributes) {
    AgentLiveActivityWidget()
} contentStates: {
    previewState(phase: .approved, status: "已允许本次操作", detail: "批准已送达")
}

#Preview("已拒绝 · 展开", as: .dynamicIsland(.expanded), using: previewAttributes) {
    AgentLiveActivityWidget()
} contentStates: {
    previewState(phase: .rejected, status: "已拒绝本次操作", detail: "本次命令未执行")
}

#Preview("提交失败 · 展开", as: .dynamicIsland(.expanded), using: previewAttributes) {
    AgentLiveActivityWidget()
} contentStates: {
    previewState(
        phase: .failed,
        status: "未能确认审批结果",
        detail: "确认结果后才能继续操作",
        command: "./gradlew :androidApp:assembleDebug"
    )
}

#Preview("运行中 · 锁屏", as: .content, using: previewAttributes) {
    AgentLiveActivityWidget()
} contentStates: {
    previewState(
        phase: .running,
        status: "正在执行",
        detail: "正在分析构建日志",
        stepKind: .message
    )
}

#Preview("等待审批 · 锁屏", as: .content, using: previewAttributes) {
    AgentLiveActivityWidget()
} contentStates: {
    previewState(
        phase: .awaitingApproval,
        status: "需要批准 · 仅限本次",
        detail: "运行构建命令",
        command: "./gradlew :androidApp:assembleDebug"
    )
}
#endif
