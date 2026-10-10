package ua.calmalarm.app

import android.content.Context
import android.text.Html
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt

data class MonitorPost(val channel: String, val time: Long, val text: String)

data class ChanceResult(
    val percent: Int,
    /** Не null, якщо тривога у вибраному регіоні вже триває. */
    val alertNow: RegionStatus?,
    val reasons: List<String>,
    val posts: List<MonitorPost>,
    val monitorsOk: Int,
    val monitorsTotal: Int,
    val mapOk: Boolean,
    val time: Long = System.currentTimeMillis(),
)

/**
 * Оцінка ймовірності, що найближчим часом (≈30 хв) у вибраному регіоні оголосять тривогу.
 * Джерела: поточна карта тривог (сусідні області, масштаб атаки) і публічні Telegram-канали моніторів.
 */
object Chance {
    val CHANNELS = listOf(
        "kpszsu" to "Повітряні сили",
        "war_monitor" to "monitor",
        "eRadarrua" to "єРадар",
        "deraketaua" to "Де Ракета",
    )

    private const val WINDOW_MIN = 60.0
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    private val THREAT = listOf(
        "бпла", "шахед", "дрон", "мопед", "ракет", "баліст", "каб", "реактив", "курс", "напрям", "загроз",
        "ціл", "груп", "кинджал", "калібр", "іскандер", "вибух", "ппо", "авіабомб", "герань", "гербер", "х-",
        "пролітає", "прямує", "рухаєт", "швидкісн", "крилат",
    )
    private val CLEAR = listOf(
        "відбій", "чисто", "мінус", "збит", "знищен", "дорозвідк", "без загроз", "спокійно", "покинув", "покинули",
    )
    private val GLOBAL = listOf(
        "міг-31", "миг-31", "міг31", "кинджал", "ту-95", "ту-160", "ту-22", "стратег", "баліст", "масован",
        "х-101", "х-555", "калібр", "пуски", "пуск ракет", "всій території", "по всій україні",
        "ракетна небезпека", "ракетна загроза",
    )

    private fun regexFor(patterns: List<String>): Regex =
        Regex("(?<!\\p{L})(?:" + patterns.joinToString("|") + ")")

    private val anyOblast: Regex by lazy { regexFor(Regions.oblasts.flatMap { it.patterns }) }

    /** Корінь назви району: «Броварський район» → «бровар». */
    private fun districtStem(name: String): String? {
        val first = name.lowercase().replace("’", "'").split(" ").firstOrNull() ?: return null
        val stem = first.replace(Regex("(ськ|цьк|зьк)ий$"), "")
        return if (stem.length >= 4) Regex.escape(stem) else null
    }

    private fun normalize(s: String): String = s.lowercase().replace("’", "'").replace("ʼ", "'").replace("`", "'")

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

    suspend fun compute(ctx: Context, region: Region): ChanceResult = withContext(Dispatchers.IO) {
        coroutineScope {
            val snapD = async { runCatching { AlertApi.fetch(7000) }.getOrNull() }
            val cacheD = async { runCatching { Regions.loadDistricts(ctx) }.getOrNull() }
            val postsD = CHANNELS.map { (ch, title) ->
                async {
                    runCatching { parseTelegram(Net.get("https://t.me/s/$ch", 8000, Net.BROWSER_UA), title) }.getOrNull()
                }
            }
            cacheD.await()
            val snap = snapD.await()
            val postLists = postsD.awaitAll()
            evaluate(ctx, region, snap, postLists)
        }
    }

