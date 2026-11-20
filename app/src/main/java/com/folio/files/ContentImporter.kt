package com.folio.files

import android.content.ContentResolver
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Copies one user-granted content URI into primary Downloads without overwriting files. */
class ContentImporter(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    /** The fixed primary-storage folder used for every import. */
    val destinationDirectory: File
        get() = File(
            File(Environment.getExternalStorageDirectory(), Environment.DIRECTORY_DOWNLOADS),
            IMPORT_DIRECTORY_NAME,
        )

    data class Metadata(
        val displayName: String,
        val sizeBytes: Long?,
        val mimeType: String,
    )

    enum class ImportError {
        INVALID_URI,
        PERMISSION_DENIED,
        METADATA_UNAVAILABLE,
        SOURCE_UNAVAILABLE,
        DESTINATION_UNAVAILABLE,
        COPY_FAILED,
        NAME_COLLISIONS_EXHAUSTED,
    }

    sealed interface MetadataResult {
        data class Success(
            val metadata: Metadata,
            val destinationDirectory: File,
        ) : MetadataResult

        data class Failure(
            val error: ImportError,
            val cause: Throwable? = null,
        ) : MetadataResult
    }

    sealed interface ImportResult {
        data class Success(
            val destination: File,
            val metadata: Metadata,
            val bytesCopied: Long,
            val mediaScanRequested: Boolean,
        ) : ImportResult

        data class Failure(
            val error: ImportError,
            val metadata: Metadata? = null,
            val cause: Throwable? = null,
        ) : ImportResult
    }

    /** Reads and sanitizes the information needed to ask the user to confirm an import. */
    suspend fun inspect(
        uri: Uri,
        ioContext: CoroutineContext = Dispatchers.IO,
    ): MetadataResult = withContext(ioContext) {
        try {
            MetadataResult.Success(
                metadata = readMetadata(uri),
                destinationDirectory = destinationDirectory,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ImportFailure) {
            MetadataResult.Failure(failure.error, failure.cause)
        } catch (denied: SecurityException) {
            MetadataResult.Failure(ImportError.PERMISSION_DENIED, denied)
        } catch (error: Exception) {
            MetadataResult.Failure(ImportError.METADATA_UNAVAILABLE, error)
        }
    }

    /**
     * Imports [uri] on [ioContext]. The caller owns the coroutine, confirmation UI, permissions,
     * and presentation of the returned result. Cancellation removes any unfinished staging file.
     */
    suspend fun importFile(
        uri: Uri,
        ioContext: CoroutineContext = Dispatchers.IO,
    ): ImportResult = withContext(ioContext) {
        var metadata: Metadata? = null
        var staging: File? = null
        try {
            val sourceMetadata = readMetadata(uri).also { metadata = it }
            val directory = prepareDestinationDirectory()
            val source = openSource(uri)
            val temporary = createStagingFile(directory).also { staging = it }

            val bytesCopied = source.buffered().use { input ->
                FileOutputStream(temporary).use { output ->
                    copy(input, output)
                }
            }
            currentCoroutineContext().ensureActive()
            val destination = publish(temporary, directory, sourceMetadata.displayName)
            val scanRequested = requestMediaScan(destination, sourceMetadata.mimeType)
            ImportResult.Success(
                destination = destination,
                metadata = sourceMetadata,
                bytesCopied = bytesCopied,
                mediaScanRequested = scanRequested,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ImportFailure) {
            ImportResult.Failure(
                error = failure.error,
                metadata = metadata,
                cause = failure.cause,
            )
        } catch (denied: SecurityException) {
            ImportResult.Failure(
                error = ImportError.PERMISSION_DENIED,
                metadata = metadata,
                cause = denied,
            )
        } catch (error: Exception) {
            ImportResult.Failure(
                error = ImportError.COPY_FAILED,
                metadata = metadata,
                cause = error,
            )
        } finally {
            deleteQuietly(staging)
        }
    }

    private fun readMetadata(uri: Uri): Metadata {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            throw ImportFailure(ImportError.INVALID_URI)
        }

        var suppliedName: String? = null
        var size: Long? = null
        try {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use {
                if (it.moveToFirst()) {
                    val nameColumn = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameColumn >= 0 && !it.isNull(nameColumn)) {
                        suppliedName = it.getString(nameColumn)
                    }
                    val sizeColumn = it.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeColumn >= 0 && !it.isNull(sizeColumn)) {
                        size = it.getLong(sizeColumn).takeIf { value -> value >= 0L }
                    }
                }
            }
        } catch (_: RuntimeException) {
            // Minimal document providers may not support metadata queries; the stream can still work.
        }

        val resolvedMimeType = try {
            resolver.getType(uri)
        } catch (_: RuntimeException) {
            null
        }
        val safeName = sanitizeFileName(suppliedName, resolvedMimeType)
        val mimeType = resolvedMimeType?.takeIf { it.isNotBlank() }
            ?: FileUtils.mimeType(File(safeName))
        return Metadata(
            displayName = safeName,
            sizeBytes = size,
            mimeType = mimeType,
        )
    }

    private fun prepareDestinationDirectory(): File {
        try {
            val directory = destinationDirectory
            if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) {
                throw ImportFailure(ImportError.DESTINATION_UNAVAILABLE)
            }
            if (!directory.isDirectory || !directory.canWrite()) {
                throw ImportFailure(ImportError.DESTINATION_UNAVAILABLE)
            }

            // Use the canonical directory as the fixed parent for every staging/final file.
            val canonicalDirectory = directory.canonicalFile
            val downloads = File(
                Environment.getExternalStorageDirectory(),
                Environment.DIRECTORY_DOWNLOADS,
            ).canonicalFile
            if (!isWithin(downloads, canonicalDirectory)) {
                throw ImportFailure(ImportError.DESTINATION_UNAVAILABLE)
            }
            return canonicalDirectory
        } catch (failure: ImportFailure) {
            throw failure
        } catch (denied: SecurityException) {
            throw denied
        } catch (error: Exception) {
            throw ImportFailure(ImportError.DESTINATION_UNAVAILABLE, error)
        }
    }

    private fun openSource(uri: Uri): InputStream {
        return try {
            resolver.openInputStream(uri)
                ?: throw ImportFailure(ImportError.SOURCE_UNAVAILABLE)
        } catch (failure: ImportFailure) {
            throw failure
        } catch (denied: SecurityException) {
            throw denied
        } catch (error: FileNotFoundException) {
            throw ImportFailure(ImportError.SOURCE_UNAVAILABLE, error)
        } catch (error: RuntimeException) {
            throw ImportFailure(ImportError.SOURCE_UNAVAILABLE, error)
        }
    }

    private fun createStagingFile(directory: File): File {
        return try {
            File.createTempFile(STAGING_PREFIX, STAGING_SUFFIX, directory)
        } catch (denied: SecurityException) {
            throw denied
        } catch (error: IOException) {
            throw ImportFailure(ImportError.DESTINATION_UNAVAILABLE, error)
        }
    }

    private suspend fun copy(input: InputStream, output: FileOutputStream): Long {
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            output.write(buffer, 0, count)
            total += count
        }
        output.flush()
        return total
    }

    private suspend fun publish(staging: File, directory: File, displayName: String): File {
        val nameParts = splitName(displayName)
        for (collision in 0..MAX_COLLISIONS) {
            currentCoroutineContext().ensureActive()
            val suffix = if (collision == 0) "" else " ($collision)"
            val candidateName = fitFileName(nameParts, suffix)
            if (!FileUtils.isValidName(candidateName) || candidateName.contains('\\')) {
                throw ImportFailure(ImportError.COPY_FAILED)
            }

            // A direct child of the fixed canonical directory cannot escape through traversal.
            val candidate = File(directory, candidateName)
            val reserved = try {
                candidate.createNewFile()
            } catch (denied: SecurityException) {
                throw denied
            } catch (error: IOException) {
                throw ImportFailure(ImportError.COPY_FAILED, error)
            }
            if (!reserved) continue

            var published = false
            try {
                // renameTo replaces only the empty target atomically reserved by this call.
                if (!staging.renameTo(candidate)) {
                    throw ImportFailure(ImportError.COPY_FAILED)
                }
                published = true
                return candidate
            } finally {
                if (!published) deleteQuietly(candidate)
            }
        }
        throw ImportFailure(ImportError.NAME_COLLISIONS_EXHAUSTED)
    }

    private fun requestMediaScan(file: File, mimeType: String): Boolean {
        return try {
            if (!file.isFile || !file.canRead()) return false
            MediaScannerConnection.scanFile(
                appContext,
                arrayOf(file.absolutePath),
                arrayOf(mimeType),
                null,
            )
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun sanitizeFileName(suppliedName: String?, mimeType: String?): String {
        val fallbackExtension = mimeType
            ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            ?.takeIf { it.matches(SAFE_EXTENSION) }
            ?.let { ".$it" }
            .orEmpty()
        val fallback = "$FALLBACK_NAME$fallbackExtension"

        var name = suppliedName.orEmpty().map { character ->
            if (character == '/' || character == '\\' || Character.isISOControl(character)) {
                '_'
            } else {
                character
            }
        }.joinToString("").trim().trimEnd('.', ' ')
        if (name.isBlank() || name == "." || name == ".." || !FileUtils.isValidName(name)) {
            name = fallback
        }
        return fitFileName(splitName(name), suffix = "")
    }

    private fun splitName(name: String): NameParts {
        val dot = name.lastIndexOf('.')
        return if (dot > 0 && dot < name.lastIndex) {
            NameParts(
                stem = name.substring(0, dot),
                extension = name.substring(dot),
            )
        } else {
            NameParts(stem = name, extension = "")
        }
    }

    private fun fitFileName(parts: NameParts, suffix: String): String {
        // Keep normal extensions intact while leaving room for collision suffixes and UTF-8.
        val extension = truncateUtf8(parts.extension, MAX_EXTENSION_BYTES)
        val reservedBytes = utf8Size(extension) + utf8Size(suffix)
        val stemBudget = (MAX_FILE_NAME_BYTES - reservedBytes).coerceAtLeast(MIN_STEM_BYTES)
        val stem = truncateUtf8(parts.stem, stemBudget).ifBlank { FALLBACK_NAME }
        return stem + suffix + extension
    }

    private fun truncateUtf8(value: String, maxBytes: Int): String {
        if (utf8Size(value) <= maxBytes) return value
        val result = StringBuilder()
        var bytes = 0
        var offset = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            val codePointBytes = when {
                codePoint <= 0x7f -> 1
                codePoint <= 0x7ff -> 2
                codePoint <= 0xffff -> 3
                else -> 4
            }
            if (bytes + codePointBytes > maxBytes) break
            result.appendCodePoint(codePoint)
            bytes += codePointBytes
            offset += Character.charCount(codePoint)
        }
        return result.toString()
    }

    private fun utf8Size(value: String): Int = value.toByteArray(Charsets.UTF_8).size

    private fun isWithin(parent: File, child: File): Boolean {
        val parentPath = parent.path
        val childPath = child.path
        if (parentPath == childPath) return true
        val prefix = if (parentPath.endsWith(File.separator)) {
            parentPath
        } else {
            parentPath + File.separator
        }
        return childPath.startsWith(prefix)
    }

    private fun deleteQuietly(file: File?) {
        if (file == null) return
        try {
            if (file.exists()) file.delete()
        } catch (_: RuntimeException) {
            // Cleanup is best-effort and must not mask the import result or cancellation.
        }
    }

    private data class NameParts(val stem: String, val extension: String)

    private class ImportFailure(
        val error: ImportError,
        cause: Throwable? = null,
    ) : Exception(error.name, cause)

    private companion object {
        const val IMPORT_DIRECTORY_NAME = "MyFiles imports"
        const val STAGING_PREFIX = ".myfiles-import-"
        const val STAGING_SUFFIX = ".tmp"
        const val FALLBACK_NAME = "imported-file"
        const val COPY_BUFFER_SIZE = 64 * 1024
        const val MAX_COLLISIONS = 9_999
        const val MAX_FILE_NAME_BYTES = 220
        const val MAX_EXTENSION_BYTES = 64
        const val MIN_STEM_BYTES = 32
        val SAFE_EXTENSION = Regex("[A-Za-z0-9]{1,16}")
    }
}
