import Foundation
import SwiftUI
import AlarmKit
import ActivityKit
import UserNotifications

/// Стан застосунку і вся логіка будильників.
///
/// На iPhone застосунок не може «прокинутися» саме в момент будильника, тому:
/// - кожен будильник - це системний будильник AlarmKit, який дзвонить завжди (навіть якщо щось зламалось);
/// - за кілька хвилин до будильника сервер надсилає тихий сигнал, якщо у вашому регіоні тривога;
///   телефон сам перевіряє карту тривог і переносить будильник;
/// - поки триває тривога, стоїть запасний будильник через 20 хв: якщо сигнал про відбій не дійде,
///   він просто задзвонить (краще прокинутися, ніж проспати). Сервер сигналить кожні 10 хв, тож запас - 10 хв.
@MainActor
final class AppModel: ObservableObject {
    static let shared = AppModel()

    @Published private(set) var alarms: [WakeAlarm] = []
    @Published private(set) var region: Region?
    @Published private(set) var waitings: [Int: Waiting] = [:]
    @Published var status: RegionStatus?
    @Published var statusFailed = false
    @Published var authState: AlarmManager.AuthorizationState = .notDetermined
    @Published var toast: String?

    /// Будильник → основний системний будильник AlarmKit.
    private var kitIds: [Int: UUID] = [:]
    private var pushToken: String?
    private var busy = false
    private var syncTask: Task<Void, Never>?

    /// Через скільки дзвонить запасний будильник, поки чекаємо відбою.
    static let checkpoint: TimeInterval = 20 * 60
    /// За скільки хвилин до будильника перевіряємо тривогу.
    static let lookahead: TimeInterval = 5 * 60

    enum Titles {
        static let normal = "Будильник"
        static let afterAlert = "Тривога закінчилась - можна вставати ☀️"
        static let fallback = "Будильник (не вдалося дочекатися сигналу про відбій)"
    }

    private let defaults = UserDefaults.standard

    /// Демо-режим для знімків екрана (запуск з аргументом -demo YES): без системних будильників і сервера.
    static let demo = UserDefaults.standard.bool(forKey: "demo")
    static let demoAlert = UserDefaults.standard.bool(forKey: "demoAlert")

    private init() {
        if AppModel.demo {
            seedDemo()
            return
        }
        load()
        authState = AlarmManager.shared.authorizationState
        pushToken = defaults.string(forKey: "push_token")
    }

    private func seedDemo() {
        alarms = [
            WakeAlarm(id: 1, hour: 7, minute: 0, days: WEEKDAYS),
            WakeAlarm(id: 2, hour: 9, minute: 30, days: [6, 7], tone: 1, skipYellow: false, maxWaitMin: 90),
            WakeAlarm(id: 3, hour: 6, minute: 15, days: [], enabled: false, tone: 3),
        ]
        region = Region(oblastId: "31", oblastName: "м. Київ")
        authState = .authorized
        if AppModel.demoAlert, let occ = Calendar.current.date(bySettingHour: 7, minute: 0, second: 0, of: Date()) {
            waitings = [1: Waiting(alarmId: 1, occ: occ, deadline: occ.addingTimeInterval(7200), since: occ, level: .red)]
        }
    }

    // MARK: - Збереження

    private func load() {
        alarms = decode([WakeAlarm].self, "alarms_v1") ?? []
        region = decode(Region.self, "region_v1")
        waitings = decode([Int: Waiting].self, "waitings_v1") ?? [:]
        kitIds = decode([Int: UUID].self, "kit_v1") ?? [:]
    }

    private func persist() {
        if AppModel.demo { return }
        encode(alarms, "alarms_v1")
        encode(waitings, "waitings_v1")
        encode(kitIds, "kit_v1")
        if let r = region { encode(r, "region_v1") } else { defaults.removeObject(forKey: "region_v1") }
    }

