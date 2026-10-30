package com.alexi.softface

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.alexi.softface.camera.CameraPipeline
import com.alexi.softface.data.BeautySettings
import com.alexi.softface.data.SettingsStore
import com.alexi.softface.face.EnrolResult
import com.alexi.softface.face.FaceEngine
import com.alexi.softface.face.FaceStore
import com.alexi.softface.gl.RenderThread
import com.alexi.softface.record.MediaSaver
import com.alexi.softface.record.VideoRecorder
import kotlin.concurrent.thread

/** What another app asked us for (or nothing, when opened normally). */
sealed interface CaptureRequest {
    data object None : CaptureRequest
    class Photo(val output: Uri?) : CaptureRequest
    class Video(val output: Uri?) : CaptureRequest
}

enum class Screen { CAMERA, ENROL, MORE }

/** Everything the screens need. Lives as long as the activity. */
class AppState(private val activity: ComponentActivity, val request: CaptureRequest) {
    private val main = Handler(Looper.getMainLooper())
    val prefs = SettingsStore(activity)
    val faceStore = FaceStore(activity)
    val render = RenderThread()

    var engine: FaceEngine? by mutableStateOf(null)
        private set
    private var pipeline: CameraPipeline? = null

    var screen by mutableStateOf(Screen.CAMERA)
    var settings by mutableStateOf(prefs.loadCurrent())
        private set
    var presets by mutableStateOf(prefs.loadPresets())
        private set
    var front by mutableStateOf(prefs.useFrontCamera)
        private set
    var faceLockOn by mutableStateOf(prefs.faceLockOn)
        private set
    var strictness by mutableFloatStateOf(prefs.strictness)
        private set
    var enrolled by mutableStateOf(faceStore.isEnrolled)
        private set
    var panelOpen by mutableStateOf(true)
    var lineUpMode by mutableStateOf(false)
        private set
    var recording by mutableStateOf(false)
        private set
    var busySaving by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
    var fps by mutableFloatStateOf(0f)
        private set

    private var recorder: VideoRecorder? = null
    private var recordingTarget: Triple<Uri, android.os.ParcelFileDescriptor, Boolean>? = null
    private var displayRotation = 0

    init {
        render.settings = settings
        render.onFps = { f -> main.post { fps = f } }
        thread(name = "SoftFaceInit") {
            val e = FaceEngine(activity, faceStore)
            main.post {
                e.faceLockOn = faceLockOn
                e.threshold = thresholdFor(strictness)
                engine = e
                pipeline = CameraPipeline(activity, e, render)
                startCamera()
            }
        }
    }

    fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    fun startCamera() {
        val p = pipeline ?: return
        if (!hasCameraPermission()) return
        val useFront = if (screen == Screen.ENROL) true else front
        p.bind(activity, useFront, displayRotation) { msg -> message = msg }
    }

    fun onDisplayRotation(rotation: Int) {
        displayRotation = rotation
        pipeline?.setDisplayRotation(rotation)
    }

    fun go(to: Screen) {
        if (recording) return
        val wasEnrol = screen == Screen.ENROL
        screen = to
        render.effectsOn = to != Screen.ENROL
        if (to == Screen.ENROL || wasEnrol) startCamera()
        if (to != Screen.CAMERA) setLineUp(false)
    }

    // ---- look ----

    fun update(s: BeautySettings) {
        settings = s
        render.settings = s
        prefs.saveCurrent(s)
    }

    fun reset() = update(BeautySettings())

    fun loadPreset(name: String) {
        val p = BeautySettings.BUILT_IN[name] ?: presets[name] ?: return
        // Keep your line-up when switching looks.
        update(p.copy(offsetX = settings.offsetX, offsetY = settings.offsetY, size = settings.size))
        message = "Loaded \"$name\""
    }

    fun savePreset(name: String) {
        val clean = name.trim().ifEmpty { return }
        presets = presets + (clean to settings)
        prefs.savePresets(presets)
        message = "Saved \"$clean\""
    }

    fun deletePreset(name: String) {
        presets = presets - name
        prefs.savePresets(presets)
    }

    fun setLineUp(on: Boolean) {
        lineUpMode = on
        render.showMask = on
    }

    // ---- camera ----

    fun flipCamera() {
        if (recording) return
        front = !front
        prefs.useFrontCamera = front
        startCamera()
    }

    val mirror get() = pipeline?.front ?: front

    // ---- face lock ----

    fun setFaceLock(on: Boolean) {
        faceLockOn = on
        prefs.faceLockOn = on
        engine?.faceLockOn = on
    }

    fun changeStrictness(v: Float) {
        strictness = v
        prefs.strictness = v
        engine?.threshold = thresholdFor(v)
    }

    private fun thresholdFor(v: Float) = 0.32f + v * 0.28f

    fun takeEnrolShot(cb: (EnrolResult) -> Unit) {
        val e = engine ?: return
        e.requestEnrolShot { r -> main.post { cb(r) } }
    }

    fun saveEnrolment(list: List<FloatArray>) {
        faceStore.save(list)
        enrolled = true
        engine?.resetTracking()
        setFaceLock(true)
    }

    fun deleteFace() {
        faceStore.clear()
        enrolled = false
        engine?.resetTracking()
        message = "Your face data is deleted."
    }

    // ---- photo / video ----

    fun takePhoto(onDone: (Uri?, Bitmap?) -> Unit = { _, _ -> }) {
        if (busySaving) return
        busySaving = true
        render.capturePhoto { bmp ->
            thread(name = "SoftFaceSave") {
                val target = (request as? CaptureRequest.Photo)?.output
                val uri = runCatching { MediaSaver.savePhoto(activity, bmp, target) }.getOrNull()
                main.post {
                    busySaving = false
                    if (request is CaptureRequest.None) message = if (uri != null) "Saved to Gallery" else "Couldn't save the photo"
                    onDone(uri, bmp)
                }
            }
        }
    }

    fun startRecording(): Boolean {
        if (recording) return false
        val w = render.frameW
        val h = render.frameH
        if (w == 0 || h == 0) return false
        val target = (request as? CaptureRequest.Video)?.output
        val (uri, pfd) = MediaSaver.openVideo(activity, target) ?: run { message = "Couldn't make a video file"; return false }
        val withAudio = ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val r = runCatching { VideoRecorder(w, h, pfd.fileDescriptor, withAudio) }.getOrElse {
            pfd.close(); message = "Video didn't start"; return false
        }
        r.start()
        render.startEncoder(r.inputSurface, r.width, r.height)
        recorder = r
        recordingTarget = Triple(uri, pfd, target == null)
        recording = true
        return true
    }

    fun stopRecording(onDone: (Uri?) -> Unit = {}) {
        val r = recorder ?: return
        val t = recordingTarget
        recorder = null
        recordingTarget = null
        busySaving = true
        render.stopEncoder {
            thread(name = "SoftFaceStop") {
                runCatching { r.stop() }
                t?.let { MediaSaver.finishVideo(activity, it.first, it.second, it.third) }
                main.post {
                    recording = false
                    busySaving = false
                    if (request is CaptureRequest.None) message = "Video saved to Gallery"
                    onDone(t?.first)
                }
            }
        }
    }

    fun onStop() {
        if (recording) stopRecording()
    }

    fun release() {
        pipeline?.unbind()
        render.release()
    }
}
