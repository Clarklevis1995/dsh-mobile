import SwiftUI

struct GlassSurface: ViewModifier {
    let radius: CGFloat
    let dark: Bool
    let tint: Color?

    @ViewBuilder
    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content
                .background(tint ?? .clear, in: RoundedRectangle(cornerRadius: radius, style: .continuous))
                .glassEffect(.regular, in: .rect(cornerRadius: radius))
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
                .shadow(color: DSHColor.navy.opacity(0.18), radius: 16, y: 8)
        }
    }
}

extension View {
    func glassSurface(radius: CGFloat = 22, dark: Bool = false, tint: Color? = nil) -> some View {
        modifier(GlassSurface(radius: radius, dark: dark, tint: tint))
    }
}

struct ConnectionDot: View {
    let state: ConnectionState
    var body: some View {
        Circle()
            .fill(state.isConnected ? DSHColor.success : (state == .connecting ? .orange : .gray))
            .frame(width: 8, height: 8)
            .shadow(color: state.isConnected ? DSHColor.success.opacity(0.65) : .clear, radius: 5)
    }
}

struct HarnessMark: View {
    var body: some View {
        HStack(spacing: 7) {
            DeepSeekWhaleIcon(size: 27)
            Text("deepseek").font(.system(size: 22, weight: .semibold, design: .rounded))
            Text("HARNESS")
                .font(.system(size: 9, weight: .bold, design: .monospaced))
                .padding(.horizontal, 5).padding(.vertical, 3)
                .overlay(RoundedRectangle(cornerRadius: 3).stroke(Color.primary, lineWidth: 1))
        }
    }
}

struct DeepSeekWhaleIcon: View {
    var size: CGFloat

    var body: some View {
        Image("DeepSeekWhale")
            .renderingMode(.template)
            .resizable()
            .scaledToFit()
            .frame(width: size, height: size * 0.743)
            .accessibilityHidden(true)
    }
}
