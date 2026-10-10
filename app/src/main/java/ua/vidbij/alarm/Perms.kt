package ua.vidbij.alarm

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/** Перевірка дозволів, без яких будильник може не спрацювати. */
object Perms {
    enum class Issue(val title: String, val hint: String) {
        NOTIFICATIONS("Сповіщення", "щоб показувати будильник і кнопку «Вимкнути»"),
        FULL_SCREEN("Показ на весь екран", "щоб будильник з'являвся на заблокованому екрані"),
        EXACT("Будильники в точний час", "щоб будильник спрацьовував хвилина в хвилину"),
        BATTERY("Робота у фоні", "щоб телефон не «приспав» будильник уночі"),
    }

    fun issues(ctx: Context): List<Issue> {
        val out = mutableListOf<Issue>()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            out += Issue.NOTIFICATIONS
        }
        if (Build.VERSION.SDK_INT >= 34 &&
            !ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        ) {
            out += Issue.FULL_SCREEN
        }
        if (Build.VERSION.SDK_INT >= 31 && !ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) {
            out += Issue.EXACT
        }
        val pm = ctx.getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
            out += Issue.BATTERY
        }
        return out
    }

    fun settingsIntent(ctx: Context, issue: Issue): Intent {
        val pkg = Uri.parse("package:${ctx.packageName}")
        return when (issue) {
            Issue.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            Issue.FULL_SCREEN -> if (Build.VERSION.SDK_INT >= 34) {
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg)
            } else {
                appDetails(ctx)
            }
            Issue.EXACT -> if (Build.VERSION.SDK_INT >= 31) {
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg)
            } else {
                appDetails(ctx)
            }
            Issue.BATTERY -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)
        }
    }

    fun appDetails(ctx: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))

    fun open(ctx: Context, issue: Issue) {
        try {
            ctx.startActivity(settingsIntent(ctx, issue).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            try {
                ctx.startActivity(appDetails(ctx).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
            }
        }
    }
}
