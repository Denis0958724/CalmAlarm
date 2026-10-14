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
        .background(Palette.background)
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
            ChanceGauge(
                fraction: Double(r.percent) / 100,
                color: color,
                center: "\(r.percent)%",
                caption: now != nil ? "Тривога вже триває" : chanceLabel(r.percent)
            )
            .padding(.top, 8)
            Text(alertText(now) ?? "Шанс, що тривогу оголосять найближчі 30 хвилин")
                .font(.body)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            if now == nil {
                Text("Зазвичай для вашого місця: \(r.usualPercent)%")
                    .font(.subheadline)
                    .foregroundStyle(.secondary.opacity(0.8))
                    .padding(.top, 2)
            }

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
                .card()
                .padding(.top, 20)
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
                            .card(Palette.cardAlt, radius: 16)
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

    private func alertText(_ s: RegionStatus?) -> String? {
        guard let s = s else { return nil }
        var t = s.level == .yellow ? "Жовта тривога" : "Червона тривога"
        if let since = s.since { t += " з \(hm(since))" }
        return t
    }
}

private struct ChanceGauge: View {
    let fraction: Double
    let color: Color
    let center: String
    let caption: String
    @State private var shown: Double = 0

    var body: some View {
        ZStack {
            Circle()
                .trim(from: 0, to: 0.75)
                .stroke(Palette.track, style: StrokeStyle(lineWidth: 22, lineCap: .round))
                .rotationEffect(.degrees(135))
            Circle()
                .trim(from: 0, to: 0.75 * min(1, max(0, shown)))
                .stroke(color, style: StrokeStyle(lineWidth: 22, lineCap: .round))
                .rotationEffect(.degrees(135))
            VStack(spacing: 0) {
                Text(center)
                    .font(.system(size: 56, weight: .semibold))
                    .foregroundStyle(color)
                Text(caption).font(.headline)
            }
        }
        .frame(width: 212, height: 212)
        .padding(14)
        .onAppear {
            withAnimation(.easeOut(duration: 0.9)) { shown = fraction }
        }
        .onChange(of: fraction) { _, v in
            withAnimation(.easeOut(duration: 0.9)) { shown = v }
        }
    }
}
