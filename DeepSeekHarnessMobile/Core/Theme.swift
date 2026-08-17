import SwiftUI

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
    var body: some View {
        ZStack {
            LinearGradient(colors: [Color.black, DSHColor.navy, Color(red: 0.02, green: 0.12, blue: 0.23)], startPoint: .topLeading, endPoint: .bottomTrailing)
            Canvas { context, size in
                let spacing: CGFloat = 34
                for x in stride(from: 0, through: size.width, by: spacing) {
                    context.stroke(Path { $0.move(to: CGPoint(x: x, y: 0)); $0.addLine(to: CGPoint(x: x, y: size.height)) }, with: .color(.white.opacity(0.035)))
                }
                for y in stride(from: 0, through: size.height, by: spacing) {
                    context.stroke(Path { $0.move(to: CGPoint(x: 0, y: y)); $0.addLine(to: CGPoint(x: size.width, y: y)) }, with: .color(.white.opacity(0.035)))
                }
                for index in 0..<54 {
                    let x = size.width * CGFloat((index * 37) % 101) / 101
                    let y = size.height * (0.35 + 0.6 * CGFloat((index * 19) % 97) / 97)
                    context.fill(Path(ellipseIn: CGRect(x: x, y: y, width: index % 3 == 0 ? 2 : 1, height: index % 3 == 0 ? 2 : 1)), with: .color(.white.opacity(0.16)))
                }
            }
            Ellipse()
                .stroke(AngularGradient(colors: [.clear, .cyan.opacity(0.8), .white, .clear], center: .center), lineWidth: 7)
                .frame(width: 132, height: 72)
                .blur(radius: 4)
                .rotationEffect(.degrees(-18))
                .offset(x: 55, y: -210)
                .shadow(color: .blue.opacity(0.75), radius: 36)
        }
        .ignoresSafeArea()
    }
}
