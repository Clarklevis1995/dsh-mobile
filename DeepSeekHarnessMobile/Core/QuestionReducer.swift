import Foundation

struct QuestionState: Equatable {
    var pendingRequests: [GatewayPendingQuestionRequest] = []
    var requestStatuses: [String: GatewayQuestionRequestStatus] = [:]
}

enum QuestionSubmission {
    case answer([GatewayQuestionAnswer])
    case cancel
}

enum QuestionAction {
    case reset
    case requestReceived(GatewayPendingQuestionRequest)
    case submit(
        request: GatewayPendingQuestionRequest,
        submission: QuestionSubmission,
        isConnected: Bool
    )
    case responseReceived(
        rpcID: String,
        action: GatewayQuestionAction,
        accepted: Bool,
        reason: String?
    )
    case resolved(rpcID: String)
    case requestFailed(rpcID: String, message: String)
}

enum QuestionReducer {
    static func reduce(state: inout QuestionState, action: QuestionAction) {
        switch action {
        case .reset:
            state.pendingRequests.removeAll()
            state.requestStatuses.removeAll()

        case .requestReceived(let request):
            if let index = state.pendingRequests.firstIndex(where: { $0.rpcId == request.rpcId }) {
                // A reconnect replay refreshes the payload without resetting a
                // submission result that is already visible to the user.
                state.pendingRequests[index] = request
            } else {
                state.pendingRequests.append(request)
                state.requestStatuses[request.rpcId] = .idle
            }

        case .submit(let request, let submission, let isConnected):
            switch submission {
            case .answer(let answers):
                if !isConnected {
                    state.requestStatuses[request.rpcId] = .rejected(String(localized: "q.rejected.ws.disconnected.answer", defaultValue: "WebSocket 已断开，重连后再提交答案。"))
                } else if let validationError = validate(request: request, answers: answers) {
                    state.requestStatuses[request.rpcId] = .rejected(validationError)
                } else {
                    state.requestStatuses[request.rpcId] = .submitting(.answer)
                }
            case .cancel:
                state.requestStatuses[request.rpcId] = isConnected
                    ? .submitting(.cancel)
                    : .rejected(String(localized: "q.rejected.ws.disconnected.skip", defaultValue: "WebSocket 已断开，重连后再跳过问题。"))
            }

        case .responseReceived(let rpcID, let action, let accepted, let reason):
            if accepted {
                state.requestStatuses[rpcID] = .accepted(action)
            } else if reason == "not-pending" {
                state.pendingRequests.removeAll { $0.rpcId == rpcID }
                state.requestStatuses[rpcID] = nil
            } else {
                state.requestStatuses[rpcID] = .rejected(String(
                    localized: "q.rejected.server-refused",
                    defaultValue: "服务端未接受答案（\(reason ?? "bad-response")），请检查后重试。"
                ))
            }

        case .resolved(let rpcID):
            state.pendingRequests.removeAll { $0.rpcId == rpcID }
            state.requestStatuses[rpcID] = nil

        case .requestFailed(let rpcID, let message):
            state.requestStatuses[rpcID] = .rejected(
                message.isEmpty ? String(localized: "服务端拒绝了问题响应。") : message
            )
        }
    }

    private static func validate(
        request: GatewayPendingQuestionRequest,
        answers: [GatewayQuestionAnswer]
    ) -> String? {
        guard request.questions.map(\.id) == answers.map(\.id) else {
            return String(localized: "答案必须按原顺序覆盖整组问题。")
        }
        for (question, answer) in zip(request.questions, answers) {
            let allowedLabels = Set((question.options ?? []).map(\.label))
            guard Set(answer.selected).count == answer.selected.count,
                  answer.selected.allSatisfy(allowedLabels.contains) else {
                return String(localized: "q.rejected.bad-options", defaultValue: "“\(question.question)”包含无效或重复选项。")
            }
            if !question.allowsMultipleSelections,
               answer.selected.count > 1 || (answer.selected.count == 1 && answer.custom != nil) {
                return String(localized: "单选题只能选择一个选项，且不能同时填写自定义答案。")
            }
        }
        return nil
    }
}
