package ua.calmalarm.app

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import java.time.ZonedDateTime

/**
 * Головна логіка «розумного» будильника:
 * у звичайний час дзвонить як завжди; якщо в цей момент триває тривога потрібного рівня -
 * чекає відбою і дзвонить одразу після нього; якщо тривога триває довше за ліміт - не дзвонить.
 */
object AlarmEngine {
    private const val TAG = "CalmAlarm"
    private const val CHECK_EVERY_MS = 60_000L
    private const val RETRY_NET_MS = 30_000L

    const val NOTE_AFTER_ALERT = "Тривога закінчилась - можна вставати ☀️"
    private const val NOTE_NO_NET =
        "Не вдалося перевірити тривогу (немає інтернету), тому будильник спрацював як звичайно."

    fun shouldWait(a: Alarm, level: Level?): Boolean = when (level ?: Level.RED) {
        Level.RED -> a.skipRed
        Level.YELLOW -> a.skipYellow
    }

    private fun status(ctx: Context, region: Region): RegionStatus? = try {
        AlertApi.statusFor(ctx, AlertApi.fetch(4000), region)
    } catch (e: Throwable) {
        Log.w(TAG, "alert check failed: $e")
        null
    }

    /** Настав час будильника (або закінчилось «ще 5 хв»). */
    fun onFire(ctx: Context, id: Int, snooze: Boolean) {
        val a = Store.alarm(ctx, id) ?: return
        if (!snooze && !a.enabled) return
        if (!snooze) {
            if (a.days.isEmpty()) {
                Store.saveAlarm(ctx, a.copy(enabled = false))
            } else {
                Scheduler.schedule(ctx, a, ZonedDateTime.now().plusMinutes(1))
            }
        }
        Store.removeWaiting(ctx, id)
        Scheduler.cancelCheck(ctx, id)

        val region = Store.region(ctx)
        if (region == null || !a.waitsForAlerts) {
            ring(ctx, a, "")
            return
        }
        val now = System.currentTimeMillis()
        val st = status(ctx, region)
        when {
            st == null -> {
                // Немає інтернету - ще кілька спроб протягом ~1,5 хв, потім дзвонимо.
                Store.putWaiting(ctx, Waiting(id, now, now + a.maxWaitMin * 60_000L, fails = 1, level = null))
                Scheduler.scheduleCheck(ctx, id, now + RETRY_NET_MS)
            }
            st.active && shouldWait(a, st.level) -> {
                val w = Waiting(id, now, now + a.maxWaitMin * 60_000L, fails = 0, level = st.level)
                Store.putWaiting(ctx, w)
                Notifs.showWaiting(ctx, a, w)
                Scheduler.scheduleCheck(ctx, id, now + CHECK_EVERY_MS)
            }
            else -> ring(ctx, a, "")
        }
    }

    /** Повторна перевірка, поки будильник чекає відбою. */
    fun onCheck(ctx: Context, id: Int) {
        val w = Store.waiting(ctx, id) ?: return
        val a = Store.alarm(ctx, id)
        if (a == null) {
            Store.removeWaiting(ctx, id)
            Notifs.cancelWaiting(ctx, id)
            return
        }
        val now = System.currentTimeMillis()
        if (now >= w.deadline) {
            Store.removeWaiting(ctx, id)
            Notifs.cancelWaiting(ctx, id)
            if (w.level != null) Notifs.showGaveUp(ctx, a) else ring(ctx, a, NOTE_NO_NET)
            return
        }
        val region = Store.region(ctx)
        if (region == null || !a.waitsForAlerts) {
            finishAndRing(ctx, a, "")
            return
        }
        val st = status(ctx, region)
        if (st == null) {
            val limit = if (w.level == null) 3 else 6
            if (w.fails + 1 >= limit) {
                finishAndRing(ctx, a, NOTE_NO_NET)
            } else {
                Store.putWaiting(ctx, w.copy(fails = w.fails + 1))
                Scheduler.scheduleCheck(ctx, id, now + RETRY_NET_MS)
            }
            return
        }
        if (st.active && shouldWait(a, st.level)) {
            val nw = w.copy(fails = 0, level = st.level)
            Store.putWaiting(ctx, nw)
            Notifs.showWaiting(ctx, a, nw)
            Scheduler.scheduleCheck(ctx, id, now + CHECK_EVERY_MS)
        } else {
            finishAndRing(ctx, a, if (w.level != null) NOTE_AFTER_ALERT else "")
        }
    }

    /** Користувач вирішив не чекати відбою. */
    fun cancelWait(ctx: Context, id: Int) {
        Store.removeWaiting(ctx, id)
        Scheduler.cancelCheck(ctx, id)
        Notifs.cancelWaiting(ctx, id)
    }

    private fun finishAndRing(ctx: Context, a: Alarm, note: String) {
        Store.removeWaiting(ctx, a.id)
        ring(ctx, a, note)
    }

    fun ring(ctx: Context, a: Alarm, note: String) {
        Notifs.cancelWaiting(ctx, a.id)
        val i = Intent(ctx, RingService::class.java)
            .putExtra(Scheduler.EXTRA_ID, a.id)
            .putExtra(RingService.EXTRA_NOTE, note)
        try {
            ContextCompat.startForegroundService(ctx, i)
        } catch (e: Exception) {
            Log.e(TAG, "cannot start ring service: $e")
            Notifs.showFallbackRing(ctx, a, note)
        }
    }
}
