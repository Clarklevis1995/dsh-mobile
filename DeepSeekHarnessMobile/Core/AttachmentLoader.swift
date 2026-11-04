import Foundation
import Photos
import UniformTypeIdentifiers

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


enum PhotoLibraryImageSaver {
    static func save(_ data: Data, mediaType: String) async throws {
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard status == .authorized || status == .limited else {
            throw NSError(domain: "PhotoLibraryImageSaver", code: 1, userInfo: [
                NSLocalizedDescriptionKey: String(localized: "请在系统设置中允许添加照片后重试")
            ])
        }
        let options = PHAssetResourceCreationOptions()
        let fileExtension = UTType(mimeType: mediaType)?.preferredFilenameExtension ?? "png"
        options.originalFilename = "DshMobile-\(UUID().uuidString).\(fileExtension)"
        try await PHPhotoLibrary.shared().performChanges {
            PHAssetCreationRequest.forAsset().addResource(with: .photo, data: data, options: options)
        }
    }
}
