package ua.calmalarm.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Повноекранний екран будильника (показується і на заблокованому екрані). */
class RingActivity : ComponentActivity() {
    private var alarmId = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        render(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render(intent)
    }

    private fun render(i: Intent) {
        alarmId = i.getIntExtra(Scheduler.EXTRA_ID, -1)
        val note = i.getStringExtra(RingService.EXTRA_NOTE).orEmpty()
        val snoozeMin = Store.alarm(this, alarmId)?.snoozeMin ?: 5
        setContent {
            CalmAlarmTheme(darkTheme = true) {
                BackHandler { /* щоб випадково не закрити — вибір лише кнопками */ }
                var wasRinging by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    RingService.ringing.collect { on ->
                        if (on) wasRinging = true
                        if (!on && wasRinging) finish()
                    }
                }
                RingScreen(
                    note = note,
                    snoozeMin = snoozeMin,
                    onStop = { send(RingService.ACTION_STOP) },
                    onSnooze = { send(RingService.ACTION_SNOOZE) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            startService(Intent(this, RingService::class.java).setAction(RingService.ACTION_SHOWN))
        } catch (_: Exception) {
        }
    }

    private fun send(action: String) {
        try {
            startService(Intent(this, RingService::class.java).setAction(action).putExtra(Scheduler.EXTRA_ID, alarmId))
        } catch (_: Exception) {
        }
        finish()
    }
}

private val UK = java.util.Locale("uk", "UA")
private val DATE_FMT: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM", UK)

@Composable
private fun RingScreen(note: String, snoozeMin: Int, onStop: () -> Unit, onSnooze: () -> Unit) {
    var now by remember { mutableStateOf(hm(System.currentTimeMillis())) }
    var date by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            now = hm(System.currentTimeMillis())
            date = java.time.LocalDate.now().format(DATE_FMT).replaceFirstChar { it.uppercase() }
            delay(5_000)
        }
    }
    val t = rememberInfiniteTransition(label = "aurora")
    val drift by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Reverse),
        label = "drift",
    )
    val pulse by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
        label = "pulse",
    )
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF070B16)),
    ) {
        // М'яке «полярне сяйво» з кольорових плям, що повільно рухаються.
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            fun blob(color: Color, cx: Float, cy: Float, r: Float) {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(color, color.copy(alpha = 0f)),
                        center = Offset(cx, cy),
                        radius = r,
                    ),
                    radius = r,
                    center = Offset(cx, cy),
                )
            }
            blob(Color(0xFF4F46E5).copy(alpha = 0.55f), w * (0.15f + 0.25f * drift), h * 0.22f, w * 0.85f)
            blob(Color(0xFF14B8A6).copy(alpha = 0.35f), w * (0.95f - 0.3f * drift), h * (0.45f + 0.08f * drift), w * 0.75f)
            blob(Color(0xFFFF8A5B).copy(alpha = 0.45f), w * (0.4f + 0.2f * drift), h * 1.02f, w * 0.95f)
            // Пульсуючі кола навколо годинника
            val c = Offset(w / 2f, h * 0.36f)
            for (k in 0 until 3) {
                val ph = (pulse + k / 3f) % 1f
                drawCircle(
                    color = Color.White.copy(alpha = 0.10f * (1f - ph)),
                    radius = w * (0.30f + 0.22f * ph),
                    center = c,
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
        }
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(0.55f))
            Text(
                date,
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                now,
                color = Color.White,
                fontSize = 104.sp,
                fontWeight = FontWeight.Thin,
                letterSpacing = (-2).sp,
            )
            Box(
                Modifier
                    .padding(top = 8.dp)
                    .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(50))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            ) {
                Text(
                    note.ifBlank { "Доброго ранку! ☀️" },
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = onSnooze,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp),
                shape = RoundedCornerShape(29.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White.copy(alpha = 0.14f),
                    contentColor = Color.White,
                ),
            ) {
                Text("Ще $snoozeMin хв", fontSize = 18.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onStop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(66.dp),
                shape = RoundedCornerShape(33.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF0B1020)),
            ) {
                Text("Вимкнути", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
