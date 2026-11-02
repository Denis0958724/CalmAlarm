import SwiftUI

private func chanceColor(_ p: Int) -> Color {
    if p < 15 { return Palette.green }
    if p < 40 { return Palette.yellow }
    if p < 70 { return Palette.orange }
    return Palette.red
}

private func chanceLabel(_ p: Int) -> String {
    if p < 15 { return "Низька" }
    if p < 40 { return "Помірна" }
    if p < 70 { return "Висока" }
    return "Дуже висока"
}

private struct ChanceKey: Equatable {
    var region: Region?
    var refresh: Int
}

struct ChanceTab: View {
    @EnvironmentObject private var model: AppModel
    var onPickRegion: () -> Void

    @State private var result: ChanceResult?
    @State private var resultRegion: Region?
    @State private var loading = false
    @State private var failed = false
    @State private var refresh = 0
    @State private var showPosts = false

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                if let region = model.region {
                    ScreenHeader(title: "Ймовірність тривоги", subtitle: region.label)
                    content
                } else {
                    ScreenHeader(title: "Ймовірність тривоги")
                    RegionStatusCard(region: nil, status: nil, failed: false, onRefresh: {}, onPick: onPickRegion)
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 24)
        }
        .background(ScreenBackground())
        .refreshable { await load() }
        .task(id: ChanceKey(region: model.region, refresh: refresh)) {
            if resultRegion != model.region { result = nil }
            while !Task.isCancelled {
                await load()
                try? await Task.sleep(nanoseconds: 120_000_000_000)
            }
        }
    }

    private func load() async {
        guard let region = model.region else { return }
        loading = true
        let r = try? await Chance.compute(region)
        loading = false
        if let r = r {
            result = r
            resultRegion = region
            failed = false
        } else {
            failed = true
        }
    }

    @ViewBuilder
    private var content: some View {
        if let r = result {
            let now = r.alertNow
            let color = now != nil ? levelColor(now?.level) : chanceColor(r.percent)
            VStack(spacing: 6) {
                ChanceGauge(
                    fraction: Double(r.percent) / 100,
                    alert: now != nil,
                    color: color,
                    center: "\(r.percent)%",
                    caption: now != nil ? "Тривога вже триває" : chanceLabel(r.percent)
                )
                Text(alertText(now) ?? "Шанс, що тривогу оголосять у найближчі 30 хвилин")
                    .font(.body)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 20)
                if now == nil {
                    Text("Зазвичай для вашого місця: \(r.usualPercent)%")
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(.secondary)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 5)
                        .background(Color.secondary.opacity(0.12), in: Capsule())
                        .padding(.top, 4)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 18)
            .glassCard(tint: color)

            mapCard(r)
                .padding(.top, 14)

            if !r.reasons.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Чому так").font(.headline)
                    ForEach(r.reasons, id: \.self) { reason in
                        HStack(alignment: .firstTextBaseline, spacing: 8) {
                            Text("•").fontWeight(.bold).foregroundStyle(color)
                            Text(reason).font(.subheadline)
                        }
                    }
                }
                .padding(18)
                .glassCard()
                .padding(.top, 14)
            }

            if !r.posts.isEmpty {
                Button {
                    withAnimation { showPosts.toggle() }
                } label: {
                    HStack(spacing: 4) {
                        Text(showPosts ? "Сховати повідомлення моніторів" : "Що пишуть монітори")
                        Image(systemName: showPosts ? "chevron.up" : "chevron.down")
                    }
                    .font(.subheadline.weight(.semibold))
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.vertical, 12)
                if showPosts {
                    VStack(spacing: 8) {
                        ForEach(r.posts) { p in
                            VStack(alignment: .leading, spacing: 2) {
                                Text("\(p.channel) · \(hm(p.time))")
                                    .font(.caption.weight(.medium))
                                    .foregroundStyle(Palette.accent)
                                Text(p.text).font(.subheadline).lineLimit(4)
                            }
                            .padding(14)
                            .glassCard(radius: 18)
                        }
                    }
                }
            }

            HStack {
                Text(loading ? "Оновлюю…" : "Оновлено о \(hm(r.time))")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                Spacer()
                Button {
                    refresh += 1
                } label: {
                    Image(systemName: "arrow.clockwise").frame(width: 40, height: 40)
                }
                .disabled(loading)
            }
            .padding(.top, 12)
            Text("Оцінку розраховано за історією офіційних тривог по районах, поточною картою тривог і повідомленнями Повітряних сил. Це прогноз, а не тривога - завжди дійте за офіційним сигналом.")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
        } else if failed && !loading {
            VStack(spacing: 12) {
                Text("Не вдалося отримати дані.\nПеревірте інтернет.")
                    .multilineTextAlignment(.center)
                Button("Спробувати ще") { refresh += 1 }
                    .buttonStyle(.borderedProminent)
            }
            .padding(.vertical, 64)
        } else {
            ProgressView()
                .controlSize(.large)
                .padding(.vertical, 80)
        }
    }

    private func mapCard(_ r: ChanceResult) -> some View {
        let near = threatsNear(model.region ?? Region(oblastId: "", oblastName: ""), r.threats)
        let parts = (near.0 > 0 ? ["шахеди: \(near.0)"] : []) + (near.1 > 0 ? ["ракети: \(near.1)"] : [])
        let chip = parts.isEmpty ? "Загроз поруч не видно" : parts.joined(separator: " · ").capitalizedFirst
        let chipColor = parts.isEmpty ? Palette.green : Palette.orange
        return VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("Навколо вас").font(.headline)
                Spacer()
                Text(chip)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(chipColor)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 4)
                    .background(chipColor.opacity(0.16), in: Capsule())
            }
            .padding(.horizontal, 4)
            if let region = model.region {
                RegionMapView(region: region, alerts: r.alerts, threats: r.threats)
            }
            MapLegend().padding(.horizontal, 4)
            Text("Тривоги - з офіційної карти. Шахеди й ракети - за повідомленнями Повітряних сил за останні 30 хв, місце приблизне (район або область).")
                .font(.caption)
                .foregroundStyle(.secondary)
                .padding(.horizontal, 4)
            Text(UaGeo.shared?.attribution ?? "")
                .font(.system(size: 10))
                .foregroundStyle(.tertiary)
                .padding(.horizontal, 4)
        }
        .padding(14)
        .glassCard()
    }

    private func alertText(_ s: RegionStatus?) -> String? {
        guard let s = s else { return nil }
        var t = s.level == .yellow ? "Жовта тривога" : "Червона тривога"
        if let since = s.since { t += " з \(hm(since))" }
        return t
    }
}

