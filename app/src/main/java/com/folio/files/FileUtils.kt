package com.folio.files

import android.content.Context
import android.text.format.DateFormat
import android.text.format.Formatter
import android.webkit.MimeTypeMap
import java.io.File
import java.util.Date
import java.util.Locale

object FileUtils {
    fun metadata(context: Context, item: FileItem): String {
        if (item.isDirectory) {
            val count = item.file.listFiles()?.size ?: 0
            return context.resources.getQuantityString(R.plurals.item_count, count, count)
        }
        val size = Formatter.formatShortFileSize(context, item.size)
        val date = DateFormat.getMediumDateFormat(context).format(Date(item.modified))
        return "$size · $date"
    }

    fun mimeType(file: File): String {
        val extension = file.extension.lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: when (extension) {
                "txt", "log", "md", "csv" -> "text/plain"
                "json", "xml" -> "text/plain"
                "apk" -> "application/vnd.android.package-archive"
                else -> "application/octet-stream"
            }
    }

    fun isValidName(name: String): Boolean {
        return name.isNotBlank() && name != "." && name != ".." &&
            !name.contains('/') && !name.contains('\u0000')
    }
}
