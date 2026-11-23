
package com.folio.files

import android.content.Context
import android.graphics.BitmapFactory
import android.text.format.DateFormat
import android.text.format.Formatter
import android.webkit.MimeTypeMap
import java.io.File
import java.util.Date
import java.util.Locale

object FileUtils {

    private val imageExtensions = setOf(
        "jpg",
        "jpeg",
        "png",
        "gif",
        "webp",
        "bmp",
        "heic",
        "heif",
        "avif"
    )

    fun metadata(context: Context, item: FileItem): String {
        if (item.isDirectory) {
            val count = item.file.listFiles()?.size ?: 0
            return context.resources.getQuantityString(
                R.plurals.item_count,
                count,
                count
            )
        }

        val size = Formatter.formatShortFileSize(context, item.size)
        val date = DateFormat.getMediumDateFormat(context)
            .format(Date(item.modified))

        return "$size · $date"
    }

    fun mimeType(file: File): String {
        val extension = file.extension.lowercase(Locale.ROOT)

        return MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(extension)
            ?: when (extension) {
                "txt", "log", "md", "csv" -> "text/plain"
                "json", "xml" -> "text/plain"
                "apk" -> "application/vnd.android.package-archive"
                else -> "application/octet-stream"
            }
    }

    fun isImageFile(file: File): Boolean {
        return file.isFile &&
            file.extension.lowercase(Locale.ROOT) in imageExtensions
    }

    fun isValidImageFile(file: File): Boolean {
        if (!isImageFile(file) || !file.canRead()) {
            return false
        }

        return try {
            val bounds = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }

            BitmapFactory.decodeFile(file.absolutePath, bounds)

            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return false
            }

            val sampleSize = calculateSampleSize(
                bounds.outWidth,
                bounds.outHeight
            )

            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }

            val bitmap = BitmapFactory.decodeFile(
                file.absolutePath,
                options
            )

            val isValid = bitmap != null &&
                bitmap.width > 0 &&
                bitmap.height > 0

            bitmap?.recycle()

            isValid
        } catch (_: Exception) {
            false
        } catch (_: OutOfMemoryError) {
            false
        }
    }

    private fun calculateSampleSize(
        width: Int,
        height: Int
    ): Int {
        var sampleSize = 1
        val maximumDimension = 128

        while (
            width / sampleSize > maximumDimension ||
            height / sampleSize > maximumDimension
        ) {
            sampleSize *= 2
        }

        return sampleSize
    }

    fun isValidName(name: String): Boolean {
        return name.isNotBlank() &&
            name != "." &&
            name != ".." &&
            !name.contains('/') &&
            !name.contains('\u0000')
    }
}
