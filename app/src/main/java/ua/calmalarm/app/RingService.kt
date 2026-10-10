package ua.calmalarm.app

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Відтворює мелодію будильника як сервіс переднього плану. */
class RingService : Service() {

    companion object {
        const val ACTION_STOP = "ua.calmalarm.app.STOP"
        const val ACTION_SNOOZE = "ua.calmalarm.app.SNOOZE"
        const val EXTRA_NOTE = "note"
        const val EXTRA_TIME = "time"
        const val SNOOZE_MIN = 5
        private const val AUTO_STOP_MS = 10 * 60 * 1000L

        private val _ringing = MutableStateFlow(false)
        val ringing: StateFlow<Boolean> = _ringing
    }

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())
    private var volume = 0.2f
    private var currentId = -1
    private var currentTime = ""

    private val alarmAttrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private val ramp = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (volume < 1f) {
                volume = (volume + 0.05f).coerceAtMost(1f)
                try {
                    p.setVolume(volume, volume)
                } catch (_: Exception) {
                }
                handler.postDelayed(this, 1500)
            }
        }
    }

    private val autoStop = Runnable {
        Notifs.showMissed(this, currentTime)
        stopRinging()
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRinging()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SNOOZE -> {
                val id = intent.getIntExtra(Scheduler.EXTRA_ID, currentId)
                if (id >= 0) Scheduler.scheduleSnooze(this, id, SNOOZE_MIN)
                stopRinging()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val id = intent?.getIntExtra(Scheduler.EXTRA_ID, -1) ?: -1
        val note = intent?.getStringExtra(EXTRA_NOTE).orEmpty()
        val alarm = Store.alarm(this, id)
        currentId = id
        currentTime = alarm?.timeText ?: hm(System.currentTimeMillis())

        val notification = Notifs.ringing(this, id, currentTime, note)
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        try {
            ServiceCompat.startForeground(this, Notifs.ID_RING, notification, type)
        } catch (e: Exception) {
            Log.e("CalmAlarm", "startForeground failed", e)
            alarm?.let { Notifs.showFallbackRing(this, it, note) }
            stopSelf()
            return START_NOT_STICKY
        }

        startSound(alarm?.tone ?: 0)
        startVibration()
        _ringing.value = true
        handler.removeCallbacks(autoStop)
        handler.postDelayed(autoStop, AUTO_STOP_MS)

        // Якщо екран увімкнено і дозволено — одразу відкриваємо екран будильника.
        try {
            startActivity(Notifs.ringActivityIntent(this, id, currentTime, note))
        } catch (_: Exception) {
            // Android може заборонити відкривати екран з фону — тоді спрацює повноекранне сповіщення.
        }
        return START_NOT_STICKY
    }

    private fun startSound(tone: Int) {
        stopSound()
        volume = 0.2f
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(alarmAttrs)
            val afd = resources.openRawResourceFd(Tones.resFor(tone))
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            mp.isLooping = true
            mp.setVolume(volume, volume)
            mp.prepare()
            mp.start()
            player = mp
        } catch (e: Exception) {
            Log.e("CalmAlarm", "tone failed, using system alarm sound", e)
            mp.release()
            try {
                val fallback = MediaPlayer()
                fallback.setAudioAttributes(alarmAttrs)
                fallback.setDataSource(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
                fallback.isLooping = true
                fallback.prepare()
                fallback.start()
                player = fallback
            } catch (e2: Exception) {
                Log.e("CalmAlarm", "system alarm sound failed", e2)
            }
        }
        handler.removeCallbacks(ramp)
        handler.postDelayed(ramp, 1500)
    }

    @Suppress("DEPRECATION")
    private fun startVibration() {
        val v = getSystemService(Vibrator::class.java) ?: return
        if (!v.hasVibrator()) return
        vibrator = v
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 700, 700), 0)
        try {
            v.vibrate(effect, alarmAttrs)
        } catch (e: Exception) {
            try {
                v.vibrate(effect)
            } catch (_: Exception) {
            }
        }
    }

    private fun stopSound() {
        handler.removeCallbacks(ramp)
        player?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        player = null
    }

    private fun stopRinging() {
        handler.removeCallbacks(autoStop)
        stopSound()
        try {
            vibrator?.cancel()
        } catch (_: Exception) {
        }
        vibrator = null
        _ringing.value = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopRinging()
        super.onDestroy()
    }
}
