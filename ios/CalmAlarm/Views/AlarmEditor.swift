import SwiftUI
import AVFoundation
import AudioToolbox

struct AlarmEditor: View {
    let initial: WakeAlarm
    let isNew: Bool
    var onSave: (WakeAlarm) -> Void
    var onDelete: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var time: Date
    @State private var days: Set<Int>
    @State private var tone: Int
    @State private var red: Bool
    @State private var yellow: Bool
    @State private var wait: Int
    @State private var snooze: Int
    @State private var confirmDelete = false

    init(initial: WakeAlarm, isNew: Bool, onSave: @escaping (WakeAlarm) -> Void, onDelete: @escaping () -> Void) {
        self.initial = initial
        self.isNew = isNew
        self.onSave = onSave
        self.onDelete = onDelete
        let t = Calendar.current.date(bySettingHour: initial.hour, minute: initial.minute, second: 0, of: Date()) ?? Date()
        _time = State(initialValue: t)
        _days = State(initialValue: Set(initial.days))
        _tone = State(initialValue: initial.tone)
        _red = State(initialValue: initial.skipRed)
        _yellow = State(initialValue: initial.skipYellow)
        _wait = State(initialValue: initial.maxWaitMin)
        _snooze = State(initialValue: initial.snoozeMin)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    DatePicker("Час", selection: $time, displayedComponents: .hourAndMinute)
                        .datePickerStyle(.wheel)
                        .labelsHidden()
                        .frame(maxWidth: .infinity)
                        .environment(\.locale, Locale(identifier: "uk_UA"))
                }

                Section {
                    HStack(spacing: 6) {
                        ForEach(1...7, id: \.self) { d in
                            let on = days.contains(d)
                            Button {
                                if on { days.remove(d) } else { days.insert(d) }
                            } label: {
                                Text(DAY_SHORT[d - 1])
                                    .font(.subheadline.weight(.semibold))
                                    .frame(maxWidth: .infinity)
                                    .frame(height: 40)
                                    .background(on ? Palette.blue : Palette.cardAlt, in: Circle())
                                    .foregroundStyle(on ? Color.white : Color.primary)
                            }
                            .buttonStyle(.borderless)
                        }
                    }
                    HStack(spacing: 8) {
                        preset("Будні", Set(WEEKDAYS))
                        preset("Щодня", Set(ALL_DAYS))
                        preset("Один раз", [])
                    }
                } header: {
                    Text("Дні")
                } footer: {
                    Text(days.isEmpty ? "Спрацює один раз, у найближчий такий час" : "Повторюється: \(daysText(Array(days)))")
                }

                Section("Мелодія") {
                    ForEach(Tones.names.indices, id: \.self) { i in
                        Button {
                            tone = i
                            TonePreview.shared.play(i)
                        } label: {
                            HStack {
                                Text(Tones.names[i]).foregroundStyle(Color.primary)
                                Spacer()
                                if tone == i {
                                    Image(systemName: "checkmark").foregroundStyle(Palette.accent)
                                }
                            }
                        }
                    }
                }

                Section {
                    Toggle(isOn: $red) {
                        levelLabel(Palette.red, "Червона тривога", "ракети, масована атака")
                    }
                    Toggle(isOn: $yellow) {
                        levelLabel(Palette.yellow, "Жовта тривога", "дрони")
                    }
                } header: {
                    Text("Не будити, поки триває")
                } footer: {
                    Text(red || yellow
                        ? "Будильник задзвонить одразу після відбою. Якщо рівень тривоги не вказано - вважаємо її червоною."
                        : "Будильник дзвонитиме як звичайний, навіть під час тривоги.")
                }

                if red || yellow {
                    Section {
                        sliderRow(
                            value: $wait, range: WAIT_MIN...WAIT_MAX, step: WAIT_STEP,
                            label: waitLabel(wait)
                        )
                    } header: {
                        Text("Якщо тривога затягнеться")
                    } footer: {
                        Text("Не дзвонити зовсім, якщо тривога триває довше ніж \(waitLabel(wait)).")
                    }
                }

                Section {
                    sliderRow(value: $snooze, range: SNOOZE_LOW...SNOOZE_HIGH, step: 1, label: "\(snooze) хв")
                } header: {
                    Text("Кнопка «Ще трохи»")
                } footer: {
                    Text("На скільки відкласти будильник, коли він дзвонить.")
                }

                if !isNew {
                    Section {
                        Button("Видалити будильник", role: .destructive) { confirmDelete = true }
                            .frame(maxWidth: .infinity)
                    }
                }
            }
            .navigationTitle(isNew ? "Новий будильник" : "Будильник")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Закрити") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Зберегти") { save() }.fontWeight(.semibold)
                }
            }
            .confirmationDialog("Видалити будильник?", isPresented: $confirmDelete, titleVisibility: .visible) {
                Button("Видалити", role: .destructive, action: onDelete)
                Button("Скасувати", role: .cancel) {}
            }
        }
        .onDisappear { TonePreview.shared.stop() }
    }

    private func preset(_ title: String, _ value: Set<Int>) -> some View {
        let on = days == value
        return Button(title) { days = value }
            .font(.footnote.weight(.semibold))
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(on ? Palette.primaryContainer : Palette.cardAlt, in: Capsule())
            .foregroundStyle(on ? Palette.onPrimaryContainer : Color.primary)
            .buttonStyle(.borderless)
    }

    private func levelLabel(_ color: Color, _ title: String, _ sub: String) -> some View {
        HStack(spacing: 12) {
            Dot(color: color, size: 12)
            VStack(alignment: .leading, spacing: 1) {
                Text(title)
                Text(sub).font(.caption).foregroundStyle(.secondary)
            }
        }
    }

    private func sliderRow(value: Binding<Int>, range: ClosedRange<Int>, step: Int, label: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label).font(.title3.weight(.semibold)).foregroundStyle(Palette.accent)
            Slider(
                value: Binding<Double>(
                    get: { Double(value.wrappedValue) },
                    set: { v in
                        let snapped = Int((v / Double(step)).rounded()) * step
                        value.wrappedValue = min(range.upperBound, max(range.lowerBound, snapped))
                    }
                ),
                in: Double(range.lowerBound)...Double(range.upperBound)
            )
        }
        .padding(.vertical, 4)
    }

    private func save() {
        let c = Calendar.current.dateComponents([.hour, .minute], from: time)
        var a = initial
        a.hour = c.hour ?? initial.hour
        a.minute = c.minute ?? initial.minute
        a.days = days.sorted()
        a.tone = tone
        a.skipRed = red
        a.skipYellow = yellow
        a.maxWaitMin = wait
        a.snoozeMin = snooze
        a.enabled = true
        TonePreview.shared.stop()
        onSave(a)
    }
}

/// Коротке прослуховування мелодії в редакторі.
final class TonePreview {
    static let shared = TonePreview()
    private var player: AVAudioPlayer?
    private var stopWork: DispatchWorkItem?

    func play(_ i: Int) {
        stop()
        guard let name = Tones.file(i), let url = Bundle.main.url(forResource: name, withExtension: "caf") else {
            AudioServicesPlayAlertSound(SystemSoundID(1005))
            return
        }
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
        try? AVAudioSession.sharedInstance().setActive(true)
        player = try? AVAudioPlayer(contentsOf: url)
        player?.play()
        let work = DispatchWorkItem { [weak self] in self?.stop() }
        stopWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 4, execute: work)
    }

    func stop() {
        stopWork?.cancel()
        stopWork = nil
        player?.stop()
        player = nil
    }
}
