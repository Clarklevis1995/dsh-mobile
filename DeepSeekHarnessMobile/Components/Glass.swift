import SwiftUI

struct GlassSurface: ViewModifier {
    let radius: CGFloat
    let dark: Bool
    let tint: Color?
    let clear: Bool

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
                .shadow(color: DSHColor.navy.opacity(0.18), radius: 16, y: 8)
        }
    }
}

extension View {
    func glassSurface(radius: CGFloat = 22, dark: Bool = false, tint: Color? = nil, clear: Bool = false) -> some View {
        modifier(GlassSurface(radius: radius, dark: dark, tint: tint, clear: clear))
    }
}

struct ConnectionDot: View {
    let state: ConnectionState

    private var color: Color {
        switch state {
        case .connected:
            DSHColor.success
        case .failed:
            .red
        case .disconnected, .connecting:
            DSHColor.amber
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
    var body: some View {
        HStack(spacing: 7) {
            DeepSeekWhaleIcon(size: 27)
            Text("deepseek").font(.system(size: 22, weight: .semibold, design: .rounded))
            Text("HARNESS")
                .font(.system(size: 9, weight: .bold, design: .monospaced))
                .padding(.horizontal, 5).padding(.vertical, 3)
                .overlay {
                    RoundedRectangle(cornerRadius: 3, style: .continuous)
                        .stroke(Color.white, lineWidth: 1)
                }
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
