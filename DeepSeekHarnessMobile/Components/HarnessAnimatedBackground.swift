import SwiftUI
import UIKit

/// 手机使用紧凑构图；平板和展开后的折叠屏共用宽幅构图与细密粒子。
enum HarnessBackgroundStyle {
    case phone
    case pad

    static func resolve(size: CGSize, isPad: Bool) -> HarnessBackgroundStyle {
        // Duo 展开约 626×890，旋转后短边不变；普通手机横屏仍使用手机背景。
        // iPad 的窄窗口保留原有平板背景，不随分屏切换风格。
        isPad || min(size.width, size.height) >= 600 ? .pad : .phone
    }
}

/// The native counterpart of the DeepSeek Harness landing-page background.
/// Metal renders the fluid field while Canvas keeps the grid and sampled whale
/// independent, so each layer can be tuned or disabled without touching UI.
struct HarnessAnimatedBackground: View {
    let style: HarnessBackgroundStyle
    var prefersOpenLightArcs = false
    var animationPaused = false
    @State private var animationClock = HarnessBackgroundAnimationClock()
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase

    private var usesStaticBackground: Bool {
        if #available(iOS 26.0, *) {
            return reduceMotion
        }
        return true
    }

    private var pausesAnimation: Bool {
        animationPaused || usesStaticBackground || scenePhase != .active
    }

    var body: some View {
        Group {
            if usesStaticBackground {
                // 低版本保留静态粒子与构图，不创建逐帧更新时间线。
                backgroundLayers(time: 0, isStatic: true)
            } else {
                TimelineView(.animation(minimumInterval: 1.0 / 30.0, paused: pausesAnimation)) { timeline in
                    let elapsed = animationClock.elapsed(at: timeline.date)
                        .truncatingRemainder(dividingBy: 4_096)
                    backgroundLayers(time: elapsed, isStatic: false)
                }
            }
        }
        .onAppear { animationClock.setPaused(pausesAnimation, at: Date()) }
        .onChangeCompat(of: pausesAnimation) { _, paused in animationClock.setPaused(paused, at: Date()) }
        .background(DSHColor.navy)
        .ignoresSafeArea()
        .clipped()
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    private func backgroundLayers(time: TimeInterval, isStatic: Bool) -> some View {
        ZStack {
            HarnessFluidLayer(
                time: time, style: style, prefersOpenLightArcs: prefersOpenLightArcs
            )
            HarnessTechnicalGrid(style: style)
            HarnessWhaleField(time: time, isStatic: isStatic, style: style)
                .blendMode(.screen)

            LinearGradient(
                colors: [.clear, DSHColor.navy.opacity(0.12), Color.black.opacity(0.58)],
                startPoint: .top,
                endPoint: .bottom
            )
        }
    }
}

/// 暂停时保持当前画面，恢复后接着播放，避免手指松开时背景突然跳到另一个相位。
struct HarnessBackgroundAnimationClock {
    private var pausedAt: Date?
    private var pausedDuration: TimeInterval = 0

    mutating func setPaused(_ paused: Bool, at date: Date) {
        if paused {
            if pausedAt == nil { pausedAt = date }
        } else if let start = pausedAt {
            pausedDuration += max(0, date.timeIntervalSince(start))
            pausedAt = nil
        }
    }

    func elapsed(at date: Date) -> TimeInterval {
        (pausedAt ?? date).timeIntervalSinceReferenceDate - pausedDuration
    }
}

/// 平板随窗口宽高比调整构图；手机使用独立的尺寸和位置。
struct HarnessBackgroundComposition {
    let whaleSize: CGFloat
    let whaleCenter: CGPoint

    init(size: CGSize, style: HarnessBackgroundStyle) {
        let width = max(1, size.width)
        let height = max(1, size.height)
        if style == .phone {
            whaleSize = max(width * 1.15, height * 0.48)
            whaleCenter = CGPoint(x: width * 0.55, y: height * 0.34)
            return
        }
        let aspect = width / height
        let wideProgress = min(1, max(0, (aspect - 0.65) / 0.75))
        whaleSize = min(max(width * 1.15, height * 0.48), height * 1.12) * 0.82
        whaleCenter = CGPoint(
            x: width * (0.55 + 0.05 * wideProgress),
            y: height * (0.34 + 0.12 * wideProgress)
        )
    }
}

private struct HarnessFluidLayer: View {
    let time: TimeInterval
    let style: HarnessBackgroundStyle
    let prefersOpenLightArcs: Bool

