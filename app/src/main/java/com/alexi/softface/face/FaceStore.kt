package com.alexi.softface.face

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * Your enrolled face, saved as numbers only (no photos) in the app's private storage.
 * Nothing leaves the phone: the app has no internet permission.
 */
class FaceStore(context: Context) {
    private val file = File(context.filesDir, "face_lock.json")

    @Volatile
    var embeddings: List<FloatArray> = load()
        private set

    val isEnrolled get() = embeddings.isNotEmpty()

    fun save(list: List<FloatArray>) {
        val arr = JSONArray()
        list.forEach { e -> arr.put(JSONArray().apply { e.forEach { put(it.toDouble()) } }) }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
        embeddings = list
    }

    fun clear() {
        file.delete()
        embeddings = emptyList()
    }

    /** Best match against every enrolled angle, -1..1. */
    fun bestMatch(e: FloatArray): Float {
        var best = -1f
        for (m in embeddings) best = maxOf(best, FaceEmbedder.cosine(e, m))
        return best
    }

    private fun load(): List<FloatArray> = runCatching {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        List(arr.length()) { i ->
            val a = arr.getJSONArray(i)
            FloatArray(a.length()) { a.getDouble(it).toFloat() }
        }
    }.getOrDefault(emptyList())
}