    private func decode<T: Decodable>(_ type: T.Type, _ key: String) -> T? {
        guard let data = defaults.data(forKey: key) else { return nil }
        return try? JSONDecoder().decode(type, from: data)
    }

    private func encode<T: Encodable>(_ value: T, _ key: String) {
        if let data = try? JSONEncoder().encode(value) { defaults.set(data, forKey: key) }
    }

    private func newId() -> Int { (alarms.map { $0.id }.max() ?? 0) + 1 }

    private func update(_ id: Int, _ change: (inout WakeAlarm) -> Void) {
        guard let i = alarms.firstIndex(where: { $0.id == id }) else { return }
        change(&alarms[i])
    }

    // MARK: - Дії користувача

    /// Зберігає будильник і повертає, коли він задзвонить.
    @discardableResult
    func save(_ draft: WakeAlarm, isNew: Bool) async -> Date? {
        var a = draft
        if isNew { a.id = newId() }
        await dropWaiting(a.id, restore: false)
        cancelKit(kitIds[a.id])
        kitIds[a.id] = nil
        if let i = alarms.firstIndex(where: { $0.id == a.id }) { alarms[i] = a } else { alarms.append(a) }
        alarms.sort { ($0.hour, $0.minute, $0.id) < ($1.hour, $1.minute, $1.id) }
        var at: Date?
        if a.enabled {
            if let id = await scheduleKit(a, baseSchedule(a), title: Titles.normal) {
                kitIds[a.id] = id
                at = nextOccurrence(a, after: Date())
            }
            askNotifications()
        }
        persist()
        scheduleSync()
        if let at = at { showToast("Задзвонить через \(untilText(at.timeIntervalSinceNow))") }
        return at
    }

    func toggle(_ a: WakeAlarm, on: Bool) async {
        var u = a
        u.enabled = on
        await save(u, isNew: false)
    }

    func delete(_ a: WakeAlarm) async {
        await dropWaiting(a.id, restore: false)
        cancelKit(kitIds[a.id])
        kitIds[a.id] = nil
        alarms.removeAll { $0.id == a.id }
        persist()
        scheduleSync()
    }

    /// Користувач вирішив не чекати відбою: будильник більше не переноситься сьогодні.
    func cancelWait(_ id: Int) async {
        guard let a = alarms.first(where: { $0.id == id }) else { return }
        await dropWaiting(id, restore: true, alarm: a)
        persist()
    }

    func setRegion(_ r: Region) {
        region = r
        status = nil
        statusFailed = false
        persist()
        scheduleSync()
        Task { await refreshStatus() }
    }

    func refreshStatus() async {
        if AppModel.demo {
            let since = Calendar.current.date(bySettingHour: 6, minute: 41, second: 0, of: Date())
            status = AppModel.demoAlert
                ? RegionStatus(active: true, level: .red, since: since, partial: false)
                : RegionStatus(active: false, level: nil, since: nil, partial: false)
            return
        }
        guard let r = region else { return }
        if let s = await AlertAPI.currentStatus(r, timeout: 7) {
            status = s
            statusFailed = false
        } else {
            statusFailed = true
        }
    }

    func requestAlarmAccess() async {
        _ = await ensureAuthorized()
    }

    private func showToast(_ text: String) {
        toast = text
        Task {
            try? await Task.sleep(nanoseconds: 2_500_000_000)
            if toast == text { toast = nil }
        }
    }

    // MARK: - AlarmKit

    private func ensureAuthorized() async -> Bool {
        let manager = AlarmManager.shared
        switch manager.authorizationState {
        case .authorized:
            authState = .authorized
            return true
        case .notDetermined:
            let s = (try? await manager.requestAuthorization()) ?? .denied
            authState = s
            return s == .authorized
        default:
            authState = manager.authorizationState
            return false
        }
    }

