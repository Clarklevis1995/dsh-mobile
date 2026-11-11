import SwiftUI
import DeepSeekHarnessShared

func voiceOverlayTransition(reduceMotion: Bool = false) -> AnyTransition {
    .asymmetric(
        insertion: reduceMotion ? .opacity : .opacity.combined(with: .offset(
            y: CGFloat(VoiceInputInteraction.shared.OVERLAY_ENTER_OFFSET))),
        removal: .opacity)
}

/// 入场先快速显现，再平滑收尾；不等待麦克风启动。
func voiceOverlayAnimation(active: Bool, reduceMotion: Bool = false) -> Animation {
    if reduceMotion { return .linear(duration: 0.08) }
    if active {
        return .timingCurve(0, 0, 0.2, 1,
            duration: Double(VoiceInputInteraction.shared.OVERLAY_FADE_IN_MILLISECONDS) / 1000)
    }
    return .easeInOut(duration: Double(VoiceInputInteraction.shared.OVERLAY_FADE_OUT_MILLISECONDS) / 1000)
}

/// 底部录音遮罩：静音时粒子漂移，讲话时收束成真实音量的柱状波形。
struct VoiceRipplePanel: View {
    let state: SharedVoiceInputSnapshot
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        GeometryReader { geometry in
            let cancelling = state.cancelArmed
            let palette = VoiceOverlayPalette(isDark: colorScheme == .dark, cancelling: cancelling)
            let base = palette.base
            let glow = palette.glow
            let ink = palette.ink
            let contentHeight = geometry.size.height
            ZStack {
                ZStack {
                    base
                    Ellipse().fill(glow.opacity(0.8))
                        .frame(width: geometry.size.width * 1.5, height: geometry.size.height * 0.9)
                        .blur(radius: 34).offset(y: 20)
                }
                .frame(width: geometry.size.width, height: geometry.size.height)
                .clipped()
                TimelineView(.animation(minimumInterval: 1.0 / 30, paused: reduceMotion)) { timeline in
                    VoiceParticleField(
                        levels: state.waveformLevels.map { $0.doubleValue },
                        gathering: Double(state.visualActivity),
                        time: reduceMotion ? 0 : timeline.date.timeIntervalSinceReferenceDate,
                        ink: ink
                    )
                    .animation(reduceMotion ? nil : .easeOut(duration: state.waveformActive ? 0.16 : 0.32), value: state.visualActivity)
                }
                .accessibilityHidden(true)
                VStack(spacing: 8) {
                    Text(state.status?.localized ?? "")
                        .appFont(.system(size: 17, weight: .medium))
                    if state.phase == "recording" {
                        Text(state.recordingTimeLabel)
                            .appFont(.system(size: 15, weight: .medium)).monospacedDigit()
                    }
                }
                .foregroundStyle(state.recordingLimitApproaching ? Color.red : ink.opacity(0.9))
                .position(x: geometry.size.width / 2, y: contentHeight * 0.4)

            }
            .mask(VoiceEllipticalMask())
            .background {
                VoiceOuterHalo(color: colorScheme == .dark ? Color(uiColor: .systemBackground) : .white)
                    .allowsHitTesting(false)
            }
            .contentShape(Rectangle())
            .animation(reduceMotion ? nil : .easeInOut(duration: 0.2), value: cancelling)
        }
        .onAppear { traceVoiceInputLatency("overlay-mounted") }
    }
}

/// 浅色模式使用明亮雾蓝与深色前景，深色模式保留柔和亮度；两端色值一致。
private struct VoiceOverlayPalette {
    let base: Color
    let glow: Color
    let ink: Color

    init(isDark: Bool, cancelling: Bool) {
        let colors: (Int, Int, Int)
        switch (isDark, cancelling) {
        case (true, true): colors = (0x5B3543, 0x895668, 0xFAE5EB)
        case (true, false): colors = (0x203B5C, 0x39658F, 0xE3F0FF)
        case (false, true): colors = (0xF0ACBA, 0xFFD6DA, 0x642434)
        case (false, false): colors = (0x73A9E7, 0xAFD7FA, 0x173C65)
        }
        func color(_ rgb: Int) -> Color {
            Color(red: Double((rgb >> 16) & 255) / 255,
                  green: Double((rgb >> 8) & 255) / 255,
                  blue: Double(rgb & 255) / 255)
        }
        base = color(colors.0)
        glow = color(colors.1)
        ink = color(colors.2)
    }
}

/// 更宽的背景色椭圆衬在彩色蒙层后方，柔化外缘并淡化边缘下方的会话内容。
private struct VoiceOuterHalo: View {
    let color: Color

    var body: some View {
        Canvas { context, size in
            let fadeHeight = size.height * 0.78
            let radiusX = size.width * 1.12
            let gradient = Gradient(stops: (0...32).map { index in
                let radius = Double(index) / 32
                let progress = min(1, max(0, (radius - 0.5) / 0.5))
                let opacity = 1 - progress * progress * (3 - 2 * progress)
                return .init(color: color.opacity(opacity), location: radius)
            })
            context.translateBy(x: size.width / 2, y: fadeHeight)
            context.scaleBy(x: radiusX, y: fadeHeight)
            let topRegion = CGRect(x: -size.width / (2 * radiusX), y: -1, width: size.width / radiusX, height: 1)
            context.fill(Path(topRegion), with: .radialGradient(gradient, center: .zero, startRadius: 0, endRadius: 1))
        }
    }
}

