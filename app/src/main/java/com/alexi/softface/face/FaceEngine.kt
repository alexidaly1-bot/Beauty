package com.alexi.softface.face

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.hypot

/** The face to make up this frame, in upright camera-frame coords (0..1, y down). */
class FaceFrame(val points: FloatArray)

enum class LockStatus { NOT_SET_UP, LOOKING, FOUND_YOU, NOT_YOU, OFF_ANY_FACE, NO_FACE }

sealed interface EnrolResult {
    class Ok(val embedding: FloatArray, val yaw: Float) : EnrolResult
    class Problem(val message: String) : EnrolResult
}

/**
 * Finds faces with MediaPipe Face Landmarker, works out which one is you,
 * and returns your face's landmarks for the same frame. Runs on the camera thread.
 */
class FaceEngine(context: Context, private val store: FaceStore) {

    private val landmarker: FaceLandmarker = create(context, Delegate.GPU) ?: create(context, Delegate.CPU)!!
    private val embedder = FaceEmbedder(context)

    private val _status = MutableStateFlow(LockStatus.LOOKING)
    val status: StateFlow<LockStatus> = _status
    private val _faceCount = MutableStateFlow(0)
    val faceCount: StateFlow<Int> = _faceCount

    @Volatile var faceLockOn = true
    /** Cosine similarity needed to count as you. */
    @Volatile var threshold = 0.45f

    private val pendingEnrol = AtomicReference<((EnrolResult) -> Unit)?>(null)

    private class Track(val id: Int, var cx: Float, var cy: Float, var size: Float, var seenMs: Long) {
        var checkedMs = 0L
        var score = -1f
        var isMe = false
        var lowCount = 0
        lateinit var pts: FloatArray
    }

    private val tracks = mutableListOf<Track>()
    private var nextId = 1
    private var lastTs = 0L
    private var targetId = -1
    private val filters = Array(Mesh.COUNT * 2) { OneEuro() }
    private var lastOutMs = 0L

    fun requestEnrolShot(cb: (EnrolResult) -> Unit) = pendingEnrol.set(cb)

    /** @param bmp small upright copy of the camera frame */
    fun process(bmp: Bitmap): FaceFrame? = try {
        detect(bmp)
    } catch (t: Throwable) {
        Log.e(TAG, "face detect failed", t)
        null
    }

    private fun detect(bmp: Bitmap): FaceFrame? {
        if (resetRequested) { tracks.clear(); targetId = -1; resetRequested = false }
        var ts = SystemClock.uptimeMillis()
        if (ts <= lastTs) ts = lastTs + 1
        lastTs = ts
        val result = landmarker.detectForVideo(BitmapImageBuilder(bmp).build(), ts)
        val faces = result.faceLandmarks().map { lm ->
            FloatArray(Mesh.COUNT * 2).also { a ->
                for (i in 0 until minOf(Mesh.COUNT, lm.size)) { a[2 * i] = lm[i].x(); a[2 * i + 1] = lm[i].y() }
            }
        }
        _faceCount.value = faces.size
        val aspect = bmp.width.toFloat() / bmp.height

        pendingEnrol.getAndSet(null)?.let { cb ->
            cb(
                when {
                    faces.isEmpty() -> EnrolResult.Problem("I can't see a face. Move into the light.")
                    faces.size > 1 -> EnrolResult.Problem("Only you in the shot, please.")
                    else -> EnrolResult.Ok(embedder.embed(bmp, faces[0]), yaw(faces[0]))
                },
            )
        }

        updateTracks(faces, ts, aspect)
        recognise(bmp, ts)

        val target = pickTarget()
        _status.value = when {
            !faceLockOn -> if (target != null) LockStatus.OFF_ANY_FACE else LockStatus.NO_FACE
            !store.isEnrolled -> LockStatus.NOT_SET_UP
            faces.isEmpty() -> LockStatus.NO_FACE
            target != null -> LockStatus.FOUND_YOU
            tracks.any { it.checkedMs == 0L } -> LockStatus.LOOKING
            else -> LockStatus.NOT_YOU
        }

        if (target == null) { targetId = -1; return null }
        if (target.id != targetId) { filters.forEach { it.reset() }; targetId = target.id }
        val dt = if (lastOutMs == 0L) 0f else (ts - lastOutMs) / 1000f
        lastOutMs = ts
        val out = FloatArray(target.pts.size) { filters[it].filter(target.pts[it], dt) }
        return FaceFrame(out)
    }

