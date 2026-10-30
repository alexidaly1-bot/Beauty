package com.alexi.softface.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexi.softface.AppState
import com.alexi.softface.CaptureRequest
import com.alexi.softface.Screen
import com.alexi.softface.data.BeautySettings
import com.alexi.softface.face.LockStatus
import com.alexi.softface.gl.RenderThread
import kotlin.math.roundToInt

/** The live camera picture, drawn by the GL thread. */
@Composable
fun CameraView(render: RenderThread, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(h: SurfaceHolder) {}
                    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hgt: Int) = render.setViewSurface(h.surface, w, hgt).let {}
                    override fun surfaceDestroyed(h: SurfaceHolder) = render.setViewSurface(null, 0, 0).let {}
                })
            }
        },
    )
}

@Composable
fun CameraScreen(state: AppState, onPhotoForApp: () -> Unit, onVideoForApp: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val wide = maxWidth > 600.dp
        val half = maxHeight * 0.5f
        if (wide) {
            // Inner screen: picture on the left, sliders on the right.
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight()) { CameraArea(state, onPhotoForApp, onVideoForApp) }
                if (state.panelOpen) {
                    Box(Modifier.width(400.dp).fillMaxHeight().background(Panel).safeDrawingPadding()) {
                        SliderPanel(state, Modifier.fillMaxSize())
                    }
                }
            }
        } else {
            // Cover screen: sliders slide over the bottom of the picture.
            Box(Modifier.fillMaxSize()) {
                CameraArea(state, onPhotoForApp, onVideoForApp, controlsBottomPadding = if (state.panelOpen) half else 0.dp)
                if (state.panelOpen) {
                    Box(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(half)
                            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(Panel),
                    ) { SliderPanel(state, Modifier.fillMaxSize()) }
                }
            }
        }
    }
}

@Composable
private fun CameraArea(
    state: AppState,
    onPhotoForApp: () -> Unit,
    onVideoForApp: () -> Unit,
    controlsBottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val settings by rememberUpdatedState(state.settings)
    Box(Modifier.fillMaxSize()) {
        CameraView(state.render, Modifier.fillMaxSize())

        // Drag to move, pinch to resize the overlay (only in line-up mode).
        if (state.lineUpMode) {
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val s = settings
                        val sign = if (state.mirror) -1f else 1f
                        val dx = pan.x / size.width * state.render.visibleFracS * sign
                        val dy = pan.y / size.height * state.render.visibleFracT
                        state.update(
                            s.copy(
                                offsetX = (s.offsetX + dx).coerceIn(-0.15f, 0.15f),
                                offsetY = (s.offsetY + dy).coerceIn(-0.15f, 0.15f),
                                size = (s.size * zoom).coerceIn(0.8f, 1.2f),
                            ),
                        )
                    }
                },
            )
        }

        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
            TopBar(state)
            Spacer(Modifier.weight(1f))
            if (state.lineUpMode) {
                Hint("Drag to move. Pinch to resize. Tap Done when it lines up.")
                Spacer(Modifier.height(8.dp))
            }
            BottomBar(state, onPhotoForApp, onVideoForApp)
            Spacer(Modifier.height(controlsBottomPadding))
        }

        state.message?.let { msg ->
            Box(Modifier.align(Alignment.Center).padding(24.dp)) { Hint(msg) }
            androidx.compose.runtime.LaunchedEffect(msg) {
                kotlinx.coroutines.delay(2500)
                if (state.message == msg) state.message = null
            }
        }
    }
}

@Composable
private fun TopBar(state: AppState) {
    val engine = state.engine
    val status = engine?.status?.collectAsStateWithLifecycle()?.value
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val (text, colour) = when {
            engine == null -> "Starting…" to Color(0xFF6B6B76)
            status == LockStatus.NOT_SET_UP -> "Face lock: tap to set up" to Color(0xFF8A5A00)
            status == LockStatus.FOUND_YOU -> "Found you" to Color(0xFF1F7A4D)
            status == LockStatus.LOOKING -> "Checking face…" to Color(0xFF55556A)
            status == LockStatus.NOT_YOU -> "Not you, so no makeup" to Color(0xFF55556A)
            status == LockStatus.OFF_ANY_FACE -> "Face lock off" to Color(0xFF8A5A00)
            else -> "No face" to Color(0xFF55556A)
        }
        Pill(text, colour) {
            if (status == LockStatus.NOT_SET_UP) state.go(Screen.ENROL) else state.go(Screen.MORE)
        }
        Spacer(Modifier.weight(1f))
        if (state.fps > 0) Text("${state.fps.roundToInt()} fps", color = TextSoft, style = MaterialTheme.typography.labelMedium)
        RoundIcon(Icons.Filled.Refresh, "Flip camera") { state.flipCamera() }
        RoundIcon(Icons.Filled.Settings, "Settings") { state.go(Screen.MORE) }
    }
}

@Composable
private fun BottomBar(state: AppState, onPhotoForApp: () -> Unit, onVideoForApp: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // One tap hides or shows the sliders.
        Pill(if (state.panelOpen) "Hide sliders" else "Show sliders", Color(0xCC2A2A32)) {
            state.panelOpen = !state.panelOpen
        }
        Spacer(Modifier.weight(1f))
        when (state.request) {
            is CaptureRequest.Photo -> Shutter(recording = false, video = false) { onPhotoForApp() }
            is CaptureRequest.Video -> Shutter(recording = state.recording, video = true) { onVideoForApp() }
            CaptureRequest.None -> {
                Shutter(recording = false, video = false) { state.takePhoto() }
                Spacer(Modifier.width(14.dp))
                Shutter(recording = state.recording, video = true) {
                    if (state.recording) state.stopRecording() else state.startRecording()
                }
            }
        }
    }
}

