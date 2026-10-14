import Foundation

/// Область. `patterns` - фрагменти регулярних виразів (у нижньому регістрі), за якими
/// впізнаємо згадки області в повідомленнях моніторів. Ідентифікатори - як в API ukrainealarm.
struct Oblast: Identifiable {
    let id: String
    let name: String
    let patterns: [String]
    var frontline: Bool = false

    var short: String { name.hasSuffix(" область") ? String(name.dropLast(" область".count)) : name }
    var hasDistricts: Bool { id != "31" && id != "9999" }
}

struct City: Identifiable, Hashable {
    let id: String
    let name: String
    /// «м. Харків та Харківська територіальна громада» → «м. Харків».
    var short: String {
        (name.components(separatedBy: " та ").first ?? name).trimmingCharacters(in: .whitespaces)
    }
}

struct District: Identifiable {
    let id: String
    let name: String
    var cities: [City] = []
}

enum Regions {
    private static let E = "(?!\\p{L})" // кінець слова

    static let oblasts: [Oblast] = [
        Oblast(id: "4", name: "Вінницька область", patterns: ["вінниц", "вінничч", "жмерин", "козятин", "гайсин", "ладижин"]),
        Oblast(id: "8", name: "Волинська область", patterns: ["волин", "луцьк", "ковел", "нововолинськ"]),
        Oblast(id: "9", name: "Дніпропетровська область", patterns: [
            "дніпропетровщ", "дніпропетровськ", "дніпр(о|а|і|у|ом)\(E)", "кривий ріг", "кривого рогу",
            "кривому розі", "кривбас", "нікопол", "марганц", "павлоград", "кам'янськ", "синельников",
        ], frontline: true),
        Oblast(id: "28", name: "Донецька область", patterns: [
            "донеч", "донецьк", "краматорськ", "слов'янськ", "покровськ", "костянтинівк", "дружківк", "добропілл",
        ], frontline: true),
        Oblast(id: "10", name: "Житомирська область", patterns: ["житомир", "коростен", "бердичів", "звягел", "новоград-волинськ", "овруч"]),
        Oblast(id: "11", name: "Закарпатська область", patterns: ["закарпат", "ужгород", "мукачев", "хуст", "берегов"]),
        Oblast(id: "12", name: "Запорізька область", patterns: ["запоріж", "запорізьк", "енергодар", "оріхів", "мелітопол", "бердянськ"], frontline: true),
        Oblast(id: "13", name: "Івано-Франківська область", patterns: ["франківськ", "франківщ", "прикарпат", "калуш", "коломи"]),
        Oblast(id: "14", name: "Київська область", patterns: [
            "київщ", "київськ(а|ої|ій|у)\\s+обл", "бровар", "борисп", "біла церква", "білої церкви", "білій церкві",
            "білоцерк", "обухів", "обухов", "вишгород", "фастів", "фастов", "ірпін", "буч(а|і|у|ею)\(E)", "васильк",
            "славутич", "бородянк", "макарів", "переяслав", "яготин", "березан", "кагарлик", "богуслав", "сквир",
            "гостомел",
        ]),
        Oblast(id: "31", name: "м. Київ", patterns: [
            "київ(?!щ|ськ)", "києв", "столиц", "дарниц", "оболон", "троєщин", "позняк", "святошин", "голосіїв",
            "солом'ян", "лівобереж", "правобереж", "лівий берег", "правий берег",
        ]),
        Oblast(id: "15", name: "Кіровоградська область", patterns: ["кіровоград", "кропивницьк", "олександрі(я|ї)\(E)", "світловодськ", "знам'янк"]),
        Oblast(id: "16", name: "Луганська область", patterns: ["луган", "сєвєродонецьк", "лисичанськ"], frontline: true),
        Oblast(id: "27", name: "Львівська область", patterns: ["львів", "дрогоб", "стрий", "червоноград", "шептицьк", "самбір", "золочів"]),
        Oblast(id: "17", name: "Миколаївська область", patterns: [
            "миколаїв", "очаків", "вознесенськ", "південноукраїнськ", "первомайськ", "баштанк", "кінбурн",
        ], frontline: true),
        Oblast(id: "18", name: "Одеська область", patterns: [
            "одес", "одещ", "ізмаїл", "чорноморськ", "білгород-дністров", "заток", "рені\(E)", "подільськ", "болград", "кілі(я|ї)\(E)",
        ]),
        Oblast(id: "19", name: "Полтавська область", patterns: ["полтав", "кременчу", "миргород", "лубн", "горішні плавні", "гадяч", "пирятин", "карлівк"]),
        Oblast(id: "5", name: "Рівненська область", patterns: ["рівне\(E)", "рівного", "рівному", "рівненщ", "рівненськ", "дубн", "вараш", "сарн", "костопіл"]),
        Oblast(id: "20", name: "Сумська область", patterns: [
            "сумщ", "сумськ", "сум(и|ах|ам|ами)\(E)", "конотоп", "шостк", "охтирк", "ромн", "глухів", "тростян",
            "лебедин", "кролевец", "путивл",
        ], frontline: true),
        Oblast(id: "21", name: "Тернопільська область", patterns: ["тернопіл", "чортків", "кременец", "бережан"]),
        Oblast(id: "22", name: "Харківська область", patterns: [
            "харків", "куп'янськ", "ізюм", "лозов", "чугуїв", "богодухів", "балаклі", "красноград", "берестин", "мерефа", "вовчанськ",
        ], frontline: true),
        Oblast(id: "23", name: "Херсонська область", patterns: ["херсон", "берислав", "каховк"], frontline: true),
        Oblast(id: "3", name: "Хмельницька область", patterns: [
            "хмельниц", "хмельничч", "старокостянтин", "шепетівк", "кам'янець", "нетішин", "славут(а|и|і|у)\(E)", "красилів", "полонн",
        ]),
        Oblast(id: "24", name: "Черкаська область", patterns: [
            "черкас", "черкащ", "уман", "сміл(а|и|і|у)\(E)", "золотонош", "канів", "звенигородк", "корсун", "чигирин",
        ]),
        Oblast(id: "26", name: "Чернівецька область", patterns: ["чернівц", "чернівецьк", "буковин", "хотин", "новодністровськ"]),
        Oblast(id: "25", name: "Чернігівська область", patterns: [
            "чернігів", "ніжин", "прилук", "новгород-сіверськ", "семенівк", "козел(е|ь)ц", "бахмач", "корюківк", "сновськ", "борзн",
        ], frontline: true),
        Oblast(id: "9999", name: "Автономна Республіка Крим", patterns: ["крим", "севастопол", "сімферопол", "джанкой", "феодосі", "керч", "євпатор"]),
    ]

