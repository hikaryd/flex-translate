import SwiftUI

// Тёмные дизайн-токены Flex Translate, портированы из системы aquacard.
// Значения — КАНОНИЧЕСКИЕ hex из единого источника правды на Android,
// переведены в доли компонент sRGB (канал / 255) без округлений.
// Только тёмная тема, dynamic color выключен, глубина — через слои поверхностей (никогда не тени).
enum FlexTheme {
    // Графитовые слои поверхностей
    static let background = Color(red: 14.0 / 255.0, green: 15.0 / 255.0, blue: 17.0 / 255.0) // #0E0F11
    static let surface = Color(red: 22.0 / 255.0, green: 24.0 / 255.0, blue: 28.0 / 255.0) // #16181C
    static let elevated = Color(red: 30.0 / 255.0, green: 33.0 / 255.0, blue: 38.0 / 255.0) // #1E2126

    // Акцент — холодный индиго (Accent / OnAccentContainer)
    static let primary = Color(red: 124.0 / 255.0, green: 156.0 / 255.0, blue: 255.0 / 255.0) // #7C9CFF
    static let secondary = Color(red: 201.0 / 255.0, green: 213.0 / 255.0, blue: 255.0 / 255.0) // #C9D5FF

    // Text
    static let text = Color(red: 230.0 / 255.0, green: 232.0 / 255.0, blue: 235.0 / 255.0) // #E6E8EB
    static let mutedText = Color(red: 155.0 / 255.0, green: 161.0 / 255.0, blue: 168.0 / 255.0) // #9BA1A8

    // Ошибка (приглушённая)
    static let danger = Color(red: 255.0 / 255.0, green: 107.0 / 255.0, blue: 107.0 / 255.0) // #FF6B6B

    // Текст/иконки поверх акцентной заливки (OnPrimary)
    static let onAccent = Color(red: 11.0 / 255.0, green: 16.0 / 255.0, blue: 32.0 / 255.0) // #0B1020

    static let cardRadius: CGFloat = 18
}

extension View {
    // Панель-поверхность: отступы + фон surface + сглаженные скруглённые углы.
    func panel(padding: CGFloat = 16) -> some View {
        self
            .padding(padding)
            .background(FlexTheme.surface)
            .clipShape(RoundedRectangle(cornerRadius: FlexTheme.cardRadius, style: .continuous))
    }

    // Приподнятая поверхность для контента, который лежит поверх панели.
    func profileSurface(padding: CGFloat = 16) -> some View {
        self
            .padding(padding)
            .background(FlexTheme.elevated)
            .clipShape(RoundedRectangle(cornerRadius: FlexTheme.cardRadius, style: .continuous))
    }
}