@Composable
private fun Shutter(recording: Boolean, video: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(72.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when {
            video && recording -> Box(Modifier.size(26.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFFE53950)))
            video -> Box(Modifier.size(52.dp).clip(CircleShape).background(Color(0xFFE53950)))
            else -> Box(Modifier.size(56.dp).clip(CircleShape).background(Color.White))
        }
    }
}

@Composable
fun Pill(text: String, bg: Color, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    Row(
        Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).background(bg).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = Color.White); Spacer(Modifier.width(8.dp)) }
        Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(52.dp).clip(CircleShape).background(Color(0xCC2A2A32)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = Color.White) }
}

@Composable
fun Hint(text: String) {
    Text(
        text,
        color = Color.White,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xE0202028)).padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

// ---------------- Sliders ----------------

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun SliderPanel(state: AppState, modifier: Modifier = Modifier) {
    val s = state.settings
    var saveOpen by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<String?>(null) }

    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Your look", style = MaterialTheme.typography.titleLarge, color = TextMain)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { state.reset() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Reset") }
        }
        Spacer(Modifier.height(6.dp))

        BigSlider("Overall", s.strength) { state.update(s.copy(strength = it)) }
        BigSlider("Smooth skin", s.smooth) { state.update(s.copy(smooth = it)) }
        BigSlider("Contour", s.contour) { state.update(s.copy(contour = it)) }
        BigSlider("Blush", s.blush) { state.update(s.copy(blush = it)) }
        BigSlider("Lips", s.lips) { state.update(s.copy(lips = it)) }
        BigSlider("Bright eyes", s.eyes) { state.update(s.copy(eyes = it)) }
        BigSlider("Slim face", s.slim) { state.update(s.copy(slim = it)) }

        Spacer(Modifier.height(12.dp))
        Text("Line up", style = MaterialTheme.typography.titleMedium, color = TextMain)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Drag on picture", style = MaterialTheme.typography.bodyLarge, color = TextMain, modifier = Modifier.weight(1f))
            Switch(checked = state.lineUpMode, onCheckedChange = { state.setLineUp(it) })
        }
        CentreSlider("Left / right", s.offsetX, -0.15f..0.15f) { state.update(s.copy(offsetX = it)) }
        CentreSlider("Up / down", s.offsetY, -0.15f..0.15f) { state.update(s.copy(offsetY = it)) }
        CentreSlider("Size", s.size, 0.8f..1.2f) { state.update(s.copy(size = it)) }
        TextButton(onClick = { state.update(s.copy(offsetX = 0f, offsetY = 0f, size = 1f)) }) { Text("Centre it again") }

        Spacer(Modifier.height(12.dp))
        Text("Saved looks", style = MaterialTheme.typography.titleMedium, color = TextMain)
        Text("Tap to use. Hold your own looks to delete.", style = MaterialTheme.typography.bodyMedium, color = TextSoft)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BeautySettings.BUILT_IN.keys.forEach { name ->
                Chip(name, onClick = { state.loadPreset(name) }, onLongClick = null)
            }
            state.presets.keys.forEach { name ->
                Chip(name, onClick = { state.loadPreset(name) }, onLongClick = { toDelete = name })
            }
        }
        Spacer(Modifier.height(10.dp))
        Button(onClick = { saveOpen = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Save this look") }
        Spacer(Modifier.height(24.dp))
    }

    if (saveOpen) {
        var name by remember { mutableStateOf("My look ${state.presets.size + 1}") }
        AlertDialog(
            onDismissRequest = { saveOpen = false },
            title = { Text("Name this look") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge) },
            confirmButton = { Button(onClick = { state.savePreset(name); saveOpen = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { saveOpen = false }) { Text("Cancel") } },
        )
    }
    toDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete \"$name\"?") },
            confirmButton = { Button(onClick = { state.deletePreset(name); toDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Keep") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Chip(text: String, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    Box(
        Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFF2E2A33))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) { Text(text, color = TextMain, style = MaterialTheme.typography.labelLarge) }
}

@Composable
private fun BigSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = TextMain, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("${(value * 100).roundToInt()}%", style = MaterialTheme.typography.bodyLarge, color = TextSoft)
        }
        Slider(
            value = value, onValueChange = onChange, valueRange = 0f..1f,
            colors = SliderDefaults.colors(thumbColor = Rose, activeTrackColor = Rose, inactiveTrackColor = Color(0xFF3A3540)),
            modifier = Modifier.height(48.dp),
        )
    }
}

@Composable
private fun CentreSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    val mid = (range.start + range.endInclusive) / 2
    val shown = ((value - mid) / (range.endInclusive - mid) * 100).roundToInt()
    Column(Modifier.padding(vertical = 2.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = TextMain)
            Spacer(Modifier.weight(1f))
            Text(if (shown > 0) "+$shown" else "$shown", style = MaterialTheme.typography.bodyLarge, color = TextSoft)
        }
        Slider(
            value = value, onValueChange = onChange, valueRange = range,
            colors = SliderDefaults.colors(thumbColor = Color(0xFFB9A3FF), activeTrackColor = Color(0xFF6D5FA8), inactiveTrackColor = Color(0xFF3A3540)),
            modifier = Modifier.height(48.dp),
        )
    }
}
