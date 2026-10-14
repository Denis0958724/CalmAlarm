import Foundation

struct MonitorPost: Identifiable, Hashable {
    var channel: String
    var time: Date
    var text: String
    var id: String { "\(channel)|\(time.timeIntervalSince1970)|\(text.prefix(40))" }
}

struct ChanceResult {
    var percent: Int
    /// Звичайна ймовірність для цього місця й години, коли поруч тихо.
    var usualPercent: Int
    /// Не nil, якщо тривога у вибраному регіоні вже триває.
    var alertNow: RegionStatus?
    var reasons: [String]
    var posts: [MonitorPost]
    var mapOk: Bool
    var monitorsOk: Bool
    var time = Date()
}

/// Невелика обгортка над NSRegularExpression.
struct Rx {
    let re: NSRegularExpression?

    init(_ pattern: String, dotAll: Bool = false) {
        re = try? NSRegularExpression(pattern: pattern, options: dotAll ? [.dotMatchesLineSeparators] : [])
    }

    func test(_ s: String) -> Bool {
        guard let re = re else { return false }
        return re.firstMatch(in: s, options: [], range: NSRange(s.startIndex..., in: s)) != nil
    }

    func group(_ s: String, _ n: Int = 1) -> String? {
        guard let re = re,
              let m = re.firstMatch(in: s, options: [], range: NSRange(s.startIndex..., in: s)),
              m.numberOfRanges > n,
              let r = Range(m.range(at: n), in: s)
        else { return nil }
        return String(s[r])
    }

    func allGroups(_ s: String, _ n: Int = 1) -> [String] {
        guard let re = re else { return [] }
        return re.matches(in: s, options: [], range: NSRange(s.startIndex..., in: s)).compactMap { m in
            guard m.numberOfRanges > n, let r = Range(m.range(at: n), in: s) else { return nil }
            return String(s[r])
        }
    }
}

/// Коефіцієнти моделі (chance_model.json, та сама, що й на Android).
private struct ChanceModel {
    let intercept: Double
    let units: [String: Double]
    let hour: [Double]
    let partial: Double
    let partialCount: [Double]
    let ownNew: Double
    let neighbors: [Double]
    let totalEdges: [Int]
    let total: [Double]
    let nbNewEdges: [Int]
    let nbNew: [Double]
    let waveEdges: [Int]
    let wave: [Double]
    let recentEnd: Double
    let usual: [String: Double]
    let tgObl: [Double]
    let tgDir: Double
    let tgDist: Double
    let tgGlobal: Double
    let tgClear: Double

    init?(_ o: [String: Any]) {
        func d(_ v: Any?) -> Double { (v as? NSNumber)?.doubleValue ?? 0 }
        func ds(_ v: Any?) -> [Double] { (v as? [Any] ?? []).map { d($0) } }
        func ints(_ v: Any?) -> [Int] { (v as? [Any] ?? []).map { (($0 as? NSNumber)?.intValue) ?? 0 } }
        func map(_ v: Any?) -> [String: Double] {
            var out: [String: Double] = [:]
            for (k, x) in v as? [String: Any] ?? [:] { out[Chance.normKey(k)] = d(x) }
            return out
        }
        guard o["intercept"] != nil else { return nil }
        intercept = d(o["intercept"])
        units = map(o["units"])
        hour = ds(o["hour"])
        partial = d(o["partial"])
        partialCount = ds(o["partial_count"])
        ownNew = d(o["own_new"])
        neighbors = ds(o["neighbors"])
        totalEdges = ints(o["total_edges"])
        total = ds(o["total"])
        nbNewEdges = ints(o["nb_new_edges"])
        nbNew = ds(o["nb_new"])
        waveEdges = ints(o["wave_edges"])
        wave = ds(o["wave"])
        recentEnd = d(o["recent_end"])
        usual = map(o["usual"])
        let tg = o["tg"] as? [String: Any] ?? [:]
        let obl = ds(tg["obl"])
        tgObl = obl.count >= 3 ? obl : [0, 0, 0]
        tgDir = d(tg["dir"])
        tgDist = d(tg["dist"])
        tgGlobal = d(tg["global"])
        tgClear = d(tg["clear"])
    }

