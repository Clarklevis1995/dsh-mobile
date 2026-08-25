import SwiftUI
import MarkdownUI

private struct QuestionDraft: Equatable {
    var selected: [String] = []
    var custom = ""

    var isAnswered: Bool {
        !selected.isEmpty || !custom.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }
}

struct HumanQuestionView: View {
    let request: GatewayPendingQuestionRequest
    let status: GatewayQuestionRequestStatus
    let onAnswer: ([GatewayQuestionAnswer]) -> Void
    let onCancel: () -> Void

    @Environment(\.colorScheme) private var colorScheme
    @State private var drafts: [String: QuestionDraft]
    @State private var currentIndex = 0
    @State private var isCollapsed = false
    @State private var showsCancelConfirmation = false
    @State private var validationMessage: String?

    init(
        request: GatewayPendingQuestionRequest,
        status: GatewayQuestionRequestStatus,
        onAnswer: @escaping ([GatewayQuestionAnswer]) -> Void,
        onCancel: @escaping () -> Void
    ) {
        self.request = request
        self.status = status
        self.onAnswer = onAnswer
        self.onCancel = onCancel
        _drafts = State(initialValue: Dictionary(
            uniqueKeysWithValues: request.questions.map { ($0.id, QuestionDraft()) }
        ))
    }

    var body: some View {
        Group {
            if isCollapsed { collapsedCard }
            else if let plan = planReview { planReviewCard(plan) }
            else { questionCard }
        }
        .glassSurface(radius: 24, tint: cardTint)
        .overlay {
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .stroke(colorScheme == .dark ? .white.opacity(0.14) : .white.opacity(0.72), lineWidth: 0.8)
        }
        .shadow(color: .black.opacity(colorScheme == .dark ? 0.30 : 0.12), radius: 20, y: 9)
        .padding(.horizontal, 14)
        .confirmationDialog(String(localized: "放弃这组问题？"), isPresented: $showsCancelConfirmation, titleVisibility: .visible) {
            Button(String(localized: "跳过并让 Agent 继续"), role: .destructive, action: onCancel)
            Button(String(localized: "继续回答"), role: .cancel) {}
        } message: {
            Text(String(localized: "当前填写的答案不会提交。"))
        }
        .accessibilityElement(children: .contain)
    }

    private var collapsedCard: some View {
        Button {
            withAnimation(.snappy(duration: 0.24)) { isCollapsed = false }
        } label: {
            HStack(spacing: 12) {
                Image(systemName: "questionmark.bubble.fill")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(DSHColor.ocean)
                VStack(alignment: .leading, spacing: 2) {
                    Text(String(localized: "Agent 正在等待回答"))
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.primary)
                    Text(request.questions[currentIndex].question)
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
    }

    private var questionCard: some View {
        VStack(spacing: 0) {
            header
            Divider().opacity(0.55)

            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    questionHeading(request.questions[currentIndex])
                    answerControls(for: request.questions[currentIndex])
                    statusMessage
                }
                .padding(.horizontal, 17)
                .padding(.vertical, 16)
            }
            .scrollBounceBehavior(.basedOnSize)
            .frame(maxHeight: 390)

            Divider().opacity(0.55)
            footer
        }
    }

