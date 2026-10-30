package com.alexi.softface.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.alexi.softface.face.FaceEngine
import com.alexi.softface.gl.FrameSlot
import com.alexi.softface.gl.RenderThread
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * One camera stream feeds both face finding and the screen, so the makeup
 * always lines up with the exact frame it was worked out on (no lag on lips/cheeks).
 */
class CameraPipeline(
    private val context: Context,
    private val engine: FaceEngine,
    private val render: RenderThread,
) : ImageAnalysis.Analyzer {

    private val executor = Executors.newSingleThreadExecutor()
    private val slots = Array(3) { FrameSlot() }
    private var analysis: ImageAnalysis? = null
    private var provider: ProcessCameraProvider? = null

    @Volatile var front = true
        private set

    private var full: Bitmap? = null
    private var small: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun bind(owner: LifecycleOwner, useFront: Boolean, displayRotation: Int, onError: (String) -> Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                val selector = if (useFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                val chosen = if (p.hasCamera(selector)) selector else CameraSelector.DEFAULT_BACK_CAMERA
                front = chosen == CameraSelector.DEFAULT_FRONT_CAMERA
                val a = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                            .setResolutionStrategy(
                                ResolutionStrategy(Size(1440, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
                            )
                            .build(),
                    )
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetRotation(displayRotation)
                    .build()
                a.setAnalyzer(executor, this)
                p.unbindAll()
                p.bindToLifecycle(owner, chosen, a)
                analysis = a
                engine.resetTracking()
            } catch (t: Throwable) {
                Log.e(TAG, "camera bind failed", t)
                onError("The camera didn't start. Close other camera apps and try again.")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun setDisplayRotation(rotation: Int) {
        analysis?.targetRotation = rotation
    }

    fun unbind() {
        provider?.unbindAll()
        analysis = null
    }

    override fun analyze(image: ImageProxy) {
        val slot = slots.firstOrNull { !it.busy }
        if (slot == null) { image.close(); return }
        val w = image.width
        val h = image.height
        val rotation = image.imageInfo.rotationDegrees
        try {
            val plane = image.planes[0]
            val need = w * h * 4
            if (slot.buf.capacity() < need) slot.buf = ByteBuffer.allocateDirect(need)
            val src = plane.buffer
            val dst = slot.buf
            dst.clear()
            val rowBytes = w * 4
            if (plane.rowStride == rowBytes) {
                src.position(0); src.limit(need)
                dst.put(src)
            } else {
                for (y in 0 until h) {
                    src.limit(y * plane.rowStride + rowBytes)
                    src.position(y * plane.rowStride)
                    dst.put(src)
                }
            }
            dst.flip()
        } catch (t: Throwable) {
            Log.e(TAG, "copy failed", t)
            image.close()
            return
        }
        image.close()

        slot.width = w; slot.height = h; slot.rotation = rotation; slot.mirror = front
        slot.face = engine.process(smallUpright(slot.buf, w, h, rotation))
        render.submit(slot)
    }

    /** Small upright copy for face finding (faster than full size). */
    private fun smallUpright(buf: ByteBuffer, w: Int, h: Int, rotation: Int): Bitmap {
        val f = full?.takeIf { it.width == w && it.height == h }
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { full = it }
        buf.position(0)
        f.copyPixelsFromBuffer(buf)
        buf.position(0)
        val scale = DETECT_SIZE.toFloat() / maxOf(w, h)
        val m = Matrix().apply { setRotate(rotation.toFloat()) }
        val r = RectF(0f, 0f, w.toFloat(), h.toFloat())
        m.mapRect(r)
        m.postTranslate(-r.left, -r.top)
        m.postScale(scale, scale)
        val sw = (r.width() * scale).toInt()
        val sh = (r.height() * scale).toInt()
        val s = small?.takeIf { it.width == sw && it.height == sh }
            ?: Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888).also { small = it }
        Canvas(s).drawBitmap(f, m, paint)
        return s
    }

    companion object {
        private const val TAG = "CameraPipeline"
        private const val DETECT_SIZE = 720
    }
}
