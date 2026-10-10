package ua.calmalarm.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Просте збереження всіх налаштувань у SharedPreferences. */
object Store {
    private const val PREFS = "calmalarm"
    private val lock = Any()

    const val KEY_ALARMS = "alarms"
    const val KEY_WAITING = "waiting"
    const val KEY_REGION = "region"
    private const val KEY_NEXT_ID = "next_id"
    private const val KEY_REGIONS_CACHE = "regions_cache_v3"

    fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- Будильники ----------

    fun alarms(ctx: Context): List<Alarm> = synchronized(lock) {
        val raw = prefs(ctx).getString(KEY_ALARMS, "[]") ?: "[]"
        try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { Alarm.fromJson(arr.getJSONObject(it)) }
                .sortedWith(compareBy({ it.hour }, { it.minute }))
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun alarm(ctx: Context, id: Int): Alarm? = alarms(ctx).firstOrNull { it.id == id }

    fun saveAlarm(ctx: Context, a: Alarm) = synchronized(lock) {
        val list = alarms(ctx).filter { it.id != a.id } + a
        writeAlarms(ctx, list)
    }

    fun deleteAlarm(ctx: Context, id: Int) = synchronized(lock) {
        writeAlarms(ctx, alarms(ctx).filter { it.id != id })
    }

    private fun writeAlarms(ctx: Context, list: List<Alarm>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs(ctx).edit().putString(KEY_ALARMS, arr.toString()).commit()
    }

    fun newId(ctx: Context): Int = synchronized(lock) {
        val p = prefs(ctx)
        val id = p.getInt(KEY_NEXT_ID, 1)
        p.edit().putInt(KEY_NEXT_ID, id + 1).commit()
        id
    }

    // ---------- Регіон ----------

    fun region(ctx: Context): Region? {
        val raw = prefs(ctx).getString(KEY_REGION, null) ?: return null
        return try {
            Region.fromJson(JSONObject(raw))
        } catch (e: Exception) {
            null
        }
    }

    fun saveRegion(ctx: Context, r: Region) {
        prefs(ctx).edit().putString(KEY_REGION, r.toJson().toString()).commit()
    }

    // ---------- Очікування відбою ----------

    fun waitings(ctx: Context): Map<Int, Waiting> = synchronized(lock) {
        val raw = prefs(ctx).getString(KEY_WAITING, "{}") ?: "{}"
        try {
            val o = JSONObject(raw)
            val out = mutableMapOf<Int, Waiting>()
            o.keys().forEach { k -> out[k.toInt()] = Waiting.fromJson(o.getJSONObject(k)) }
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun waiting(ctx: Context, id: Int): Waiting? = waitings(ctx)[id]

    fun putWaiting(ctx: Context, w: Waiting) = synchronized(lock) {
        val m = waitings(ctx).toMutableMap()
        m[w.alarmId] = w
        writeWaitings(ctx, m)
    }

    fun removeWaiting(ctx: Context, id: Int) {
        synchronized(lock) {
            val m = waitings(ctx).toMutableMap()
            if (m.remove(id) != null) {
                writeWaitings(ctx, m)
            }
        }
    }

    private fun writeWaitings(ctx: Context, m: Map<Int, Waiting>) {
        val o = JSONObject()
        m.forEach { (k, v) -> o.put(k.toString(), v.toJson()) }
        prefs(ctx).edit().putString(KEY_WAITING, o.toString()).commit()
    }

    // ---------- Кеш районів ----------

    fun regionsCache(ctx: Context): String? = prefs(ctx).getString(KEY_REGIONS_CACHE, null)

    fun saveRegionsCache(ctx: Context, s: String) {
        prefs(ctx).edit().putString(KEY_REGIONS_CACHE, s).apply()
    }

    // ---------- Коли востаннє бачили тривогу у вибраному регіоні ----------

    fun noteActive(ctx: Context, key: String) {
        prefs(ctx).edit().putString("last_active_key", key).putLong("last_active_at", System.currentTimeMillis()).apply()
    }

    fun lastActiveAt(ctx: Context, key: String): Long {
        val p = prefs(ctx)
        return if (p.getString("last_active_key", null) == key) p.getLong("last_active_at", 0L) else 0L
    }

    // ---------- Різне ----------

    fun flag(ctx: Context, key: String): Boolean = prefs(ctx).getBoolean(key, false)
    fun setFlag(ctx: Context, key: String) {
        prefs(ctx).edit().putBoolean(key, true).apply()
    }
}
