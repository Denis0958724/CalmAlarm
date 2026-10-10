package ua.calmalarm.app

import android.content.Context
import android.text.Html
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

data class MonitorPost(val channel: String, val time: Long, val text: String)

data class ChanceResult(
    val percent: Int,
    /** Звичайна ймовірність для цього місця й години, коли поруч тихо. */
    val usualPercent: Int,
    /** Не null, якщо тривога у вибраному регіоні вже триває. */
    val alertNow: RegionStatus?,
    val reasons: List<String>,
    val posts: List<MonitorPost>,
    val mapOk: Boolean,
    val monitorsOk: Boolean,
    val time: Long = System.currentTimeMillis(),
)

/**
 * Ймовірність, що протягом 30 хвилин у вибраному місці оголосять тривогу.
 *
 * Коефіцієнти моделі (res/raw/chance_model.json) підібрані на офіційній історії тривог по районах
 * (грудень 2025 – вересень 2026) та історії повідомлень Повітряних сил і каналу «Повітряна тривога»:
 * частота тривог у вашому районі в цю годину, тривоги в інших районах області й у сусідніх областях,
 * нові тривоги за останні 20–30 хв, масштаб атаки, нещодавній відбій, повідомлення про загрози.
 */
object Chance {
    /** Канали, на яких відкалібровано модель. */
    private val CALIBRATED = listOf("kpszsu" to "Повітряні сили", "air_alert_ua" to "Повітряна тривога")

    /** Канали волонтерів-моніторів — лише для показу повідомлень. */
    private val EXTRA = listOf("war_monitor" to "monitor", "eRadarrua" to "єРадар", "deraketaua" to "Де Ракета")

    private const val MIN = 60_000L
    private const val TG_WINDOW = 30 * MIN

    private val THREAT = listOf(
        "бпла", "шахед", "дрон", "мопед", "ракет", "баліст", "каб", "реактив", "курс", "напрям", "загроз",
        "ціл", "груп", "кинджал", "калібр", "іскандер", "авіабомб", "герань", "гербер", "х-", "пролітає",
        "прямує", "рухаєт", "швидкісн", "крилат",
    )
    private val CLEAR = listOf("відбій", "чисто", "мінус", "збит", "знищен", "дорозвідк", "без загроз", "покинув", "покинули")
    private val GLOBAL = listOf(
        "міг-31", "миг-31", "міг31", "кинджал", "ту-95", "ту-160", "ту-22", "стратег", "баліст", "масован",
        "х-101", "х-555", "калібр", "пуски", "пуск ракет", "всій території", "по всій україні", "ракетна небезпека",
    )

    // ---------------- Модель ----------------

    private class Model(o: JSONObject) {
        val intercept = o.getDouble("intercept")
        val units: Map<String, Double> = o.getJSONObject("units").let { u ->
            val m = HashMap<String, Double>()
            u.keys().forEach { k -> m[normKey(k)] = u.getDouble(k) }
            m
        }
        val hour = arr(o.getJSONArray("hour"))
        val partial = o.getDouble("partial")
        val partialCount = arr(o.getJSONArray("partial_count"))
        val ownNew = o.getDouble("own_new")
        val neighbors = arr(o.getJSONArray("neighbors"))
        val totalEdges = ints(o.getJSONArray("total_edges"))
        val total = arr(o.getJSONArray("total"))
        val nbNewEdges = ints(o.getJSONArray("nb_new_edges"))
        val nbNew = arr(o.getJSONArray("nb_new"))
        val waveEdges = ints(o.getJSONArray("wave_edges"))
        val wave = arr(o.getJSONArray("wave"))
        val recentEnd = o.getDouble("recent_end")
        val usual: Map<String, Double> = o.optJSONObject("usual")?.let { u ->
            val m = HashMap<String, Double>()
            u.keys().forEach { k -> m[normKey(k)] = u.getDouble(k) }
            m
        } ?: emptyMap()
        val tg: JSONObject? = o.optJSONObject("tg")
        val tgObl = tg?.optJSONArray("obl")?.let { arr(it) } ?: doubleArrayOf(0.0, 0.0, 0.0)
        val tgDir = tg?.optDouble("dir", 0.0) ?: 0.0
        val tgDist = tg?.optDouble("dist", 0.0) ?: 0.0
        val tgGlobal = tg?.optDouble("global", 0.0) ?: 0.0
        val tgClear = tg?.optDouble("clear", 0.0) ?: 0.0

        companion object {
            fun arr(a: JSONArray) = DoubleArray(a.length()) { a.getDouble(it) }
            fun ints(a: JSONArray) = IntArray(a.length()) { a.getInt(it) }
        }
    }

