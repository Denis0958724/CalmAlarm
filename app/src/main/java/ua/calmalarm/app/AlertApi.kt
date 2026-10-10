package ua.calmalarm.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Одна активна тривога (на область, район або громаду). */
data class AlertEntry(
    val regionId: String,
    val regionType: String,
    val name: String,
    val level: Level,
    val since: Long?,
)

data class Snapshot(
    val entries: List<AlertEntry>,
    /** true — дані з резервного джерела (без районів і рівнів). */
    val fallback: Boolean,
    val time: Long = System.currentTimeMillis(),
)

data class RegionStatus(
    val active: Boolean,
    val level: Level?,
    val since: Long?,
    /** Тривога є в іншій частині області (в інших районах/громадах). */
    val partial: Boolean,
)

object AlertApi {
    /** Відкрите дзеркало API «Повітряна тривога» (ukrainealarm.com) — без ключа, з рівнями тривог. */
    private const val SIREN = "https://siren.pp.ua/api/v3/alerts"

    /** Резервне джерело — лише області, без рівнів. */
    private const val UBILLING = "https://ubilling.net.ua/aerialalerts/"

    private val KYIV: ZoneId = ZoneId.of("Europe/Kyiv")

    /** Завантажує поточні тривоги. Кидає виняток, якщо обидва джерела недоступні. */
    fun fetch(timeoutMs: Int = 5000): Snapshot {
        return try {
            parseSiren(Net.get(SIREN, timeoutMs))
        } catch (first: Exception) {
            try {
                parseUbilling(Net.get(UBILLING, (timeoutMs * 0.7).toInt()))
            } catch (second: Exception) {
                throw first
            }
        }
    }

    private fun parseSiren(body: String): Snapshot {
        val arr = JSONArray(body)
        val out = mutableListOf<AlertEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val alerts = o.optJSONArray("activeAlerts") ?: continue
            var air = false
            var red = false
            var yellow = false
            var since: Long? = null
            for (j in 0 until alerts.length()) {
                val a = alerts.getJSONObject(j)
                if (!a.optString("type").equals("AIR", ignoreCase = true)) continue
                air = true
                parseTime(a.optString("lastUpdate"))?.let { t -> since = minOf(since ?: t, t) }
                val levels = a.optJSONArray("activeAlertLevels")
                if (levels == null || levels.length() == 0) {
                    red = true // рівень не вказано — вважаємо червоною
                } else {
                    for (k in 0 until levels.length()) {
                        val l = levels.getJSONObject(k).optString("alertLevel")
                        if (l.equals("Yellow", ignoreCase = true)) yellow = true else red = true
                    }
                }
            }
            if (air) {
                out += AlertEntry(
                    regionId = o.optString("regionId"),
                    regionType = o.optString("regionType"),
                    name = o.optString("regionName"),
                    level = if (red || !yellow) Level.RED else Level.YELLOW,
                    since = since,
                )
            }
        }
        return Snapshot(out, fallback = false)
    }

    private fun parseUbilling(body: String): Snapshot {
        val states = JSONObject(body).getJSONObject("states")
        val out = mutableListOf<AlertEntry>()
        states.keys().forEach { name ->
            val s = states.optJSONObject(name) ?: return@forEach
            if (!s.optBoolean("alertnow")) return@forEach
            val ob = Regions.byName(name) ?: return@forEach
            if (out.any { it.regionId == ob.id }) return@forEach
            out += AlertEntry(ob.id, "State", ob.name, Level.RED, parseLocal(s.optString("changed")))
        }
        return Snapshot(out, fallback = true)
    }

    private fun parseTime(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        return try {
            Instant.parse(s).toEpochMilli()
        } catch (e: Exception) {
            try {
                OffsetDateTime.parse(s).toInstant().toEpochMilli()
            } catch (e2: Exception) {
                try {
                    LocalDateTime.parse(s).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
                } catch (e3: Exception) {
                    null
                }
            }
        }
    }

    private val LOCAL_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private fun parseLocal(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        return try {
            LocalDateTime.parse(s, LOCAL_FMT).atZone(KYIV).toInstant().toEpochMilli()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Чи є тривога саме у вибраному регіоні.
     * Тривога рахується, якщо її оголошено на всю область або на вибраний район.
     */
    fun statusFor(ctx: Context, snap: Snapshot, region: Region): RegionStatus {
        val ids = mutableSetOf(region.oblastId)
        region.districtId?.let { ids += it }
        val hits = snap.entries.filter { it.regionId in ids }
        if (hits.isEmpty()) {
            val partial = snap.entries.any {
                it.regionId !in ids && Regions.oblastOf(ctx, it.regionId) == region.oblastId
            }
            return RegionStatus(active = false, level = null, since = null, partial = partial)
        }
        val level = if (hits.any { it.level == Level.RED }) Level.RED else Level.YELLOW
        return RegionStatus(
            active = true,
            level = level,
            since = hits.mapNotNull { it.since }.minOrNull(),
            partial = false,
        )
    }
}
