import SwiftUI

struct AppleMusicCardDestination: Equatable {
    let url: URL
    let name: String
    let artist: String?
    let album: String?
    let artworkURL: URL?
    let durationSeconds: Double?

    private struct Metadata: Decodable {
        let v: Int
        let title: String
        let artist: String?
        let album: String?
        let artworkURL: String?
        let durationSeconds: Double?
    }

    init?(link: String, title: String) {
        guard link.utf16.count <= 12288, title.utf16.count <= 1000, var parts = URLComponents(string: link) else { return nil }
        var metadata: Metadata?
        if let fragment = parts.fragment {
            let prefix = "dsh-music-card="
            guard fragment.hasPrefix(prefix) else { return nil }
            var base64 = String(fragment.dropFirst(prefix.count)).replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
            base64 += String(repeating: "=", count: (4 - base64.count % 4) % 4)
            guard let data = Data(base64Encoded: base64), data.count <= 4096,
                  let value = try? JSONDecoder().decode(Metadata.self, from: data), value.v == 1,
                  !value.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, value.title.utf16.count <= 200,
                  (value.artist?.utf16.count ?? 0) <= 200, (value.album?.utf16.count ?? 0) <= 200,
                  value.artworkURL.map({ AppleMusicLink.artworkURL($0) != nil }) ?? true,
                  value.durationSeconds.map({ $0.isFinite && (0...86400).contains($0) }) ?? true else { return nil }
            metadata = value
            parts.fragment = nil
        }
        guard let rawURL = parts.url?.absoluteString, let url = AppleMusicLink.url(rawURL) else { return nil }
        self.url = url
        let name = title.trimmingCharacters(in: .whitespacesAndNewlines)
        self.name = metadata?.title ?? (name.isEmpty || name == link ? "Apple Music" : name)
        self.artist = metadata?.artist
        self.album = metadata?.album
        self.artworkURL = metadata.flatMap { $0.artworkURL.flatMap(AppleMusicLink.artworkURL) }
        self.durationSeconds = metadata?.durationSeconds
    }

