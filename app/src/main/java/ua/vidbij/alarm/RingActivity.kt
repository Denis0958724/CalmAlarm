package ua.vidbij.alarm

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.OutlinedButton
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
        setContent {
            VidbijTheme(darkTheme = true) {
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
                    onStop = { send(RingService.ACTION_STOP) },
                    onSnooze = { send(RingService.ACTION_SNOOZE) },
                )
            }
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

@Composable
private fun RingScreen(note: String, onStop: () -> Unit, onSnooze: () -> Unit) {
    var now by remember { mutableStateOf(hm(System.currentTimeMillis())) }
    LaunchedEffect(Unit) {
        while (true) {
            now = hm(System.currentTimeMillis())
            delay(5_000)
        }
    }
    val bg = Brush.verticalGradient(
        listOf(Color(0xFF0E1A38), Palette.Night, Palette.Blue, Color(0xFFF2B705)),
    )
    Box(
        Modifier
            .fillMaxSize()
            .background(bg)
            .systemBarsPadding()
            .padding(28.dp),
    ) {
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Будильник", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.titleMedium)
            Text(now, color = Color.White, fontSize = 92.sp, fontWeight = FontWeight.Light)
            Text(
                note.ifBlank { "Доброго ранку! ☀️" },
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp, start = 8.dp, end = 8.dp),
            )
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onStop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                shape = RoundedCornerShape(32.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Palette.Night),
            ) {
                Text("Вимкнути", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(
                onClick = onSnooze,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(28.dp),
                border = BorderStroke(1.5.dp, Color.White.copy(alpha = 0.8f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
            ) {
                Text("Ще ${RingService.SNOOZE_MIN} хвилин", fontSize = 18.sp)
            }
        }
    }
}
