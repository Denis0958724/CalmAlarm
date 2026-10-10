package ua.calmalarm.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat

object Notifs {
    const val CH_RING = "ring"
    const val CH_STATUS = "status"
    const val CH_FALLBACK = "ring_fallback"

    const val ID_RING = 1

    private fun waitId(alarmId: Int) = 1000 + alarmId
    private fun infoId(alarmId: Int) = 2000 + alarmId

    private fun nm(ctx: Context): NotificationManager = ctx.getSystemService(NotificationManager::class.java)

    fun ensureChannels(ctx: Context) {
        val nm = nm(ctx)
        val ring = NotificationChannel(CH_RING, "Будильник", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Екран будильника, коли він дзвонить"
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val status = NotificationChannel(CH_STATUS, "Стан будильника", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Будильник чекає відбою тривоги, будильник скасовано"
        }
        val fallback = NotificationChannel(CH_FALLBACK, "Будильник (резервний)", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Використовується, якщо основний будильник не зміг запуститися"
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannels(listOf(ring, status, fallback))
    }

    private fun openAppPi(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 1, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun ringActivityIntent(ctx: Context, alarmId: Int, time: String, note: String): Intent =
        Intent(ctx, RingActivity::class.java)
            .putExtra(Scheduler.EXTRA_ID, alarmId)
            .putExtra(RingService.EXTRA_TIME, time)
            .putExtra(RingService.EXTRA_NOTE, note)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)

    fun ringing(ctx: Context, alarmId: Int, time: String, note: String): Notification {
        val full = PendingIntent.getActivity(
            ctx, 7, ringActivityIntent(ctx, alarmId, time, note),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            ctx, 8,
            Intent(ctx, RingService::class.java).setAction(RingService.ACTION_STOP).putExtra(Scheduler.EXTRA_ID, alarmId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val snooze = PendingIntent.getService(
            ctx, 9,
            Intent(ctx, RingService::class.java).setAction(RingService.ACTION_SNOOZE).putExtra(Scheduler.EXTRA_ID, alarmId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(ctx, CH_RING)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Будильник $time")
            .setContentText(note.ifBlank { "Час вставати!" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(note.ifBlank { "Час вставати!" }))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .addAction(0, "Ще ${Store.alarm(ctx, alarmId)?.snoozeMin ?: 5} хв", snooze)
            .addAction(0, "Вимкнути", stop)
            .build()
    }

    fun showWaiting(ctx: Context, a: Alarm, w: Waiting) {
        val cancel = PendingIntent.getBroadcast(
            ctx, 3000 + a.id,
            Intent(ctx, AlarmReceiver::class.java).setAction(Scheduler.ACTION_CANCEL_WAIT).putExtra(Scheduler.EXTRA_ID, a.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = "Зараз ${w.level.title()} тривога. Будильник задзвонить одразу після відбою, " +
            "але не пізніше ${hm(w.deadline)}."
        val n = NotificationCompat.Builder(ctx, CH_STATUS)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Будильник ${a.timeText} чекає відбою")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(openAppPi(ctx))
            .addAction(0, "Не чекати", cancel)
            .build()
        nm(ctx).notify(waitId(a.id), n)
    }

    fun cancelWaiting(ctx: Context, alarmId: Int) {
        nm(ctx).cancel(waitId(alarmId))
    }

    fun showGaveUp(ctx: Context, a: Alarm) {
        val text = "Тривога не закінчилась за ${waitLabel(a.maxWaitMin)}, тому будильник ${a.timeText} сьогодні не дзвонитиме."
        val n = NotificationCompat.Builder(ctx, CH_STATUS)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Будильник ${a.timeText} скасовано")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppPi(ctx))
            .setAutoCancel(true)
            .build()
        nm(ctx).notify(infoId(a.id), n)
    }

    fun showMissed(ctx: Context, time: String) {
        val n = NotificationCompat.Builder(ctx, CH_STATUS)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Пропущений будильник $time")
            .setContentText("Будильник дзвонив 10 хвилин і вимкнувся сам.")
            .setContentIntent(openAppPi(ctx))
            .setAutoCancel(true)
            .build()
        nm(ctx).notify(infoId(0), n)
    }

    /** Якщо сервіс будильника не вдалося запустити — звичайне гучне сповіщення зі звуком будильника. */
    fun showFallbackRing(ctx: Context, a: Alarm, note: String) {
        val full = PendingIntent.getActivity(
            ctx, 7, ringActivityIntent(ctx, a.id, a.timeText, note),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CH_FALLBACK)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Будильник ${a.timeText}")
            .setContentText(note.ifBlank { "Час вставати!" })
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .setAutoCancel(true)
            .build()
        n.flags = n.flags or Notification.FLAG_INSISTENT
        nm(ctx).notify(ID_RING + 50, n)
    }
}
