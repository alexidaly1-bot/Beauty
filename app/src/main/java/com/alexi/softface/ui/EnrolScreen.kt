package com.alexi.softface.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.alexi.softface.AppState
import com.alexi.softface.Screen
import com.alexi.softface.face.EnrolResult

private val STEPS = listOf(
    "Look straight at the screen",
    "Turn your head a little to the left",
    "Turn your head a little to the right",
    "Tip your chin up a little",
    "Tip your chin down a little",
)

/** Face lock set-up: 5 quick photos from different angles. Only numbers are kept, never the photos. */
@Composable
fun EnrolScreen(state: AppState) {
    val shots = remember { mutableStateListOf<FloatArray>() }
    var problem by remember { mutableStateOf<String?>(null) }
    var waiting by remember { mutableStateOf(false) }
    val done = shots.size >= STEPS.size

    BackHandler { state.go(Screen.CAMERA) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        CameraView(state.render, Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Panel).padding(16.dp),
            ) {
                Text("Set up face lock", style = MaterialTheme.typography.titleLarge, color = TextMain)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Only you in the shot. Good light. Your face stays on this phone.",
                    style = MaterialTheme.typography.bodyMedium, color = TextSoft,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    STEPS.indices.forEach { i ->
                        Box(
                            Modifier.size(18.dp).clip(CircleShape)
                                .background(if (i < shots.size) Rose else Color(0xFF3A3540)),
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            val prompt = when {
                done -> "All done! Tap Save."
                else -> "${shots.size + 1} of ${STEPS.size}: ${STEPS[shots.size]}"
            }
            Hint(problem ?: prompt)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { if (shots.isEmpty()) state.go(Screen.CAMERA) else { shots.clear(); problem = null } },
                    modifier = Modifier.heightIn(min = 56.dp),
                ) { Text(if (shots.isEmpty()) "Cancel" else "Start again") }
                Spacer(Modifier.weight(1f))
                if (done) {
                    Button(
                        onClick = { state.saveEnrolment(shots.toList()); state.go(Screen.CAMERA) },
                        modifier = Modifier.heightIn(min = 56.dp),
                    ) { Text("Save") }
                } else {
                    Button(
                        enabled = !waiting && state.engine != null,
                        onClick = {
                            waiting = true
                            state.takeEnrolShot { r ->
                                waiting = false
                                when (r) {
                                    is EnrolResult.Ok -> { shots.add(r.embedding); problem = null }
                                    is EnrolResult.Problem -> problem = r.message
                                }
                            }
                        },
                        modifier = Modifier.heightIn(min = 56.dp),
                    ) { Text(if (waiting) "Wait…" else "Take photo") }
                }
            }
        }
    }
}
