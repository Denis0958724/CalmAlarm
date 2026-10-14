import Foundation

/// Одна активна тривога (на область, район або громаду).
struct AlertEntry {
    var regionId: String
    var regionType: String
    var name: String
    var level: Level
    var since: Date?
}

struct Snapshot {
    var entries: [AlertEntry]
    /// true - дані з резервного джерела (без районів і рівнів).
    var fallback: Bool
    var time = Date()
}

struct RegionStatus: Equatable {
    var active: Bool
    var level: Level?
    var since: Date?
    /// Тривога є в іншій частині області.
    var partial: Bool
}

enum AlertAPI {
    /// Відкрите дзеркало API «Повітряна тривога» (ukrainealarm.com) - без ключа, з рівнями тривог.
    static let siren = "https://siren.pp.ua/api/v3/alerts"
    /// Резервне джерело - лише області, без рівнів.
    static let ubilling = "https://ubilling.net.ua/aerialalerts/"

    /// Тривоги, що тривають понад добу (окуповані території, «вічні» тривоги громад), не враховуємо.
    static let staleSeconds: TimeInterval = 24 * 3600

    static func fetch(timeout: TimeInterval = 6) async throws -> Snapshot {
        do {
            let body = try await Net.get(siren, timeout: timeout)
            return try parseSiren(body)
        } catch {
            if let body = try? await Net.get(ubilling, timeout: timeout * 0.7),
               let s = try? parseUbilling(body) {
                return s
            }
            throw error
        }
    }

    static func parseSiren(_ body: String) throws -> Snapshot {
        guard let arr = try JSONSerialization.jsonObject(with: Data(body.utf8)) as? [[String: Any]] else {
            throw URLError(.cannotParseResponse)
        }
        var order: [String] = []
        var merged: [String: AlertEntry] = [:]
        for o in arr {
            guard let alerts = o["activeAlerts"] as? [[String: Any]] else { continue }
            let oid = o["regionId"] as? String ?? ""
            for a in alerts {
                guard (a["type"] as? String)?.uppercased() == "AIR" else { continue }
                var rid = a["regionId"] as? String ?? ""
                if rid.isEmpty { rid = oid }
                var rtype = a["regionType"] as? String ?? ""
                if rtype.isEmpty { rtype = o["regionType"] as? String ?? "" }
                let name = rid == oid ? (o["regionName"] as? String ?? "") : ""
                let since = ISO.parse(a["lastUpdate"] as? String)
                var red = false
                var yellow = false
                let levels = a["activeAlertLevels"] as? [[String: Any]] ?? []
                if levels.isEmpty {
                    red = true // рівень не вказано - вважаємо червоною
                } else {
                    for l in levels {
                        if (l["alertLevel"] as? String)?.lowercased() == "yellow" { yellow = true } else { red = true }
                    }
                }
                let level: Level = (red || !yellow) ? .red : .yellow
                if var prev = merged[rid] {
                    if prev.name.isEmpty { prev.name = name }
                    if level == .red { prev.level = .red }
                    if let s = since, prev.since.map({ s < $0 }) ?? true { prev.since = s }
                    merged[rid] = prev
                } else {
                    order.append(rid)
                    merged[rid] = AlertEntry(regionId: rid, regionType: rtype, name: name, level: level, since: since)
                }
            }
        }
        return Snapshot(entries: order.compactMap { merged[$0] }, fallback: false)
    }

    private static let localFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd HH:mm:ss"
        f.timeZone = KYIV_TZ
        f.locale = Locale(identifier: "en_US_POSIX")
        return f
    }()

    static func parseUbilling(_ body: String) throws -> Snapshot {
        guard let root = try JSONSerialization.jsonObject(with: Data(body.utf8)) as? [String: Any],
              let states = root["states"] as? [String: Any]
        else { throw URLError(.cannotParseResponse) }
        var out: [AlertEntry] = []
        for (name, v) in states {
            guard let s = v as? [String: Any], (s["alertnow"] as? Bool) == true,
                  let ob = Regions.byName(name), !out.contains(where: { $0.regionId == ob.id })
            else { continue }
            let since = (s["changed"] as? String).flatMap { localFormatter.date(from: $0) }
            out.append(AlertEntry(regionId: ob.id, regionType: "State", name: ob.name, level: .red, since: since))
        }
        return Snapshot(entries: out, fallback: true)
    }

    static func fresh(_ e: AlertEntry, now: Date = Date()) -> Bool {
        guard let s = e.since else { return true }
        return now.timeIntervalSince(s) < staleSeconds
    }

    /// Чи є тривога саме у вибраному регіоні:
    /// - місто: тривога на область, район міста або саме місто;
    /// - район: тривога на область або на цей район;
    /// - «вся область»: тривога на область або хоча б в одному її районі чи місті.
    static func status(_ snap: Snapshot, region: Region) -> RegionStatus {
        let now = Date()
        let entries = snap.entries.filter { fresh($0, now: now) }
        let ids = Set([region.oblastId, region.districtId, region.cityId].compactMap { $0 })
        let hits = entries.filter { e in
            ids.contains(e.regionId) || (region.wholeOblast && Regions.oblastOf(e.regionId) == region.oblastId)
        }
        if hits.isEmpty {
            let partial = entries.contains { Regions.oblastOf($0.regionId) == region.oblastId }
            return RegionStatus(active: false, level: nil, since: nil, partial: partial)
        }
        let level: Level = hits.contains { $0.level == .red } ? .red : .yellow
        LastActive.note(region.label)
        return RegionStatus(active: true, level: level, since: hits.compactMap { $0.since }.min(), partial: false)
    }

    /// Поточний стан тривоги у регіоні або nil, якщо немає зв'язку.
    static func currentStatus(_ region: Region, timeout: TimeInterval = 6) async -> RegionStatus? {
        guard let snap = try? await fetch(timeout: timeout) else { return nil }
        return status(snap, region: region)
    }
}

/// Коли востаннє бачили тривогу у вибраному місці (для «нещодавнього відбою»).
enum LastActive {
    static func note(_ key: String) {
        let d = UserDefaults.standard
        d.set(key, forKey: "last_active_key")
        d.set(Date().timeIntervalSince1970, forKey: "last_active_at")
    }

    static func at(_ key: String) -> Date? {
        let d = UserDefaults.standard
        guard d.string(forKey: "last_active_key") == key else { return nil }
        let t = d.double(forKey: "last_active_at")
        return t > 0 ? Date(timeIntervalSince1970: t) : nil
    }
}
