// 仅生成自动验收自建内容的二维码；不依赖鸿蒙模拟器不支持的码图生成API。
import AppKit
import CoreImage.CIFilterBuiltins

struct Fixture: Decodable { let label: String; let value: String; let valid: Bool }
let rows = try JSONDecoder().decode([Fixture].self, from: Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1])))
let directory = URL(fileURLWithPath: CommandLine.arguments[2], isDirectory: true)
try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
let context = CIContext()
for (index, row) in rows.enumerated() {
    let generator = CIFilter.qrCodeGenerator()
    generator.message = Data(row.value.utf8)
    generator.correctionLevel = "M"
    guard let output = generator.outputImage?.transformed(by: CGAffineTransform(scaleX: 8, y: 8)),
          let image = context.createCGImage(output, from: output.extent) else { fatalError("QR generation failed") }
    let source = NSBitmapImageRep(cgImage: image)
    guard let png = source.representation(using: .png, properties: [:]) else { fatalError("PNG encoding failed") }
    try png.write(to: directory.appendingPathComponent("qr-\(index).png"))
}
