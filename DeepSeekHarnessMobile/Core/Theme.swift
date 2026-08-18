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
        HarnessAnimatedBackground()
    }
}