    static let shared: ChanceModel? = {
        guard let url = Bundle.main.url(forResource: "chance_model", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return nil }
        return ChanceModel(o)
    }()
}

private func at<T>(_ a: [T], _ i: Int, _ def: T) -> T { (i >= 0 && i < a.count) ? a[i] : def }

/// Ймовірність, що протягом 30 хвилин у вибраному місці оголосять тривогу.
/// Модель підібрана на офіційній історії тривог по районах та повідомленнях Повітряних сил.
enum Chance {
    /// Канали, на яких відкалібровано модель.
    static let calibrated = [("kpszsu", "Повітряні сили"), ("air_alert_ua", "Повітряна тривога")]
    /// Канали волонтерів-моніторів - лише для показу повідомлень.
    static let extra = [("war_monitor", "monitor"), ("eRadarrua", "єРадар"), ("deraketaua", "Де Ракета")]

    static let tgWindow: TimeInterval = 30 * 60

    static let threat = [
        "бпла", "шахед", "дрон", "мопед", "ракет", "баліст", "каб", "реактив", "курс", "напрям", "загроз",
        "ціл", "груп", "кинджал", "калібр", "іскандер", "авіабомб", "герань", "гербер", "х-", "пролітає",
        "прямує", "рухаєт", "швидкісн", "крилат",
    ]
    static let clear = ["відбій", "чисто", "мінус", "збит", "знищен", "дорозвідк", "без загроз", "покинув", "покинули"]
    static let global = [
        "міг-31", "миг-31", "міг31", "кинджал", "ту-95", "ту-160", "ту-22", "стратег", "баліст", "масован",
        "х-101", "х-555", "калібр", "пуски", "пуск ракет", "всій території", "по всій україні", "ракетна небезпека",
    ]

    static func normKey(_ s: String) -> String {
        s.replacingOccurrences(of: "'", with: "’").replacingOccurrences(of: "ʼ", with: "’").trimmingCharacters(in: .whitespaces)
    }

    private static func bucket(_ v: Int, _ edges: [Int]) -> Int { edges.filter { v >= $0 }.count }
    private static func sigmoid(_ z: Double) -> Double { 1.0 / (1.0 + exp(-z)) }

    private static func unitKey(_ r: Region) -> String {
        if r.oblastId == "31" { return "м. Київ|" }
        if let c = r.cityName { return "\(r.oblastName)|\(c)" }
        if let d = r.districtName { return "\(r.oblastName)|\(d)" }
        return "\(r.oblastName)|*"
    }

    // ---------------- Telegram ----------------

    private static func regexFor(_ patterns: [String]) -> Rx {
        Rx("(?<!\\p{L})(?:" + patterns.joined(separator: "|") + ")")
    }

    private static let anyOblast: Rx = regexFor(Regions.oblasts.flatMap { $0.patterns })

    /// Корінь назви району або міста: «Броварський район» → «бровар», «м. Харків» → «харків».
    static func placeStem(_ name: String) -> String? {
        var n = name.lowercased().replacingOccurrences(of: "’", with: "'")
        if n.hasPrefix("м. ") { n = String(n.dropFirst(3)) }
        guard let first = n.components(separatedBy: " ").first else { return nil }
        let stem = first.replacingOccurrences(of: "(ськ|цьк|зьк)ий$", with: "", options: .regularExpression)
        return stem.count >= 4 ? NSRegularExpression.escapedPattern(for: String(stem.prefix(8))) : nil
    }

    private static func normalize(_ s: String) -> String {
        s.lowercased()
            .replacingOccurrences(of: "’", with: "'")
            .replacingOccurrences(of: "ʼ", with: "'")
            .replacingOccurrences(of: "`", with: "'")
    }

    private static let textRe = Rx("<div class=\"[^\"]*js-message_text[^\"]*\"[^>]*>(.*?)</div>", dotAll: true)
    private static let timeRe = Rx("<time[^>]*datetime=\"([^\"]+)\"")
    private static let postIdRe = Rx("data-post=\"[^/]+/(\\d+)\"")

