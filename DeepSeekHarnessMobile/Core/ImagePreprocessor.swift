import UIKit
import ImageIO
import UniformTypeIdentifiers

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

/// 根据实际文件格式决定是否保留原图；需要转换时按目标尺寸解码并输出 JPEG。
/// 合规的 GIF、WebP 保留动画，缩放和方向校正保留完整宽高比。
enum GatewayImagePreprocessor {
    static let maximumBytes = 3_670_016
    static let maximumPixelSide = 2_000
    static let maximumPixels = 40_000_000

    static func prepare(data: Data, byteLimit: Int = maximumBytes, pixelSideLimit: Int = maximumPixelSide) throws -> GatewayPreparedImage {
        let sourceOptions = [kCGImageSourceShouldCache: false] as CFDictionary
        guard let source = CGImageSourceCreateWithData(data as CFData, sourceOptions),
              let originalDimensions = GatewayImageInspector.dimensions(of: data) else {
            throw GatewayImagePreprocessorError.cannotDecode
        }
        let mediaType = CGImageSourceGetType(source).flatMap { UTType($0 as String)?.preferredMIMEType }
        let orientation = GatewayImageInspector.orientation(of: data)
        if let mediaType, ["image/png", "image/jpeg", "image/webp", "image/gif"].contains(mediaType),
           data.count <= byteLimit, originalDimensions.longestSide <= pixelSideLimit,
           accepts(data: data, dimensions: originalDimensions), orientation == 1 {
            return GatewayPreparedImage(mediaType: mediaType, data: data, dimensions: originalDimensions)
        }

        var targetSize = fittedSize(for: originalDimensions, pixelSideLimit: pixelSideLimit)
        // 先按目标大小解码，同时应用 EXIF 旋转和镜像，避免展开超大原图。
        let thumbnailOptions: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: Int(max(targetSize.width, targetSize.height)),
            kCGImageSourceShouldCacheImmediately: true
        ]
        guard let thumbnail = CGImageSourceCreateThumbnailAtIndex(source, 0, thumbnailOptions as CFDictionary) else {
            throw GatewayImagePreprocessorError.cannotDecode
        }
        let sourceImage = UIImage(cgImage: thumbnail)

        while targetSize.width >= 1, targetSize.height >= 1 {
            let rendered = renderJPEGSource(sourceImage, size: targetSize)
            for quality in stride(from: 0.9, through: 0.3, by: -0.1) {
                guard let encoded = rendered.jpegData(compressionQuality: quality) else { continue }
                let dimensions = GatewayImageDimensions(
                    width: Int(targetSize.width.rounded(.down)),
                    height: Int(targetSize.height.rounded(.down))
                )
                if encoded.count <= byteLimit && accepts(data: encoded, dimensions: dimensions) {
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

    private static func fittedSize(for dimensions: GatewayImageDimensions, pixelSideLimit: Int) -> CGSize {
        let width = Double(dimensions.width)
        let height = Double(dimensions.height)
        let dimensionScale = min(1, Double(min(maximumPixelSide, pixelSideLimit)) / max(width, height))
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
