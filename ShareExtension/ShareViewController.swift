import SwiftUI
import UIKit
import Intents
import UniformTypeIdentifiers

@MainActor
private final class ShareHandoffState: ObservableObject {
    @Published var message = "正在准备分享内容…"
    @Published var failed = false
}

final class ShareViewController: UIViewController {
    private let state = ShareHandoffState()
    private var started = false
    private var savedDraft: SharedIncomingDraft?

    override func viewDidLoad() {
        super.viewDidLoad()
        let host = UIHostingController(rootView: ShareHandoffView(state: state,
            retry: { [weak self] in Task { await self?.receive() } },
            cancel: { [weak self] in self?.extensionContext?.completeRequest(returningItems: nil) }))
        addChild(host)
        host.view.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(host.view)
        NSLayoutConstraint.activate([
            host.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            host.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            host.view.topAnchor.constraint(equalTo: view.topAnchor),
            host.view.bottomAnchor.constraint(equalTo: view.bottomAnchor)
        ])
        host.didMove(toParent: self)
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        guard !started else { return }
        started = true
        Task { await receive() }
    }

    @MainActor
    private func receive() async {
        state.failed = false
        if let savedDraft { await openApplication(for: savedDraft); return }
        let id = UUID()
        var directory: URL?
        do {
            let extensionInfo = Bundle.main.object(forInfoDictionaryKey: "NSExtension") as? [String: Any]
            // Action Extension 读取 intent 会触发系统异常；只有 Share Extension 会携带会话建议。
            let isShareExtension = extensionInfo?["NSExtensionPointIdentifier"] as? String == "com.apple.share-services"
            let suggestedID = isShareExtension ? (extensionContext?.intent as? INSendMessageIntent)?.conversationIdentifier : nil
            let target: SharedConversation
            if let suggestedID {
                guard let suggested = ShareInbox.suggestedDestination(identifier: suggestedID) else {
                    throw ShareError.message("此会话已不可用，请打开 Whaleness 后重新分享。")
                }
                target = suggested
            } else {
                target = ShareInbox.defaultDestination()
            }
            state.message = suggestedID == nil ? "正在打开默认工作区的新会话…" : "正在打开「\(target.title)」…"
            let folder = try ShareInbox.directory().appendingPathComponent(id.uuidString, isDirectory: true)
            directory = folder
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            var texts: [String] = []
            var files: [String] = []
            var totalBytes = 0
            let providers = (extensionContext?.inputItems as? [NSExtensionItem] ?? []).flatMap { $0.attachments ?? [] }
            guard providers.count <= 20 else { throw ShareError.message("一次最多分享 20 项内容") }
            for provider in providers {
                if provider.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier) || provider.hasItemConformingToTypeIdentifier(UTType.image.identifier) ||
                    (!provider.hasItemConformingToTypeIdentifier(UTType.url.identifier) && !provider.hasItemConformingToTypeIdentifier(UTType.text.identifier)) {
                    let type = provider.registeredTypeIdentifiers.first { UTType($0)?.conforms(to: .image) == true }
                        ?? provider.registeredTypeIdentifiers.first { UTType($0)?.conforms(to: .data) == true }
                        ?? UTType.data.identifier
                    let filename: String
                    if provider.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier) {
                        let item = try await load(provider, type: UTType.fileURL.identifier)
                        guard let url = item as? URL else { throw ShareError.message("无法读取附件地址") }
                        let access = url.startAccessingSecurityScopedResource()
                        defer { if access { url.stopAccessingSecurityScopedResource() } }
                        let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
                        guard size <= 100 * 1024 * 1024 else { throw ShareError.message("分享附件不能超过 100 MiB") }
                        filename = "\(files.count)-" + url.lastPathComponent
                        try FileManager.default.copyItem(at: url, to: folder.appendingPathComponent(filename))
                    } else {
                        filename = try await copyFile(provider, type: type, folder: folder, index: files.count)
                    }
                    files.append(filename)
                    totalBytes += (try folder.appendingPathComponent(filename).resourceValues(forKeys: [.fileSizeKey])).fileSize ?? 0
                    guard totalBytes <= 100 * 1024 * 1024 else { throw ShareError.message("分享附件总大小不能超过 100 MiB") }
                } else if provider.hasItemConformingToTypeIdentifier(UTType.url.identifier) {
                    let item = try await load(provider, type: UTType.url.identifier)
                    if let url = item as? URL { texts.append(url.absoluteString) }
                } else {
                    let item = try await load(provider, type: UTType.text.identifier)
                    if let text = item as? String { texts.append(text) }
                }
            }
            guard !texts.isEmpty || !files.isEmpty else { throw ShareError.message("没有可分享的内容") }
            let draft = SharedIncomingDraft(id: id, target: target, text: texts.joined(separator: "\n\n"),
                files: files, createsNewConversation: suggestedID == nil)
            try ShareInbox.save(draft)
            savedDraft = draft
            await openApplication(for: draft)
        } catch {
            if let directory { try? FileManager.default.removeItem(at: directory) }
            state.message = error.localizedDescription
            state.failed = true
        }
    }

    private func openApplication(for draft: SharedIncomingDraft) async {
        let url = URL(string: "dshmobile://share?id=\(draft.id.uuidString)")!
        // 从已出现的扩展窗口交给 UIScene 打开；不使用扩展不支持的 UIApplication.shared。
        let opened: Bool
        if let scene = view.window?.windowScene {
            opened = await withCheckedContinuation { continuation in
                scene.open(url, options: nil) { continuation.resume(returning: $0) }
            }
        } else if let context = extensionContext {
            opened = await context.open(url)
        } else { opened = false }
        if opened {
            extensionContext?.completeRequest(returningItems: nil)
        } else {
            state.message = "内容已保存，暂时无法打开 Whaleness。请重试，或打开应用继续。"
            state.failed = true
        }
    }

    private func load(_ provider: NSItemProvider, type: String) async throws -> NSSecureCoding {
        try await withCheckedThrowingContinuation { continuation in
            provider.loadItem(forTypeIdentifier: type, options: nil) { item, error in
                if let error { continuation.resume(throwing: error) }
                else if let item { continuation.resume(returning: item) }
                else { continuation.resume(throwing: ShareError.message("无法读取分享内容")) }
            }
        }
    }

    private func copyFile(_ provider: NSItemProvider, type: String, folder: URL, index: Int) async throws -> String {
        try await withCheckedThrowingContinuation { continuation in
            provider.loadFileRepresentation(forTypeIdentifier: type) { url, error in
                do {
                    if let error { throw error }
                    guard let url else { throw ShareError.message("无法读取附件") }
                    let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
                    guard size <= 100 * 1024 * 1024 else { throw ShareError.message("单个分享附件不能超过 100 MiB") }
                    var name = "\(index)-" + url.lastPathComponent
                    if url.pathExtension.isEmpty, let suffix = UTType(type)?.preferredFilenameExtension {
                        name += "." + suffix
                    }
                    try FileManager.default.copyItem(at: url, to: folder.appendingPathComponent(name))
                    continuation.resume(returning: name)
                } catch { continuation.resume(throwing: error) }
            }
        }
    }
}

private struct ShareHandoffView: View {
    @ObservedObject var state: ShareHandoffState
    let retry: () -> Void
    let cancel: () -> Void

    var body: some View {
        VStack(spacing: 20) {
            if !state.failed { ProgressView() }
            Text(state.message).multilineTextAlignment(.center)
            if state.failed {
                Button("重试打开 Whaleness", action: retry)
                Button("关闭", action: cancel).foregroundStyle(.secondary)
            }
        }
        .padding(28)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(uiColor: .systemBackground))
    }
}

private enum ShareError: LocalizedError {
    case message(String)
    var errorDescription: String? { if case .message(let text) = self { return text }; return nil }
}