    private var header: some View {
        HStack(spacing: 11) {
            Image(systemName: "questionmark.bubble")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(DSHColor.ocean)
            VStack(alignment: .leading, spacing: 1) {
                Text(String(localized: "需要你的回答"))
                    .font(.subheadline.weight(.semibold))
                if request.replay {
                    Text(String(localized: "已从断线前恢复"))
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
            Spacer()
            if status.isBusy { ProgressView().controlSize(.small) }
            Button {
                withAnimation(.snappy(duration: 0.24)) { isCollapsed = true }
            } label: {
                Image(systemName: "chevron.down")
                    .frame(width: 30, height: 30)
            }
            .accessibilityLabel(String(localized: "收起问题卡片"))
            Button { showsCancelConfirmation = true } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 14, weight: .semibold))
                    .frame(width: 30, height: 30)
            }
            .accessibilityLabel(String(localized: "放弃整组问题"))
        }
        .foregroundStyle(.secondary)
        .padding(.leading, 17)
        .padding(.trailing, 9)
        .padding(.vertical, 10)
    }

    @ViewBuilder
    private func questionHeading(_ question: GatewayQuestion) -> some View {
        VStack(alignment: .leading, spacing: 7) {
            if let header = question.header, !header.isEmpty {
                Text(header)
                    .font(.caption.weight(.medium))
                    .foregroundStyle(.secondary)
            }
            Text(question.question)
                .font(.title3.weight(.semibold))
                .fixedSize(horizontal: false, vertical: true)
            if let detail = question.detail, !detail.isEmpty {
                Markdown(detail)
                    .markdownTextStyle { ForegroundColor(.secondary) }
                    .font(.subheadline)
            }
            if question.allowsMultipleSelections {
                Label(String(localized: "可以选择多项"), systemImage: "checklist")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }

    @ViewBuilder
    private func answerControls(for question: GatewayQuestion) -> some View {
        let options = question.options ?? []
        VStack(alignment: .leading, spacing: 10) {
            ForEach(options) { option in
                optionRow(option, question: question)
            }

            if options.isEmpty {
                TextEditor(text: customBinding(for: question))
                    .font(.body)
                    .scrollContentBackground(.hidden)
                    .frame(minHeight: 92, maxHeight: 138)
                    .padding(10)
                    .background(Color.primary.opacity(0.055), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                    .overlay(alignment: .topLeading) {
                        if draft(for: question).custom.isEmpty {
                            Text(String(localized: "输入你的答案"))
                                .foregroundStyle(.tertiary)
                                .padding(.horizontal, 15)
                                .padding(.vertical, 18)
                                .allowsHitTesting(false)
                        }
                    }
            } else {
                HStack(spacing: 9) {
                    Image(systemName: "pencil.line")
                        .foregroundStyle(.secondary)
                    TextField(String(localized: "或输入自定义答案"), text: customBinding(for: question), axis: .vertical)
                        .lineLimit(1...3)
                        .textFieldStyle(.plain)
                }
                .padding(.horizontal, 13)
                .padding(.vertical, 11)
                .background(Color.primary.opacity(0.05), in: RoundedRectangle(cornerRadius: 13, style: .continuous))
            }
        }
        .disabled(status.isBusy)
    }

    private func optionRow(_ option: GatewayQuestionOption, question: GatewayQuestion) -> some View {
        let isSelected = draft(for: question).selected.contains(option.label)
        let metadata = optionMetadata(option.label)
        return Button {
            select(option.label, for: question)
        } label: {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: isSelected
                      ? (question.allowsMultipleSelections ? "checkmark.square.fill" : "largecircle.fill.circle")
                      : (question.allowsMultipleSelections ? "square" : "circle"))
                    .font(.system(size: 19, weight: .medium))
                    .foregroundStyle(isSelected ? DSHColor.ocean : Color.secondary.opacity(0.55))
                    .padding(.top, 1)
                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 7) {
                        Text(metadata.title)
                            .font(.body.weight(.semibold))
                            .foregroundStyle(.primary)
                            .fixedSize(horizontal: false, vertical: true)
                        if metadata.isRecommended {
                            Text(String(localized: "推荐"))
                                .font(.caption2.weight(.semibold))
                                .foregroundStyle(DSHColor.ocean)
                                .padding(.horizontal, 6)
                                .padding(.vertical, 3)
                                .background(DSHColor.ocean.opacity(0.12), in: Capsule())
                        }
                    }
                    if let description = option.description, !description.isEmpty {
                        Text(description)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(12)
            .background(
                isSelected ? DSHColor.ocean.opacity(0.09) : Color.primary.opacity(0.035),
                in: RoundedRectangle(cornerRadius: 14, style: .continuous)
            )
            .overlay {
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .stroke(isSelected ? DSHColor.ocean.opacity(0.48) : Color.primary.opacity(0.07), lineWidth: 1)
            }
        }
        .buttonStyle(.plain)
    }

    private var footer: some View {
        VStack(spacing: 9) {
            if let validationMessage {
                Text(validationMessage)
                    .font(.caption)
                    .foregroundStyle(.red)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            HStack(spacing: 10) {
                Button { move(to: currentIndex - 1) } label: {
                    Image(systemName: "chevron.left")
                        .frame(width: 34, height: 34)
                }
                .disabled(currentIndex == 0 || status.isBusy)
                Text("\(currentIndex + 1) / \(request.questions.count)")
                    .font(.subheadline.monospacedDigit())
                    .foregroundStyle(.secondary)
                Button { move(to: currentIndex + 1) } label: {
                    Image(systemName: "chevron.right")
                        .frame(width: 34, height: 34)
                }
                .disabled(currentIndex == request.questions.count - 1 || status.isBusy)

                Spacer(minLength: 4)

                Button(String(localized: "跳过问题")) { showsCancelConfirmation = true }
                    .buttonStyle(.bordered)
                    .disabled(status.isBusy)
                Button(currentIndex == request.questions.count - 1 ? String(localized: "提交") : String(localized: "下一题")) {
                    continueFlow()
                }
                .buttonStyle(.borderedProminent)
                .tint(DSHColor.ocean)
                .foregroundStyle(.white)
                .disabled(status.isBusy)
            }
        }
        .padding(.horizontal, 13)
        .padding(.vertical, 11)
    }

    @ViewBuilder
    private var statusMessage: some View {
        switch status {
        case .idle:
            EmptyView()
        case .submitting(let action):
            Label(action == .answer ? String(localized: "questions.submitting.answers", defaultValue: "正在提交整组答案…") : String(localized: "questions.skipping", defaultValue: "正在跳过问题…"), systemImage: "arrow.triangle.2.circlepath")
                .font(.caption).foregroundStyle(.secondary)
        case .accepted:
            Label(String(localized: "服务端已接收，正在恢复 Agent…"), systemImage: "checkmark.circle.fill")
                .font(.caption).foregroundStyle(.green)
        case .rejected(let message):
            Label(message, systemImage: "exclamationmark.triangle.fill")
                .font(.caption).foregroundStyle(.red)
        }
    }

    private func planReviewCard(_ plan: PlanReview) -> some View {
        VStack(spacing: 0) {
            header
            Divider().opacity(0.55)
            ScrollView {
                VStack(alignment: .leading, spacing: 15) {
                    Label(String(localized: "计划待审"), systemImage: "doc.text.magnifyingglass")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.orange)
                    Text(plan.question.question)
                        .font(.title3.weight(.semibold))
                    Markdown(plan.question.detail ?? "")
                    statusMessage
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(17)
            }
            .frame(maxHeight: 390)
            Divider().opacity(0.55)
            HStack(spacing: 10) {
                Button(String(localized: "去聊天里说")) { showsCancelConfirmation = true }
                    .buttonStyle(.bordered)
                Spacer()
                if let reject = plan.rejectLabel {
                    Button(String(localized: "拒绝")) { submitPlan(label: reject, question: plan.question) }
                        .buttonStyle(.bordered)
                        .tint(.red)
                }
                Button(String(localized: "确认执行")) { submitPlan(label: plan.approveLabel, question: plan.question) }
                    .buttonStyle(.borderedProminent)
                    .tint(DSHColor.ocean)
                    .foregroundStyle(.white)
            }
            .disabled(status.isBusy)
            .padding(13)
        }
    }

    private var cardTint: Color {
        Color(uiColor: .secondarySystemBackground).opacity(colorScheme == .dark ? 0.78 : 0.82)
    }

    private func draft(for question: GatewayQuestion) -> QuestionDraft {
        drafts[question.id] ?? QuestionDraft()
    }

    private func customBinding(for question: GatewayQuestion) -> Binding<String> {
        Binding(
            get: { draft(for: question).custom },
            set: { value in
                var updated = draft(for: question)
                updated.custom = value
                if !question.allowsMultipleSelections && !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    updated.selected.removeAll()
                }
                drafts[question.id] = updated
                validationMessage = nil
            }
        )
    }

    private func select(_ label: String, for question: GatewayQuestion) {
        var updated = draft(for: question)
        if question.allowsMultipleSelections {
            if let index = updated.selected.firstIndex(of: label) { updated.selected.remove(at: index) }
            else { updated.selected.append(label) }
        } else {
            updated.selected = updated.selected == [label] ? [] : [label]
            if !updated.selected.isEmpty { updated.custom = "" }
        }
        drafts[question.id] = updated
        validationMessage = nil

        if !question.allowsMultipleSelections,
           !updated.selected.isEmpty,
           currentIndex < request.questions.count - 1 {
            Task { @MainActor in
                try? await Task.sleep(for: .milliseconds(180))
                guard draft(for: question).selected == updated.selected else { return }
                move(to: currentIndex + 1)
            }
        }
    }

    private func continueFlow() {
        let question = request.questions[currentIndex]
        guard draft(for: question).isAnswered else {
            validationMessage = String(localized: "questions.validation.pick-one", defaultValue: "请选择一个选项或填写自定义答案。")
            return
        }
        guard currentIndex == request.questions.count - 1 else {
            move(to: currentIndex + 1)
            return
        }
        if let firstMissing = request.questions.firstIndex(where: { !draft(for: $0).isAnswered }) {
            validationMessage = String(localized: "questions.validation.finish-first", defaultValue: "请先完成这道问题。")
            move(to: firstMissing, clearsValidation: false)
            return
        }
        onAnswer(makeAnswers())
    }

    private func move(to index: Int, clearsValidation: Bool = true) {
        guard request.questions.indices.contains(index) else { return }
        if clearsValidation { validationMessage = nil }
        withAnimation(.snappy(duration: 0.22)) { currentIndex = index }
    }

    private func makeAnswers() -> [GatewayQuestionAnswer] {
        request.questions.map { question in
            let value = draft(for: question)
            return GatewayQuestionAnswer(id: question.id, selected: value.selected, custom: value.custom)
        }
    }

    private func optionMetadata(_ label: String) -> (title: String, isRecommended: Bool) {
        let suffixes = [" (recommended)", "（recommended）", " (推荐)", "（推荐）"]
        if let suffix = suffixes.first(where: { label.lowercased().hasSuffix($0.lowercased()) }) {
            return (String(label.dropLast(suffix.count)), true)
        }
        return (label, false)
    }

    private struct PlanReview {
        let question: GatewayQuestion
        let approveLabel: String
        let rejectLabel: String?
    }

    private var planReview: PlanReview? {
        guard request.questions.count == 1,
              let question = request.questions.first,
              question.intent?.kind == "plan-review",
              !question.allowsMultipleSelections,
              question.detail?.isEmpty == false,
              let approve = question.intent?.approve,
              let options = question.options,
              options.count <= 2,
              options.contains(where: { $0.label == approve }) else { return nil }
        return PlanReview(
            question: question,
            approveLabel: approve,
            rejectLabel: options.first(where: { $0.label != approve })?.label
        )
    }

    private func submitPlan(label: String, question: GatewayQuestion) {
        onAnswer([GatewayQuestionAnswer(id: question.id, selected: [label])])
    }
}
