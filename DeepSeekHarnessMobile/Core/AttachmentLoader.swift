import Foundation

struct AttachmentLoadRequest: Equatable, Sendable {
    var sessionID: String
    var attachment: GatewayImageAttachment
}

struct AttachmentLoader: Equatable {
    private(set) var queuedAttachmentIDs: Set<String> = []
    private(set) var inFlightAttachmentIDs: Set<String> = []
    private var queue: [AttachmentLoadRequest] = []
    let maximumConcurrentRequests: Int

    init(maximumConcurrentRequests: Int = 3) {
        self.maximumConcurrentRequests = maximumConcurrentRequests
    }

    mutating func reset() {
        queuedAttachmentIDs.removeAll()
        inFlightAttachmentIDs.removeAll()
        queue.removeAll()
    }

    mutating func enqueue(
        _ attachments: [GatewayImageAttachment],
        sessionID: String,
        isCached: (String) -> Bool
    ) -> [AttachmentLoadRequest] {
        for attachment in attachments {
            guard !isCached(attachment.id),
                  !queuedAttachmentIDs.contains(attachment.id),
                  !inFlightAttachmentIDs.contains(attachment.id) else { continue }
            queuedAttachmentIDs.insert(attachment.id)
            queue.append(AttachmentLoadRequest(sessionID: sessionID, attachment: attachment))
        }
        return drainReadyRequests(isCached: isCached)
    }

    mutating func complete(
        attachmentID: String,
        isCached: (String) -> Bool
    ) -> [AttachmentLoadRequest] {
        inFlightAttachmentIDs.remove(attachmentID)
        return drainReadyRequests(isCached: isCached)
    }

    private mutating func drainReadyRequests(
        isCached: (String) -> Bool
    ) -> [AttachmentLoadRequest] {
        var ready: [AttachmentLoadRequest] = []
        while inFlightAttachmentIDs.count < maximumConcurrentRequests, !queue.isEmpty {
            let request = queue.removeFirst()
            queuedAttachmentIDs.remove(request.attachment.id)
            guard !isCached(request.attachment.id) else { continue }
            inFlightAttachmentIDs.insert(request.attachment.id)
            ready.append(request)
        }
        return ready
    }
}