/// 顶部按高度比例平滑过渡到页面背景，下方覆盖底部安全区。
private struct VoiceEllipticalMask: View {
    @Environment(\.displayScale) private var displayScale
    // .5 等于视图侧边的归一化半径；加宽羽化范围，接入下方实色区域时仍完全不透明。
    private static let gradient = Gradient(stops: (0...32).map { index in
        let radius = Double(index) / 32
        let progress = min(1, max(0, (radius - 0.5) / 0.5))
        let opacity = 1 - progress * progress * (3 - 2 * progress)
        return .init(color: .black.opacity(opacity), location: radius)
    })

    var body: some View {
        Canvas { context, size in
            let fadeHeight = floor(size.height * 0.72 * displayScale) / displayScale
            let solidStart = max(0, fadeHeight - 1 / displayScale)
            let radiusX = size.width
            context.fill(Path(CGRect(x: 0, y: solidStart, width: size.width, height: size.height - solidStart)),
                         with: .color(.black))
            context.translateBy(x: size.width / 2, y: fadeHeight)
            context.scaleBy(x: radiusX, y: fadeHeight)
            let topRegion = CGRect(x: -size.width / (2 * radiusX), y: -1, width: size.width / radiusX, height: 1)
            context.fill(Path(topRegion), with: .radialGradient(Self.gradient, center: .zero, startRadius: 0, endRadius: 1))
        }
    }
}

private struct VoiceParticleField: View, Animatable {
    let levels: [Double]
    var gathering: Double
    let time: Double
    let ink: Color
    var animatableData: Double {
        get { gathering }
        set { gathering = newValue }
    }

    private func seed(_ index: Int, _ salt: Double) -> Double {
        let value = sin(Double(index) * 127.1 + salt) * 43758.5453
        return value - floor(value)
    }

    var body: some View {
        Canvas { context, size in
            let bars = 45
            let particlesPerBar = 13
            let pitch = min(6.4, (size.width - 48) / Double(bars))
            let waveWidth = pitch * Double(bars - 1)
            let centerY = size.height * 0.68
            for index in 0..<(bars * particlesPerBar) {
                let column = index % bars
                let row = index / bars
                let edge = sin(Double(column) / Double(bars - 1) * .pi)
                let level = column < levels.count ? levels[column] : 0
                let height = 5 + level * 56 * (0.35 + 0.65 * edge)
                let freeX = 16 + seed(index, 1) * (size.width - 32)
                let freeY = size.height * (0.16 + seed(index, 8) * 0.79)
                let driftX = sin(time * (0.16 + seed(index, 3) * 0.18) + Double(index)) * 14
                    + cos(time * 0.19 + seed(index, 10) * 2 * .pi) * 5
                let driftY = cos(time * (0.12 + seed(index, 4) * 0.16) + Double(index)) * 12
                    + sin(time * 0.17 + seed(index, 11) * 2 * .pi) * 4
                let targetX = (size.width - waveWidth) / 2 + Double(column) * pitch
                let targetY = centerY + (Double(row) / Double(particlesPerBar - 1) - 0.5) * height
                let x = (freeX + driftX) * (1 - gathering) + targetX * gathering
                let y = (freeY + driftY) * (1 - gathering) + targetY * gathering
                // 自由粒子独立漂浮与呼吸；波形目标使用中心包络和更轻的扰动。
                let freePhase = seed(index, 12) * 2 * Double.pi
                let freePulse = sin(time * (0.30 + seed(index, 13) * 0.20) + freePhase)
                let freeShimmer = cos(time * (0.24 + seed(index, 14) * 0.18) + freePhase * 1.7)
                let freeRadius = (0.7 + seed(index, 5) * 0.8) * (1 + freePulse * 0.18)
                let freeAlpha = (0.12 + seed(index, 6) * 0.26) * (0.72 + freeShimmer * 0.28)
                let phase = seed(index, 9) * 2 * Double.pi
                let pulse = sin(time * (Double.pi / 10) + phase)
                let shimmer = cos(time * (Double.pi / 10) + phase * 1.7)
                let waveRadius = (0.8 + edge * 0.5 + (seed(index, 5) - 0.5) * 0.12) * (1 + pulse * 0.025)
                let waveAlpha = (0.26 + edge * 0.4 + (seed(index, 6) - 0.5) * 0.06) * (0.98 + shimmer * 0.02)
                let radius = freeRadius * (1 - gathering) + waveRadius * gathering
                let alpha = freeAlpha * (1 - gathering) + waveAlpha * gathering
                context.fill(Path(ellipseIn: CGRect(x: x - radius, y: y - radius, width: radius * 2, height: radius * 2)),
                             with: .color(ink.opacity(alpha)))
            }
        }
    }
}