    @Volatile
    private var model: Model? = null

    private fun model(ctx: Context): Model = model ?: synchronized(this) {
        model ?: ctx.resources.openRawResource(R.raw.chance_model).bufferedReader(Charsets.UTF_8).use {
            Model(JSONObject(it.readText()))
        }.also { model = it }
    }

    private fun normKey(s: String) = s.replace("'", "’").replace("ʼ", "’").trim()

    private fun bucket(v: Int, edges: IntArray): Int = edges.count { v >= it }

    private fun sigmoid(z: Double) = 1.0 / (1.0 + exp(-z))

    private fun unitKey(r: Region): String = when {
        r.oblastId == "31" -> "м. Київ|"
        r.cityName != null -> "${r.oblastName}|${r.cityName}"
        r.districtName != null -> "${r.oblastName}|${r.districtName}"
        else -> "${r.oblastName}|*"
    }

    // ---------------- Telegram ----------------

    private fun regexFor(patterns: List<String>): Regex =
        Regex("(?<!\\p{L})(?:" + patterns.joinToString("|") + ")")

    private val anyOblast: Regex by lazy { regexFor(Regions.oblasts.flatMap { it.patterns }) }

    /** Корінь назви району або міста: «Броварський район» → «бровар», «м. Харків» → «харків». */
    fun placeStem(name: String): String? {
        val first = name.lowercase().replace("’", "'").removePrefix("м. ").split(" ").firstOrNull() ?: return null
        val stem = first.replace(Regex("(ськ|цьк|зьк)ий$"), "")
        return if (stem.length >= 4) Regex.escape(stem.take(8)) else null
    }

    private fun normalize(s: String): String =
        s.lowercase().replace("’", "'").replace("ʼ", "'").replace("`", "'")

    fun parseTelegram(html: String, channel: String): List<MonitorPost> {
        val textRe = Regex("<div class=\"[^\"]*js-message_text[^\"]*\"[^>]*>(.*?)</div>", RegexOption.DOT_MATCHES_ALL)
        val timeRe = Regex("<time[^>]*datetime=\"([^\"]+)\"")
        val out = mutableListOf<MonitorPost>()
        for (part in html.split("tgme_widget_message_wrap").drop(1)) {
            val rawText = textRe.find(part)?.groupValues?.get(1) ?: continue
            val rawTime = timeRe.find(part)?.groupValues?.get(1) ?: continue
            val time = try {
                OffsetDateTime.parse(rawTime).toInstant().toEpochMilli()
            } catch (e: Exception) {
                try {
                    Instant.parse(rawTime).toEpochMilli()
                } catch (e2: Exception) {
                    continue
                }
            }
            val text = Html.fromHtml(rawText.replace("<br/>", "\n").replace("<br>", "\n"), Html.FROM_HTML_MODE_LEGACY)
                .toString().trim()
            if (text.isNotEmpty()) out += MonitorPost(channel, time, text)
        }
        return out
    }

    private fun fetchChannel(ch: String, title: String): List<MonitorPost> {
        val html = Net.get("https://t.me/s/$ch", 8000, Net.BROWSER_UA)
        val posts = parseTelegram(html, title).toMutableList()
        // Якщо на першій сторінці менше ніж 30 хв історії — дочитуємо ще одну.
        val oldest = posts.minOfOrNull { it.time }
        if (oldest != null && System.currentTimeMillis() - oldest < TG_WINDOW) {
            Regex("data-post=\"[^/]+/(\\d+)\"").findAll(html).mapNotNull { it.groupValues[1].toLongOrNull() }.minOrNull()?.let { minId ->
                runCatching {
                    posts += parseTelegram(Net.get("https://t.me/s/$ch?before=$minId", 8000, Net.BROWSER_UA), title)
                }
            }
        }
        return posts.distinctBy { it.time to it.text }
    }

    private class TgFeatures(
        val oblCount: Int,
        val dir: Boolean,
        val dist: Boolean,
        val global: Boolean,
        val clear: Boolean,
        val recentEnd: Boolean,
        val relevant: List<MonitorPost>,
    )

