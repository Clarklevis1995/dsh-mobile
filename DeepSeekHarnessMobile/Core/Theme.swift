import SwiftUI
import UIKit

enum AppFontScale {
    static let key = "appearance.fontScale"
    static let followsSystemKey = "appearance.fontFollowsSystem"
    static func effective(_ value: Double, followsSystem: Bool) -> Double {
        followsSystem ? 1 : normalized(value)
    }
    static func normalized(_ value: Double) -> Double {
        value.isFinite ? min(1.5, max(0.8, value)) : 1
    }
    static var current: CGFloat {
        let value = UserDefaults.standard.object(forKey: key) as? Double ?? 1
        return CGFloat(effective(value, followsSystem: UserDefaults.standard.bool(forKey: followsSystemKey)))
    }
}

// 统一描述字体，避免固定字号、语义字体和独立 UIKit 宿主使用不同的缩放规则。
struct AppFont {
    private var style: UIFont.TextStyle = .body
    private var size: CGFloat?
    private var fontWeight: Font.Weight?
    private var design: Font.Design = .default
    private var usesMonospacedDigits = false

    static let largeTitle = system(.largeTitle)
    static let title = system(.title)
    static let title2 = system(.title2)
    static let title3 = system(.title3)
    static let headline = system(.headline)
    static let subheadline = system(.subheadline)
    static let body = system(.body)
    static let callout = system(.callout)
    static let footnote = system(.footnote)
    static let caption = system(.caption)
    static let caption2 = system(.caption2)

    static func system(size: CGFloat, weight: Font.Weight = .regular, design: Font.Design = .default) -> AppFont {
        AppFont(size: size, fontWeight: weight, design: design)
    }

    static func system(_ style: Font.TextStyle, design: Font.Design = .default) -> AppFont {
        let uiStyle: UIFont.TextStyle
        switch style {
        case .largeTitle: uiStyle = .largeTitle
        case .title: uiStyle = .title1
        case .title2: uiStyle = .title2
        case .title3: uiStyle = .title3
        case .headline: uiStyle = .headline
        case .subheadline: uiStyle = .subheadline
        case .callout: uiStyle = .callout
        case .footnote: uiStyle = .footnote
        case .caption: uiStyle = .caption1
        case .caption2: uiStyle = .caption2
        default: uiStyle = .body
        }
        return AppFont(style: uiStyle, design: design)
    }

    func weight(_ value: Font.Weight) -> AppFont {
        var copy = self; copy.fontWeight = value; return copy
    }
    func bold() -> AppFont { weight(.bold) }
    func monospaced() -> AppFont {
        var copy = self; copy.design = .monospaced; return copy
    }
    func monospacedDigit() -> AppFont {
        var copy = self; copy.usesMonospacedDigits = true; return copy
    }
    func resolved(scale: CGFloat) -> Font {
        let base = size.map { UIFontMetrics.default.scaledValue(for: $0) }
            ?? UIFont.preferredFont(forTextStyle: style).pointSize
        var font = Font.system(size: base * scale, weight: fontWeight ?? (style == .headline ? .semibold : .regular), design: design)
        if usesMonospacedDigits { font = font.monospacedDigit() }
        return font
    }
}

private struct AppFontModifier: ViewModifier {
    @AppStorage(AppFontScale.key) private var scale = 1.0
    @AppStorage(AppFontScale.followsSystemKey) private var followsSystem = false
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let value: AppFont
    func body(content: Content) -> some View {
        content.font(value.resolved(scale: CGFloat(AppFontScale.effective(scale, followsSystem: followsSystem))))
    }
}

struct AppFontScope: ViewModifier {
    @AppStorage(AppFontScale.key) private var scale = 1.0
    @AppStorage(AppFontScale.followsSystemKey) private var followsSystem = false
    @Environment(\.dynamicTypeSize) private var systemSize

