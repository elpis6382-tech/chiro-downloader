package io.github.apostolichiro.videodl

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import java.io.File
import java.io.IOException

/** Puts finished files into Download/Chiro video, where the gallery and file apps see them. */
object Saver {
    const val FOLDER = "Chiro video"

    fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "3gp" -> "video/3gpp"
        "m4a" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "opus", "ogg" -> "audio/ogg"
        "aac" -> "audio/aac"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> "application/octet-stream"
    }

    fun freeBytes(): Long = try {
        StatFs(Environment.getExternalStorageDirectory().path).availableBytes
    } catch (_: Exception) {
        Long.MAX_VALUE
    }

    /** Copies [file] into the shared Download folder and deletes the working copy. */
    fun save(ctx: Context, file: File, displayName: String = file.name): SavedFile {
        val size = file.length()
        if (freeBytes() < size + (32L shl 20)) throw IOException("No space left on device")
        val mime = mimeOf(displayName)
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("could not create the file in Download")
        try {
            resolver.openOutputStream(uri, "w")!!.use { out ->
                file.inputStream().use { it.copyTo(out, 1 shl 20) }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            throw e
        }
        file.delete()
        // The store may have renamed it ("name (1).mp4") if one with the same name exists.
        val finalName = try {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) {
            null
        } ?: displayName
        return SavedFile(uri.toString(), finalName, mime, size)
    }

    fun exists(ctx: Context, uri: String): Boolean = try {
        ctx.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false
    } catch (_: Exception) {
        false
    }
}
