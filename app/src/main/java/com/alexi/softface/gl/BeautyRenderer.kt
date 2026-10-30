package com.alexi.softface.gl

import android.graphics.Bitmap
import android.opengl.GLES30
import com.alexi.softface.data.BeautySettings
import com.alexi.softface.face.Mesh
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.hypot
import kotlin.math.max

/**
 * GPU passes: camera frame -> blurred copy + face mask -> makeup composite -> screen / video.
 * Must be used on the GL thread.
 */
class BeautyRenderer {
    private val copyProg = Gl.program(Shaders.QUAD_VS, Shaders.COPY_FS)
    private val blurProg = Gl.program(Shaders.QUAD_VS, Shaders.BLUR_FS)
    private val maskProg = Gl.program(Shaders.MASK_VS, Shaders.MASK_FS)
    private val compProg = Gl.program(Shaders.QUAD_VS, Shaders.COMPOSITE_FS)

    private val camTex: Int
    private var camW = 0
    private var camH = 0

    var frameW = 0; private set
    var frameH = 0; private set
    private var frame: Fbo? = null
    private var comp: Fbo? = null
    private var blurA: Fbo? = null
    private var blurB: Fbo? = null
    private var maskA: Fbo? = null
    private var maskB: Fbo? = null

    private val quadPos = Gl.floats(8).apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).position(0) }
    private val quadTex = Gl.floats(8)
    private val idTex = Gl.floats(8).apply { put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)).position(0) }
    private val polyBuf = Gl.floats(2 * 64)

    init {
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        camTex = t[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, camTex)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    /** Upload one RGBA camera buffer (tightly packed, top row first). */
    fun upload(buf: ByteBuffer, w: Int, h: Int) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, camTex)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        buf.position(0)
        if (w != camW || h != camH) {
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
            camW = w; camH = h
        } else {
            GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        }
    }

    private fun ensureSize(w: Int, h: Int) {
        if (w == frameW && h == frameH) return
        listOf(frame, comp, blurA, blurB, maskA, maskB).forEach { it?.release() }
        frameW = w; frameH = h
        frame = Fbo(w, h)
        comp = Fbo(w, h)
        blurA = Fbo(w / 2, h / 2)
        blurB = Fbo(w / 2, h / 2)
        maskA = Fbo(w / 4, h / 4)
        maskB = Fbo(w / 4, h / 4)
    }

    /**
     * Rotate the camera buffer upright into the frame texture.
     * Frame corner (s,t) -> image (u = s, v = 1 - t) -> buffer (x, y), sampled at (x, y).
     */
    fun drawCamera(rotation: Int) {
        val upW = if (rotation % 180 == 0) camW else camH
        val upH = if (rotation % 180 == 0) camH else camW
        ensureSize(upW, upH)
        quadTex.position(0)
        for (k in 0 until 4) {
            val u = idTex.get(2 * k)
            val v = 1f - idTex.get(2 * k + 1)
            val (x, y) = when (rotation) {
                90 -> v to 1f - u
                180 -> 1f - u to 1f - v
                270 -> 1f - v to u
                else -> u to v
            }
            quadTex.put(x).put(y)
        }
        quadTex.position(0)
        frame!!.bind()
        drawQuad(copyProg, camTex, quadTex)
    }

    /**
     * Apply the look.
     * @param pts your face landmarks (image coords, y down), or null for no face
     * @param fade 0..1 smooth fade in/out
     */
    fun drawEffects(pts: FloatArray?, s: BeautySettings, fade: Float, showMask: Boolean) {
        val f = frame ?: return
        val c = comp!!
        val aspect = frameW.toFloat() / frameH
        val active = pts != null && fade > 0.001f
        if (active) {
            val st = toSt(pts!!, s)
            // Blurred copy for skin smoothing.
            blur(f.tex, blurA!!, blurB!!, 1.6f, 2)
            buildMask(st)
            blur(maskB!!.tex, maskA!!, maskB!!, 1.2f, 2)
            setFaceUniforms(st, s, aspect)
        }
        c.bind()
        GLES30.glUseProgram(compProg)
        bindTex(0, f.tex, compProg, "uFrame")
        bindTex(1, blurB!!.tex, compProg, "uBlur")
        bindTex(2, maskB!!.tex, compProg, "uMask")
        u1("uAspect", aspect)
        u1("uFade", if (active) fade else 0f)
        u1("uShowMask", if (showMask) 1f else 0f)
        val k = s.strength
        u1("uSmooth", s.smooth * k)
        u1("uContour", s.contour * k)
        u1("uBlush", s.blush * k)
        u1("uLip", s.lips * k)
        u1("uEye", s.eyes * k)
        drawQuadBound(idTex)
    }

    /** Frame -> screen (fill + centre crop). Returns visible fraction of the frame (s, t). */
    fun drawToView(viewW: Int, viewH: Int, mirror: Boolean): Pair<Float, Float> {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, viewW, viewH)
        val scale = max(viewW.toFloat() / frameW, viewH.toFloat() / frameH)
        val fs = viewW / scale / frameW
        val ft = viewH / scale / frameH
        setCrop(fs, ft, mirror)
        drawQuad(copyProg, comp!!.tex, quadTex)
        return fs to ft
    }

    /** Whole frame into the current surface (video). */
    fun drawFull(w: Int, h: Int, mirror: Boolean) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, w, h)
        setCrop(1f, 1f, mirror)
        drawQuad(copyProg, comp!!.tex, quadTex)
    }

    /** Read the finished frame as a Bitmap (top row first). */
    fun readComposite(mirror: Boolean): Bitmap {
        val c = comp!!
        c.bind()
        val buf = ByteBuffer.allocateDirect(c.width * c.height * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, c.width, c.height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        val bmp = Bitmap.createBitmap(c.width, c.height, Bitmap.Config.ARGB_8888)
        buf.position(0)
        bmp.copyPixelsFromBuffer(buf)
        // GL rows are bottom-up; flip (and mirror for selfies).
        val m = android.graphics.Matrix().apply { postScale(if (mirror) -1f else 1f, -1f, c.width / 2f, c.height / 2f) }
        return Bitmap.createBitmap(bmp, 0, 0, c.width, c.height, m, false).also { bmp.recycle() }
    }

    // ---- helpers ----

    private fun setCrop(fs: Float, ft: Float, mirror: Boolean) {
        var s0 = 0.5f - fs / 2; var s1 = 0.5f + fs / 2
        val t0 = 0.5f - ft / 2; val t1 = 0.5f + ft / 2
        if (mirror) { val tmp = s0; s0 = s1; s1 = tmp }
        quadTex.position(0)
        quadTex.put(s0).put(t0).put(s1).put(t0).put(s0).put(t1).put(s1).put(t1)
        quadTex.position(0)
    }

    /** Landmarks (image coords) -> st coords, with your line-up nudge and size applied. */
    private fun toSt(pts: FloatArray, s: BeautySettings): FloatArray {
        var cx = 0f; var cy = 0f
        for (i in Mesh.FACE_OVAL) { cx += pts[2 * i]; cy += pts[2 * i + 1] }
        cx /= Mesh.FACE_OVAL.size; cy /= Mesh.FACE_OVAL.size
        val out = FloatArray(pts.size)
        for (i in 0 until pts.size / 2) {
            val x = cx + (pts[2 * i] - cx) * s.size + s.offsetX
            val y = cy + (pts[2 * i + 1] - cy) * s.size + s.offsetY
            out[2 * i] = x
            out[2 * i + 1] = 1f - y
        }
        return out
    }

    /** Separable blur: src -> (H) a -> (V) b, repeated. Result ends in b. */
    private fun blur(src: Int, a: Fbo, b: Fbo, step: Float, passes: Int) {
        var input = src
        GLES30.glUseProgram(blurProg)
        repeat(passes) {
            a.bind()
            bindTex(0, input, blurProg, "uTex")
            GLES30.glUniform2f(loc(blurProg, "uStep"), step / a.width, 0f)
            drawQuadBound(idTex)
            b.bind()
            bindTex(0, a.tex, blurProg, "uTex")
            GLES30.glUniform2f(loc(blurProg, "uStep"), 0f, step / b.height)
            drawQuadBound(idTex)
            input = b.tex
        }
    }

    private fun buildMask(st: FloatArray) {
        val m = maskB!! // drawn here, then blurred (result back in maskB)
        m.bind()
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(maskProg)
        val col = loc(maskProg, "uColor")

        // Red: skin = face oval minus eyes, brows and lips.
        GLES30.glColorMask(true, false, false, false)
        GLES30.glUniform4f(col, 1f, 0f, 0f, 0f)
        fan(st, Mesh.FACE_OVAL, 1f)
        GLES30.glUniform4f(col, 0f, 0f, 0f, 0f)
        fan(st, Mesh.EYE_A, 1.35f); fan(st, Mesh.EYE_B, 1.35f)
        fan(st, Mesh.BROW_A, 1.3f); fan(st, Mesh.BROW_B, 1.3f)
        fan(st, Mesh.LIPS_OUTER, 1.08f)

        // Green: lips minus the inside of the mouth.
        GLES30.glColorMask(false, true, false, false)
        GLES30.glUniform4f(col, 0f, 1f, 0f, 0f)
        fan(st, Mesh.LIPS_OUTER, 1f)
        GLES30.glUniform4f(col, 0f, 0f, 0f, 0f)
        fan(st, Mesh.LIPS_INNER, 1.05f)

        // Blue: around the eyes (a little bigger, nudged down to cover under-eyes).
        GLES30.glColorMask(false, false, true, false)
        GLES30.glUniform4f(col, 0f, 0f, 1f, 0f)
        fan(st, Mesh.EYE_A, 1.9f, dropDown = true); fan(st, Mesh.EYE_B, 1.9f, dropDown = true)

        GLES30.glColorMask(true, true, true, true)
    }

    /** Fill a polygon (scaled around its centre) as a triangle fan. */
    private fun fan(st: FloatArray, idx: IntArray, scale: Float, dropDown: Boolean = false) {
        var cx = 0f; var cy = 0f
        for (i in idx) { cx += st[2 * i]; cy += st[2 * i + 1] }
        cx /= idx.size; cy /= idx.size
        var minY = 1f; var maxY = 0f
        for (i in idx) { minY = minOf(minY, st[2 * i + 1]); maxY = maxOf(maxY, st[2 * i + 1]) }
        val dy = if (dropDown) -(maxY - minY) * 0.35f else 0f
        polyBuf.position(0)
        polyBuf.put(cx).put(cy + dy)
        for (k in 0..idx.size) {
            val i = idx[k % idx.size]
            polyBuf.put(cx + (st[2 * i] - cx) * scale).put(cy + dy + (st[2 * i + 1] - cy) * scale)
        }
        polyBuf.position(0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, polyBuf)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_FAN, 0, idx.size + 2)
    }

    private fun setFaceUniforms(st: FloatArray, s: BeautySettings, aspect: Float) {
        GLES30.glUseProgram(compProg)
        fun px(i: Int) = st[2 * i] * aspect
        fun py(i: Int) = st[2 * i + 1]
        fun lerpX(a: Int, b: Int, t: Float) = px(a) + (px(b) - px(a)) * t
        fun lerpY(a: Int, b: Int, t: Float) = py(a) + (py(b) - py(a)) * t

        val faceW = hypot(px(Mesh.SIDE_B) - px(Mesh.SIDE_A), py(Mesh.SIDE_B) - py(Mesh.SIDE_A))
        val faceH = hypot(px(Mesh.CHIN) - px(Mesh.FOREHEAD), py(Mesh.CHIN) - py(Mesh.FOREHEAD))
        // "Down the face" direction (handles head tilt).
        val dnX = (px(Mesh.CHIN) - px(Mesh.FOREHEAD)) / faceH.coerceAtLeast(1e-4f)
        val dnY = (py(Mesh.CHIN) - py(Mesh.FOREHEAD)) / faceH.coerceAtLeast(1e-4f)

        // Face slim: jaw and lower cheeks pulled inwards.
        val midX = lerpX(Mesh.NOSE_TIP, Mesh.CHIN, 0.5f); val midY = lerpY(Mesh.NOSE_TIP, Mesh.CHIN, 0.5f)
        val warpC = FloatArray(8); val warpD = FloatArray(8)
        val ctrl = intArrayOf(Mesh.JAW_A[2], Mesh.JAW_B[2], Mesh.JAW_A[0], Mesh.JAW_B[0])
        for (k in 0 until 4) {
            val i = ctrl[k]
            val tx = if (k < 2) midX else px(Mesh.NOSE_TIP)
            val ty = if (k < 2) midY else py(Mesh.NOSE_TIP)
            val vx = tx - px(i); val vy = ty - py(i)
            val len = hypot(vx, vy).coerceAtLeast(1e-4f)
            val amt = s.slim * s.strength * faceW * (if (k < 2) 0.032f else 0.018f)
            warpC[2 * k] = px(i); warpC[2 * k + 1] = py(i)
            warpD[2 * k] = vx / len * amt; warpD[2 * k + 1] = vy / len * amt
        }
        GLES30.glUniform2fv(loc(compProg, "uWarpC"), 4, warpC, 0)
        GLES30.glUniform2fv(loc(compProg, "uWarpD"), 4, warpD, 0)
        u1("uWarpR", faceW * 0.38f)

        // Blush on the apples of the cheeks.
        val up = -0.02f * faceH
        val blush = floatArrayOf(
            lerpX(Mesh.CHEEK_A, Mesh.SIDE_A, 0.08f) + dnX * up, lerpY(Mesh.CHEEK_A, Mesh.SIDE_A, 0.08f) + dnY * up,
            lerpX(Mesh.CHEEK_B, Mesh.SIDE_B, 0.08f) + dnX * up, lerpY(Mesh.CHEEK_B, Mesh.SIDE_B, 0.08f) + dnY * up,
        )
        GLES30.glUniform2fv(loc(compProg, "uBlushC"), 2, blush, 0)
        u1("uBlushR", faceW * 0.075f)

        // Contour lines: (ax, ay, bx, by) + width.
        val segs = FloatArray(4 * Shaders.MAX_SEGS)
        val widths = FloatArray(Shaders.MAX_SEGS)
        val strengths = FloatArray(Shaders.MAX_SEGS)
        var n = 0
        fun seg(ax: Float, ay: Float, bx: Float, by: Float, w: Float, k: Float = 1f) {
            if (n >= Shaders.MAX_SEGS) return
            segs[4 * n] = ax; segs[4 * n + 1] = ay; segs[4 * n + 2] = bx; segs[4 * n + 3] = by
            widths[n] = w; strengths[n] = k; n++
        }
        // Cheek hollows: from the side of the face towards the mouth corner, a little lower.
        val drop = 0.035f * faceH
        for ((side, mouth) in listOf(Mesh.SIDE_A to Mesh.MOUTH_A, Mesh.SIDE_B to Mesh.MOUTH_B)) {
            seg(
                lerpX(side, mouth, 0.06f) + dnX * drop, lerpY(side, mouth, 0.06f) + dnY * drop,
                lerpX(side, mouth, 0.5f) + dnX * drop, lerpY(side, mouth, 0.5f) + dnY * drop,
                faceW * 0.04f, 0.9f,
            )
        }
        // Jaw line, nudged slightly inside the edge.
        val cX = px(Mesh.NOSE_TIP); val cY = py(Mesh.NOSE_TIP)
        for (jaw in listOf(Mesh.JAW_A, Mesh.JAW_B)) {
            for (k in 0 until 3) {
                val a = jaw[k]; val b = jaw[k + 1]
                seg(
                    px(a) + (cX - px(a)) * 0.06f, py(a) + (cY - py(a)) * 0.06f,
                    px(b) + (cX - px(b)) * 0.06f, py(b) + (cY - py(b)) * 0.06f,
                    faceW * 0.03f, 0.8f,
                )
            }
        }
        // Nose sides.
        val wingMidX = (px(Mesh.NOSE_WING_A) + px(Mesh.NOSE_WING_B)) / 2
        val wingMidY = (py(Mesh.NOSE_WING_A) + py(Mesh.NOSE_WING_B)) / 2
        for (wing in intArrayOf(Mesh.NOSE_WING_A, Mesh.NOSE_WING_B)) {
            val ox = (px(wing) - wingMidX) * 0.6f; val oy = (py(wing) - wingMidY) * 0.6f
            seg(
                px(Mesh.NOSE_TOP) + ox * 0.55f, py(Mesh.NOSE_TOP) + oy * 0.55f,
                lerpX(Mesh.NOSE_TOP, Mesh.NOSE_TIP, 0.8f) + ox * 0.85f, lerpY(Mesh.NOSE_TOP, Mesh.NOSE_TIP, 0.8f) + oy * 0.85f,
                faceW * 0.014f, 0.45f,
            )
        }
        // Temples.
        seg(px(Mesh.TEMPLE_A), py(Mesh.TEMPLE_A), px(21), py(21), faceW * 0.03f, 0.6f)
        seg(px(Mesh.TEMPLE_B), py(Mesh.TEMPLE_B), px(251), py(251), faceW * 0.03f, 0.6f)

        GLES30.glUniform4fv(loc(compProg, "uSeg"), Shaders.MAX_SEGS, segs, 0)
        GLES30.glUniform1fv(loc(compProg, "uSegW"), Shaders.MAX_SEGS, widths, 0)
        GLES30.glUniform1fv(loc(compProg, "uSegK"), Shaders.MAX_SEGS, strengths, 0)
    }

    private val locCache = HashMap<String, Int>()
    private fun loc(prog: Int, name: String): Int = locCache.getOrPut("$prog/$name") { GLES30.glGetUniformLocation(prog, name) }
    private fun u1(name: String, v: Float) = GLES30.glUniform1f(loc(compProg, name), v)

    private fun bindTex(unit: Int, tex: Int, prog: Int, name: String) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glUniform1i(loc(prog, name), unit)
    }

    private fun drawQuad(prog: Int, tex: Int, texCoords: FloatBuffer) {
        GLES30.glUseProgram(prog)
        bindTex(0, tex, prog, "uTex")
        drawQuadBound(texCoords)
    }

    private fun drawQuadBound(texCoords: FloatBuffer) {
        texCoords.position(0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, quadPos)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 0, texCoords)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(1)
    }
}
