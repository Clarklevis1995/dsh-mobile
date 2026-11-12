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
    @State private var openFailed = false

    var body: some View {
        AppLinkCard(icon: "ConnectorMusic", name: destination.name, subtitle: String(localized: "在 Apple Music 中打开"),
                    artworkURL: destination.artworkURL, detail: destination.artist, secondaryDetail: destination.secondaryDetail, imageSize: 64) {
            UIApplication.shared.open(destination.url, options: [.universalLinksOnly: true]) { success in
                Task { @MainActor in openFailed = !success }
            }
        }
        .accessibilityIdentifier("apple-music-app-card")
        .alert("无法打开 Apple Music", isPresented: $openFailed) {
            Button("好", role: .cancel) { }
        } message: {
            Text("请确认已安装 Apple Music，并允许其打开音乐链接。")
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
                VStack(alignment: .leading, spacing: 5) {
                    Text(name).appFont(.body.weight(.semibold)).foregroundStyle(.primary).lineLimit(2)
                    if let detail, !detail.isEmpty { Text(detail).appFont(.subheadline).foregroundStyle(.secondary).lineLimit(1) }
                    if let secondaryDetail { Text(secondaryDetail).appFont(.caption).foregroundStyle(.secondary).lineLimit(1) }
                    Text(subtitle).appFont(.caption).foregroundStyle(.secondary)
                }
                Spacer(minLength: 4)
                Image(systemName: "arrow.up.right").font(.body.weight(.medium)).foregroundStyle(.secondary)
            }
            .padding(14).frame(maxWidth: .infinity, alignment: .leading)
            .modifier(AppLinkCardSurface())
            .contentShape(RoundedRectangle(cornerRadius: 16))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text([name, detail, secondaryDetail, subtitle].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: "，")))
    }
}

/// iOS 26 使用原生交互玻璃；旧系统及“降低透明度”保留清晰的中性底色。
private struct AppLinkCardSurface: ViewModifier {
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency

    @ViewBuilder
    func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: 16, style: .continuous)
        if #available(iOS 26.0, *), !reduceTransparency {
            content
                .glassEffect(
                    .regular.tint(Color(uiColor: .secondarySystemBackground).opacity(0.45)).interactive(),
                    in: shape
                )
        } else {
            content
                .background(Color(uiColor: .secondarySystemBackground), in: shape)
                .overlay(shape.strokeBorder(Color(uiColor: .separator).opacity(0.35), lineWidth: 0.5))
        }
    }
}