    static let byId: [String: Oblast] = Dictionary(uniqueKeysWithValues: oblasts.map { ($0.id, $0) })

    /// Сусідні області (за спільним кордоном).
    static let neighbors: [String: [String]] = [
        "4": ["10", "14", "24", "15", "18", "3", "26"],
        "8": ["5", "27"],
        "9": ["22", "19", "15", "17", "23", "12", "28"],
        "28": ["16", "22", "9", "12"],
        "10": ["5", "3", "4", "14"],
        "11": ["27", "13"],
        "12": ["9", "28", "23"],
        "13": ["27", "11", "26", "21"],
        "14": ["10", "4", "24", "19", "25", "31"],
        "15": ["4", "24", "19", "9", "17", "18"],
        "16": ["22", "28"],
        "27": ["8", "5", "21", "13", "11"],
        "17": ["18", "15", "9", "23"],
        "18": ["17", "15", "4"],
        "19": ["14", "25", "20", "22", "9", "15", "24"],
        "5": ["8", "27", "21", "3", "10"],
        "20": ["25", "19", "22"],
        "21": ["5", "3", "26", "13", "27"],
        "22": ["20", "19", "9", "28", "16"],
        "23": ["17", "9", "12", "9999"],
        "3": ["5", "10", "4", "26", "21"],
        "24": ["14", "19", "15", "4"],
        "26": ["13", "21", "3", "4"],
        "25": ["14", "19", "20"],
        "31": ["14"],
        "9999": ["23"],
    ]

    /// Області в алфавітному порядку для вибору.
    static var sorted: [Oblast] {
        oblasts.sorted { $0.name.compare($1.name, locale: Locale(identifier: "uk_UA")) == .orderedAscending }
    }

    private static func norm(_ s: String) -> String {
        s.lowercased()
            .replacingOccurrences(of: "область", with: "")
            .replacingOccurrences(of: "обл.", with: "")
            .replacingOccurrences(of: "м.", with: "")
            .replacingOccurrences(of: "’", with: "'")
            .replacingOccurrences(of: "ʼ", with: "'")
            .trimmingCharacters(in: .whitespaces)
    }

    /// Пошук області за назвою (для резервного джерела, де є лише назви).
    static func byName(_ name: String) -> Oblast? {
        let n = norm(name)
        if n.hasPrefix("київ") && !n.contains("ськ") { return byId["31"] }
        if n.contains("крим") || n.contains("севастоп") { return byId["9999"] }
        return oblasts.first { norm($0.name) == n } ?? oblasts.first { n.hasPrefix(String(norm($0.name).prefix(5))) }
    }

    // ---------- Райони (завантажуються з мережі й кешуються) ----------

    private static let regionsURL = "https://siren.pp.ua/api/v3/regions"
    private static let cacheKey = "regions_cache_v3"

    /// Міста, для яких оголошують окремі тривоги (не лише разом із районом).
    private static let cityPrefixes = ["м. Харків ", "м. Запоріжжя ", "м. Нікополь ", "м. Марганець ", "м. Краматорськ "]

    /// Міста, які в джерелі записані окремим «регіоном» верхнього рівня: префікс → (область, район).
    private static let topLevelCities: [(String, String, String)] = [
        ("м. Харків ", "22", "Харківський район"),
        ("м. Запоріжжя ", "12", "Запорізький район"),
    ]

    private static let lock = NSLock()
    private static var districtsMem: [String: [District]]?
    private static var parentMem: [String: String] = [:]
    private static var districtIds: Set<String> = []