    func body(content: Content) -> some View {
        // 系统控件也需要显式更新 Dynamic Type；应用自己的字体保留精确倍率。
        let sizes = DynamicTypeSize.allCases
        let target = UIFont.preferredFont(forTextStyle: .body).pointSize * AppFontScale.effective(scale, followsSystem: followsSystem)
        let closest = sizes.min { lhs, rhs in
            abs(bodySize(lhs) - target) < abs(bodySize(rhs) - target)
        } ?? systemSize
        content
            .font(AppFont.body.resolved(scale: CGFloat(AppFontScale.effective(scale, followsSystem: followsSystem))))
            .dynamicTypeSize(closest)
    }

    private func bodySize(_ size: DynamicTypeSize) -> CGFloat {
        let categories: [UIContentSizeCategory] = [.extraSmall, .small, .medium, .large, .extraLarge,
            .extraExtraLarge, .extraExtraExtraLarge, .accessibilityMedium, .accessibilityLarge,
            .accessibilityExtraLarge, .accessibilityExtraExtraLarge, .accessibilityExtraExtraExtraLarge]
        let index = DynamicTypeSize.allCases.firstIndex(of: size) ?? 3
        return UIFont.preferredFont(forTextStyle: .body,
            compatibleWith: UITraitCollection(preferredContentSizeCategory: categories[index])).pointSize
    }
}

extension View {
    func appFont(_ value: AppFont) -> some View { modifier(AppFontModifier(value: value)) }
}

extension UIFont {
    static func appPreferredFont(forTextStyle style: UIFont.TextStyle) -> UIFont {
        let font = preferredFont(forTextStyle: style)
        return font.withSize(font.pointSize * AppFontScale.current)
    }
    static func appMonospacedFont(ofSize size: CGFloat, weight: UIFont.Weight) -> UIFont {
        monospacedSystemFont(ofSize: UIFontMetrics.default.scaledValue(for: size) * AppFontScale.current, weight: weight)
    }
}

enum DSHColor {
    static let navy = Color(red: 0.025, green: 0.09, blue: 0.17)
    static let navyRaised = Color(red: 0.05, green: 0.14, blue: 0.25)
    static let ocean = Color(red: 0.18, green: 0.42, blue: 0.9)
    static let mist = Color(red: 0.75, green: 0.84, blue: 1)
    static let ink = Color(red: 0.055, green: 0.075, blue: 0.1)
    static let paper = Color(red: 0.975, green: 0.98, blue: 0.99)
    static let purple = Color(red: 0.48, green: 0.33, blue: 0.78)
    static let orange = Color(red: 0.94, green: 0.49, blue: 0.08)
    static let amber = Color(red: 1.0, green: 0.68, blue: 0.12)
    static let success = Color(red: 0.18, green: 0.72, blue: 0.36)
}

struct DeepOceanBackground: View {
    var prefersOpenLightArcs = false
    var animationPaused = false

    var body: some View {
        GeometryReader { geometry in
            HarnessAnimatedBackground(
                style: HarnessBackgroundStyle.resolve(
                    size: geometry.size,
                    isPad: UIDevice.current.userInterfaceIdiom == .pad
                ),
                prefersOpenLightArcs: prefersOpenLightArcs,
                animationPaused: animationPaused
            )
        }
        .ignoresSafeArea()
    }
}


/// 根据当前容器伸缩；不读取设备型号或 UIScreen，分屏和台前调度同样生效。
enum AdaptiveLayout {
    static let pageWidth: CGFloat = 1_040
    static let workspaceWidth: CGFloat = 1_440

    static func workspaceUsesColumns(size: CGSize, accessibilityText: Bool = false) -> Bool {
        guard !accessibilityText, size.height >= 500 else { return false }
        // 900 是原来的分界（双栏每栏最少 300px）。iPhone Duo 展开横拿约 890×626，
        // 差一点到 900，所以额外允许「横向且宽 ≥ 700」也分栏；
        // 折叠（466×678）与展开竖拿（626×890）仍保持单栏。
        return size.width >= 900 || (size.width >= 700 && size.width > size.height)
    }

