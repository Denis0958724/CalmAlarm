import SwiftUI

struct RootView: View {
    @EnvironmentObject private var model: AppModel
    @State private var tab = 0

    var body: some View {
        TabView(selection: $tab) {
            AlarmTab(onPickRegion: { tab = 2 })
                .tabItem { Label("Будильник", systemImage: "alarm") }
                .tag(0)
            ChanceTab(onPickRegion: { tab = 2 })
                .tabItem { Label("Ймовірність", systemImage: "gauge.with.dots.needle.33percent") }
                .tag(1)
            RegionTab(onSaved: { tab = 0 })
                .tabItem { Label("Регіон", systemImage: "mappin.and.ellipse") }
                .tag(2)
        }
        .onAppear {
            if model.region == nil { tab = 2 }
        }
        .overlay(alignment: .bottom) {
            if let t = model.toast {
                Text(t)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 18)
                    .padding(.vertical, 12)
                    .background(Color.black.opacity(0.8), in: Capsule())
                    .padding(.bottom, 96)
                    .transition(.opacity)
            }
        }
        .animation(.easeInOut(duration: 0.2), value: model.toast)
        .task {
            // Поки застосунок відкритий - щохвилини оновлюємо стан тривоги і перевіряємо будильники.
            while !Task.isCancelled {
                await model.refreshStatus()
                await model.reconcile(reason: "timer")
                try? await Task.sleep(nanoseconds: 60_000_000_000)
            }
        }
    }
}

struct RegionStatusCard: View {
    let region: Region?
    let status: RegionStatus?
    let failed: Bool
    var onRefresh: () -> Void
    var onPick: () -> Void

    var body: some View {
        if let region = region {
            let d = describe()
            HStack(spacing: 14) {
                Dot(color: d.color, size: 12)
                VStack(alignment: .leading, spacing: 2) {
                    Text(d.text).font(.headline)
                    Text(region.label)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                Spacer(minLength: 0)
                Button(action: onRefresh) {
                    Image(systemName: "arrow.clockwise")
                        .font(.body.weight(.semibold))
                        .frame(width: 40, height: 40)
                }
                .buttonStyle(.plain)
                .foregroundStyle(.secondary)
            }
            .padding(.leading, 18)
            .padding(.vertical, 12)
            .padding(.trailing, 6)
            .card(d.color.opacity(0.13))
        } else {
            VStack(alignment: .leading, spacing: 6) {
                Text("Спершу виберіть свій регіон").font(.headline)
                Text("Тоді будильник знатиме, чи є тривога саме у вас.").font(.subheadline)
                Button("Вибрати регіон", action: onPick)
                    .buttonStyle(.borderedProminent)
                    .padding(.top, 6)
            }
            .foregroundStyle(Palette.onPrimaryContainer)
            .padding(20)
            .card(Palette.primaryContainer)
        }
    }

    private func describe() -> (color: Color, text: String) {
        guard let s = status else {
            return (Color.gray, failed ? "Немає зв'язку з картою тривог" : "Перевіряю тривогу…")
        }
        if s.active {
            var t = s.level == .yellow ? "Жовта тривога" : "Червона тривога"
            if let since = s.since { t += " з \(hm(since))" }
            return (levelColor(s.level), t)
        }
        if s.partial { return (Palette.orange, "Тривога в частині області") }
        return (Palette.green, "Тривоги немає")
    }
}
