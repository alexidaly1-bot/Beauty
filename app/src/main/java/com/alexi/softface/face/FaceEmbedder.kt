package com.alexi.softface.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/**
 * Turns a face into 128 numbers (MobileFaceNet, runs on the phone).
 * Two photos of the same person give similar numbers; different people don't.
 */
class FaceEmbedder(context: Context) {
    private val interpreter: Interpreter
    private val crop = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(crop)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val pixels = IntArray(SIZE * SIZE)
    private val in1 = ByteBuffer.allocateDirect(4 * 3 * SIZE * SIZE).order(ByteOrder.nativeOrder())
    private val in2 = ByteBuffer.allocateDirect(4 * 3 * SIZE * SIZE).order(ByteOrder.nativeOrder())
    private val out = Array(2) { FloatArray(DIM) }

    init {
        val fd = context.assets.openFd("mobile_facenet.tflite")
        val model = FileInputStream(fd.fileDescriptor).channel
            .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        interpreter = Interpreter(model, Interpreter.Options().setNumThreads(2))
    }

    /**
     * @param image upright camera frame
     * @param pts landmarks in normalised image coords (x0,y0,x1,y1...)
     * @return unit-length embedding
     */
    @Synchronized
    fun embed(image: Bitmap, pts: FloatArray): FloatArray {
        val w = image.width.toFloat()
        val h = image.height.toFloat()
        fun px(i: Int) = pts[i * 2] * w
        fun py(i: Int) = pts[i * 2 + 1] * h
        // Five key points: eye centres, nose tip, mouth corners.
        val src = floatArrayOf(
            (px(33) + px(133)) / 2, (py(33) + py(133)) / 2,
            (px(362) + px(263)) / 2, (py(362) + py(263)) / 2,
            px(Mesh.NOSE_TIP), py(Mesh.NOSE_TIP),
            px(Mesh.MOUTH_A), py(Mesh.MOUTH_A),
            px(Mesh.MOUTH_B), py(Mesh.MOUTH_B),
        )
        canvas.drawColor(android.graphics.Color.BLACK)
        canvas.save()
        canvas.concat(similarity(src, TEMPLATE))
        canvas.drawBitmap(image, 0f, 0f, paint)
        canvas.restore()
        crop.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        fill(in1, flip = false)
        fill(in2, flip = true)
        interpreter.runForMultipleInputsOutputs(arrayOf<Any>(in1, in2), mapOf(0 to out))
        // Average the face and its mirror image: steadier result.
        val e = FloatArray(DIM) { out[0][it] + out[1][it] }
        normalise(e)
        return e
    }

    private fun fill(buf: ByteBuffer, flip: Boolean) {
        buf.rewind()
        val plane = SIZE * SIZE
        val f = buf.asFloatBuffer()
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            val p = pixels[y * SIZE + if (flip) SIZE - 1 - x else x]
            val o = y * SIZE + x
            f.put(o, ((p shr 16) and 0xFF) / 255f)
            f.put(plane + o, ((p shr 8) and 0xFF) / 255f)
            f.put(2 * plane + o, (p and 0xFF) / 255f)
        }
    }

    companion object {
        const val SIZE = 112
        const val DIM = 128

        /** Standard 112x112 face position (eyes, nose, mouth corners). */
        private val TEMPLATE = floatArrayOf(
            38.2946f, 51.6963f, 73.5318f, 51.5014f, 56.0252f, 71.7366f, 41.5493f, 92.3655f, 70.7299f, 92.2041f,
        )

        fun normalise(v: FloatArray) {
            var s = 0f
            for (x in v) s += x * x
            val n = sqrt(s).coerceAtLeast(1e-6f)
            for (i in v.indices) v[i] /= n
        }

        fun cosine(a: FloatArray, b: FloatArray): Float {
            var s = 0f
            for (i in a.indices) s += a[i] * b[i]
            return s
        }

        /** Best-fit rotate + scale + move from src points to dst points. */
        fun similarity(src: FloatArray, dst: FloatArray): Matrix {
            val n = src.size / 2
            var sx = 0f; var sy = 0f; var dx = 0f; var dy = 0f
            for (i in 0 until n) { sx += src[2 * i]; sy += src[2 * i + 1]; dx += dst[2 * i]; dy += dst[2 * i + 1] }
            sx /= n; sy /= n; dx /= n; dy /= n
            var num1 = 0f; var num2 = 0f; var den = 0f
            for (i in 0 until n) {
                val ax = src[2 * i] - sx; val ay = src[2 * i + 1] - sy
                val bx = dst[2 * i] - dx; val by = dst[2 * i + 1] - dy
                num1 += ax * bx + ay * by
                num2 += ax * by - ay * bx
                den += ax * ax + ay * ay
            }
            val a = num1 / den.coerceAtLeast(1e-6f)
            val b = num2 / den.coerceAtLeast(1e-6f)
            val tx = dx - (a * sx - b * sy)
            val ty = dy - (b * sx + a * sy)
            return Matrix().apply { setValues(floatArrayOf(a, -b, tx, b, a, ty, 0f, 0f, 1f)) }
        }
    }
}
