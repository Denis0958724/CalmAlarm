package ua.calmalarm.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Область. [patterns] — фрагменти регулярних виразів (у нижньому регістрі), за якими
 * впізнаємо згадки області в повідомленнях моніторів. Ідентифікатори — як в API ukrainealarm.
 */
data class Oblast(
    val id: String,
    val name: String,
    val patterns: List<String>,
    val frontline: Boolean = false,
) {
    val short: String get() = name.removeSuffix(" область")
    val hasDistricts: Boolean get() = id != "31" && id != "9999"
}

data class District(val id: String, val name: String)

object Regions {
    private const val E = "(?!\\p{L})" // кінець слова

    val oblasts: List<Oblast> = listOf(
        Oblast("4", "Вінницька область", listOf("вінниц", "вінничч", "жмерин", "козятин", "гайсин", "ладижин")),
        Oblast("8", "Волинська область", listOf("волин", "луцьк", "ковел", "нововолинськ")),
        Oblast(
            "9", "Дніпропетровська область",
            listOf(
                "дніпропетровщ", "дніпропетровськ", "дніпр(о|а|і|у|ом)$E", "кривий ріг", "кривого рогу",
                "кривому розі", "кривбас", "нікопол", "марганц", "павлоград", "кам'янськ", "синельников",
            ),
            frontline = true,
        ),
        Oblast(
            "28", "Донецька область",
            listOf("донеч", "донецьк", "краматорськ", "слов'янськ", "покровськ", "костянтинівк", "дружківк", "добропілл"),
            frontline = true,
        ),
        Oblast("10", "Житомирська область", listOf("житомир", "коростен", "бердичів", "звягел", "новоград-волинськ", "овруч")),
        Oblast("11", "Закарпатська область", listOf("закарпат", "ужгород", "мукачев", "хуст", "берегов")),
        Oblast(
            "12", "Запорізька область",
            listOf("запоріж", "запорізьк", "енергодар", "оріхів", "мелітопол", "бердянськ"),
            frontline = true,
        ),
        Oblast("13", "Івано-Франківська область", listOf("франківськ", "франківщ", "прикарпат", "калуш", "коломи")),
        Oblast(
            "14", "Київська область",
            listOf(
                "київщ", "київськ(а|ої|ій|у)\\s+обл", "бровар", "борисп", "біла церква", "білої церкви", "білій церкві",
                "білоцерк", "обухів", "обухов", "вишгород", "фастів", "фастов", "ірпін", "буч(а|і|у|ею)$E", "васильк",
                "славутич", "бородянк", "макарів", "переяслав", "яготин", "березан", "кагарлик", "богуслав", "сквир",
                "гостомел",
            ),
        ),
        Oblast(
            "31", "м. Київ",
            listOf(
                "київ(?!щ|ськ)", "києв", "столиц", "дарниц", "оболон", "троєщин", "позняк", "святошин", "голосіїв",
                "солом'ян", "лівобереж", "правобереж", "лівий берег", "правий берег",
            ),
        ),
        Oblast("15", "Кіровоградська область", listOf("кіровоград", "кропивницьк", "олександрі(я|ї)$E", "світловодськ", "знам'янк")),
        Oblast("16", "Луганська область", listOf("луган", "сєвєродонецьк", "лисичанськ"), frontline = true),
        Oblast("27", "Львівська область", listOf("львів", "дрогоб", "стрий", "червоноград", "шептицьк", "самбір", "золочів")),
        Oblast(
            "17", "Миколаївська область",
            listOf("миколаїв", "очаків", "вознесенськ", "південноукраїнськ", "первомайськ", "баштанк", "кінбурн"),
            frontline = true,
        ),
        Oblast("18", "Одеська область", listOf("одес", "одещ", "ізмаїл", "чорноморськ", "білгород-дністров", "заток", "рені$E", "подільськ", "болград", "кілі(я|ї)$E")),
        Oblast("19", "Полтавська область", listOf("полтав", "кременчу", "миргород", "лубн", "горішні плавні", "гадяч", "пирятин", "карлівк")),
        Oblast("5", "Рівненська область", listOf("рівне$E", "рівного", "рівному", "рівненщ", "рівненськ", "дубн", "вараш", "сарн", "костопіл")),
        Oblast(
            "20", "Сумська область",
            listOf(
                "сумщ", "сумськ", "сум(и|ах|ам|ами)$E", "конотоп", "шостк", "охтирк", "ромн", "глухів", "тростян",
                "лебедин", "кролевец", "путивл",
            ),
            frontline = true,
        ),
        Oblast("21", "Тернопільська область", listOf("тернопіл", "чортків", "кременец", "бережан")),
        Oblast(
            "22", "Харківська область",
            listOf("харків", "куп'янськ", "ізюм", "лозов", "чугуїв", "богодухів", "балаклі", "красноград", "берестин", "мерефа", "вовчанськ"),
            frontline = true,
        ),
        Oblast("23", "Херсонська область", listOf("херсон", "берислав", "каховк"), frontline = true),
        Oblast(
            "3", "Хмельницька область",
            listOf("хмельниц", "хмельничч", "старокостянтин", "шепетівк", "кам'янець", "нетішин", "славут(а|и|і|у)$E", "красилів", "полонн"),
        ),
        Oblast("24", "Черкаська область", listOf("черкас", "черкащ", "уман", "сміл(а|и|і|у)$E", "золотонош", "канів", "звенигородк", "корсун", "чигирин")),
        Oblast("26", "Чернівецька область", listOf("чернівц", "чернівецьк", "буковин", "хотин", "новодністровськ")),
        Oblast(
            "25", "Чернігівська область",
            listOf("чернігів", "ніжин", "прилук", "новгород-сіверськ", "семенівк", "козел(е|ь)ц", "бахмач", "корюківк", "сновськ", "борзн"),
            frontline = true,
        ),
        Oblast("9999", "Автономна Республіка Крим", listOf("крим", "севастопол", "сімферопол", "джанкой", "феодосі", "керч", "євпатор")),
    )

