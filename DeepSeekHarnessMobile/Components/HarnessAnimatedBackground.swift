import SwiftUI
import UIKit

/// The native counterpart of the DeepSeek Harness landing-page background.
/// Metal renders the fluid field while Canvas keeps the grid and sampled whale
/// independent, so each layer can be tuned or disabled without touching UI.
struct HarnessAnimatedBackground: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase

    private var pausesAnimation: Bool {
        reduceMotion || scenePhase != .active
    }

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30.0, paused: pausesAnimation)) { timeline in
            let elapsed = reduceMotion
                ? 0
                : timeline.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 4_096)

            ZStack {
                HarnessFluidLayer(time: elapsed)
                HarnessTechnicalGrid()
                HarnessWhaleField(time: elapsed, isStatic: reduceMotion)
                    .blendMode(.screen)

                LinearGradient(
                    colors: [.clear, DSHColor.navy.opacity(0.12), Color.black.opacity(0.58)],
                    startPoint: .top,
                    endPoint: .bottom
                )
            }
        }
        .background(DSHColor.navy)
        .ignoresSafeArea()
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

private struct HarnessFluidLayer: View {
    let time: TimeInterval

    var body: some View {
        Rectangle()
            .fill(DSHColor.navy)
            .visualEffect { content, proxy in
                content.colorEffect(
                    ShaderLibrary.harnessFluid(
                        .float2(proxy.size),
                        .float(Float(time))
                    )
                )
            }
    }
}

private struct HarnessTechnicalGrid: View {
    var body: some View {
        Canvas(rendersAsynchronously: true) { context, size in
            let spacing: CGFloat = 42
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

    private let particles = WhaleParticleMask.particles

    var body: some View {
        Canvas(rendersAsynchronously: true) { context, size in
            guard !particles.isEmpty else { return }

            let square = max(size.width * 1.15, size.height * 0.48)
            let center = CGPoint(x: size.width * 0.55, y: size.height * 0.34)
            let baseDot = max(1.45, square / 220)
            let t = isStatic ? 0 : time

            for particle in particles {
                let tail = smoothstep(0.54, 0.94, particle.x)
                let edgeDrift = 0.35 + 0.65 * particle.edge
                let driftX = sin(t * 0.50 + particle.phase * 0.53) * 2.8 * edgeDrift
                let driftY = cos(t * 0.42 + particle.phase * 0.71) * 2.6 * edgeDrift
                let tailWave = sin(t * 1.10 - particle.x * 7.0) * 7.0 * tail
                let shimmer = 0.90 + 0.10 * sin(t * 1.5 + particle.x * 15 + particle.y * 9)

                let point = CGPoint(
                    x: center.x + (particle.x - 0.5) * square + driftX,
                    y: center.y + (particle.y - 0.5) * square + driftY + tailWave
                )
                let dotSize = baseDot * (0.72 + particle.luminance * 0.58)
                let rect = CGRect(
                    x: point.x - dotSize * 0.5,
                    y: point.y - dotSize * 0.5,
                    width: dotSize,
                    height: dotSize
                )
                let alpha = (0.10 + particle.luminance * 0.38) * shimmer
                let color = Color(
                    red: 0.73 + 0.15 * particle.light,
                    green: 0.79 + 0.12 * particle.light,
                    blue: 0.92 + 0.08 * particle.light
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
    static let particles: [WhaleParticle] = makeParticles()

    private static func makeParticles(sampleSize: Int = 60) -> [WhaleParticle] {
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
        result.reserveCapacity(1_000)

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
