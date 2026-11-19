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

/// 普通会话和 Bot 共用缓存、排队、请求合并、取消和超时；来源只适配字节协议。
@MainActor
final class MarkdownImageLoader {
    private enum Source {
        case workspace(session: String, workspace: String, path: String, resolve: Bool)
        case bot(SharedBotImageTransfer)
    }
    private struct Pending {
        let key: String
        let source: Source
        var waiters: [UUID: CheckedContinuation<Data, Error>]
    }

    var send: ((String) -> Void)?
    var sendBot: ((String) -> Bool)?
    private let store = SharedWorkspaceFileStore()
    private let cache = NSCache<NSString, NSData>()
    private var queue: [Pending] = []
    private var active: Pending?
    private var requestID: String?
    private var bytes = Data()
    private var timeout: Task<Void, Never>?
    private static let maximumBytes = 32 * 1024 * 1024

    init() {
        cache.totalCostLimit = Self.maximumBytes
        cache.countLimit = 32
    }

    func load(sessionID: String, workspaceSessionID: String, path: String, supportsResolve: Bool) async throws -> Data {
        try await enqueue(key: "workspace:\(sessionID.utf8.count):\(sessionID)\(workspaceSessionID.utf8.count):\(workspaceSessionID)\(path)",
            source: .workspace(session: sessionID, workspace: workspaceSessionID, path: path, resolve: supportsResolve))
    }

    func loadBot(sessionID: String, attachmentID: String?, path: String?, author: String?, preview: Bool) async throws -> Data {
        let transfer = SharedBotImageTransfer(sessionId: sessionID, attachmentId: attachmentID, path: path, author: author, preview: preview)
        return try await enqueue(key: "bot:" + transfer.endpoint + transfer.payloadJson(), source: .bot(transfer))
    }

