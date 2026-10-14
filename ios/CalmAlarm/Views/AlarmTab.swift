import SwiftUI
import AlarmKit
import UIKit

struct AlarmTab: View {
    @EnvironmentObject private var model: AppModel
    var onPickRegion: () -> Void

    @State private var editing: WakeAlarm?
    @State private var editingIsNew = false

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            ScrollView {
                LazyVStack(spacing: 12) {
                    ScreenHeader(title: "Будильник", subtitle: "Не будить під час тривоги - дзвонить після відбою")
                    RegionStatusCard(
                        region: model.region,
                        status: model.status,
                        failed: model.statusFailed,
                        onRefresh: { Task { await model.refreshStatus() } },
                        onPick: onPickRegion
                    )
                    ForEach(model.waitings.values.sorted { $0.since < $1.since }, id: \.alarmId) { w in
                        WaitingCard(w: w, alarm: model.alarms.first { $0.id == w.alarmId }) {
                            Task { await model.cancelWait(w.alarmId) }
                        }
                    }
                    if model.authState == .denied {
                        PermissionCard()
                    }
                    if model.alarms.isEmpty {
                        EmptyAlarms()
                    }
                    ForEach(model.alarms) { a in
                        AlarmCard(
                            alarm: a,
                            onTap: {
                                editingIsNew = false
                                editing = a
                            },
                            onToggle: { on in Task { await model.toggle(a, on: on) } }
                        )
                    }
                    if !model.alarms.isEmpty {
                        Text("Порада: не закривайте CalmAlarm свайпом у перемикачі програм. Інакше iPhone не отримає сигнал про тривогу, і будильник задзвонить як звичайний.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .padding(.horizontal, 4)
                            .padding(.top, 4)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 100)
            }
            .background(Palette.background)

            Button {
                editingIsNew = true
                editing = WakeAlarm(id: -1, hour: 7, minute: 0, days: WEEKDAYS)
            } label: {
                Image(systemName: "plus")
                    .font(.title2.weight(.semibold))
                    .foregroundStyle(.white)
                    .frame(width: 60, height: 60)
                    .background(Palette.blue, in: Circle())
                    .shadow(color: .black.opacity(0.18), radius: 8, y: 4)
            }
            .accessibilityLabel("Додати будильник")
            .padding(20)
        }
        .onAppear {
            if AppModel.demo && UserDefaults.standard.bool(forKey: "demoEdit"), editing == nil {
                editingIsNew = false
                editing = model.alarms.first
            }
        }
        .sheet(item: $editing) { e in
            AlarmEditor(
                initial: e,
                isNew: editingIsNew,
                onSave: { a in
                    let isNew = editingIsNew
                    editing = nil
                    Task { await model.save(a, isNew: isNew) }
                },
                onDelete: {
                    editing = nil
                    Task { await model.delete(e) }
                }
            )
        }
    }
}

private struct WaitingCard: View {
    let w: Waiting
    let alarm: WakeAlarm?
    var onCancel: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("⏳ Будильник \(alarm?.timeText ?? "") чекає відбою").font(.headline)
            Text("Зараз \((w.level ?? .red).title) тривога. Задзвонить одразу після відбою, але не пізніше \(hm(w.deadline)). Якщо тривога триватиме довше - сьогодні не дзвонитиме.")
                .font(.subheadline)
            HStack {
                Spacer()
                Button("Не чекати", action: onCancel).font(.subheadline.weight(.semibold))
            }
        }
        .foregroundStyle(Palette.onWaitCard)
        .padding(18)
        .card(Palette.waitCard)
    }
}

private struct PermissionCard: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label("Будильники вимкнені", systemImage: "exclamationmark.triangle.fill")
                .font(.headline)
                .foregroundStyle(Palette.yellow)
            Text("Дозвольте CalmAlarm ставити будильники: Параметри → CalmAlarm → Будильники й таймери.")
                .font(.subheadline)
            Button("Відкрити Параметри") {
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    UIApplication.shared.open(url)
                }
            }
            .buttonStyle(.bordered)
        }
        .padding(16)
        .card()
    }
}

private struct EmptyAlarms: View {
    var body: some View {
        VStack(spacing: 6) {
            Image(systemName: "alarm")
                .font(.system(size: 52))
                .foregroundStyle(.secondary.opacity(0.6))
                .padding(.bottom, 6)
            Text("Ще немає будильників").font(.headline)
            Text("Натисніть «+», щоб додати перший").font(.subheadline).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 48)
    }
}

private struct AlarmCard: View {
    let alarm: WakeAlarm
    var onTap: () -> Void
    var onToggle: (Bool) -> Void

    var body: some View {
        let dim = alarm.enabled ? 1.0 : 0.45
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 2) {
                Text(alarm.timeText)
                    .font(.system(size: 44, weight: .light))
                    .monospacedDigit()
                    .foregroundStyle(.primary.opacity(dim))
                Text(daysText(alarm.days))
                    .font(.subheadline)
                    .foregroundStyle(.secondary.opacity(dim))
                HStack(spacing: 4) {
                    if alarm.waitsForAlerts {
                        if alarm.skipRed { Dot(color: Palette.red.opacity(dim), size: 8) }
                        if alarm.skipYellow { Dot(color: Palette.yellow.opacity(dim), size: 8) }
                        Text("Чекає відбою · до \(waitLabel(alarm.maxWaitMin))")
                            .padding(.leading, 2)
                    } else {
                        Text("Дзвонить навіть під час тривоги")
                    }
                }
                .font(.caption.weight(.medium))
                .foregroundStyle(.secondary.opacity(dim))
                .padding(.top, 4)
            }
            Spacer()
            Toggle("", isOn: Binding(get: { alarm.enabled }, set: { onToggle($0) }))
                .labelsHidden()
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 16)
        .card(radius: 24)
        .contentShape(Rectangle())
        .onTapGesture(perform: onTap)
    }
}