    @discardableResult
    private static func applyCache(_ raw: String) -> Bool {
        guard let root = try? JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: Any],
              let o = root["o"] as? [String: Any],
              let m = root["m"] as? [String: String]
        else { return false }
        var d: [String: [District]] = [:]
        var dids = Set<String>()
        for (sid, v) in o {
            guard let arr = v as? [[Any]] else { continue }
            var list: [District] = []
            for e in arr {
                guard e.count >= 2, let did = e[0] as? String, let dname = e[1] as? String else { continue }
                var cities: [City] = []
                if e.count > 2, let carr = e[2] as? [[String]] {
                    cities = carr.compactMap { $0.count >= 2 ? City(id: $0[0], name: $0[1]) : nil }
                }
                dids.insert(did)
                list.append(District(id: did, name: dname, cities: cities))
            }
            d[sid] = list.sorted { $0.name.compare($1.name, locale: Locale(identifier: "uk_UA")) == .orderedAscending }
        }
        lock.lock()
        districtsMem = d
        parentMem = m
        districtIds = dids
        lock.unlock()
        return true
    }

    private static func ensureMem() {
        lock.lock()
        let has = districtsMem != nil
        lock.unlock()
        if !has, let raw = UserDefaults.standard.string(forKey: cacheKey) {
            applyCache(raw)
        }
    }

    static func cachedDistricts() -> [String: [District]]? {
        ensureMem()
        lock.lock()
        defer { lock.unlock() }
        return districtsMem
    }

    /// Райони всіх областей; за потреби завантажує з мережі.
    static func loadDistricts() async throws -> [String: [District]] {
        if let c = cachedDistricts() { return c }
        let body = try await Net.get(regionsURL, timeout: 20)
        guard let root = try JSONSerialization.jsonObject(with: Data(body.utf8)) as? [String: Any],
              let states = root["states"] as? [[String: Any]]
        else { throw URLError(.cannotParseResponse) }
        var o: [String: [[Any]]] = [:]
        var m: [String: String] = [:]
        var tops: [(String, String, String, String)] = [] // id, назва, область, район
        for s in states {
            guard let sid = s["regionId"] as? String else { continue }
            let sname = s["regionName"] as? String ?? ""
            if let top = topLevelCities.first(where: { sname.hasPrefix($0.0) }) {
                tops.append((sid, sname, top.1, top.2))
                continue
            }
            guard byId[sid] != nil else { continue }
            var arr: [[Any]] = []
            for d in s["regionChildIds"] as? [[String: Any]] ?? [] {
                guard let did = d["regionId"] as? String else { continue }
                m[did] = sid
                var cities: [[String]] = []
                for c in d["regionChildIds"] as? [[String: Any]] ?? [] {
                    guard let cid = c["regionId"] as? String else { continue }
                    let cname = c["regionName"] as? String ?? ""
                    m[cid] = sid
                    if cityPrefixes.contains(where: { cname.hasPrefix($0) }) { cities.append([cid, cname]) }
                }
                if (d["regionType"] as? String) == "District" {
                    arr.append([did, d["regionName"] as? String ?? "", cities])
                }
            }
            o[sid] = arr
        }
        for (cid, cname, oid, dname) in tops {
            m[cid] = oid
            guard var arr = o[oid] else { continue }
            for k in arr.indices where (arr[k][1] as? String) == dname {
                var cities = arr[k].count > 2 ? (arr[k][2] as? [[String]] ?? []) : []
                cities.append([cid, cname])
                if arr[k].count > 2 { arr[k][2] = cities } else { arr[k].append(cities) }
            }
            o[oid] = arr
        }
        let data = try JSONSerialization.data(withJSONObject: ["o": o, "m": m])
        let raw = String(decoding: data, as: UTF8.self)
        guard applyCache(raw) else { throw URLError(.cannotParseResponse) }
        UserDefaults.standard.set(raw, forKey: cacheKey)
        return cachedDistricts() ?? [:]
    }

    /// До якої області належить регіон (область, район або громада).
    static func oblastOf(_ regionId: String) -> String? {
        if byId[regionId] != nil { return regionId }
        ensureMem()
        lock.lock()
        defer { lock.unlock() }
        return parentMem[regionId]
    }

    static func isDistrict(_ regionId: String) -> Bool {
        ensureMem()
        lock.lock()
        defer { lock.unlock() }
        return districtIds.contains(regionId)
    }

    /// Скільки районів в області (для підрахунку масштабу тривоги).
    static func districtCount(_ oblastId: String) -> Int {
        if oblastId == "31" { return 1 }
        let n = cachedDistricts()?[oblastId]?.count ?? 0
        return n > 0 ? n : 5
    }

    /// Ідентифікатори регіонів, тривога в яких означає тривогу «у вас» (для сервера сповіщень).
    static func watchIds(_ r: Region) -> [String] {
        var ids = [r.oblastId, r.districtId, r.cityId].compactMap { $0 }
        if r.wholeOblast {
            ensureMem()
            lock.lock()
            ids += parentMem.filter { $0.value == r.oblastId }.map { $0.key }
            lock.unlock()
        }
        return Array(Set(ids)).sorted()
    }
}