    private func enqueue(key: String, source: Source) async throws -> Data {
        try Task.checkCancellation()
        if let data = cache.object(forKey: key as NSString) { return data as Data }
        let waiterID = UUID()
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                guard !Task.isCancelled else { continuation.resume(throwing: CancellationError()); return }
                if active?.key == key { active?.waiters[waiterID] = continuation; return }
                if let index = queue.firstIndex(where: { $0.key == key }) {
                    queue[index].waiters[waiterID] = continuation
                    return
                }
                queue.append(Pending(key: key, source: source, waiters: [waiterID: continuation]))
                startNext()
            }
        } onCancel: {
            Task { @MainActor [weak self] in self?.cancelWaiter(key: key, id: waiterID) }
        }
    }

    private func cancelWaiter(key: String, id: UUID) {
        if active?.key == key, let waiter = active?.waiters.removeValue(forKey: id) {
            waiter.resume(throwing: CancellationError())
            if active?.waiters.isEmpty == true { finish(.failure(CancellationError())) }
        } else if let index = queue.firstIndex(where: { $0.key == key }), let waiter = queue[index].waiters.removeValue(forKey: id) {
            waiter.resume(throwing: CancellationError())
            if queue[index].waiters.isEmpty { queue.remove(at: index) }
        }
    }

    func cancelAll() {
        let queued = queue.flatMap { $0.waiters.values }
        queue.removeAll()
        if active != nil { finish(.failure(CancellationError())) }
        cache.removeAllObjects()
        queued.forEach { $0.resume(throwing: CancellationError()) }
    }

    @discardableResult
    func accept(_ frame: GatewayFrame) -> Bool {
        guard let active, case .workspace = active.source, let requestID else { return false }
        let requestMatches = frame.requestId == requestID || frame.requestId == requestID + "-download"
        let transferID = store.snapshot().activeDownload?.transferId
        let transferMatches = transferID != nil && (frame.transferId == transferID || frame.requestId == transferID)
        guard requestMatches || transferMatches else { return false }
        guard let data = try? JSONEncoder().encode(frame) else { return false }
        apply(store.acceptFrame(json: String(decoding: data, as: UTF8.self)))
        return true
    }

    @discardableResult
    func acceptBot(_ json: String) -> Bool {
        guard let active, case .bot(let transfer) = active.source, let requestID,
              let frame = (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: Any],
              frame["requestId"] as? String == requestID else { return false }
        self.requestID = nil
        guard frame["kind"] as? String == "bot-result", frame["endpoint"] as? String == transfer.endpoint,
              let value = frame["value"], let data = try? JSONSerialization.data(withJSONObject: value),
              transfer.acceptJson(json: String(decoding: data, as: UTF8.self)) else {
            finish(.failure(URLError(.cannotLoadFromNetwork))); return true
        }
        if transfer.finished {
            guard let encoded = transfer.dataBase64, let data = Data(base64Encoded: encoded) else {
                finish(.failure(URLError(.cannotDecodeContentData))); return true
            }
            finish(.success(data))
        } else { sendBotChunk(transfer) }
        return true
    }

    private func startNext() {
        guard active == nil, !queue.isEmpty else { return }
        let pending = queue.removeFirst()
        active = pending
        bytes = Data()
        let seconds = if case .bot = pending.source { 60 } else { 30 }
        timeout = Task { [weak self] in
            do { try await Task.sleep(for: .seconds(seconds)) } catch { return }
            guard self?.active?.key == pending.key else { return }
            self?.finish(.failure(URLError(.timedOut)))
        }
        switch pending.source {
        case .workspace(let session, let workspace, let path, let resolve):
            let id = UUID().uuidString
            requestID = id
            _ = store.reset(sessionId: session)
            store.setWorkspaceSession(sessionId: session, workspaceSessionId: workspace)
            apply(store.resolve(sessionId: session, path: path, requestId: id, supportsResolve: resolve, useCache: false))
        case .bot(let transfer): sendBotChunk(transfer)
        }
    }

    private func sendBotChunk(_ transfer: SharedBotImageTransfer) {
        let id = "bot-image-" + UUID().uuidString
        requestID = id
        guard sendBot?(transfer.requestJson(requestId: id)) == true else {
            finish(.failure(URLError(.notConnectedToInternet))); return
        }
    }

    private func apply(_ transition: SharedWorkspaceFileTransition) {
        if transition.snapshot.lastError != nil {
            if let request = transition.request { send?(request.payload) }
            finish(.failure(URLError(.cannotLoadFromNetwork)))
            return
        }
        if let download = transition.snapshot.activeDownload, download.size > Self.maximumBytes {
            finish(.failure(URLError(.dataLengthExceedsMaximum))); return
        }
        if let encoded = transition.appendBase64Data {
            guard let chunk = Data(base64Encoded: encoded), bytes.count + chunk.count <= Self.maximumBytes else {
                finish(.failure(URLError(.dataLengthExceedsMaximum))); return
            }
            bytes.append(chunk)
        }
        if let completion = transition.completion {
            guard bytes.count == completion.size else { finish(.failure(URLError(.cannotDecodeContentData))); return }
            finish(.success(bytes))
        } else if let request = transition.request { send?(request.payload) }
    }

    private func finish(_ result: Result<Data, Error>) {
        guard let pending = active else { return }
        timeout?.cancel()
        timeout = nil
        active = nil
        requestID = nil
        if case .workspace = pending.source, let request = store.cancel().request { send?(request.payload) }
        _ = store.reset(sessionId: nil)
        bytes = Data()
        let validated = result.flatMap { data -> Result<Data, Error> in
            guard let dimensions = GatewayImageInspector.dimensions(of: data), dimensions.width > 0, dimensions.height > 0,
                  dimensions.width <= 16_384, dimensions.height <= 16_384,
                  dimensions.width * dimensions.height <= 80_000_000 else { return .failure(URLError(.cannotDecodeContentData)) }
            cache.setObject(data as NSData, forKey: pending.key as NSString, cost: data.count)
            return .success(data)
        }
        pending.waiters.values.forEach { $0.resume(with: validated) }
        startNext()
    }
}