    private fun evaluate(
        ctx: Context,
        region: Region,
        snap: Snapshot?,
        postLists: List<List<MonitorPost>?>,
    ): ChanceResult {
        val now = System.currentTimeMillis()
        val monitorsOk = postLists.count { it != null }
        if (snap == null && monitorsOk == 0) throw IllegalStateException("no data")

        val oblast = Regions.byId[region.oblastId]
        val reasons = mutableListOf<Pair<Double, String>>()

        // 1. Тривога вже триває?
        if (snap != null) {
            val st = AlertApi.statusFor(ctx, snap, region)
            if (st.active) {
                return ChanceResult(
                    percent = 100, alertNow = st, reasons = emptyList(), posts = emptyList(),
                    monitorsOk = monitorsOk, monitorsTotal = CHANNELS.size, mapOk = true,
                )
            }
        }

        // 2. Карта тривог: сусіди, частина області, масштаб.
        var pNeighbors = 0.0
        var pOwnPart = 0.0
        var pScale = 0.0
        if (snap != null) {
            val fresh = snap.entries.filter { it.since == null || now - it.since < DAY_MS }
            val byOblast = mutableMapOf<String, Level>()
            val wholeOblast = mutableSetOf<String>()
            for (e in fresh) {
                val ob = Regions.oblastOf(ctx, e.regionId) ?: continue
                if (byOblast[ob] != Level.RED) byOblast[ob] = e.level
                if (e.regionId == ob) wholeOblast += ob
            }
            val nb = Regions.neighbors[region.oblastId].orEmpty().filter { it in byOblast }
            for (id in nb) {
                val w = if (byOblast[id] == Level.RED) 0.15 else 0.09
                pNeighbors += w * (if (id in wholeOblast) 1.0 else 0.6)
            }
            pNeighbors = min(0.45, pNeighbors)
            if (nb.isNotEmpty()) {
                val names = nb.mapNotNull { Regions.byId[it]?.short }.joinToString(", ")
                reasons += pNeighbors to "Тривога поруч: $names"
            }
            if (byOblast.containsKey(region.oblastId)) {
                pOwnPart = 0.30
                reasons += pOwnPart to "Тривога в іншій частині вашої області"
            }
            val n = byOblast.keys.count { it != "9999" }
            pScale = when {
                n >= 15 -> 0.25
                n >= 8 -> 0.12
                n >= 4 -> 0.05
                else -> 0.0
            }
            if (pScale > 0) reasons += pScale to "Тривога зараз у $n областях"
        }

        // 3. Монітори в Telegram.
        val mine = mutableListOf<String>()
        oblast?.patterns?.let { mine += it }
        region.districtName?.let { name -> districtStem(name)?.let { mine += it } }
        val myRe = if (mine.isNotEmpty()) regexFor(mine) else null
        val dirRe = if (mine.isNotEmpty()) {
            Regex(
                "(?:курс(?:ом)?\\s+на|в\\s+напрямку|у\\s+напрямку|напрям(?:ок|ку)?|на|над|через|біля|повз|в|у|до)\\s+" +
                    "(?:\\p{L}+\\s+)?(?:" + mine.joinToString("|") + ")",
            )
        } else {
            null
        }

        var regScore = 0.0
        var clearScore = 0.0
        var regCount = 0
        var gScore = 0.0
        val relevant = mutableListOf<MonitorPost>()
        val allPosts = postLists.filterNotNull().flatten().sortedBy { it.time }
        for (p in allPosts) {
            val age = ((now - p.time) / 60_000.0).coerceAtLeast(0.0)
            if (age > WINDOW_MIN) continue
            val w = exp(-age / 15.0)
            val t = normalize(p.text)
            val clear = CLEAR.any { t.contains(it) }
            if (myRe != null && myRe.containsMatchIn(t)) {
                relevant += p
                if (clear) {
                    clearScore += w
                } else if (THREAT.any { t.contains(it) }) {
                    val strong = dirRe?.containsMatchIn(t) == true
                    regScore += w * (if (strong) 1.0 else 0.6)
                    regCount++
                }
            } else if (!clear && GLOBAL.any { t.contains(it) } &&
                (!anyOblast.containsMatchIn(t) || t.contains("україн") || t.contains("всій території"))
            ) {
                gScore += w
                relevant += p
            }
        }
        val pTg = 1 - exp(-0.9 * (regScore - 0.7 * clearScore).coerceAtLeast(0.0))
        if (regCount > 0 && pTg > 0.02) {
            reasons += pTg to "Монітори пишуть про ваш регіон: $regCount повідомл. за годину"
        } else if (clearScore > 0.3 && regCount == 0) {
            reasons += 0.0 to "Монітори повідомляють, що у вашому регіоні чисто"
        }
        val pGlobal = min(0.5, 1 - exp(-0.5 * gScore))
        if (pGlobal > 0.03) reasons += pGlobal to "Загроза для багатьох областей (ракети, авіація)"

        // 4. Базовий фон.
        val pBase = if (oblast?.frontline == true) 0.10 else 0.04
        if (oblast?.frontline == true) reasons += 0.01 to "Прифронтовий або прикордонний регіон — тривоги частіші"

        val parts = listOf(pBase, pNeighbors, pOwnPart, pScale, pTg, pGlobal)
        var keep = 1.0
        parts.forEach { keep *= (1 - it.coerceIn(0.0, 0.99)) }
        val p = (1 - keep).coerceIn(0.02, 0.97)
        val percent = (p * 100).roundToInt()

        val reasonTexts = reasons.sortedByDescending { it.first }.map { it.second }.toMutableList()
        if (reasonTexts.isEmpty() || (percent < 15 && reasons.none { it.first >= 0.05 })) {
            reasonTexts.add(0, "Поруч тривог немає, монітори про ваш регіон не пишуть")
        }
        if (snap == null) reasonTexts += "Карта тривог зараз недоступна — оцінка лише за моніторами"
        if (monitorsOk == 0) reasonTexts += "Канали моніторів зараз недоступні — оцінка лише за картою тривог"

        return ChanceResult(
            percent = percent,
            alertNow = null,
            reasons = reasonTexts.distinct().take(5),
            posts = relevant.sortedByDescending { it.time }.take(6),
            monitorsOk = monitorsOk,
            monitorsTotal = CHANNELS.size,
            mapOk = snap != null,
        )
    }
}
