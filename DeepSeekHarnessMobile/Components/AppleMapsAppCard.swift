import SwiftUI
import MapKit

/// 卡片只接受苹果地图坐标链接；点击时从校验后的字段构造 MapKit 请求。
struct AppleMapsCardDestination: Equatable {
    let latitude: Double
    let longitude: Double
    let name: String
    let transport: String?

    init?(link: String, title: String) {
        guard link.utf16.count <= 4096,
              let url = URLComponents(string: link),
              ["https", "maps"].contains(url.scheme?.lowercased() ?? ""),
              url.host?.lowercased() == "maps.apple.com", url.user == nil, url.password == nil,
              url.port == nil, url.fragment == nil, ["", "/"].contains(url.path),
              let items = url.queryItems, Set(items.map(\.name)).count == items.count,
              Set(items.map(\.name)).isSubset(of: ["ll", "q", "daddr", "dirflg"]) else { return nil }
        let values = Dictionary(uniqueKeysWithValues: items.map { ($0.name, $0.value ?? "") })
        let route = values["daddr"] != nil
        guard !(route && values["ll"] != nil), route || values["dirflg"] == nil,
              let pair = values[route ? "daddr" : "ll"]?.split(separator: ",", omittingEmptySubsequences: false),
              pair.count == 2, let latitude = Double(pair[0]), let longitude = Double(pair[1]),
              latitude.isFinite, longitude.isFinite, (-90...90).contains(latitude), (-180...180).contains(longitude)
        else { return nil }
        let mode = route ? values["dirflg"] ?? "d" : nil
        guard mode == nil || ["d", "w", "r"].contains(mode!) else { return nil }
        let label = title.trimmingCharacters(in: .whitespacesAndNewlines)
        let name = label.isEmpty || label == link ? values["q"] ?? "" : label
        guard name.utf16.count <= 1000 else { return nil }
        self.latitude = latitude
        self.longitude = longitude
        self.name = name.isEmpty ? String(localized: "地图地点") : name
        self.transport = mode
    }

    @MainActor
    func open() -> Bool {
        let destination = MKMapItem(placemark: MKPlacemark(coordinate: .init(latitude: latitude, longitude: longitude)))
        destination.name = name
        var options: [String: Any] = [:]
        if let transport {
            options[MKLaunchOptionsDirectionsModeKey] = ["d": MKLaunchOptionsDirectionsModeDriving,
                "w": MKLaunchOptionsDirectionsModeWalking, "r": MKLaunchOptionsDirectionsModeTransit][transport]
        }
        return destination.openInMaps(launchOptions: options)
    }
}

struct AppleMapsAppCard: View {
    let destination: AppleMapsCardDestination
    @State private var openFailed = false

    var body: some View {
        AppLinkCard(icon: "ConnectorLocation", name: destination.name, subtitle: String(localized: "在苹果地图中打开")) {
            openFailed = !destination.open()
        }
        .accessibilityIdentifier("apple-maps-app-card")
        .accessibilityLabel(Text("\(destination.name)，在苹果地图中打开"))
        .alert("无法打开苹果地图", isPresented: $openFailed) {
            Button("好", role: .cancel) { }
        } message: {
            Text("请确认已安装苹果地图，然后重试。")
        }
    }
}
