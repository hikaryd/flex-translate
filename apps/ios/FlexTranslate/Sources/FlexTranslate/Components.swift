import SwiftUI

// Общие тёмные UI-примитивы (aquacard) для оболочки WS1.
// Глубина только за счёт слоёв поверхностей — без теней и градиентов (см. дизайн-токены).

// Семантическая палитра статусов (взята из палитры сложности aquacard).
// Точные канал / 255 из канонических hex — без округлений.
enum FlexStatus {
    static let green = Color(red: 34.0 / 255.0, green: 197.0 / 255.0, blue: 94.0 / 255.0) // #22C55E ok/готово
    static let amber = Color(red: 245.0 / 255.0, green: 158.0 / 255.0, blue: 11.0 / 255.0) // #F59E0B предупреждение/гейт
    static let red = Color(red: 239.0 / 255.0, green: 68.0 / 255.0, blue: 68.0 / 255.0) // #EF4444 не поддерживается/ошибка
    static let info = FlexTheme.primary // #7C9CFF облако/инфо
}

// Бейдж-пилюля — подкрашенный фон (alpha 0.16) + цветной текст, радиус 5.
struct Badge: View {
    let text: String
    var color: Color = FlexTheme.primary
    var monospaced: Bool = false

    var body: some View {
        Text(text)
            .font(monospaced ? .system(size: 11, weight: .medium, design: .monospaced) : .system(size: 11, weight: .medium))
            .foregroundStyle(color)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(color.opacity(0.16))
            .clipShape(RoundedRectangle(cornerRadius: 5, style: .continuous))
            .accessibilityLabel(Text(text))
    }
}

// Карточка-поверхность с заголовком. Дети складываются вертикально, по левому краю.
struct SectionCard<Content: View>: View {
    let title: String?
    var subtitle: String?
    @ViewBuilder var content: () -> Content

    init(title: String? = nil, subtitle: String? = nil, @ViewBuilder content: @escaping () -> Content) {
        self.title = title
        self.subtitle = subtitle
        self.content = content
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let title {
                Text(title)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(FlexTheme.text)
            }
            if let subtitle {
                Text(subtitle)
                    .font(.system(size: 13))
                    .foregroundStyle(FlexTheme.mutedText)
            }
            content()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .panel()
    }
}

// Моноширинная строка ключ/значение для диагностики и вывода данных.
// `pending` рисует значение приглушённым тире — чтобы не подделывать отсутствующие данные.
struct StatRow: View {
    let key: String
    let value: String
    var pending: Bool = false

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Text(key)
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(FlexTheme.mutedText)
            Spacer(minLength: 8)
            Text(pending ? "—" : value)
                .font(.system(size: 13, weight: .regular, design: .monospaced))
                .foregroundStyle(pending ? FlexTheme.mutedText : FlexTheme.text)
                .multilineTextAlignment(.trailing)
        }
        .frame(maxWidth: .infinity)
    }
}

// Глобальный честный demo-баннер — закреплён сверху на каждой вкладке.
// Берёт текст из AppStrings, чтобы локализоваться при смене языка.
struct DemoBanner: View {
    @EnvironmentObject private var appStrings: AppStrings

    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.system(size: 11, weight: .semibold))
            Text(appStrings.current.demoBanner)
                .font(.system(size: 11, weight: .medium))
        }
        .foregroundStyle(FlexStatus.amber)
        .padding(.horizontal, 10)
        .padding(.vertical, 5)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(FlexStatus.amber.opacity(0.16))
        .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
        .accessibilityIdentifier("demo.banner")
    }
}

// Общий скролл-контейнер: тёмный фон, demo-баннер сверху, контент с отступами.
struct TabScaffold<Content: View>: View {
    let title: String
    @ViewBuilder var content: () -> Content

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                DemoBanner()
                content()
            }
            .padding(16)
        }
        .background(FlexTheme.background.ignoresSafeArea())
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
    }
}