    var secondaryDetail: String? {
        var parts = [album].compactMap { $0 }.filter { !$0.isEmpty }
        if let durationSeconds {
            let seconds = Int(durationSeconds)
            parts.append(seconds >= 3600 ? String(format: "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60) : String(format: "%d:%02d", seconds / 60, seconds % 60))
        }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }
}

struct AppleMusicAppCard: View {
    let destination: AppleMusicCardDestination
    @State private var failure: AppleMusicCardPlayback.Failure?
    @State private var startingPlayback = false
    @State private var playbackTask: Task<Void, Never>?

    var body: some View {
        AppLinkCard(icon: "ConnectorMusic", name: destination.name,
                    subtitle: AppleMusicLink.songID(destination.url) == nil ? "Apple Music" : String(localized: "在 Apple Music 中播放"),
                    artworkURL: destination.artworkURL, detail: destination.artist, secondaryDetail: destination.secondaryDetail,
                    imageSize: 56, contentPadding: 12, textSpacing: 3, titleLineLimit: 1, surfaceStrength: 0.6,
                    actionSymbol: "play.fill", busy: startingPlayback) {
            guard !startingPlayback else { return }
            startingPlayback = true
            playbackTask = Task { @MainActor in
                defer { startingPlayback = false; playbackTask = nil }
                do { try await AppleMusicCardPlayback.activate(destination.url) }
                catch is CancellationError { }
                catch { failure = (error as? AppleMusicCardPlayback.Failure) ?? .playbackFailed }
            }
        }
        .frame(maxWidth: 320)
        // 原生玻璃的阴影留在正文布局边界内，避免被聊天宿主或相邻段落裁切。
        .padding(.horizontal, 8).padding(.vertical, 4)
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityIdentifier("apple-music-app-card")
        .onDisappear { playbackTask?.cancel() }
        .alert("Apple Music", isPresented: Binding(get: { failure != nil }, set: { if !$0 { failure = nil } })) {
            Button("好", role: .cancel) { }
        } message: {
            Text(failureMessage)
        }
    }
    private var failureMessage: String {
        switch failure {
        case .permissionDenied: String(localized: "请允许访问 Apple Music 后再播放。")
        case .playbackFailed: String(localized: "未能播放所选歌曲，请检查 Apple Music 订阅、网络和歌曲可用性。")
        case .busy: String(localized: "正在播放另一首歌曲，请稍后重试。")
        case .unsupportedContent: String(localized: "请选择具体歌曲进行播放。")
        default: String(localized: "未能播放所选歌曲，请检查 Apple Music 订阅、网络和歌曲可用性。")
        }
    }
}

/// 地图与音乐卡片共用尺寸、配色及整卡点击区域。
struct AppLinkCard: View {
    let icon: String
    let name: String
    let subtitle: String
    var artworkURL: URL? = nil
    var detail: String? = nil
    var secondaryDetail: String? = nil
    var imageSize: CGFloat = 48
    var contentPadding: CGFloat = 14
    var textSpacing: CGFloat = 5
    var titleLineLimit = 2
    var surfaceStrength: Double = 1
    var actionSymbol = "arrow.up.right"
    var busy = false
    let open: () -> Void

    var body: some View {
        Button(action: open) {
            HStack(spacing: 12) {
                Group {
                    if let artworkURL {
                        AsyncImage(url: artworkURL) { phase in
                            if let image = phase.image { image.resizable().scaledToFill() }
                            else { Image(icon).resizable().scaledToFit() }
                        }
                    } else {
                        Image(icon).resizable().scaledToFit()
                    }
                }
                    .frame(width: imageSize, height: imageSize)
                    .clipShape(RoundedRectangle(cornerRadius: 11, style: .continuous))
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: textSpacing) {
                    Text(name).appFont(.body.weight(.semibold)).foregroundStyle(.primary)
                        .lineLimit(titleLineLimit).truncationMode(.tail)
                    if let detail, !detail.isEmpty { Text(detail).appFont(.subheadline).foregroundStyle(.secondary).lineLimit(1) }
                    if let secondaryDetail { Text(secondaryDetail).appFont(.caption).foregroundStyle(.secondary).lineLimit(1) }
                    Text(subtitle).appFont(.caption).foregroundStyle(.secondary)
                }
                Spacer(minLength: 4)
                if busy { ProgressView().controlSize(.small).frame(width: 18) }
                else { Image(systemName: actionSymbol).font(.body.weight(.medium)).foregroundStyle(.secondary) }
            }
            .padding(contentPadding).frame(maxWidth: .infinity, alignment: .leading)
            .modifier(AppLinkCardSurface(strength: surfaceStrength))
            .contentShape(RoundedRectangle(cornerRadius: 16))
        }
        .buttonStyle(.plain)
        .disabled(busy)
        .accessibilityLabel(Text([name, detail, secondaryDetail, subtitle].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: "，")))
    }
}

/// iOS 26 使用原生交互玻璃；旧系统及“降低透明度”保留清晰的中性底色。
private struct AppLinkCardSurface: ViewModifier {
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    let strength: Double

    @ViewBuilder
    func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: 16, style: .continuous)
        if #available(iOS 26.0, *), !reduceTransparency {
            if strength < 1 {
                // 只淡化玻璃背景及其原生阴影，文字和封面保持完整不透明度。
                content.background {
                    Color.clear
                        .glassEffect(.regular.tint(Color(uiColor: .secondarySystemBackground).opacity(0.45)).interactive(), in: shape)
                        .opacity(strength)
                }
            } else {
                content
                    .glassEffect(
                        .regular.tint(Color(uiColor: .secondarySystemBackground).opacity(0.45)).interactive(),
                        in: shape
                    )
            }
        } else {
            content
                .background(Color(uiColor: .secondarySystemBackground), in: shape)
                .overlay(shape.strokeBorder(Color(uiColor: .separator).opacity(0.35), lineWidth: 0.5))
        }
    }
}
