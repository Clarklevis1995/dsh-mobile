import SwiftUI

struct ApprovalRequestView: View {
    let request: GatewayPendingApprovalRequest
    let status: GatewayApprovalRequestStatus
    let commandPreview: String?
    let details: JSONValue?
    let onDecision: (GatewayApprovalOutcome) -> Void

    @Environment(\.colorScheme) private var colorScheme
    @State private var isCollapsed = false
    @State private var showsDetails = false

    private let amber = Color(red: 0.96, green: 0.61, blue: 0.05)
    private let cardShape = RoundedRectangle(cornerRadius: 24, style: .continuous)

    var body: some View {
        Group {
            if isCollapsed { collapsedCard }
            else { expandedCard }
        }
        .background(Color(uiColor: .systemBackground))
        .clipShape(cardShape)
        .overlay { cardShape.stroke(amber.opacity(0.78), lineWidth: 1) }
        .shadow(
            color: .black.opacity(colorScheme == .dark ? 0.18 : 0.055),
            radius: 10,
            y: 4
        )
        .padding(.horizontal, 14)
        .accessibilityElement(children: .contain)
        .onAppear {
            gatewayApprovalTrace(
                "ui card appeared replay=\(request.replay) status=\(status.debugName) " +
                "hasCommand=\(commandPreview?.isEmpty == false) hasDetails=\(details != nil)"
            )
        }
        .onChange(of: isCollapsed) { _, collapsed in
            gatewayApprovalTrace("ui card collapsed=\(collapsed) rpcId=\(request.rpcId)")
        }
    }

    private var collapsedCard: some View {
        Button {
            withAnimation(.snappy(duration: 0.24)) { isCollapsed = false }
        } label: {
            HStack(spacing: 12) {
                Circle().fill(amber).frame(width: 9, height: 9)
                VStack(alignment: .leading, spacing: 2) {
                    Text(String(localized: "Agent 正在等待审批"))
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.primary)
                    Text(requestReason)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                Spacer(minLength: 8)
                Image(systemName: "chevron.up")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(.secondary)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 13)
        }
        .buttonStyle(.plain)
        .disabled(status.isBusy)
        .accessibilityLabel(String(localized: "展开审批卡片"))
    }

    private var expandedCard: some View {
        VStack(spacing: 0) {
            header

            VStack(alignment: .leading, spacing: 12) {
                Text(requestReason)
                    .font(.body.weight(.medium))
                    .fixedSize(horizontal: false, vertical: true)

                if let commandPreview, !commandPreview.isEmpty {
                    Text(commandPreview)
                        .font(.system(.subheadline, design: .monospaced))
                        .foregroundStyle(.secondary)
                        .textSelection(.enabled)
                        .fixedSize(horizontal: false, vertical: true)
                }

                if let details {
                    detailsDisclosure(details)
                }

                if case .failed(let message) = status {
                    Label(message, systemImage: "exclamationmark.triangle.fill")
                        .font(.caption)
                        .foregroundStyle(.red)
                }

                actionButtons
            }
            .padding(18)
        }
    }

    private var header: some View {
        HStack(spacing: 10) {
            Circle().fill(amber).frame(width: 9, height: 9)
            Text(String(localized: "等待审批"))
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(amber)
            if request.replay {
                Text("· \(String(localized: "已恢复"))")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            if status.isBusy { ProgressView().controlSize(.small) }
            Button {
                withAnimation(.snappy(duration: 0.24)) { isCollapsed = true }
            } label: {
                Image(systemName: "chevron.down")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(.secondary)
                    .frame(width: 30, height: 30)
            }
            .buttonStyle(.plain)
            .disabled(status.isBusy)
            .accessibilityLabel(String(localized: "收起审批卡片"))
        }
        .padding(.leading, 18)
        .padding(.trailing, 9)
        .padding(.vertical, 9)
        // 外层统一裁剪顶部圆角；标题栏自身保持矩形，因此下沿不会出现圆角。
        .background(amber.opacity(colorScheme == .dark ? 0.12 : 0.08))
    }

    private var actionButtons: some View {
        HStack(spacing: 10) {
            Spacer()
            Button(String(localized: "拒绝")) { onDecision(.rejected) }
                .buttonStyle(.bordered)
            Button(String(localized: "允许一次")) { onDecision(.allowedOnce) }
                .buttonStyle(.borderedProminent)
                .tint(colorScheme == .dark ? .white : .black)
                .foregroundStyle(colorScheme == .dark ? .black : .white)
        }
        .disabled(status.isBusy)
    }

    private func detailsDisclosure(_ value: JSONValue) -> some View {
        DisclosureGroup(isExpanded: $showsDetails) {
            ScrollView {
                ApprovalDetailsView(value: value.normalizedValue)
                    .padding(.top, 8)
            }
            .scrollBounceBehavior(.basedOnSize)
            .frame(maxHeight: 190)
        } label: {
            Label(String(localized: "审批详情"), systemImage: "curlybraces")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(Color.primary.opacity(0.04), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .onChange(of: showsDetails) { _, expanded in
            gatewayApprovalTrace("ui details expanded=\(expanded) rpcId=\(request.rpcId)")
        }
    }

    private var requestReason: String {
        request.reason?.isEmpty == false
            ? request.reason!
            : String(localized: "\(request.toolName) 请求执行需要审批的操作")
    }
}

private struct ApprovalDetailsView: View {
    let value: JSONValue

    var body: some View {
        if let object = value.objectValue {
            VStack(alignment: .leading, spacing: 9) {
                ForEach(object.keys.sorted(), id: \.self) { key in
                    VStack(alignment: .leading, spacing: 4) {
                        Text(key)
                            .font(.caption2.weight(.semibold))
                            .foregroundStyle(.secondary)
                        Text(detailText(object[key] ?? .null))
                            .font(.system(.caption, design: .monospaced))
                            .foregroundStyle(.primary)
                            .textSelection(.enabled)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    if key != object.keys.sorted().last {
                        Divider().opacity(0.45)
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        } else {
            Text(value.jsonDisplayText)
                .font(.system(.caption, design: .monospaced))
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func detailText(_ value: JSONValue) -> String {
        switch value {
        case .string(let text): text
        case .number, .bool, .object, .array, .null: value.jsonDisplayText
        }
    }
}

private extension GatewayApprovalRequestStatus {
    var debugName: String {
        switch self {
        case .idle: "idle"
        case .submitting: "submitting"
        case .accepted: "accepted"
        case .failed: "failed"
        }
    }
}
