import SwiftUI
import UIKit

extension Color {
    init(hex: UInt32) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255
        )
    }

    /// Колір, що відрізняється у світлій і темній темі.
    init(light: UInt32, dark: UInt32) {
        self.init(uiColor: UIColor { trait in
            let v = trait.userInterfaceStyle == .dark ? dark : light
            return UIColor(
                red: CGFloat((v >> 16) & 0xFF) / 255,
                green: CGFloat((v >> 8) & 0xFF) / 255,
                blue: CGFloat(v & 0xFF) / 255,
                alpha: 1
            )
        })
    }
}

enum Palette {
    static let green = Color(hex: 0x2E9E5B)
    static let yellow = Color(hex: 0xE3A008)
    static let orange = Color(hex: 0xE8711C)
    static let red = Color(hex: 0xD93A3A)
    static let night = Color(hex: 0x14264F)
    static let blue = Color(hex: 0x2F5BD3)

    static let accent = Color(light: 0x2F5BD3, dark: 0x9DB7FF)
    static let background = Color(light: 0xF5F6FA, dark: 0x0E131D)
    static let card = Color(light: 0xFFFFFF, dark: 0x161D2A)
    static let cardAlt = Color(light: 0xEEF1F7, dark: 0x18202D)
    static let track = Color(light: 0xE0E4EE, dark: 0x283243)
    static let waitCard = Color(light: 0xFFE9A8, dark: 0x4A3B00)
    static let onWaitCard = Color(light: 0x2B2000, dark: 0xFFE9A8)
    static let primaryContainer = Color(light: 0xDCE4FF, dark: 0x233A7A)
    static let onPrimaryContainer = Color(light: 0x0B2370, dark: 0xDDE5FF)
}

func levelColor(_ level: Level?) -> Color { level == .yellow ? Palette.yellow : Palette.red }

struct CardStyle: ViewModifier {
    var color: Color = Palette.card
    var radius: CGFloat = 20

    func body(content: Content) -> some View {
        content
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(color, in: RoundedRectangle(cornerRadius: radius, style: .continuous))
    }
}

extension View {
    func card(_ color: Color = Palette.card, radius: CGFloat = 20) -> some View {
        modifier(CardStyle(color: color, radius: radius))
    }
}

struct ScreenHeader: View {
    let title: String
    var subtitle: String? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.system(size: 30, weight: .bold))
            if let s = subtitle {
                Text(s).font(.subheadline).foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.top, 12)
        .padding(.bottom, 8)
    }
}

struct Dot: View {
    let color: Color
    var size: CGFloat = 12

    var body: some View {
        Circle().fill(color).frame(width: size, height: size)
    }
}
