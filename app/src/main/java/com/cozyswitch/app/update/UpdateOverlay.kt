package com.cozyswitch.app.update

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** The card shown over the app while an update is on its way. */
@Composable
fun UpdateOverlay() {
    val u = Updater
    val ctx = LocalContext.current
    AnimatedVisibility(u.stage != UpdateStage.Idle, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
                .clickable(remember { MutableInteractionSource() }, null) { },
            contentAlignment = Alignment.Center
        ) {
            val (title, body) = when (u.stage) {
                UpdateStage.Downloading -> "A new version is here" to "Downloading build ${u.newBuild}…"
                UpdateStage.NeedsPermission -> "One quick switch" to
                    "Android needs to let Cozy Switch update itself. Tap Allow, turn the switch on, then come back."
                UpdateStage.Installing -> "Installing…" to "Tap Update. When it's done, tap Open."
                UpdateStage.Ready -> "Ready to update" to "Build ${u.newBuild} is downloaded."
                UpdateStage.Failed -> "No update this time" to u.error.orEmpty()
                UpdateStage.Idle -> "" to ""
            }
            Column(
                Modifier.padding(24.dp).widthIn(max = 380.dp).clip(RoundedCornerShape(22.dp))
                    .background(Color(0xFF1B2A3A)).padding(20.dp)
            ) {
                Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                if (u.newBuild > 0) {
                    Text("Build ${u.currentBuild}  →  ${u.newBuild}", color = Color.White.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                }
                Spacer(Modifier.height(12.dp))
                Text(body, color = Color.White, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                if (u.notes.isNotBlank() && u.stage != UpdateStage.Failed) {
                    Text("“${u.notes}”", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, maxLines = 2,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                if (u.stage == UpdateStage.Downloading) {
                    Spacer(Modifier.height(14.dp))
                    LinearProgressIndicator(
                        progress = { u.progress },
                        modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(6.dp)),
                        color = Color(0xFF4ADE80), trackColor = Color.White.copy(alpha = 0.15f)
                    )
                    Text("${(u.progress * 100).roundToInt().coerceIn(0, 100)} %", color = Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                    if (u.stage != UpdateStage.Failed) TextButton(onClick = { u.later() }) { Text("Later") }
                    when (u.stage) {
                        UpdateStage.NeedsPermission -> Button(onClick = { u.allowInstalls(ctx) }) { Text("Allow") }
                        UpdateStage.Ready -> Button(onClick = { u.installNow(ctx) }) { Text("Update now") }
                        UpdateStage.Failed -> Button(onClick = { u.later() }) { Text("OK") }
                        else -> Unit
                    }
                }
            }
        }
    }
}
