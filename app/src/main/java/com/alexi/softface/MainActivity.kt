package com.alexi.softface

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.alexi.softface.ui.CameraScreen
import com.alexi.softface.ui.EnrolScreen
import com.alexi.softface.ui.MoreScreen
import com.alexi.softface.ui.SoftFaceTheme
import com.alexi.softface.ui.TextSoft

class MainActivity : ComponentActivity() {
    private lateinit var state: AppState
    private var hasCamera by mutableStateOf(false)

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        hasCamera = res[Manifest.permission.CAMERA] == true || state.hasCameraPermission()
        if (hasCamera) state.startCamera()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        state = AppState(this, readRequest(intent))
        state.onDisplayRotation(currentRotation())
        hasCamera = state.hasCameraPermission()
        if (!hasCamera || ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            askPermissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }

        setContent {
            SoftFaceTheme {
                if (!hasCamera) {
                    Column(
                        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("Soft Face needs the camera", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(8.dp))
                        Text("Everything stays on your phone.", style = MaterialTheme.typography.bodyLarge, color = TextSoft)
                        Spacer(Modifier.height(20.dp))
                        Button(
                            onClick = { askPermissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) },
                            modifier = Modifier.heightIn(min = 56.dp),
                        ) { Text("Allow camera") }
                    }
                } else {
                    when (state.screen) {
                        Screen.CAMERA -> CameraScreen(state, onPhotoForApp = ::photoForApp, onVideoForApp = ::videoForApp)
                        Screen.ENROL -> EnrolScreen(state)
                        Screen.MORE -> MoreScreen(state)
                    }
                }
            }
        }
    }

    private fun readRequest(i: Intent?): CaptureRequest {
        val out = i?.let { IntentCompat.getParcelableExtra(it, MediaStore.EXTRA_OUTPUT, Uri::class.java) }
        return when (i?.action) {
            MediaStore.ACTION_IMAGE_CAPTURE -> CaptureRequest.Photo(out)
            MediaStore.ACTION_VIDEO_CAPTURE -> CaptureRequest.Video(out)
            else -> CaptureRequest.None
        }
    }

    /** Another app asked for a photo: take it, hand it back, close. */
    private fun photoForApp() {
        state.takePhoto { uri, bmp ->
            val req = state.request as? CaptureRequest.Photo
            if (req?.output != null) {
                setResult(if (uri != null) Activity.RESULT_OK else Activity.RESULT_CANCELED)
            } else if (bmp != null) {
                // No file given: send back a small preview, as Android expects.
                val scale = 480f / maxOf(bmp.width, bmp.height)
                val thumb = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
                setResult(Activity.RESULT_OK, Intent().putExtra("data", thumb))
            }
            finish()
        }
    }

    /** Another app asked for a video: first tap starts, second tap stops and hands it back. */
    private fun videoForApp() {
        if (!state.recording) {
            state.startRecording()
            return
        }
        state.stopRecording { uri ->
            if (uri != null) setResult(Activity.RESULT_OK, Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            else setResult(Activity.RESULT_CANCELED)
            finish()
        }
    }

    private fun currentRotation(): Int =
        @Suppress("DEPRECATION")
        (if (Build.VERSION.SDK_INT >= 30) display?.rotation else windowManager.defaultDisplay.rotation) ?: 0

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Folding, unfolding or turning the phone: keep the picture upright.
        state.onDisplayRotation(currentRotation())
    }

    override fun onStop() {
        super.onStop()
        state.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        state.release()
    }
}