    private fun tgFeatures(region: Region, calibrated: List<MonitorPost>, extra: List<MonitorPost>, now: Long): TgFeatures {
        val ob = Regions.byId[region.oblastId]
        val oblRe = ob?.let { regexFor(it.patterns) }
        val stem = region.place?.let { placeStem(it) }
        val distRe = stem?.let { Regex("(?<!\\p{L})$it") }
        val mine = (ob?.patterns.orEmpty()) + listOfNotNull(stem)
        val dirRe = if (mine.isNotEmpty()) {
            Regex(
                "(?:курс(?:ом)?\\s+на|в\\s+напрямку|у\\s+напрямку|напрям(?:ок|ку)?|на|над|через|біля|повз|в|у|до)\\s+" +
                    "(?:\\p{L}+\\s+)?(?:" + mine.joinToString("|") + ")",
            )
        } else {
            null
        }
        var oblCount = 0
        var dir = false
        var dist = false
        var global = false
        var clear = false
        var recentEnd = false
        val relevant = mutableListOf<MonitorPost>()
        val endName = normalize(region.place ?: region.oblastName)

        for (p in calibrated) {
            val age = now - p.time
            if (age < -5 * MIN) continue
            val t = normalize(p.text)
            if (p.channel == "Повітряна тривога") {
                // Офіційний канал тривог: шукаємо відбій у вашому місці за останню годину.
                if (age <= 60 * MIN && t.contains("відбій тривоги") && t.contains(endName)) recentEnd = true
                continue
            }
            if (age > TG_WINDOW) continue
            val isClear = CLEAR.any { t.contains(it) }
            val isThreat = THREAT.any { t.contains(it) }
            val mentionsMine = oblRe?.containsMatchIn(t) == true || distRe?.containsMatchIn(t) == true
            if (mentionsMine) {
                relevant += p
                if (isClear) {
                    clear = true
                    oblCount++
                } else if (isThreat) {
                    oblCount++
                    if (dirRe?.containsMatchIn(t) == true) dir = true
                    if (distRe?.containsMatchIn(t) == true) dist = true
                }
            } else if (!isClear && GLOBAL.any { t.contains(it) } &&
                (!anyOblast.containsMatchIn(t) || t.contains("україн") || t.contains("всій території"))
            ) {
                global = true
                relevant += p
            }
        }
        for (p in extra) {
            if (now - p.time > 60 * MIN) continue
            val t = normalize(p.text)
            if (oblRe?.containsMatchIn(t) == true || distRe?.containsMatchIn(t) == true) relevant += p
        }
        return TgFeatures(oblCount, dir, dist, global, clear, recentEnd, relevant)
    }

    // ---------------- Обчислення ----------------

    suspend fun compute(ctx: Context, region: Region): ChanceResult = withContext(Dispatchers.IO) {
        coroutineScope {
            val snapD = async { runCatching { AlertApi.fetch(7000) }.getOrNull() }
            val cacheD = async { runCatching { Regions.loadDistricts(ctx) }.getOrNull() }
            val calD = CALIBRATED.map { (ch, title) -> async { runCatching { fetchChannel(ch, title) }.getOrNull() } }
            val extD = EXTRA.map { (ch, title) -> async { runCatching { fetchChannel(ch, title) }.getOrNull() } }
            cacheD.await()
            val snap = snapD.await()
            val cal = calD.awaitAll()
            val ext = extD.awaitAll()
            evaluate(ctx, region, snap, cal, ext)
        }
    }

