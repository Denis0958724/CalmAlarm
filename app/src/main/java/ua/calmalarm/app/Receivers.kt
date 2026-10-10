package ua.calmalarm.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import kotlin.concurrent.thread

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        val id = intent.getIntExtra(Scheduler.EXTRA_ID, -1)
        val action = intent.action
        if (id < 0 || action == null) return
        val pending = goAsync()
        val wl = ctx.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "calmalarm:alarm")
        wl.acquire(20_000)
        thread(name = "calmalarm-alarm") {
            try {
                when (action) {
                    Scheduler.ACTION_FIRE -> AlarmEngine.onFire(ctx, id, intent.getBooleanExtra(Scheduler.EXTRA_SNOOZE, false))
                    Scheduler.ACTION_CHECK -> AlarmEngine.onCheck(ctx, id)
                    Scheduler.ACTION_CANCEL_WAIT -> AlarmEngine.cancelWait(ctx, id)
                }
            } catch (e: Throwable) {
                Log.e("CalmAlarm", "alarm handling failed", e)
                // Безпечний варіант: краще розбудити, ніж проспати.
                if (action == Scheduler.ACTION_FIRE) {
                    Store.alarm(ctx, id)?.let { AlarmEngine.ring(ctx, it, "") }
                }
            } finally {
                try {
                    if (wl.isHeld) wl.release()
                } catch (_: Exception) {
                }
                pending.finish()
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Scheduler.scheduleAll(context.applicationContext)
    }
}
