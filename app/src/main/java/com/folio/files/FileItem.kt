package com.folio.files

import java.io.File

data class FileItem(val file: File) {
    val name: String = file.name.ifBlank { "Internal storage" }
    val isDirectory: Boolean = file.isDirectory
    val size: Long = if (isDirectory) 0L else file.length()
    val modified: Long = file.lastModified()
}