    private fun evaluate(
        ctx: Context,
        region: Region,
        snap: Snapshot?,
        cal: List<List<MonitorPost>?>,
        ext: List<List<MonitorPost>?>,
    ): ChanceResult {
        val now = System.currentTimeMillis()
        val monitorsOk = cal.any { it != null }
        if (snap == null && !monitorsOk) throw IllegalStateException("no data")
        val m = model(ctx)

        val tg = tgFeatures(region, cal.filterNotNull().flatten(), ext.filterNotNull().flatten(), now)
        val posts = tg.relevant.sortedByDescending { it.time }.distinctBy { it.text }.take(6)

        if (snap != null) {
            val st = AlertApi.statusFor(ctx, snap, region)
            if (st.active) {
                return ChanceResult(100, 100, st, emptyList(), posts, mapOk = true, monitorsOk = monitorsOk)
            }
        }

        val reasons = mutableListOf<Pair<Double, String>>()
        val unitCoef = m.units[normKey(unitKey(region))]
            ?: m.units[normKey("${region.oblastName}|*")]
            ?: 0.0
        val hour = java.time.ZonedDateTime.now(KYIV_ZONE).hour
        val zUsual = m.intercept + unitCoef + m.hour[hour]
        var z = zUsual

        if (snap != null) {
            val isUnitLocal = region.districtId != null || region.cityId != null
            var total = 0
            var wave = 0
            val oblActive = mutableSetOf<String>()
            val oblNew = mutableMapOf<String, Int>()
            var pc = 0
            var partialAny = false
            var ownNew = false
            for (e in snap.entries) {
                if (!AlertApi.fresh(e, now)) continue
                val ob = Regions.oblastOf(ctx, e.regionId) ?: continue
                if (ob == "16" || ob == "9999") continue
                val isState = e.regionId == ob
                val isDistrict = Regions.isDistrict(ctx, e.regionId)
                val raions = when {
                    isState -> Regions.districtCount(ctx, ob)
                    isDistrict -> 1
                    else -> 0
                }
                val age = if (e.since != null) now - e.since else Long.MAX_VALUE
                total += raions
                if (age <= 30 * MIN) wave += raions
                if (isState || isDistrict) oblActive += ob
                if (age <= 20 * MIN && raions > 0) oblNew[ob] = (oblNew[ob] ?: 0) + raions
                if (isUnitLocal && ob == region.oblastId && e.regionId != region.districtId && e.regionId != region.cityId) {
                    if (isDistrict) {
                        pc++
                        if (age <= 20 * MIN) ownNew = true
                    }
                    partialAny = true
                }
            }
            val nbList = Regions.neighbors[region.oblastId].orEmpty().filter { it in oblActive }
            val nbNew = Regions.neighbors[region.oblastId].orEmpty().sumOf { oblNew[it] ?: 0 }

            val cPartial = (if (partialAny) m.partial else 0.0) + m.partialCount[minOf(pc, 3)]
            val cOwnNew = if (ownNew) m.ownNew else 0.0
            val cNb = m.neighbors[minOf(nbList.size, 3)]
            val cTotal = m.total[bucket(total, m.totalEdges)]
            val cNbNew = m.nbNew[bucket(nbNew, m.nbNewEdges)]
            val cWave = m.wave[bucket(wave, m.waveEdges)]
            z += cPartial + cOwnNew + cNb + cTotal + cNbNew + cWave

            if (partialAny) reasons += (cPartial + cOwnNew) to (if (pc > 0) "Тривога в інших районах вашої області: $pc" else "Тривога в іншій частині вашої області")
            if (nbList.isNotEmpty()) {
                reasons += cNb to "Тривога в сусідніх областях: " + nbList.mapNotNull { Regions.byId[it]?.short }.joinToString(", ")
            }
            if (nbNew > 0 || ownNew) reasons += (cNbNew + cOwnNew) to "Нові тривоги поруч за останні 20 хв"
            if (wave >= 4) reasons += cWave to "Хвиля нових тривог: $wave районів за 30 хв"
            if (total >= 31) reasons += cTotal to "Масштабна атака: тривога у $total районах країни"
        }

        val recent = tg.recentEnd || now - Store.lastActiveAt(ctx, region.label) <= 60 * MIN
        if (recent) {
            z += m.recentEnd
            reasons += m.recentEnd to "Нещодавно був відбій — атаки часто повертаються хвилями"
        }

        val cTg = m.tgObl[minOf(tg.oblCount, 2)] + (if (tg.dir) m.tgDir else 0.0) + (if (tg.dist) m.tgDist else 0.0)
        val cGlobal = if (tg.global) m.tgGlobal else 0.0
        val cClear = if (tg.clear) m.tgClear else 0.0
        z += cTg + cGlobal + cClear
        if (tg.oblCount > 0) {
            reasons += cTg to "Повітряні сили згадують ваш регіон: ${tg.oblCount} повідомл. за 30 хв"
        }

        val p = sigmoid(z).coerceIn(0.01, 0.97)
        val usual = (m.usual[normKey(unitKey(region))] ?: sigmoid(zUsual)).coerceIn(0.01, 0.97)
        val percent = (p * 100).roundToInt().coerceAtLeast(1)
        val usualPercent = (usual * 100).roundToInt().coerceAtLeast(1)

        val texts = reasons.filter { abs(it.first) >= 0.12 || it.first == 0.0 }
            .sortedByDescending { abs(it.first) }
            .map { it.second }
            .toMutableList()
        if (texts.isEmpty()) texts += "Поруч тривог немає — звичайний рівень для вашого району"
        if (snap == null) texts += "Карта тривог зараз недоступна — оцінка неповна"
        if (!monitorsOk) texts += "Канали Повітряних сил зараз недоступні — оцінка лише за картою"

        return ChanceResult(
            percent = percent,
            usualPercent = usualPercent,
            alertNow = null,
            reasons = texts.distinct().take(5),
            posts = posts,
            mapOk = snap != null,
            monitorsOk = monitorsOk,
        )
    }
}
