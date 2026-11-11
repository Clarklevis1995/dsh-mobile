import SwiftUI
import Intents
import UIKit

@MainActor
final class IncomingShareCoordinator: ObservableObject {
    static let shared = IncomingShareCoordinator()
    @Published var pending: SharedIncomingDraft?
    func refresh(preferredID: UUID? = nil) {
        let drafts = ShareInbox.pending()
        if let preferredID, let requested = drafts.first(where: { $0.id == preferredID }) {
            pending = requested
        } else if pending == nil { pending = drafts.first }
    }
    func resolveNewConversation(_ draft: SharedIncomingDraft, to target: SharedConversation) throws {
        guard pending?.id == draft.id else { return }
        pending = try ShareInbox.resolve(draft, to: target)
    }
    func complete(_ draft: SharedIncomingDraft) {
        guard pending?.id == draft.id else { return }
        ShareInbox.remove(draft)
        pending = nil
        refresh()
    }
}

/// 只在网关确认消息发送后更新建议，不把打开会话伪装成一次发消息。
@MainActor
final class ConversationShareSuggestions {
    static let shared = ConversationShareSuggestions()
    private var donationTask: Task<Void, Never>?
    private let restorationKey = "sharing.message-intent-registration-v1"
    private let defaults: UserDefaults
    private let donateInteraction: (INInteraction) async -> Bool
    private var restorationScheduled = false

    init(defaults: UserDefaults = .standard,
         donateInteraction: ((INInteraction) async -> Bool)? = nil) {
        self.defaults = defaults
        self.donateInteraction = donateInteraction ?? { await Self.donateToSystem($0) }
    }

    func record(_ conversation: SharedConversation) {
        let old = ShareInbox.recentConversations()
        let recent = ShareInbox.recordConversation(conversation)
        let retainedIDs = Set(recent.map { $0.conversation.suggestionIdentifier })
        let expiredIDs = old.map { $0.conversation.suggestionIdentifier }.filter { !retainedIDs.contains($0) }
        let previous = donationTask
        donationTask = Task {
            await previous?.value
            // 只清理被挤出的会话，不重置其余会话已积累的系统建议权重。
            if !expiredIDs.isEmpty {
                await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                    INInteraction.delete(with: expiredIDs) { _ in continuation.resume() }
                }
            }
            for item in recent where item.conversation.id == conversation.id {
                _ = await donateInteraction(Self.makeInteraction(for: item))
            }
        }
    }

    /// 补交旧版因缺少 Intent 声明而失败的真实消息记录，保留原始时间，不要求用户再发消息。
    func restoreRecentConversationsIfNeeded() {
        guard !restorationScheduled, !defaults.bool(forKey: restorationKey) else { return }
        let recent = ShareInbox.recentConversations()
        guard !recent.isEmpty else { return }
        restorationScheduled = true
        let previous = donationTask
        donationTask = Task {
            await previous?.value
            var succeeded = true
            for item in recent {
                let accepted = await donateInteraction(Self.makeInteraction(for: item))
                succeeded = succeeded && accepted
            }
            if succeeded {
                defaults.set(true, forKey: restorationKey)
                LocalAppLog.shared.write("INFO Share suggestions restoration succeeded count=\(recent.count)")
            } else {
                restorationScheduled = false
            }
        }
    }

    static func makeInteraction(for item: SharedRecentConversation) -> INInteraction {
        let target = item.conversation
        let avatar = avatar(title: target.title)
        let recipient = INPerson(personHandle: INPersonHandle(value: target.suggestionIdentifier, type: .unknown),
            nameComponents: nil, displayName: target.title, image: avatar,
            contactIdentifier: nil, customIdentifier: target.suggestionIdentifier)
        let intent = INSendMessageIntent(recipients: [recipient], outgoingMessageType: .outgoingMessageText,
            content: nil, speakableGroupName: INSpeakableString(spokenPhrase: target.title),
            conversationIdentifier: target.suggestionIdentifier, serviceName: "Whaleness", sender: nil, attachments: nil)
        intent.setImage(avatar, forParameterNamed: \.speakableGroupName)
        let interaction = INInteraction(intent: intent, response: nil)
        interaction.identifier = target.suggestionIdentifier
        interaction.groupIdentifier = "whaleness.recent-conversations"
        interaction.direction = .outgoing
        interaction.dateInterval = DateInterval(start: item.interactedAt, duration: 1)
        return interaction
    }

    private static func donateToSystem(_ interaction: INInteraction) async -> Bool {
        await withCheckedContinuation { continuation in
            interaction.donate { error in
                if let error {
                    let failure = error as NSError
                    LocalAppLog.shared.write("WARN Share suggestion donation failed domain=\(failure.domain) code=\(failure.code): \(failure.localizedDescription)")
                } else {
                    LocalAppLog.shared.write("INFO Share suggestion donation succeeded")
                }
                continuation.resume(returning: error == nil)
            }
        }
    }

    #if DEBUG
    func awaitPendingDonationsForTesting() async {
        await donationTask?.value
    }
    #endif

    private static func avatar(title: String) -> INImage? {
        let size = CGSize(width: 360, height: 360)
        let image = UIGraphicsImageRenderer(size: size).image { context in
            UIColor.systemBlue.setFill()
            context.fill(CGRect(origin: .zero, size: size))
            let text = String(title.prefix(1)).uppercased() as NSString
            let attributes: [NSAttributedString.Key: Any] = [
                .font: UIFont.systemFont(ofSize: 160, weight: .semibold), .foregroundColor: UIColor.white
            ]
            let dimensions = text.size(withAttributes: attributes)
            text.draw(at: CGPoint(x: (size.width - dimensions.width) / 2, y: (size.height - dimensions.height) / 2),
                withAttributes: attributes)
        }
        return image.pngData().map(INImage.init(imageData:))
    }
}
