package com.alexi.softface.gl

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.alexi.softface.data.BeautySettings
import com.alexi.softface.face.FaceFrame
import java.nio.ByteBuffer

/** One camera frame waiting to be drawn. The camera thread reuses it once [busy] is false. */
class FrameSlot {
    var buf: ByteBuffer = ByteBuffer.allocateDirect(4)
    var width = 0
    var height = 0
    var rotation = 0
    var mirror = false
    var face: FaceFrame? = null
    @Volatile var busy = false
}

/** Owns the GL context. Draws each camera frame with the look to the screen and, when recording, to the video. */
class RenderThread {
    private val thread = HandlerThread("SoftFaceGL").apply { start() }
    private val handler = Handler(thread.looper)

    private lateinit var egl: EglCore
    private lateinit var renderer: BeautyRenderer

    private var viewSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var viewW = 0
    private var viewH = 0

    private var encSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var encW = 0
    private var encH = 0

    @Volatile var settings = BeautySettings()
    @Volatile var effectsOn = true
    @Volatile var showMask = false

    /** Visible part of the frame on screen (for drag-to-line-up). */
    @Volatile var visibleFracS = 1f; private set
    @Volatile var visibleFracT = 1f; private set
    /** Upright frame size, known after the first frame. */
    @Volatile var frameW = 0; private set
    @Volatile var frameH = 0; private set

    private var pending: FrameSlot? = null
    private var lastPts: FloatArray? = null
    private var fade = 0f
    private var lastDrawMs = 0L
    private var photoRequest: ((Bitmap) -> Unit)? = null

    var onFps: ((Float) -> Unit)? = null
    private var fpsCount = 0
    private var fpsStart = 0L

    init {
        handler.post {
            egl = EglCore()
            renderer = BeautyRenderer()
        }
    }

    fun setViewSurface(surface: Surface?, w: Int, h: Int) = handler.post {
        if (viewSurface != EGL14.EGL_NO_SURFACE) {
            egl.makeNothingCurrent()
            egl.releaseSurface(viewSurface)
            viewSurface = EGL14.EGL_NO_SURFACE
        }
        if (surface != null && surface.isValid) {
            viewSurface = egl.createWindowSurface(surface)
            viewW = w; viewH = h
        }
    }

    /** Called on the camera thread. Drops the older frame if the GPU is behind. */
    fun submit(slot: FrameSlot) {
        slot.busy = true
        handler.post {
            pending?.let { if (it !== slot) it.busy = false }
            pending = slot
            drawPending()
        }
    }

    fun capturePhoto(cb: (Bitmap) -> Unit) = handler.post { photoRequest = cb }

    fun startEncoder(surface: Surface, w: Int, h: Int) = handler.post {
        encSurface = egl.createWindowSurface(surface)
        encW = w; encH = h
    }

    fun stopEncoder(then: () -> Unit) = handler.post {
        if (encSurface != EGL14.EGL_NO_SURFACE) {
            egl.makeNothingCurrent()
            egl.releaseSurface(encSurface)
            encSurface = EGL14.EGL_NO_SURFACE
        }
        then()
    }

    private fun drawPending() {
        val slot = pending ?: return
        pending = null
        try {
            egl.makeNothingCurrent()
            renderer.upload(slot.buf, slot.width, slot.height)
            val mirror = slot.mirror
            val face = slot.face
            val rotation = slot.rotation
            slot.busy = false

            renderer.drawCamera(rotation)
            frameW = renderer.frameW; frameH = renderer.frameH

            val now = SystemClock.uptimeMillis()
            val dt = if (lastDrawMs == 0L) 0f else ((now - lastDrawMs) / 1000f).coerceAtMost(0.1f)
            lastDrawMs = now
            val target = if (face != null && effectsOn) 1f else 0f
            fade = if (target > fade) (fade + dt / 0.25f).coerceAtMost(1f) else (fade - dt / 0.35f).coerceAtLeast(0f)
            if (face != null) lastPts = face.points
            renderer.drawEffects(if (effectsOn) lastPts else null, settings, fade, showMask && effectsOn)

            photoRequest?.let { cb ->
                photoRequest = null
                cb(renderer.readComposite(mirror))
            }

            if (encSurface != EGL14.EGL_NO_SURFACE) {
                egl.makeCurrent(encSurface)
                renderer.drawFull(encW, encH, mirror)
                egl.setPresentationTime(encSurface, System.nanoTime())
                egl.swap(encSurface)
            }

            if (viewSurface != EGL14.EGL_NO_SURFACE) {
                egl.makeCurrent(viewSurface)
                val (fs, ft) = renderer.drawToView(viewW, viewH, mirror)
                visibleFracS = fs; visibleFracT = ft
                egl.swap(viewSurface)
            }
            GLES30.glFlush()

            fpsCount++
            if (fpsStart == 0L) fpsStart = now
            if (now - fpsStart >= 1000) {
                onFps?.invoke(fpsCount * 1000f / (now - fpsStart))
                fpsCount = 0; fpsStart = now
            }
        } catch (t: Throwable) {
            slot.busy = false
            Log.e("RenderThread", "draw failed", t)
        }
    }

    fun release() {
        handler.post {
            if (viewSurface != EGL14.EGL_NO_SURFACE) egl.releaseSurface(viewSurface)
            if (encSurface != EGL14.EGL_NO_SURFACE) egl.releaseSurface(encSurface)
            egl.release()
            thread.quitSafely()
        }
    }
}