    @ViewBuilder
    var body: some View {
        if #available(iOS 17.0, *) {
            // Metal 流体场依赖 iOS 17 的 ShaderLibrary 与 visualEffect。
            Rectangle()
                .fill(DSHColor.navy)
                .visualEffect { content, proxy in
                    content.colorEffect(
                        ShaderLibrary.harnessFluid(
                            .float2(proxy.size),
                            .float(Float(time)),
                            .float(style == .pad ? 1 : 0),
                            .float(prefersOpenLightArcs ? 1 : 0)
                        )
                    )
                }
        } else {
            // iOS 16.1 兜底：用静态深色渐变替代流体场，其余图层（网格/鲸鱼粒子）不受影响。
            LinearGradient(
                colors: [
                    DSHColor.navy,
                    DSHColor.navy.opacity(0.82),
                    Color.black.opacity(0.92)
                ],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
        }
    }
}

private struct HarnessTechnicalGrid: View {
    let style: HarnessBackgroundStyle

    var body: some View {
        Canvas(rendersAsynchronously: true) { context, size in
            let spacing: CGFloat = style == .pad
                ? min(64, max(42, min(size.width, size.height) / 16))
                : 42
            var grid = Path()

            for x in stride(from: -spacing, through: size.width + spacing, by: spacing) {
                grid.move(to: CGPoint(x: x, y: 0))
                grid.addLine(to: CGPoint(x: x, y: size.height))
            }
            for y in stride(from: -spacing, through: size.height + spacing, by: spacing) {
                grid.move(to: CGPoint(x: 0, y: y))
                grid.addLine(to: CGPoint(x: size.width, y: y))
            }

            context.stroke(grid, with: .color(.white.opacity(0.043)), lineWidth: 0.55)

            // Sparse square intersections mirror the website's technical grid
            // without competing with the whale particle silhouette.
            let columns = Int(ceil(size.width / spacing)) + 1
            let rows = Int(ceil(size.height / spacing)) + 1
            for row in 0..<rows {
                for column in 0..<columns where (row * 11 + column * 7) % 13 == 0 {
                    let point = CGPoint(x: CGFloat(column) * spacing, y: CGFloat(row) * spacing)
                    let rect = CGRect(x: point.x - 1.1, y: point.y - 1.1, width: 2.2, height: 2.2)
                    context.fill(Path(rect), with: .color(.white.opacity(0.085)))
                }
            }
        }
        .mask(
            LinearGradient(
                stops: [
                    .init(color: .white.opacity(0.8), location: 0),
                    .init(color: .white, location: 0.56),
                    .init(color: .clear, location: 1)
                ],
                startPoint: .top,
                endPoint: .bottom
            )
        )
    }
}

private struct HarnessWhaleField: View {
    let time: TimeInterval
    let isStatic: Bool
    let style: HarnessBackgroundStyle

    private let particles: [WhaleParticle]

    init(time: TimeInterval, isStatic: Bool, style: HarnessBackgroundStyle) {
        self.time = time
        self.isStatic = isStatic
        self.style = style
        particles = WhaleParticleMask.particles(for: style)
    }

    var body: some View {
        Canvas(rendersAsynchronously: true) { context, size in
            guard !particles.isEmpty else { return }

            let composition = HarnessBackgroundComposition(size: size, style: style)
            let square = composition.whaleSize
            // 鲸鱼轮廓以中心为基准横向舒展 8%。
            let whaleWidth = square * 1.08
            let center = composition.whaleCenter
            let motionScale: CGFloat = style == .pad ? min(2, max(1, square / 460)) : 1
            let driftAmplitude = style == .pad
                ? CGSize(width: 1.8, height: 1.7)
                : CGSize(width: 2.8, height: 2.6)
            // 手机沿用原有粒径；平板使用细密粒子并限制最大尺寸。
            let baseDot = style == .pad
                ? min(2.4, max(0.8, square / 360))
                : max(1.45, square / 220)
            let t = isStatic ? 0 : time

            for particle in particles {
                let tail = smoothstep(0.54, 0.94, particle.x)
                let edgeDrift = 0.35 + 0.65 * particle.edge
                let driftX = sin(t * 0.50 + particle.phase * 0.53) * driftAmplitude.width * edgeDrift * motionScale
                let driftY = cos(t * 0.42 + particle.phase * 0.71) * driftAmplitude.height * edgeDrift * motionScale
                let tailWave = sin(t * 1.10 - particle.x * 7.0) * 7.0 * tail * motionScale
                let shimmer = 0.90 + 0.10 * sin(t * 1.5 + particle.x * 15 + particle.y * 9)

                let point = CGPoint(
                    x: center.x + (particle.x - 0.5) * whaleWidth + driftX,
                    y: center.y + (particle.y - 0.5) * square + driftY + tailWave
                )
                let dotSize = baseDot * (0.72 + particle.luminance * 0.58)
                let rect = CGRect(
                    x: point.x - dotSize * 0.5,
                    y: point.y - dotSize * 0.5,
                    width: dotSize,
                    height: dotSize
                )
                let boundedLuminance = min(1, max(0, particle.luminance))
                let boundedLight = min(1, max(0, particle.light))
                let alpha = min(1, max(0, (0.10 + boundedLuminance * 0.38) * shimmer))
                let color = Color(
                    red: min(1, max(0, 0.73 + 0.15 * boundedLight)),
                    green: min(1, max(0, 0.79 + 0.12 * boundedLight)),
                    blue: min(1, max(0, 0.92 + 0.08 * boundedLight))
                )
                context.fill(Path(roundedRect: rect, cornerRadius: dotSize * 0.18), with: .color(color.opacity(alpha)))
            }
        }
        .opacity(0.56)
        .blur(radius: 0.18)
    }

