package com.matanh.transfer.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.matanh.transfer.R
import java.util.Locale
import java.util.concurrent.Executors

object FileTypeHelper {
    private val thumbExecutor = Executors.newFixedThreadPool(2)

    enum class Kind { IMAGE, VIDEO, AUDIO, TEXT, ARCHIVE, CODE, OTHER }

    fun kindOf(name: String): Kind {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        return when (ext) {
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic" -> Kind.IMAGE
            "mp4", "mkv", "webm", "avi", "mov", "m4v", "3gp" -> Kind.VIDEO
            "mp3", "m4a", "aac", "wav", "flac", "ogg" -> Kind.AUDIO
            "txt", "md", "log", "csv", "json", "xml", "nfo" -> Kind.TEXT
            "zip", "rar", "7z", "tar", "gz" -> Kind.ARCHIVE
            "kt", "java", "js", "ts", "py", "html", "css", "c", "cpp", "h" -> Kind.CODE
            else -> Kind.OTHER
        }
    }

    fun iconRes(name: String): Int = when (kindOf(name)) {
        Kind.IMAGE -> R.drawable.ic_file_image
        Kind.VIDEO -> R.drawable.ic_file_video
        Kind.AUDIO -> R.drawable.ic_file_audio
        Kind.TEXT, Kind.CODE -> R.drawable.ic_file_text
        Kind.ARCHIVE -> R.drawable.ic_file_archive
        Kind.OTHER -> R.drawable.ic_file_generic
    }

    fun loadImageThumb(context: Context, uri: Uri, maxPx: Int = 96, onDone: (Bitmap?) -> Unit) {
        val app = context.applicationContext
        thumbExecutor.execute {
            val bmp = try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                app.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                var sample = 1
                while (bounds.outWidth / sample > maxPx * 2 || bounds.outHeight / sample > maxPx * 2) {
                    sample *= 2
                }
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                app.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                }
            } catch (_: Exception) {
                null
            }
            android.os.Handler(app.mainLooper).post { onDone(bmp) }
        }
    }
}
