package com.alexi.softface.face

import kotlin.math.PI
import kotlin.math.abs

/** MediaPipe face mesh indices used for masks and effects (unmirrored image, "A" = image left). */
object Mesh {
    const val COUNT = 478

    val FACE_OVAL = intArrayOf(
        10, 338, 297, 332, 284, 251, 389, 356, 454, 323, 361, 288, 397, 365, 379, 378, 400, 377,
        152, 148, 176, 149, 150, 136, 172, 58, 132, 93, 234, 127, 162, 21, 54, 103, 67, 109,
    )
    val LIPS_OUTER = intArrayOf(61, 146, 91, 181, 84, 17, 314, 405, 321, 375, 291, 409, 270, 269, 267, 0, 37, 39, 40, 185)
    val LIPS_INNER = intArrayOf(78, 95, 88, 178, 87, 14, 317, 402, 318, 324, 308, 415, 310, 311, 312, 13, 82, 81, 80, 191)
    val EYE_A = intArrayOf(33, 7, 163, 144, 145, 153, 154, 155, 133, 173, 157, 158, 159, 160, 161, 246)
    val EYE_B = intArrayOf(263, 249, 390, 373, 374, 380, 381, 382, 362, 398, 384, 385, 386, 387, 388, 466)
    val BROW_A = intArrayOf(70, 63, 105, 66, 107, 55, 65, 52, 53, 46)
    val BROW_B = intArrayOf(300, 293, 334, 296, 336, 285, 295, 282, 283, 276)

    const val NOSE_TIP = 1
    const val NOSE_TOP = 168
    const val NOSE_WING_A = 129
    const val NOSE_WING_B = 358
    const val CHEEK_A = 50
    const val CHEEK_B = 280
    const val SIDE_A = 234
    const val SIDE_B = 454
    const val MOUTH_A = 61
    const val MOUTH_B = 291
    const val CHIN = 152
    const val FOREHEAD = 10
    val JAW_A = intArrayOf(132, 58, 172, 136, 150)
    val JAW_B = intArrayOf(361, 288, 397, 365, 379)
    const val TEMPLE_A = 54
    const val TEMPLE_B = 284
    const val BROW_OUTER_A = 70
    const val BROW_OUTER_B = 300
}

/** One Euro filter: steady when still, quick when moving. Removes landmark jitter. */
class OneEuro(private val minCutoff: Float = 1.2f, private val beta: Float = 0.02f, private val dCutoff: Float = 1f) {
    private var x = 0f
    private var dx = 0f
    private var started = false

    private fun alpha(cutoff: Float, dt: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }

    fun reset() { started = false }

    fun filter(value: Float, dt: Float): Float {
        if (!started || dt <= 0f) { x = value; dx = 0f; started = true; return value }
        val rawDx = (value - x) / dt
        dx += alpha(dCutoff, dt) * (rawDx - dx)
        val cutoff = minCutoff + beta * abs(dx)
        x += alpha(cutoff, dt) * (value - x)
        return x
    }
}
