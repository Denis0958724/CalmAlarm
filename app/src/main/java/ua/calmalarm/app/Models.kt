package ua.calmalarm.app

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Рівень тривоги за новою системою: червона — ракети/масована атака, жовта — дрони. */
enum class Level { RED, YELLOW }

fun Level?.title(): String = when (this) {
    Level.YELLOW -> "жовта"
    else -> "червона"
}

data class Alarm(
    val id: Int,
    val hour: Int,
    val minute: Int,
    /** 1 = понеділок … 7 = неділя. Порожньо = один раз. */
    val days: Set<Int> = emptySet(),
    val enabled: Boolean = true,
    val tone: Int = 0,
    val skipRed: Boolean = true,
    val skipYellow: Boolean = true,
    val maxWaitMin: Int = 120,
) {
    val timeText: String get() = "%02d:%02d".format(hour, minute)
    val waitsForAlerts: Boolean get() = skipRed || skipYellow

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("h", hour)
        put("m", minute)
        put("days", JSONArray(days.sorted()))
        put("on", enabled)
        put("tone", tone)
        put("red", skipRed)
        put("yellow", skipYellow)
        put("wait", maxWaitMin)
    }

    companion object {
        fun fromJson(o: JSONObject): Alarm {
            val arr = o.optJSONArray("days") ?: JSONArray()
            val d = (0 until arr.length()).map { arr.getInt(it) }.toSet()
            return Alarm(
                id = o.getInt("id"),
                hour = o.getInt("h"),
                minute = o.getInt("m"),
                days = d,
                enabled = o.optBoolean("on", true),
                tone = o.optInt("tone", 0),
                skipRed = o.optBoolean("red", true),
                skipYellow = o.optBoolean("yellow", true),
                maxWaitMin = o.optInt("wait", 120),
            )
        }
    }
}

/** Будильник, який зараз чекає відбою тривоги. */
data class Waiting(
    val alarmId: Int,
    val since: Long,
    val deadline: Long,
    val fails: Int,
    /** null — тривогу ще не підтверджено (наприклад, не було інтернету). */
    val level: Level?,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", alarmId)
        put("since", since)
        put("deadline", deadline)
        put("fails", fails)
        put("level", level?.name ?: "")
    }

    companion object {
        fun fromJson(o: JSONObject): Waiting = Waiting(
            alarmId = o.getInt("id"),
            since = o.getLong("since"),
            deadline = o.getLong("deadline"),
            fails = o.optInt("fails", 0),
            level = when (o.optString("level")) {
                "RED" -> Level.RED
                "YELLOW" -> Level.YELLOW
                else -> null
            },
        )
    }
}

data class Region(
    val oblastId: String,
    val oblastName: String,
    val districtId: String? = null,
    val districtName: String? = null,
) {
    val subtitle: String get() = districtName ?: "Вся область"
    val label: String get() = if (districtName != null) "$oblastName · $districtName" else oblastName

    fun toJson(): JSONObject = JSONObject().apply {
        put("oid", oblastId)
        put("on", oblastName)
        put("did", districtId ?: "")
        put("dn", districtName ?: "")
    }

    companion object {
        fun fromJson(o: JSONObject): Region = Region(
            oblastId = o.getString("oid"),
            oblastName = o.getString("on"),
            districtId = o.optString("did").ifBlank { null },
            districtName = o.optString("dn").ifBlank { null },
        )
    }
}

val DAY_SHORT = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Нд")
val WEEKDAYS = setOf(1, 2, 3, 4, 5)
val ALL_DAYS = setOf(1, 2, 3, 4, 5, 6, 7)

fun daysText(days: Set<Int>): String = when {
    days.isEmpty() -> "Один раз"
    days.size == 7 -> "Щодня"
    days == WEEKDAYS -> "Будні"
    days == setOf(6, 7) -> "Вихідні"
    else -> days.sorted().joinToString(", ") { DAY_SHORT[it - 1] }
}

/** Варіанти «не дзвонити взагалі, якщо тривога триває довше ніж…». */
val WAIT_OPTIONS = listOf(
    30 to "30 хв",
    60 to "1 год",
    90 to "1,5 год",
    120 to "2 год",
    180 to "3 год",
    240 to "4 год",
)

fun waitLabel(min: Int): String = WAIT_OPTIONS.firstOrNull { it.first == min }?.second ?: "$min хв"

private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

fun hm(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(HM)

fun untilText(ms: Long): String {
    val totalMin = ((ms + 59_999) / 60_000).coerceAtLeast(1)
    val d = totalMin / (60 * 24)
    val h = (totalMin / 60) % 24
    val m = totalMin % 60
    return buildString {
        if (d > 0) append("$d д ")
        if (h > 0) append("$h год ")
        if (m > 0 || (d == 0L && h == 0L)) append("$m хв")
    }.trim()
}