    static func horizontalInset(width: CGFloat, maximumWidth: CGFloat) -> CGFloat {
        max(0, (width - maximumWidth) / 2)
    }
}

extension View {
    /// 背景和滚动区域铺满窗口，正文保持合适的阅读宽度。
    func readableContentWidth(_ maximumWidth: CGFloat = AdaptiveLayout.pageWidth) -> some View {
        frame(maxWidth: maximumWidth)
            .frame(maxWidth: .infinity)
    }

    func adaptiveListWidth(_ maximumWidth: CGFloat = AdaptiveLayout.pageWidth) -> some View {
        modifier(AdaptiveListWidth(maximumWidth: maximumWidth))
    }
}

private struct AdaptiveListWidth: ViewModifier {
    let maximumWidth: CGFloat

    func body(content: Content) -> some View {
        GeometryReader { geometry in
            content.padding(
                .horizontal,
                AdaptiveLayout.horizontalInset(width: geometry.size.width, maximumWidth: maximumWidth)
            )
        }
    }
}

// MARK: - iOS 16.1 兼容层
//
// 上游最低支持 iOS 17，本文件提供 16.1 可用的等价实现：
// 1. onChangeCompat：替代 iOS 17 的双参数 onChange(of:_:)。
// 2. DshEmptyState：替代 iOS 17 的 ContentUnavailableView。

extension View {
    /// 与 iOS 17 `onChange(of:_:)` 双参数版本语义一致（首次渲染不触发，之后携带新旧值回调）。
    func onChangeCompat<V: Equatable>(
        of value: V,
        _ action: @escaping (V, V) -> Void
    ) -> some View {
        modifier(OnChangeCompatModifier(value: value, action: action))
    }
}

private struct OnChangeCompatModifier<V: Equatable>: ViewModifier {
    let value: V
    let action: (V, V) -> Void

    @State private var previous: V?
    @State private var hasPrevious = false

    func body(content: Content) -> some View {
        content
            .onAppear {
                if !hasPrevious {
                    previous = value
                    hasPrevious = true
                }
            }
            .onChange(of: value) { newValue in
                let oldValue = previous ?? newValue
                previous = newValue
                hasPrevious = true
                guard oldValue != newValue else { return }
                action(oldValue, newValue)
            }
    }
}

/// `ContentUnavailableView` 的 iOS 16.1 替代实现，覆盖项目内用到的三种构造形式。
struct DshEmptyState: View {
    private let label: AnyView
    private let message: AnyView?
    private let actions: AnyView?

    init<L: View, D: View, A: View>(
        @ViewBuilder label: () -> L,
        @ViewBuilder description: () -> D,
        @ViewBuilder actions: () -> A
    ) {
        self.label = AnyView(label())
        self.message = AnyView(description())
        self.actions = AnyView(actions())
    }

    init<L: View, D: View>(
        @ViewBuilder label: () -> L,
        @ViewBuilder description: () -> D
    ) {
        self.label = AnyView(label())
        self.message = AnyView(description())
        self.actions = nil
    }

    init(_ title: String, systemImage: String, description: Text? = nil) {
        self.label = AnyView(
            VStack(spacing: 10) {
                Image(systemName: systemImage)
                    .font(.system(size: 40, weight: .light))
                Text(title)
                    .font(.headline)
                    .multilineTextAlignment(.center)
            }
        )
        self.message = description.map { AnyView($0.font(.subheadline).multilineTextAlignment(.center)) }
        self.actions = nil
    }

    var body: some View {
        VStack(spacing: 12) {
            label
            if let message {
                message
            }
            if let actions {
                actions
                    .padding(.top, 4)
            }
        }
        .foregroundStyle(.secondary)
        .frame(maxWidth: .infinity)
        .padding(24)
    }
}