    static func htmlToText(_ html: String) -> String {
        var s = html.replacingOccurrences(of: "<br/>", with: "\n").replacingOccurrences(of: "<br>", with: "\n")
        s = s.replacingOccurrences(of: "<[^>]+>", with: "", options: .regularExpression)
        let entities = ["&amp;": "&", "&lt;": "<", "&gt;": ">", "&quot;": "\"", "&#39;": "'", "&apos;": "'", "&nbsp;": " "]
        for (k, v) in entities { s = s.replacingOccurrences(of: k, with: v) }
        // Числові сутності &#1234; і &#x1F680;
        if s.contains("&#") {
            let re = Rx("&#(x?[0-9a-fA-F]+);")
            var out = s
            for code in Set(re.allGroups(s)) {
                let value = code.hasPrefix("x") ? UInt32(code.dropFirst(), radix: 16) : UInt32(code)
                if let v = value, let scalar = Unicode.Scalar(v) {
                    out = out.replacingOccurrences(of: "&#\(code);", with: String(Character(scalar)))
                }
            }
            s = out
        }
        return s.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func parseTelegram(_ html: String, channel: String) -> [MonitorPost] {
        var out: [MonitorPost] = []
        for part in html.components(separatedBy: "tgme_widget_message_wrap").dropFirst() {
            guard let rawText = textRe.group(part),
                  let rawTime = timeRe.group(part),
                  let time = ISO.parse(rawTime)
            else { continue }
            let text = htmlToText(rawText)
            if !text.isEmpty { out.append(MonitorPost(channel: channel, time: time, text: text)) }
        }
        return out
    }

    private static func fetchChannel(_ ch: String, _ title: String) async -> [MonitorPost]? {
        guard let html = try? await Net.get("https://t.me/s/\(ch)", timeout: 8, userAgent: Net.browserUA) else { return nil }
        var posts = parseTelegram(html, channel: title)
        // Якщо на першій сторінці менше ніж 30 хв історії - дочитуємо ще одну.
        if let oldest = posts.map({ $0.time }).min(), Date().timeIntervalSince(oldest) < tgWindow,
           let minId = postIdRe.allGroups(html).compactMap({ Int($0) }).min(),
           let more = try? await Net.get("https://t.me/s/\(ch)?before=\(minId)", timeout: 8, userAgent: Net.browserUA) {
            posts += parseTelegram(more, channel: title)
        }
        var seen = Set<String>()
        return posts.filter { seen.insert("\($0.time.timeIntervalSince1970)|\($0.text)").inserted }
    }

    private struct TgFeatures {
        var oblCount = 0
        var dir = false
        var dist = false
        var global = false
        var clear = false
        var recentEnd = false
        var relevant: [MonitorPost] = []
    }

    private static func tgFeatures(_ region: Region, calibrated: [MonitorPost], extra: [MonitorPost], now: Date) -> TgFeatures {
        let ob = Regions.byId[region.oblastId]
        let oblRe = ob.map { regexFor($0.patterns) }
        let stem = region.place.flatMap { placeStem($0) }
        let distRe = stem.map { Rx("(?<!\\p{L})\($0)") }
        let mine = (ob?.patterns ?? []) + (stem.map { [$0] } ?? [])
        let dirRe: Rx? = mine.isEmpty ? nil : Rx(
            "(?:курс(?:ом)?\\s+на|в\\s+напрямку|у\\s+напрямку|напрям(?:ок|ку)?|на|над|через|біля|повз|в|у|до)\\s+" +
                "(?:\\p{L}+\\s+)?(?:" + mine.joined(separator: "|") + ")"
        )
        var f = TgFeatures()
        let endName = normalize(region.place ?? region.oblastName)

        for p in calibrated {
            let age = now.timeIntervalSince(p.time)
            if age < -5 * 60 { continue }
            let t = normalize(p.text)
            if p.channel == "Повітряна тривога" {
                // Офіційний канал тривог: шукаємо відбій у вашому місці за останню годину.
                if age <= 3600 && t.contains("відбій тривоги") && t.contains(endName) { f.recentEnd = true }
                continue
            }
            if age > tgWindow { continue }
            let isClear = clear.contains { t.contains($0) }
            let isThreat = threat.contains { t.contains($0) }
            let mentionsMine = (oblRe?.test(t) ?? false) || (distRe?.test(t) ?? false)
            if mentionsMine {
                f.relevant.append(p)
                if isClear {
                    f.clear = true
                    f.oblCount += 1
                } else if isThreat {
                    f.oblCount += 1
                    if dirRe?.test(t) ?? false { f.dir = true }
                    if distRe?.test(t) ?? false { f.dist = true }
                }
            } else if !isClear, global.contains(where: { t.contains($0) }),
                      !anyOblast.test(t) || t.contains("україн") || t.contains("всій території") {
                f.global = true
                f.relevant.append(p)
            }
        }
        for p in extra {
            if now.timeIntervalSince(p.time) > 3600 { continue }
            let t = normalize(p.text)
            if (oblRe?.test(t) ?? false) || (distRe?.test(t) ?? false) { f.relevant.append(p) }
        }
        return f
    }

    // ---------------- Обчислення ----------------

    struct NoData: Error {}

    static func compute(_ region: Region) async throws -> ChanceResult {
        async let snapT: Snapshot? = try? AlertAPI.fetch(timeout: 7)
        async let regionsT: [String: [District]]? = try? Regions.loadDistricts()
        async let c0 = fetchChannel(calibrated[0].0, calibrated[0].1)
        async let c1 = fetchChannel(calibrated[1].0, calibrated[1].1)
        async let e0 = fetchChannel(extra[0].0, extra[0].1)
        async let e1 = fetchChannel(extra[1].0, extra[1].1)
        async let e2 = fetchChannel(extra[2].0, extra[2].1)
        _ = await regionsT
        let snap = await snapT
        let cal: [[MonitorPost]?] = await [c0, c1]
        let ext: [[MonitorPost]?] = await [e0, e1, e2]
        return try evaluate(region, snap: snap, cal: cal, ext: ext)
    }

    private static func evaluate(_ region: Region, snap: Snapshot?, cal: [[MonitorPost]?], ext: [[MonitorPost]?]) throws -> ChanceResult {
        let now = Date()
        let monitorsOk = cal.contains { $0 != nil }
        if snap == nil && !monitorsOk { throw NoData() }
        guard let m = ChanceModel.shared else { throw NoData() }

        let tg = tgFeatures(region, calibrated: cal.compactMap { $0 }.flatMap { $0 }, extra: ext.compactMap { $0 }.flatMap { $0 }, now: now)
        var seenText = Set<String>()
        let posts = Array(tg.relevant.sorted { $0.time > $1.time }.filter { seenText.insert($0.text).inserted }.prefix(6))

        if let snap = snap {
            let st = AlertAPI.status(snap, region: region)
            if st.active {
                return ChanceResult(percent: 100, usualPercent: 100, alertNow: st, reasons: [], posts: posts, mapOk: true, monitorsOk: monitorsOk)
            }
        }

        var reasons: [(Double, String)] = []
        let unitCoef = m.units[normKey(unitKey(region))] ?? m.units[normKey("\(region.oblastName)|*")] ?? 0
        var kyivCal = Calendar(identifier: .gregorian)
        kyivCal.timeZone = KYIV_TZ
        let hour = kyivCal.component(.hour, from: now)
        let zUsual = m.intercept + unitCoef + at(m.hour, hour, 0)
        var z = zUsual

        if let snap = snap {
            let isUnitLocal = region.districtId != nil || region.cityId != nil
            var total = 0
            var wave = 0
            var oblActive = Set<String>()
            var oblNew: [String: Int] = [:]
            var pc = 0
            var partialAny = false
            var ownNew = false
            for e in snap.entries {
                if !AlertAPI.fresh(e, now: now) { continue }
                guard let ob = Regions.oblastOf(e.regionId) else { continue }
                if ob == "16" || ob == "9999" { continue }
                let isState = e.regionId == ob
                let isDistrict = Regions.isDistrict(e.regionId)
                let raions = isState ? Regions.districtCount(ob) : (isDistrict ? 1 : 0)
                let age = e.since.map { now.timeIntervalSince($0) } ?? .greatestFiniteMagnitude
                total += raions
                if age <= 30 * 60 { wave += raions }
                if isState || isDistrict { oblActive.insert(ob) }
                if age <= 20 * 60 && raions > 0 { oblNew[ob, default: 0] += raions }
                if isUnitLocal && ob == region.oblastId && e.regionId != region.districtId && e.regionId != region.cityId {
                    if isDistrict {
                        pc += 1
                        if age <= 20 * 60 { ownNew = true }
                    }
                    partialAny = true
                }
            }
            let nb = Regions.neighbors[region.oblastId] ?? []
            let nbList = nb.filter { oblActive.contains($0) }
            let nbNew = nb.reduce(0) { $0 + (oblNew[$1] ?? 0) }

            let cPartial = (partialAny ? m.partial : 0) + at(m.partialCount, min(pc, 3), 0)
            let cOwnNew = ownNew ? m.ownNew : 0
            let cNb = at(m.neighbors, min(nbList.count, 3), 0)
            let cTotal = at(m.total, bucket(total, m.totalEdges), 0)
            let cNbNew = at(m.nbNew, bucket(nbNew, m.nbNewEdges), 0)
            let cWave = at(m.wave, bucket(wave, m.waveEdges), 0)
            z += cPartial + cOwnNew + cNb + cTotal + cNbNew + cWave

            if partialAny {
                reasons.append((cPartial + cOwnNew, pc > 0 ? "Тривога в інших районах вашої області: \(pc)" : "Тривога в іншій частині вашої області"))
            }
            if !nbList.isEmpty {
                reasons.append((cNb, "Тривога в сусідніх областях: " + nbList.compactMap { Regions.byId[$0]?.short }.joined(separator: ", ")))
            }
            if nbNew > 0 || ownNew { reasons.append((cNbNew + cOwnNew, "Нові тривоги поруч за останні 20 хв")) }
            if wave >= 4 { reasons.append((cWave, "Хвиля нових тривог: \(wave) районів за 30 хв")) }
            if total >= 31 { reasons.append((cTotal, "Масштабна атака: тривога у \(total) районах країни")) }
        }

        let recentLocal = LastActive.at(region.label).map { now.timeIntervalSince($0) <= 3600 } ?? false
        if tg.recentEnd || recentLocal {
            z += m.recentEnd
            reasons.append((m.recentEnd, "Нещодавно був відбій - атаки часто повертаються хвилями"))
        }

        let cTg = at(m.tgObl, min(tg.oblCount, 2), 0) + (tg.dir ? m.tgDir : 0) + (tg.dist ? m.tgDist : 0)
        let cGlobal = tg.global ? m.tgGlobal : 0
        let cClear = tg.clear ? m.tgClear : 0
        z += cTg + cGlobal + cClear
        if tg.oblCount > 0 {
            reasons.append((cTg, "Повітряні сили згадують ваш регіон: \(tg.oblCount) повідомл. за 30 хв"))
        }

        let p = min(max(sigmoid(z), 0.01), 0.97)
        let usual = min(max(m.usual[normKey(unitKey(region))] ?? sigmoid(zUsual), 0.01), 0.97)
        let percent = max(1, Int((p * 100).rounded()))
        let usualPercent = max(1, Int((usual * 100).rounded()))

        var texts: [String] = []
        for r in reasons.filter({ abs($0.0) >= 0.12 || $0.0 == 0 }).sorted(by: { abs($0.0) > abs($1.0) }) where !texts.contains(r.1) {
            texts.append(r.1)
        }
        if texts.isEmpty { texts.append("Поруч тривог немає - звичайний рівень для вашого району") }
        if snap == nil { texts.append("Карта тривог зараз недоступна - оцінка неповна") }
        if !monitorsOk { texts.append("Канали Повітряних сил зараз недоступні - оцінка лише за картою") }

        return ChanceResult(
            percent: percent,
            usualPercent: usualPercent,
            alertNow: nil,
            reasons: Array(texts.prefix(5)),
            posts: posts,
            mapOk: snap != nil,
            monitorsOk: monitorsOk
        )
    }
}