    private func smoothstep(_ edge0: CGFloat, _ edge1: CGFloat, _ value: CGFloat) -> CGFloat {
        let x = min(1, max(0, (value - edge0) / (edge1 - edge0)))
        return x * x * (3 - 2 * x)
    }
}

private struct WhaleParticle: Sendable {
    let x: CGFloat
    let y: CGFloat
    let luminance: CGFloat
    let edge: CGFloat
    let phase: CGFloat
    let light: CGFloat
}

private enum WhaleParticleMask {
    private static let phoneParticles = makeParticles(sampleSize: 60)
    private static let padParticles = makeParticles(sampleSize: 100)

    static func particles(for style: HarnessBackgroundStyle) -> [WhaleParticle] {
        style == .pad ? padParticles : phoneParticles
    }

    private static func makeParticles(sampleSize: Int) -> [WhaleParticle] {
        guard let image = UIImage(named: "HeroWhaleMask") else { return [] }

        var pixels = [UInt8](repeating: 0, count: sampleSize * sampleSize * 4)
        guard let context = CGContext(
            data: &pixels,
            width: sampleSize,
            height: sampleSize,
            bitsPerComponent: 8,
            bytesPerRow: sampleSize * 4,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ) else { return [] }

        context.setFillColor(UIColor.black.cgColor)
        context.fill(CGRect(x: 0, y: 0, width: sampleSize, height: sampleSize))

        let sourceAspect = max(0.01, image.size.width / image.size.height)
        let targetWidth = CGFloat(sampleSize)
        let targetHeight = targetWidth / sourceAspect
        let targetRect = CGRect(
            x: 0,
            y: (CGFloat(sampleSize) - targetHeight) * 0.5,
            width: targetWidth,
            height: targetHeight
        )
        UIGraphicsPushContext(context)
        image.draw(in: targetRect)
        UIGraphicsPopContext()

        func luminance(x: Int, y: Int) -> CGFloat {
            guard x >= 0, y >= 0, x < sampleSize, y < sampleSize else { return 0 }
            let offset = (y * sampleSize + x) * 4
            return (
                0.299 * CGFloat(pixels[offset])
                + 0.587 * CGFloat(pixels[offset + 1])
                + 0.114 * CGFloat(pixels[offset + 2])
            ) / 255
        }

        var result: [WhaleParticle] = []
        result.reserveCapacity(sampleSize * sampleSize / 2)

        for y in 0..<sampleSize {
            for x in 0..<sampleSize {
                let value = luminance(x: x, y: y)
                guard value > 0.2 else { continue }

                var hasNeighbour = false
                for dy in -2...2 {
                    for dx in -2...2 where dx != 0 || dy != 0 {
                        if luminance(x: x + dx, y: y + dy) > 0.2 { hasNeighbour = true }
                    }
                }
                guard hasNeighbour else { continue }

                var missingNeighbours = 0
                for dy in -1...1 {
                    for dx in -1...1 where dx != 0 || dy != 0 {
                        if luminance(x: x + dx, y: y + dy) <= 0.2 { missingNeighbours += 1 }
                    }
                }

                let normalizedX = (CGFloat(x) + 0.5) / CGFloat(sampleSize)
                let normalizedY = 1 - (CGFloat(y) + 0.5) / CGFloat(sampleSize)
                let light = max(0, 1 - hypot(normalizedX - 0.30, normalizedY - 0.28) / 0.92)
                result.append(
                    WhaleParticle(
                        x: normalizedX,
                        y: normalizedY,
                        luminance: value,
                        edge: CGFloat(missingNeighbours) / 8,
                        phase: CGFloat(result.count),
                        light: light
                    )
                )
            }
        }
        return result
    }
}
