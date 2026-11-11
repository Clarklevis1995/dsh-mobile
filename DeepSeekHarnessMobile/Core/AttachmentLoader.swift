import Foundation
import DeepSeekHarnessShared
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

/// 正文中的工作区图片复用 KMM 文件协议，与用户打开文件的预览状态相互独立。
@MainActor
final class WorkspaceMarkdownImageLoader {
    private struct Pending {
        let id: String
        let key: String
        let sessionID: String
        let workspaceSessionID: String
        let path: String
        let supportsResolve: Bool
        var waiters: [CheckedContinuation<Data, Error>]
    }

    var send: ((String) -> Void)?
    private let store = SharedWorkspaceFileStore()
    private let cache = NSCache<NSString, NSData>()
    private var queue: [Pending] = []
    private var active: Pending?
    private var bytes = Data()
    private var timeout: Task<Void, Never>?
    private static let maximumBytes = 32 * 1024 * 1024

    init() {
        cache.totalCostLimit = Self.maximumBytes
        cache.countLimit = 32
    }

    func load(sessionID: String, workspaceSessionID: String, path: String, supportsResolve: Bool) async throws -> Data {
        let key = "\(sessionID.utf8.count):\(sessionID)\(path)"
        if let data = cache.object(forKey: key as NSString) { return data as Data }
        return try await withCheckedThrowingContinuation { continuation in
            if active?.key == key { active?.waiters.append(continuation); return }
            if let index = queue.firstIndex(where: { $0.key == key }) {
                queue[index].waiters.append(continuation)
                return
            }
            queue.append(Pending(id: UUID().uuidString, key: key, sessionID: sessionID,
                workspaceSessionID: workspaceSessionID, path: path, supportsResolve: supportsResolve,
                waiters: [continuation]))
            startNext()
        }
    }

    func cancelAll() {
        timeout?.cancel()
        let waiters = (active?.waiters ?? []) + queue.flatMap(\.waiters)
        active = nil
        queue = []
        bytes = Data()
        if let request = store.cancel().request { send?(request.payload) }
        _ = store.reset(sessionId: nil)
        cache.removeAllObjects()
        waiters.forEach { $0.resume(throwing: CancellationError()) }
    }

    @discardableResult
    func accept(_ frame: GatewayFrame) -> Bool {
        guard let active else { return false }
        let requestMatches = frame.requestId == active.id || frame.requestId == active.id + "-download"
        let transferID = store.snapshot().activeDownload?.transferId
        let transferMatches = transferID != nil && (frame.transferId == transferID || frame.requestId == transferID)
        guard requestMatches || transferMatches else { return false }
        guard let data = try? JSONEncoder().encode(frame) else { return false }
        apply(store.acceptFrame(json: String(decoding: data, as: UTF8.self)))
        return true
    }

    private func startNext() {
        guard active == nil, !queue.isEmpty else { return }
        let pending = queue.removeFirst()
        active = pending
        bytes = Data()
        _ = store.reset(sessionId: pending.sessionID)
        store.setWorkspaceSession(sessionId: pending.sessionID, workspaceSessionId: pending.workspaceSessionID)
        timeout = Task { [weak self] in
            try? await Task.sleep(for: .seconds(30))
            guard !Task.isCancelled, self?.active?.id == pending.id else { return }
            self?.finish(.failure(URLError(.timedOut)))
        }
        apply(store.resolve(sessionId: pending.sessionID, path: pending.path, requestId: pending.id,
                            supportsResolve: pending.supportsResolve, useCache: false))
    }

    private func apply(_ transition: SharedWorkspaceFileTransition) {
        if transition.snapshot.lastError != nil {
            if let request = transition.request { send?(request.payload) }
            finish(.failure(URLError(.cannotLoadFromNetwork)))
            return
        }
        if let download = transition.snapshot.activeDownload, download.size > Self.maximumBytes {
            finish(.failure(URLError(.dataLengthExceedsMaximum)))
            return
        }
        if let encoded = transition.appendBase64Data {
            guard let chunk = Data(base64Encoded: encoded), bytes.count + chunk.count <= Self.maximumBytes else {
                finish(.failure(URLError(.dataLengthExceedsMaximum)))
                return
            }
            bytes.append(chunk)
        }
        if let completion = transition.completion {
            guard bytes.count == completion.size,
                  let dimensions = GatewayImageInspector.dimensions(of: bytes),
                  dimensions.width > 0, dimensions.height > 0,
                  dimensions.width <= 16_384, dimensions.height <= 16_384,
                  dimensions.width * dimensions.height <= 80_000_000 else {
                finish(.failure(URLError(.cannotDecodeContentData)))
                return
            }
            finish(.success(bytes))
        } else if let request = transition.request { send?(request.payload) }
    }

    private func finish(_ result: Result<Data, Error>) {
        guard let pending = active else { return }
        timeout?.cancel()
        if case .success(let data) = result { cache.setObject(data as NSData, forKey: pending.key as NSString, cost: data.count) }
        if let request = store.cancel().request { send?(request.payload) }
        active = nil
        bytes = Data()
        pending.waiters.forEach { $0.resume(with: result) }
        startNext()
    }
}
