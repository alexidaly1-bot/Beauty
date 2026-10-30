package com.alexi.softface.record

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saves to Gallery (Pictures/Soft Face, Movies/Soft Face) or to the file another app asked for. */
object MediaSaver {
    private fun name(prefix: String) =
        prefix + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())

    /** @return where it was saved */
    fun savePhoto(context: Context, bmp: Bitmap, target: Uri? = null): Uri? {
        val cr = context.contentResolver
        if (target != null) {
            cr.openOutputStream(target, "w")?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) } ?: return null
            return target
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name("SoftFace_") + ".jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Soft Face")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        cr.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        cr.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return uri
    }

    /** A new video file to record into. Call [finishVideo] when done. */
    fun openVideo(context: Context, target: Uri? = null): Pair<Uri, ParcelFileDescriptor>? {
        val cr = context.contentResolver
        val uri = target ?: cr.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name("SoftFace_") + ".mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Soft Face")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            },
        ) ?: return null
        val pfd = cr.openFileDescriptor(uri, "rw") ?: return null
        return uri to pfd
    }

    fun finishVideo(context: Context, uri: Uri, pfd: ParcelFileDescriptor, ownFile: Boolean) {
        runCatching { pfd.close() }
        if (ownFile) {
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        }
    }
}