    private func baseSchedule(_ a: WakeAlarm) -> Alarm.Schedule {
        let time = Alarm.Schedule.Relative.Time(hour: a.hour, minute: a.minute)
        if a.days.isEmpty {
            return .relative(Alarm.Schedule.Relative(time: time, repeats: .never))
        }
        let all: [Locale.Weekday] = [.monday, .tuesday, .wednesday, .thursday, .friday, .saturday, .sunday]
        let days = a.days.sorted().compactMap { ($0 >= 1 && $0 <= 7) ? all[$0 - 1] : nil }
        return .relative(Alarm.Schedule.Relative(time: time, repeats: .weekly(days)))
    }

    private func makeAlert(_ title: String, snoozeMin: Int) -> AlarmPresentation.Alert {
        let snooze = AlarmButton(
            text: LocalizedStringResource(stringLiteral: "Ще \(snoozeMin) хв"),
            textColor: .white,
            systemImageName: "zzz"
        )
        let t = LocalizedStringResource(stringLiteral: title)
        if #available(iOS 26.1, *) {
            return AlarmPresentation.Alert(title: t, secondaryButton: snooze, secondaryButtonBehavior: .countdown)
        } else {
            let stop = AlarmButton(text: "Вимкнути", textColor: .white, systemImageName: "stop.fill")
            return AlarmPresentation.Alert(title: t, stopButton: stop, secondaryButton: snooze, secondaryButtonBehavior: .countdown)
        }
    }

    /// Ставить системний будильник. nil - якщо не вдалося (немає дозволу тощо).
    private func scheduleKit(_ a: WakeAlarm, _ schedule: Alarm.Schedule, title: String) async -> UUID? {
        if AppModel.demo { return nil }
        guard await ensureAuthorized() else { return nil }
        let id = UUID()
        let snoozeMin = max(SNOOZE_LOW, min(SNOOZE_HIGH, a.snoozeMin))
        let presentation = AlarmPresentation(
            alert: makeAlert(title, snoozeMin: snoozeMin),
            countdown: AlarmPresentation.Countdown(title: "Ще трохи поспати…", pauseButton: nil)
        )
        let attributes = AlarmAttributes<CalmMeta>(presentation: presentation, metadata: CalmMeta(), tintColor: Palette.blue)
        let sound: AlertConfiguration.AlertSound
        if let file = Tones.file(a.tone) {
            sound = .named("\(file).caf")
        } else {
            sound = .default
        }
        let config = AlarmManager.AlarmConfiguration<CalmMeta>(
            countdownDuration: Alarm.CountdownDuration(preAlert: nil, postAlert: TimeInterval(snoozeMin * 60)),
            schedule: schedule,
            attributes: attributes,
            stopIntent: CalmStopIntent(alarmID: id.uuidString),
            sound: sound
        )
        do {
            _ = try await AlarmManager.shared.schedule(id: id, configuration: config)
            return id
        } catch {
            return nil
        }
    }

    private func cancelKit(_ id: UUID?) {
        guard let id = id else { return }
        try? AlarmManager.shared.cancel(id: id)
    }

    /// Прибирає очікування; restore = повернути звичайний розклад будильника.
    private func dropWaiting(_ id: Int, restore: Bool, alarm: WakeAlarm? = nil) async {
        guard let w = waitings[id] else { return }
        cancelKit(w.overrideId)
        if !restore { cancelKit(w.safetyId) }
        removeNotification("wait-\(id)")
        if restore, let a = alarm {
            await finishWaiting(a, gaveUp: false)
        } else {
            waitings[id] = nil
        }
    }

    // MARK: - Головна перевірка

    /// Викликається при відкритті застосунку, щохвилини поки він відкритий,
    /// після тихого сигналу від сервера і коли вимикають будильник.
    func reconcile(reason: String) async {
        if busy || AppModel.demo { return }
        busy = true
        defer { busy = false }

        let now = Date()
        // Якщо не вдалося отримати список - нічого не вважаємо «зниклим».
        let alive: Set<UUID>? = (try? AlarmManager.shared.alarms).map { Set($0.map { $0.id }) }

        var fetched = false
        var cached: RegionStatus?
        func regionStatus() async -> RegionStatus? {
            if !fetched, let r = region {
                fetched = true
                cached = await AlertAPI.currentStatus(r, timeout: 6)
                if let c = cached {
                    status = c
                    statusFailed = false
                }
            }
            return cached
        }

        for a in alarms {
            if !a.enabled {
                if let w = waitings[a.id] {
                    cancelKit(w.overrideId)
                    cancelKit(w.safetyId)
                    waitings[a.id] = nil
                }
                if let b = kitIds[a.id] {
                    cancelKit(b)
                    kitIds[a.id] = nil
                }
                continue
            }

            if let w = waitings[a.id] {
                await handleWaiting(a, w, now: now, alive: alive, status: regionStatus)
                continue
            }

            if let b = kitIds[a.id], let alive = alive, !alive.contains(b) {
                kitIds[a.id] = nil
                if a.days.isEmpty {
                    // Одноразовий будильник уже продзвонив.
                    update(a.id) { $0.enabled = false }
                    continue
                }
            }
            if kitIds[a.id] == nil, let id = await scheduleKit(a, baseSchedule(a), title: Titles.normal) {
                kitIds[a.id] = id
            }

            // За кілька хвилин до будильника: чи не триває тривога?
            if a.waitsForAlerts, region != nil, let t = nextOccurrence(a, after: now),
               t.timeIntervalSince(now) <= AppModel.lookahead,
               let s = await regionStatus(), s.active, a.shouldWait(s.level) {
                await postpone(a, occ: t, now: now, status: s)
            }
        }
        persist()
    }

    private func postpone(_ a: WakeAlarm, occ: Date, now: Date, status s: RegionStatus) async {
        let at = max(occ, now).addingTimeInterval(AppModel.checkpoint)
        guard let oid = await scheduleKit(a, .fixed(at), title: Titles.fallback) else { return }
        var safety: UUID?
        if !a.days.isEmpty, let next = nextOccurrence(a, after: occ.addingTimeInterval(60)) {
            // Страховка: якщо застосунок довго не запуститься, наступного дня будильник однаково задзвонить.
            safety = await scheduleKit(a, .fixed(next), title: Titles.normal)
        }
        cancelKit(kitIds[a.id])
        kitIds[a.id] = nil
        let w = Waiting(
            alarmId: a.id,
            occ: occ,
            deadline: occ.addingTimeInterval(TimeInterval(a.maxWaitMin * 60)),
            since: now,
            level: s.level,
            overrideId: oid,
            overrideAt: at,
            safetyId: safety
        )
        waitings[a.id] = w
        notify(
            id: "wait-\(a.id)",
            title: "⏳ Будильник \(a.timeText) чекає відбою",
            body: "Зараз \((s.level ?? .red).title) тривога. Задзвонить одразу після відбою, але не пізніше \(hm(w.deadline))."
        )
    }

    private func handleWaiting(
        _ a: WakeAlarm,
        _ w: Waiting,
        now: Date,
        alive: Set<UUID>?,
        status: @MainActor () async -> RegionStatus?
    ) async {
        // Запасний будильник уже продзвонив (або його вимкнули).
        if let o = w.overrideId, let alive = alive, !alive.contains(o) {
            await finishWaiting(a, gaveUp: false)
            return
        }
        // Немає інтернету - нічого не чіпаємо: запасний будильник задзвонить сам.
        guard let s = await status() else { return }

        if s.active && a.shouldWait(s.level) {
            if now >= w.deadline {
                cancelKit(w.overrideId)
                await finishWaiting(a, gaveUp: true)
                return
            }
            var nw = w
            nw.level = s.level
            let newAt = now.addingTimeInterval(AppModel.checkpoint)
            if w.overrideId == nil || (w.overrideAt ?? .distantPast) < newAt.addingTimeInterval(-90) {
                if let id = await scheduleKit(a, .fixed(newAt), title: Titles.fallback) {
                    cancelKit(w.overrideId)
                    nw.overrideId = id
                    nw.overrideAt = newAt
                }
            }
            waitings[a.id] = nw
            return
        }

        // Тривоги немає.
        if now < w.occ {
            // Відбій ще до часу будильника - повертаємо звичайний розклад.
            if let id = await scheduleKit(a, baseSchedule(a), title: Titles.normal) {
                cancelKit(w.overrideId)
                cancelKit(w.safetyId)
                kitIds[a.id] = id
                waitings[a.id] = nil
                removeNotification("wait-\(a.id)")
            }
        } else if await scheduleKit(a, .fixed(now.addingTimeInterval(5)), title: Titles.afterAlert) != nil {
            cancelKit(w.overrideId)
            await finishWaiting(a, gaveUp: false)
        }
    }

    private func finishWaiting(_ a: WakeAlarm, gaveUp: Bool) async {
        let w = waitings[a.id]
        waitings[a.id] = nil
        removeNotification("wait-\(a.id)")
        if a.days.isEmpty {
            update(a.id) { $0.enabled = false }
            kitIds[a.id] = nil
            cancelKit(w?.safetyId)
        } else {
            if kitIds[a.id] == nil, let id = await scheduleKit(a, baseSchedule(a), title: Titles.normal) {
                kitIds[a.id] = id
            }
            // Страховку прибираємо лише тоді, коли звичайний розклад точно повернувся.
            if kitIds[a.id] != nil { cancelKit(w?.safetyId) }
        }
        if gaveUp {
            notify(
                id: "gaveup-\(a.id)",
                title: "Будильник \(a.timeText) сьогодні не дзвонив",
                body: "Тривога тривала довше ніж \(waitLabel(a.maxWaitMin))."
            )
        }
    }

    // MARK: - Сповіщення

    private func askNotifications() {
        guard !defaults.bool(forKey: "asked_notif") else { return }
        defaults.set(true, forKey: "asked_notif")
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    private func notify(id: String, title: String, body: String) {
        let c = UNMutableNotificationContent()
        c.title = title
        c.body = body
        c.interruptionLevel = .passive
        let req = UNNotificationRequest(identifier: id, content: c, trigger: nil)
        UNUserNotificationCenter.current().add(req) { _ in }
    }

    private func removeNotification(_ id: String) {
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [id])
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [id])
    }

    // MARK: - Сервер тихих сигналів

    func setPushToken(_ token: String) {
        pushToken = token
        defaults.set(token, forKey: "push_token")
        scheduleSync()
    }

    /// Надсилає серверу розклад будильників і регіон (з невеликою затримкою, щоб не смикати його на кожну зміну).
    func scheduleSync() {
        if AppModel.demo { return }
        syncTask?.cancel()
        syncTask = Task {
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            if Task.isCancelled { return }
            await syncNow()
        }
    }

    private func syncNow() async {
        guard let token = pushToken, let base = ServerConfig.url else { return }
        #if DEBUG
        let sandbox = true
        #else
        let sandbox = false
        #endif
        let list: [[String: Any]] = alarms.filter { $0.enabled && $0.waitsForAlerts }.map {
            [
                "id": $0.id, "h": $0.hour, "m": $0.minute, "days": $0.days,
                "red": $0.skipRed, "yellow": $0.skipYellow, "wait": $0.maxWaitMin,
            ]
        }
        let payload: [String: Any] = [
            "token": token,
            "sandbox": sandbox,
            "tz": TimeZone.current.identifier,
            "watch": region.map { Regions.watchIds($0) } ?? [],
            "alarms": list,
        ]
        guard let body = try? JSONSerialization.data(withJSONObject: payload) else { return }
        try? await Net.postJSON(base.appendingPathComponent("v1/sync"), body: body)
    }
}

enum ServerConfig {
    static var url: URL? {
        guard let s = Bundle.main.object(forInfoDictionaryKey: "CalmServerURL") as? String,
              s.hasPrefix("https://")
        else { return nil }
        return URL(string: s)
    }
}
