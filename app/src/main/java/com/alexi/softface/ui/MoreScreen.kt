package com.alexi.softface.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.alexi.softface.AppState
import com.alexi.softface.Screen

@Composable
fun MoreScreen(state: AppState) {
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    BackHandler { state.go(Screen.CAMERA) }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()
            .verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Button(onClick = { state.go(Screen.CAMERA) }, modifier = Modifier.heightIn(min = 52.dp)) { Text("Back to camera") }
            }

            Section("Face lock")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Only make up my face", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = state.faceLockOn, onCheckedChange = { state.setFaceLock(it) })
            }
            Body(
                if (state.faceLockOn) "On: other people in the shot are left alone."
                else "Off: the biggest face in the shot gets the look.",
            )
            Spacer(Modifier.height(8.dp))
            Text("How sure before it counts as you", style = MaterialTheme.typography.bodyLarge)
            Slider(value = state.strictness, onValueChange = { state.changeStrictness(it) }, modifier = Modifier.height(48.dp))
            Row { Body("Easy", Modifier.weight(1f)); Body("Strict") }
            Body("If it misses you, move this left. If it picks up friends, move it right.")
            Spacer(Modifier.height(10.dp))
            Button(onClick = { state.go(Screen.ENROL) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text(if (state.enrolled) "Set up my face again" else "Set up my face")
            }
            if (state.enrolled) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text("Delete my face data")
                }
            }
            Body("Your face is saved as numbers only, on this phone. The app has no internet access, so it can't upload anything.")

            Section("Use in other apps")
            Body("What works:")
            Bullet("Side key: Settings > Advanced features > Side button > Double press > Open app > Soft Face.")
            Bullet("Apps that ask \"Take photo with…\" or \"Record video with…\" can pick Soft Face. Tick \"Always\" to make it the default.")
            Bullet("Take the photo or video here, then share it into any app (Gallery > Share).")
            Spacer(Modifier.height(6.dp))
            Body("What Android won't allow without root:")
            Bullet("Live makeup inside WhatsApp, Instagram, Snapchat, TikTok, Zoom or Teams cameras. Those apps open the camera themselves.")
            Bullet("A \"virtual camera\" that other apps see. Android keeps this for system apps only.")
            Bullet("Adding effects to Samsung's Video call effects. Samsung doesn't let other apps plug in.")
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) } },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) { Text("Open default apps settings") }

            Section("Tips")
            Bullet("Turn on \"Drag on picture\" in the sliders to line the look up with your face.")
            Bullet("Photos and videos save to Gallery > Soft Face.")
            Spacer(Modifier.height(40.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete your face data?") },
            text = { Text("Face lock will stop working until you set it up again.") },
            confirmButton = { Button(onClick = { state.deleteFace(); confirmDelete = false }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(22.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, color = Rose)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun Body(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = TextSoft, modifier = modifier)
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text("•  ", style = MaterialTheme.typography.bodyLarge)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
