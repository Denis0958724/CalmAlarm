package ua.vidbij.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.ZonedDateTime

object Scheduler {
    const val ACTION_FIRE = "ua.vidbij.alarm.FIRE"
    const val ACTION_CHECK = "ua.vidbij.alarm.CHECK"
    const val ACTION_CANCEL_WAIT = "ua.vidbij.alarm.CANCEL_WAIT"
    const val EXTRA_ID = "id"
    const val EXTRA_SNOOZE = "snooze"

    private fun am(ctx: Context): AlarmManager = ctx.getSystemService(AlarmManager::class.java)

    /** Найближчий момент спрацювання (строго після [from]). */
    fun next(a: Alarm, from: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime {
        var c = from.withHour(a.hour).withMinute(a.minute).withSecond(0).withNano(0)
        if (!c.isAfter(from)) c = c.plusDays(1)
        if (a.days.isEmpty()) return c
        repeat(8) {
            if (c.dayOfWeek.value in a.days) return c
            c = c.plusDays(1)
        }
        return c
    }

    private fun pi(ctx: Context, action: String, id: Int, code: Int, snooze: Boolean = false): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java)
            .setAction(action)
            .putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_SNOOZE, snooze)
        return PendingIntent.getBroadcast(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun showPi(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun setClock(ctx: Context, at: Long, op: PendingIntent) {
        val am = am(ctx)
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op)
                return
            }
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, showPi(ctx)), op)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op)
        }
    }

    private fun fireCode(id: Int) = id * 4
    private fun checkCode(id: Int) = id * 4 + 1
    private fun snoozeCode(id: Int) = id * 4 + 2

    /** Повертає час спрацювання або null, якщо будильник вимкнено. */
    fun schedule(ctx: Context, a: Alarm, from: ZonedDateTime = ZonedDateTime.now()): Long? {
        if (!a.enabled) {
            am(ctx).cancel(pi(ctx, ACTION_FIRE, a.id, fireCode(a.id)))
            return null
        }
        val at = next(a, from).toInstant().toEpochMilli()
        setClock(ctx, at, pi(ctx, ACTION_FIRE, a.id, fireCode(a.id)))
        return at
    }

    fun cancelAll(ctx: Context, id: Int) {
        val am = am(ctx)
        am.cancel(pi(ctx, ACTION_FIRE, id, fireCode(id)))
        am.cancel(pi(ctx, ACTION_CHECK, id, checkCode(id)))
        am.cancel(pi(ctx, ACTION_FIRE, id, snoozeCode(id), snooze = true))
    }

    fun scheduleCheck(ctx: Context, id: Int, at: Long) {
        setClock(ctx, at, pi(ctx, ACTION_CHECK, id, checkCode(id)))
    }

    fun cancelCheck(ctx: Context, id: Int) {
        am(ctx).cancel(pi(ctx, ACTION_CHECK, id, checkCode(id)))
    }

    fun scheduleSnooze(ctx: Context, id: Int, minutes: Int) {
        val at = System.currentTimeMillis() + minutes * 60_000L
        setClock(ctx, at, pi(ctx, ACTION_FIRE, id, snoozeCode(id), snooze = true))
    }

    /** Перепланувати все (після перезавантаження, зміни часу, запуску застосунку). */
    fun scheduleAll(ctx: Context) {
        Store.alarms(ctx).forEach { schedule(ctx, it) }
        val now = System.currentTimeMillis()
        Store.waitings(ctx).values.forEach { w ->
            if (w.deadline > now && Store.alarm(ctx, w.alarmId) != null) {
                scheduleCheck(ctx, w.alarmId, now + 20_000)
            } else {
                Store.removeWaiting(ctx, w.alarmId)
                Notifs.cancelWaiting(ctx, w.alarmId)
            }
        }
    }
}
