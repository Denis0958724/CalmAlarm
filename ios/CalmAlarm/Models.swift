import Foundation

/// Рівень тривоги: червона - ракети/масована атака, жовта - дрони.
enum Level: String, Codable {
    case red, yellow

    var title: String { self == .yellow ? "жовта" : "червона" }
}

struct WakeAlarm: Codable, Identifiable, Equatable {
    var id: Int
    var hour: Int
    var minute: Int
    /// 1 = понеділок … 7 = неділя. Порожньо = один раз.
    var days: [Int] = []
    var enabled: Bool = true
    var tone: Int = 0
    var skipRed: Bool = true
    var skipYellow: Bool = true
    var maxWaitMin: Int = 120
    /// На скільки хвилин відкладати кнопкою «Ще N хв».
    var snoozeMin: Int = 5

    var timeText: String { String(format: "%02d:%02d", hour, minute) }
    var waitsForAlerts: Bool { skipRed || skipYellow }

    func shouldWait(_ level: Level?) -> Bool {
        (level ?? .red) == .red ? skipRed : skipYellow
    }
}

/// Будильник, який зараз чекає відбою тривоги.
struct Waiting: Codable, Equatable {
    var alarmId: Int
    /// Коли будильник мав задзвонити.
    var occ: Date
    /// Після цього часу, якщо тривога триває, будильник не дзвонить.
    var deadline: Date
    var since: Date
    var level: Level?
    /// Запасний будильник AlarmKit: задзвонить, якщо телефон не отримає сигнал про відбій.
    var overrideId: UUID?
    var overrideAt: Date?
    /// Разова «страховка» на наступний день (для повторюваних будильників).
    var safetyId: UUID? = nil
}

struct Region: Codable, Equatable {
    var oblastId: String
    var oblastName: String
    var districtId: String? = nil
    var districtName: String? = nil
    /// Місто з окремими тривогами (наприклад, м. Харків) - громада всередині району.
    var cityId: String? = nil
    var cityName: String? = nil

    var place: String? { cityName ?? districtName }
    var subtitle: String { place ?? "Вся область" }
    var label: String {
        if let p = place { return "\(oblastName) · \(p)" }
        return oblastName
    }
    var wholeOblast: Bool { districtId == nil && cityId == nil }
}

let DAY_SHORT = ["Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Нд"]
let WEEKDAYS: [Int] = [1, 2, 3, 4, 5]
let ALL_DAYS: [Int] = [1, 2, 3, 4, 5, 6, 7]

func daysText(_ days: [Int]) -> String {
    let s = Set(days)
    if s.isEmpty { return "Один раз" }
    if s.count == 7 { return "Щодня" }
    if s == Set(WEEKDAYS) { return "Будні" }
    if s == Set([6, 7]) { return "Вихідні" }
    return s.sorted().map { DAY_SHORT[$0 - 1] }.joined(separator: ", ")
}

/// Межі повзунків (хвилини).
let WAIT_MIN = 10
let WAIT_MAX = 360
let WAIT_STEP = 10
let SNOOZE_LOW = 1
let SNOOZE_HIGH = 30

func waitLabel(_ min: Int) -> String {
    let h = min / 60
    let m = min % 60
    if h == 0 { return "\(m) хв" }
    if m == 0 { return "\(h) год" }
    return "\(h) год \(m) хв"
}

/// Київський час.
let KYIV_TZ: TimeZone = TimeZone(identifier: "Europe/Kyiv") ?? TimeZone(identifier: "Europe/Kiev") ?? .current

private let hmFormatter: DateFormatter = {
    let f = DateFormatter()
    f.dateFormat = "HH:mm"
    f.locale = Locale(identifier: "uk_UA")
    return f
}()

func hm(_ date: Date) -> String { hmFormatter.string(from: date) }

func untilText(_ seconds: TimeInterval) -> String {
    let totalMin = max(1, Int((seconds + 59) / 60))
    let d = totalMin / (60 * 24)
    let h = (totalMin / 60) % 24
    let m = totalMin % 60
    var parts: [String] = []
    if d > 0 { parts.append("\(d) д") }
    if h > 0 { parts.append("\(h) год") }
    if m > 0 || (d == 0 && h == 0) { parts.append("\(m) хв") }
    return parts.joined(separator: " ")
}

/// Найближчий момент, коли будильник має задзвонити (за часом телефона).
func nextOccurrence(_ a: WakeAlarm, after now: Date, calendar: Calendar = .current) -> Date? {
    let start = calendar.startOfDay(for: now)
    for offset in 0...8 {
        guard let day = calendar.date(byAdding: .day, value: offset, to: start),
              let t = calendar.date(bySettingHour: a.hour, minute: a.minute, second: 0, of: day)
        else { continue }
        if t <= now { continue }
        if a.days.isEmpty { return t }
        let wd = calendar.component(.weekday, from: t) // 1 = неділя
        let ours = wd == 1 ? 7 : wd - 1
        if a.days.contains(ours) { return t }
    }
    return nil
}

enum Tones {
    /// П'ять власних мелодій (як на Android) і стандартний звук iPhone.
    static let names = ["Світанок", "Дзвіночки", "Класичний", "Пташки", "Легкий бриз", "Стандартний звук iPhone"]
    static let files = ["tone_sunrise", "tone_bells", "tone_classic", "tone_birds", "tone_breeze"]

    static func file(_ i: Int) -> String? {
        guard i >= 0, i < files.count else { return nil }
        return files[i]
    }
}