private struct ChanceGauge: View {
    let fraction: Double
    let alert: Bool
    let color: Color
    let center: String
    let caption: String
    @State private var shown: Double = 0

    private var gradient: AngularGradient {
        let colors: [Color] = alert ? [color, color] : [Palette.green, Palette.yellow, Palette.orange, Palette.red, Palette.red]
        return AngularGradient(gradient: Gradient(colors: colors), center: .center, startAngle: .degrees(0), endAngle: .degrees(360))
    }

    var body: some View {
        let value = alert ? 1 : min(1, max(0.015, shown))
        ZStack {
            Circle()
                .trim(from: 0, to: 0.75)
                .stroke(Color.primary.opacity(0.08), style: StrokeStyle(lineWidth: 20, lineCap: .round))
                .rotationEffect(.degrees(135))
            Circle()
                .trim(from: 0, to: 0.75 * value)
                .stroke(gradient, style: StrokeStyle(lineWidth: 20, lineCap: .round))
                .rotationEffect(.degrees(135))
                .blur(radius: 10)
                .opacity(0.5)
            Circle()
                .trim(from: 0, to: 0.75 * value)
                .stroke(gradient, style: StrokeStyle(lineWidth: 20, lineCap: .round))
                .rotationEffect(.degrees(135))
            VStack(spacing: 0) {
                Text(center)
                    .font(.system(size: 50, weight: .bold))
                    .foregroundStyle(color)
                    .monospacedDigit()
                    .minimumScaleFactor(0.7)
                    .lineLimit(1)
                Text(caption)
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
            .frame(width: 140)
        }
        .frame(width: 196, height: 196)
        .padding(18)
        .onAppear {
            withAnimation(.easeOut(duration: 1.0)) { shown = fraction }
        }
        .onChange(of: fraction) { _, v in
            withAnimation(.easeOut(duration: 1.0)) { shown = v }
        }
    }
}