    private fun updateTracks(faces: List<FloatArray>, now: Long, aspect: Float) {
        val used = BooleanArray(tracks.size)
        for (f in faces) {
            var minX = 1f; var maxX = 0f; var minY = 1f; var maxY = 0f
            for (i in Mesh.FACE_OVAL) {
                minX = minOf(minX, f[2 * i]); maxX = maxOf(maxX, f[2 * i])
                minY = minOf(minY, f[2 * i + 1]); maxY = maxOf(maxY, f[2 * i + 1])
            }
            val cx = (minX + maxX) / 2 * aspect
            val cy = (minY + maxY) / 2
            val size = maxOf((maxX - minX) * aspect, maxY - minY)
            var best = -1
            var bestD = Float.MAX_VALUE
            tracks.forEachIndexed { i, t ->
                if (used[i]) return@forEachIndexed
                val d = hypot(t.cx - cx, t.cy - cy)
                if (d < 0.6f * maxOf(t.size, size) && d < bestD) { best = i; bestD = d }
            }
            val t = if (best >= 0) {
                used[best] = true
                tracks[best].also { it.cx = cx; it.cy = cy; it.size = size; it.seenMs = now }
            } else Track(nextId++, cx, cy, size, now).also { tracks.add(it) }
            t.pts = f
        }
        tracks.removeAll { now - it.seenMs > 500 }
    }

    /** Check at most one face per frame so the camera stays smooth. */
    private fun recognise(bmp: Bitmap, now: Long) {
        if (!store.isEnrolled || !faceLockOn) return
        val visible = tracks.filter { it.seenMs == now }
        val todo = visible.filter { it.checkedMs == 0L }.maxByOrNull { it.size }
            ?: visible.filter { now - it.checkedMs > if (it.isMe) 1500 else 700 }.minByOrNull { it.checkedMs }
            ?: return
        val score = store.bestMatch(embedder.embed(bmp, todo.pts))
        todo.checkedMs = now
        todo.score = score
        if (score >= threshold) {
            todo.isMe = true; todo.lowCount = 0
        } else if (todo.isMe) {
            // Stay locked through one bad look (blink, turn), drop after two.
            if (score < threshold - 0.08f || ++todo.lowCount >= 2) { todo.isMe = false; todo.lowCount = 0 }
        }
    }

    private fun pickTarget(): Track? {
        val now = lastTs
        val visible = tracks.filter { it.seenMs == now }
        if (!faceLockOn) return visible.maxByOrNull { it.size }
        if (!store.isEnrolled) return null
        return visible.filter { it.isMe }.maxByOrNull { it.score }
    }

    /** Forget who's who, e.g. after switching camera or re-enrolling. */
    fun resetTracking() { resetRequested = true }

    @Volatile private var resetRequested = false

    fun close() {
        landmarker.close()
    }

    companion object {
        private const val TAG = "FaceEngine"

        /** Left/right head turn: about -1 (turned to image left) to 1. */
        fun yaw(p: FloatArray): Float {
            val a = p[2 * Mesh.SIDE_A]; val b = p[2 * Mesh.SIDE_B]; val n = p[2 * Mesh.NOSE_TIP]
            return ((n - (a + b) / 2) / ((b - a) / 2).coerceAtLeast(1e-3f)).coerceIn(-1f, 1f)
        }

        private fun create(context: Context, delegate: Delegate): FaceLandmarker? = runCatching {
            FaceLandmarker.createFromOptions(
                context,
                FaceLandmarker.FaceLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker.task").setDelegate(delegate).build())
                    .setRunningMode(RunningMode.VIDEO)
                    .setNumFaces(4)
                    .setMinFaceDetectionConfidence(0.5f)
                    .setMinFacePresenceConfidence(0.5f)
                    .setMinTrackingConfidence(0.5f)
                    .build(),
            )
        }.onFailure { Log.w(TAG, "FaceLandmarker $delegate failed", it) }.getOrNull()
    }
}
