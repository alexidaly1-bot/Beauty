package com.alexi.softface.data

import android.content.Context
import org.json.JSONObject

/** All slider values. Effects are 0..1, strength is 0..1, line-up values are offsets/scale. */
data class BeautySettings(
    val smooth: Float = 0.6f,
    val contour: Float = 0.45f,
    val blush: Float = 0.5f,
    val lips: Float = 0.45f,
    val eyes: Float = 0.35f,
    val slim: Float = 0.25f,
    val strength: Float = 1.0f,
    /** Line-up nudge, as a fraction of the frame (-0.15..0.15). */
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    /** Overlay size (0.8..1.2). */
    val size: Float = 1f,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("smooth", smooth.toDouble()).put("contour", contour.toDouble())
        .put("blush", blush.toDouble()).put("lips", lips.toDouble())
        .put("eyes", eyes.toDouble()).put("slim", slim.toDouble())
        .put("strength", strength.toDouble())
        .put("offsetX", offsetX.toDouble()).put("offsetY", offsetY.toDouble())
        .put("size", size.toDouble())

    companion object {
        fun fromJson(j: JSONObject): BeautySettings {
            val d = BeautySettings()
            fun f(k: String, def: Float) = j.optDouble(k, def.toDouble()).toFloat()
            return BeautySettings(
                f("smooth", d.smooth), f("contour", d.contour), f("blush", d.blush),
                f("lips", d.lips), f("eyes", d.eyes), f("slim", d.slim),
                f("strength", d.strength), f("offsetX", d.offsetX), f("offsetY", d.offsetY),
                f("size", d.size),
            )
        }

        /** Looks that always exist and can't be deleted. */
        val BUILT_IN: Map<String, BeautySettings> = linkedMapOf(
            "Natural" to BeautySettings(),
            "Soft glam" to BeautySettings(smooth = 0.8f, contour = 0.75f, blush = 0.8f, lips = 0.8f, eyes = 0.6f, slim = 0.5f),
            "Just skin" to BeautySettings(smooth = 0.6f, contour = 0f, blush = 0f, lips = 0f, eyes = 0.2f, slim = 0f),
        )
    }
}

/** Saves the current look, user presets and face-lock options on the phone only. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("soft_face", Context.MODE_PRIVATE)

    fun loadCurrent(): BeautySettings =
        prefs.getString("current", null)?.let { runCatching { BeautySettings.fromJson(JSONObject(it)) }.getOrNull() }
            ?: BeautySettings()

    fun saveCurrent(s: BeautySettings) = prefs.edit().putString("current", s.toJson().toString()).apply()

    fun loadPresets(): Map<String, BeautySettings> {
        val raw = prefs.getString("presets", null) ?: return emptyMap()
        val j = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val out = linkedMapOf<String, BeautySettings>()
        j.keys().forEach { k -> j.optJSONObject(k)?.let { out[k] = BeautySettings.fromJson(it) } }
        return out
    }

    fun savePresets(p: Map<String, BeautySettings>) {
        val j = JSONObject()
        p.forEach { (k, v) -> j.put(k, v.toJson()) }
        prefs.edit().putString("presets", j.toString()).apply()
    }

    var faceLockOn: Boolean
        get() = prefs.getBoolean("face_lock", true)
        set(v) = prefs.edit().putBoolean("face_lock", v).apply()

    /** 0 = relaxed, 1 = strict. */
    var strictness: Float
        get() = prefs.getFloat("strictness", 0.5f)
        set(v) = prefs.edit().putFloat("strictness", v).apply()

    var useFrontCamera: Boolean
        get() = prefs.getBoolean("front", true)
        set(v) = prefs.edit().putBoolean("front", v).apply()
}