    val byId: Map<String, Oblast> = oblasts.associateBy { it.id }

    /** Сусідні області (за спільним кордоном). */
    val neighbors: Map<String, List<String>> = mapOf(
        "4" to listOf("10", "14", "24", "15", "18", "3", "26"),
        "8" to listOf("5", "27"),
        "9" to listOf("22", "19", "15", "17", "23", "12", "28"),
        "28" to listOf("16", "22", "9", "12"),
        "10" to listOf("5", "3", "4", "14"),
        "11" to listOf("27", "13"),
        "12" to listOf("9", "28", "23"),
        "13" to listOf("27", "11", "26", "21"),
        "14" to listOf("10", "4", "24", "19", "25", "31"),
        "15" to listOf("4", "24", "19", "9", "17", "18"),
        "16" to listOf("22", "28"),
        "27" to listOf("8", "5", "21", "13", "11"),
        "17" to listOf("18", "15", "9", "23"),
        "18" to listOf("17", "15", "4"),
        "19" to listOf("14", "25", "20", "22", "9", "15", "24"),
        "5" to listOf("8", "27", "21", "3", "10"),
        "20" to listOf("25", "19", "22"),
        "21" to listOf("5", "3", "26", "13", "27"),
        "22" to listOf("20", "19", "9", "28", "16"),
        "23" to listOf("17", "9", "12", "9999"),
        "3" to listOf("5", "10", "4", "26", "21"),
        "24" to listOf("14", "19", "15", "4"),
        "26" to listOf("13", "21", "3", "4"),
        "25" to listOf("14", "19", "20"),
        "31" to listOf("14"),
        "9999" to listOf("23"),
    )

    private fun norm(s: String): String = s.lowercase()
        .replace("область", "")
        .replace("обл.", "")
        .replace("м.", "")
        .replace("’", "'")
        .replace("ʼ", "'")
        .trim()

    /** Пошук області за назвою (для резервного джерела, де є лише назви). */
    fun byName(name: String): Oblast? {
        val n = norm(name)
        if (n.startsWith("київ") && !n.contains("ськ")) return byId["31"]
        if (n.contains("крим") || n.contains("севастоп")) return byId["9999"]
        return oblasts.firstOrNull { norm(it.name) == n }
            ?: oblasts.firstOrNull { n.startsWith(norm(it.name).take(5)) }
    }

    // ---------- Райони (завантажуються з мережі й кешуються) ----------

    private const val REGIONS_URL = "https://siren.pp.ua/api/v3/regions"

    @Volatile
    private var districtsMem: Map<String, List<District>>? = null

    @Volatile
    private var parentMem: Map<String, String>? = null

    private fun applyCache(raw: String): Boolean = try {
        val root = JSONObject(raw)
        val o = root.getJSONObject("o")
        val m = root.getJSONObject("m")
        val d = mutableMapOf<String, List<District>>()
        o.keys().forEach { sid ->
            val arr = o.getJSONArray(sid)
            d[sid] = (0 until arr.length()).map {
                val e = arr.getJSONArray(it)
                District(e.getString(0), e.getString(1))
            }.sortedBy { it.name }
        }
        val p = mutableMapOf<String, String>()
        m.keys().forEach { k -> p[k] = m.getString(k) }
        districtsMem = d
        parentMem = p
        true
    } catch (e: Exception) {
        false
    }

    private fun ensureMem(ctx: Context) {
        if (districtsMem == null) {
            Store.regionsCache(ctx)?.let { applyCache(it) }
        }
    }

    fun cachedDistricts(ctx: Context): Map<String, List<District>>? {
        ensureMem(ctx)
        return districtsMem
    }

    /** Повертає райони всіх областей; за потреби завантажує з мережі. Може кинути виняток. */
    fun loadDistricts(ctx: Context): Map<String, List<District>> {
        cachedDistricts(ctx)?.let { return it }
        val body = Net.get(REGIONS_URL, 20_000)
        val states = JSONObject(body).getJSONArray("states")
        val o = JSONObject()
        val m = JSONObject()
        for (i in 0 until states.length()) {
            val s = states.getJSONObject(i)
            val sid = s.getString("regionId")
            val arr = JSONArray()
            val children = s.optJSONArray("regionChildIds") ?: JSONArray()
            for (j in 0 until children.length()) {
                val d = children.getJSONObject(j)
                val did = d.getString("regionId")
                if (d.optString("regionType") == "District") {
                    arr.put(JSONArray().put(did).put(d.optString("regionName")))
                }
                m.put(did, sid)
                val comms = d.optJSONArray("regionChildIds") ?: continue
                for (k in 0 until comms.length()) {
                    m.put(comms.getJSONObject(k).getString("regionId"), sid)
                }
            }
            o.put(sid, arr)
        }
        val raw = JSONObject().put("o", o).put("m", m).toString()
        if (!applyCache(raw)) throw IllegalStateException("bad regions data")
        Store.saveRegionsCache(ctx, raw)
        return districtsMem ?: emptyMap()
    }

    /** До якої області належить регіон (область, район або громада). */
    fun oblastOf(ctx: Context, regionId: String): String? {
        if (byId.containsKey(regionId)) return regionId
        ensureMem(ctx)
        return parentMem?.get(regionId)
    }
}
