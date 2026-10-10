package ua.vidbij.alarm

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper

object Tones {
    val names = listOf("Світанок", "Дзвіночки", "Класичний", "Пташки", "Легкий бриз")
    val res = intArrayOf(
        R.raw.tone_sunrise,
        R.raw.tone_bells,
        R.raw.tone_classic,
        R.raw.tone_birds,
        R.raw.tone_breeze,
    )

    fun resFor(i: Int): Int = res[i.coerceIn(0, res.size - 1)]

    private var preview: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    fun playPreview(ctx: Context, i: Int) {
        stopPreview()
        val mp = MediaPlayer.create(ctx.applicationContext, resFor(i)) ?: return
        mp.isLooping = true
        mp.start()
        preview = mp
        handler.postDelayed({ stopPreview() }, 4000)
    }

    fun stopPreview() {
        handler.removeCallbacksAndMessages(null)
        preview?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        preview = null
    }
}
