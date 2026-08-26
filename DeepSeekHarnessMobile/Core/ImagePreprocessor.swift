import UIKit

struct GatewayPreparedImage: Sendable {
    let mediaType: String
    let data: Data
    let dimensions: GatewayImageDimensions
}

enum GatewayImagePreprocessorError: LocalizedError {
    case cannotDecode
    case cannotEncode

    var errorDescription: String? {
        switch self {
        case .cannotDecode: String(localized: "无法解码所选图片。")
        case .cannotEncode: String(localized: "无法将图片压缩到 DSH 允许的大小。")
        }
    }
}

/// Converts an oversized picker result into an attachment accepted by DSH.
/// Valid originals retain their bytes and media type (including animated GIF
/// and WebP). Oversized inputs are flattened to JPEG while preserving their
/// full aspect ratio; this avoids silently cropping content from screenshots.
enum GatewayImagePreprocessor {
    static let maximumBytes = 3_670_016
    static let maximumPixelSide = 2_000
    static let maximumPixels = 40_000_000

    static func prepare(data: Data, mediaType: String) throws -> GatewayPreparedImage {
        guard let originalDimensions = GatewayImageInspector.dimensions(of: data) else {
            throw GatewayImagePreprocessorError.cannotDecode
        }
        let orientation = GatewayImageInspector.orientation(of: data)
        if accepts(data: data, dimensions: originalDimensions), orientation == 1 {
            return GatewayPreparedImage(mediaType: mediaType, data: data, dimensions: originalDimensions)
        }
        guard let sourceImage = UIImage(data: data) else {
            throw GatewayImagePreprocessorError.cannotDecode
        }

        var targetSize = fittedSize(for: originalDimensions)
        while targetSize.width >= 1, targetSize.height >= 1 {
            let rendered = renderJPEGSource(sourceImage, size: targetSize)
            for quality in stride(from: 0.9, through: 0.3, by: -0.1) {
                guard let encoded = rendered.jpegData(compressionQuality: quality) else { continue }
                let dimensions = GatewayImageDimensions(
                    width: Int(targetSize.width.rounded(.down)),
                    height: Int(targetSize.height.rounded(.down))
                )
                if accepts(data: encoded, dimensions: dimensions) {
                    return GatewayPreparedImage(mediaType: "image/jpeg", data: encoded, dimensions: dimensions)
                }
            }
            targetSize = CGSize(
                width: max(1, floor(targetSize.width * 0.8)),
                height: max(1, floor(targetSize.height * 0.8))
            )
        }
        throw GatewayImagePreprocessorError.cannotEncode
    }

    static func accepts(data: Data, dimensions: GatewayImageDimensions) -> Bool {
        data.count <= maximumBytes &&
            dimensions.longestSide <= maximumPixelSide &&
            dimensions.width * dimensions.height <= maximumPixels
    }

    private static func fittedSize(for dimensions: GatewayImageDimensions) -> CGSize {
        let width = Double(dimensions.width)
        let height = Double(dimensions.height)
        let dimensionScale = min(1, Double(maximumPixelSide) / max(width, height))
        let pixelScale = min(1, sqrt(Double(maximumPixels) / (width * height)))
        let scale = min(dimensionScale, pixelScale)
        return CGSize(
            width: max(1, floor(width * scale)),
            height: max(1, floor(height * scale))
        )
    }

    private static func renderJPEGSource(_ image: UIImage, size: CGSize) -> UIImage {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        return UIGraphicsImageRenderer(size: size, format: format).image { context in
            UIColor.white.setFill()
            context.fill(CGRect(origin: .zero, size: size))
            image.draw(in: CGRect(origin: .zero, size: size))
        }
    }
}
