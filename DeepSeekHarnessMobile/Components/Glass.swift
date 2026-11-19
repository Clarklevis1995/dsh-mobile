import SwiftUI

/// 让会话滚到状态栏和导航栏后方，并把原安全区高度留给视口的滚动留白。
struct ConversationTopSafeArea<Content: View>: View {
    @ViewBuilder let content: (CGFloat) -> Content

    var body: some View {
        GeometryReader { geometry in
            content(geometry.safeAreaInsets.top)
                .ignoresSafeArea(.container, edges: .top)
        }
    }
}

/// 明确选择软边缘，避免系统版本变化把滚动正文的渐进模糊切回硬质背景。
struct ConversationScrollEdgeStyle: ViewModifier {
    @ViewBuilder
    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content.scrollEdgeEffectStyle(.soft, for: .top)
        } else {
            content
        }
    }
}

struct GlassSurface: ViewModifier {
    let radius: CGFloat
    let dark: Bool
    let tint: Color?
    let clear: Bool
    let shadowOpacity: Double

    @ViewBuilder
    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            if clear {
                if let tint {
                    content
                        .glassEffect(.clear.tint(tint), in: .rect(cornerRadius: radius))
                } else {
                    content
                        .glassEffect(.clear, in: .rect(cornerRadius: radius))
                }
            } else {
                if let tint {
                    content
                        .glassEffect(.regular.tint(tint), in: .rect(cornerRadius: radius))
                } else {
                    content
                        .glassEffect(.regular, in: .rect(cornerRadius: radius))
                }
            }
        } else {
            content
                .background {
                    RoundedRectangle(cornerRadius: radius, style: .continuous)
                        .fill(dark ? AnyShapeStyle(.ultraThinMaterial.opacity(tint == nil ? 0.75 : 0.82)) : AnyShapeStyle(.thinMaterial))
                        .overlay {
                            RoundedRectangle(cornerRadius: radius, style: .continuous)
                                .fill(tint ?? .clear)
                        }
                }
                .overlay(RoundedRectangle(cornerRadius: radius, style: .continuous).stroke(.white.opacity(dark ? 0.2 : 0.7), lineWidth: 0.8))
                .shadow(color: DSHColor.navy.opacity(shadowOpacity), radius: 16, y: 8)
        }
    }
}

extension View {
    func glassSurface(radius: CGFloat = 22, dark: Bool = false, tint: Color? = nil, clear: Bool = false, shadowOpacity: Double = 0.18) -> some View {
        modifier(GlassSurface(radius: radius, dark: dark, tint: tint, clear: clear, shadowOpacity: shadowOpacity))
    }

    /// Button 与 Menu 共用原生圆形液态玻璃交互，旧系统沿用材质样式。
    @ViewBuilder
    func glassCircleButton(onDarkBackground: Bool = false) -> some View {
        if #available(iOS 26.0, *) {
            if onDarkBackground {
                // 深色动态背景使用透明玻璃，避免默认材质适配时出现浅色实心填充。
                self
                    .buttonStyle(.glass(.clear.tint(DSHColor.navy.opacity(0.22))))
                    .buttonBorderShape(.circle)
            } else {
                self
                    .buttonStyle(.glass)
                    .buttonBorderShape(.circle)
            }
        } else {
            self
                .buttonStyle(.plain)
                .background(.ultraThinMaterial, in: Circle())
                .overlay(Circle().stroke(.white.opacity(0.25), lineWidth: 0.8))
        }
    }
}

/// 输入框与其上方的文件胶囊共用材质、边缘和阴影，仅轮廓半径不同。
private struct ComposerGlassSurface: ViewModifier {
    @Environment(\.colorScheme) private var colorScheme
    let radius: CGFloat
    let tint: Color?
    let edge: Color?

    func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: radius, style: .continuous)
        content
            .glassSurface(radius: radius, dark: tint != nil && colorScheme == .dark,
                tint: tint ?? Color(uiColor: .secondarySystemBackground).opacity(colorScheme == .dark ? 0.52 : 0.48),
                shadowOpacity: 0)
            .clipShape(shape)
            .overlay { shape.stroke(edge ?? Color.white.opacity(colorScheme == .dark ? 0.13 : 0.56), lineWidth: 0.7) }
            .shadow(color: .black.opacity(colorScheme == .dark ? 0.24 : 0.10), radius: 10, x: 0, y: 2)
    }
}

extension View {
    func composerGlassSurface(radius: CGFloat, tint: Color? = nil, edge: Color? = nil) -> some View {
        modifier(ComposerGlassSurface(radius: radius, tint: tint, edge: edge))
    }
}

struct ConnectionDot: View {
    let state: ConnectionState

    private var color: Color {
        switch state {
        case .connected:
            DSHColor.success
        case .failed, .connecting, .disconnected:
            .red
        }
    }

    var body: some View {
        Circle()
            .fill(color)
            .frame(width: 8, height: 8)
            .shadow(color: color.opacity(0.65), radius: 5)
    }
}

struct HarnessMark: View {
    var onDarkBackground = true
    var width: CGFloat = 216

    var body: some View {
        HStack(spacing: 4) {
            WhalenessOrcaIcon(size: 29)
                .foregroundStyle(onDarkBackground ? Color.white : Color.black)
            Image("WhalenessHarnessWordmark")
                .resizable()
                .scaledToFit()
                .colorInvertIfNeeded(onDarkBackground)
                .frame(maxWidth: .infinity, maxHeight: 29, alignment: .leading)
        }
        .frame(maxWidth: width, alignment: .leading)
        .frame(height: 29)
        .accessibilityLabel("Whaleness Harness")
    }
}

struct WhalenessOrcaIcon: View {
    var size: CGFloat

    var body: some View {
        Image("DshOrcaMark")
            .renderingMode(.template)
            .resizable()
            .scaledToFit()
            .frame(width: size, height: size)
            .accessibilityHidden(true)
    }
}

extension View {
    @ViewBuilder
    func colorInvertIfNeeded(_ inverted: Bool) -> some View {
        if inverted { colorInvert() } else { self }
    }
}
