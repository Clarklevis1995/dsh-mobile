import SwiftUI

/// 表格预览和文字选择页共用的颜色、页头尺寸与关闭按钮。
enum DocumentPageAppearance {
    static let background = Color(uiColor: .systemBackground)
    static let foreground = Color(uiColor: .label)
    static let closeBackground = Color(uiColor: UIColor { $0.userInterfaceStyle == .dark
        ? UIColor(red: 37/255, green: 37/255, blue: 40/255, alpha: 1)
        : UIColor(red: 245/255, green: 245/255, blue: 247/255, alpha: 1) })
    static let headerFill = adaptive(0xEFEFF0, 0x252527)
    static let alternateFill = adaptive(0xF8F8FA, 0x111113)
    static let border = adaptive(0xDADADD, 0x353538)
    static let toolbar = adaptive(0xF5F5F7, 0x1C1C1E)
    static let icon = adaptive(0x65656B, 0xAAAAB0)

    private static func adaptive(_ light: Int, _ dark: Int) -> Color {
        Color(uiColor: UIColor { traits in
            let value = traits.userInterfaceStyle == .dark ? dark : light
            return UIColor(red: CGFloat((value >> 16) & 255) / 255,
                green: CGFloat((value >> 8) & 255) / 255, blue: CGFloat(value & 255) / 255, alpha: 1)
        })
    }
}

struct DocumentPageHeader<Actions: View>: View {
    let title: LocalizedStringKey
    let closeIdentifier: String
    let onClose: () -> Void
    @ViewBuilder var actions: Actions

    var body: some View {
        ZStack {
            Text(title)
                .appFont(.system(size: 17, weight: .semibold))
                .lineLimit(1)
                .padding(.horizontal, 90)
                .accessibilityIdentifier("document-page-title")
            HStack(spacing: 0) {
                Button("关闭", action: onClose)
                    .buttonStyle(.plain)
                    .appFont(.system(size: 17))
                    .frame(width: 64, height: 44)
                    .background { Capsule().fill(DocumentPageAppearance.closeBackground) }
                    .accessibilityIdentifier(closeIdentifier)
                Spacer(minLength: 0)
                actions
            }
        }
        .foregroundStyle(DocumentPageAppearance.foreground)
        .padding(.horizontal, 20)
        .frame(height: 60)
        .background { Rectangle().fill(DocumentPageAppearance.background) }
    }
}

/// 每个预览独立持有方向状态，关闭后不需要恢复任何窗口级设置。
@MainActor
final class DocumentPreviewRotation: ObservableObject {
    @Published private(set) var isRotated = false

    func toggle() { isRotated.toggle() }
}

/// 预览只改变自己的布局坐标系，不请求窗口或控制器改变方向。
struct DocumentRotatingLayout<Content: View>: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let isRotated: Bool
    @ViewBuilder var content: Content

    var body: some View {
        GeometryReader { geometry in
            let size = geometry.size
            content
                .frame(width: isRotated ? size.height : size.width,
                       height: isRotated ? size.width : size.height)
                .rotationEffect(.degrees(isRotated ? -90 : 0))
                .position(x: size.width / 2, y: size.height / 2)
        }
        .clipped()
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.25), value: isRotated)
    }
}

/// 应用方向不再由表格预览的打开、旋转或关闭状态决定。
@MainActor
enum AppInterfaceOrientationPolicy {
    static var orientations: UIInterfaceOrientationMask {
        UIDevice.current.userInterfaceIdiom == .pad ? .all : .portrait
    }
}
