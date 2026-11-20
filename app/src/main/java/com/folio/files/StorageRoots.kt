package com.folio.files

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import java.io.File

/** A storage location that the browser can use as a navigation boundary. */
data class StorageRoot(
    val id: String,
    val directory: File,
    val label: String,
    val readOnly: Boolean,
    val isPrimary: Boolean = false,
) {
    /** Returns true when [file] is this root or one of its canonical descendants. */
    fun contains(file: File): Boolean = relativePath(file) != null

    /**
     * Returns the canonical path relative to this root, an empty string for the root itself,
     * or null when [file] is outside the root or its path cannot be resolved safely.
     */
    fun relativePath(file: File): String? {
        return try {
            val rootPath = directory.canonicalFile.path
            val candidatePath = file.canonicalFile.path
            if (candidatePath == rootPath) return ""

            val prefix = if (rootPath.endsWith(File.separator)) {
                rootPath
            } else {
                rootPath + File.separator
            }
            if (candidatePath.startsWith(prefix)) {
                candidatePath.substring(prefix.length)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}

/** Discovers primary storage and readable Downloads roots exposed by shared profiles. */
class StorageRootRepository(context: Context) {
    private val appContext = context.applicationContext

    /**
     * Returns primary storage first. Shared-profile discovery is best-effort and never prevents
     * primary storage from being returned.
     */
    fun discover(): List<StorageRoot> {
        val primaryDirectory = Environment.getExternalStorageDirectory()
        val roots = LinkedHashMap<String, StorageRoot>()
        val primaryKey = canonicalOrAbsolutePath(primaryDirectory)
        roots[primaryKey] = StorageRoot(
            id = PRIMARY_ROOT_ID,
            directory = primaryDirectory,
            label = PRIMARY_ROOT_LABEL,
            readOnly = !canWrite(primaryDirectory),
            isPrimary = true,
        )

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !hasAllFilesAccess()) {
            return roots.values.toList()
        }

        val currentOwner = try {
            Process.myUserHandle()
        } catch (_: RuntimeException) {
            return roots.values.toList()
        }
        val storageManager = try {
            appContext.getSystemService(StorageManager::class.java)
        } catch (_: RuntimeException) {
            null
        } ?: return roots.values.toList()
        val volumes = try {
            storageManager.storageVolumesIncludingSharedProfiles.orEmpty()
        } catch (_: RuntimeException) {
            return roots.values.toList()
        }

        volumes.forEach { volume ->
            try {
                if (volume.owner == currentOwner || !volume.isReadableMounted) {
                    return@forEach
                }
                val volumeDirectory = volume.directory ?: return@forEach
                if (!volumeDirectory.isDirectory || !volumeDirectory.canRead()) {
                    return@forEach
                }

                val downloads = File(volumeDirectory, Environment.DIRECTORY_DOWNLOADS)
                if (!downloads.isDirectory || !downloads.canRead()) return@forEach

                // Canonical paths provide both stable identity and deduplication across volumes.
                val canonicalDownloads = downloads.canonicalFile
                val key = canonicalDownloads.path
                if (roots.containsKey(key)) return@forEach

                roots[key] = StorageRoot(
                    id = "$SHARED_ROOT_ID_PREFIX$key",
                    directory = canonicalDownloads,
                    label = volumeLabel(volume),
                    readOnly = true,
                    isPrimary = false,
                )
            } catch (_: Exception) {
                // A broken, unmounted, or restricted volume must not hide the other roots.
            }
        }
        return roots.values.toList()
    }

    private fun hasAllFilesAccess(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return try {
            Environment.isExternalStorageManager()
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun volumeLabel(volume: StorageVolume): String {
        return try {
            volume.getDescription(appContext)
                ?.takeIf { it.isNotBlank() }
                ?.let { "$it · $DOWNLOADS_LABEL" }
                ?: SHARED_ROOT_LABEL
        } catch (_: RuntimeException) {
            SHARED_ROOT_LABEL
        }
    }

    private fun canonicalOrAbsolutePath(file: File): String {
        return try {
            file.canonicalPath
        } catch (_: Exception) {
            file.absolutePath
        }
    }

    private fun canWrite(file: File): Boolean {
        return try {
            file.canWrite()
        } catch (_: RuntimeException) {
            false
        }
    }

    private companion object {
        const val PRIMARY_ROOT_ID = "primary"
        const val PRIMARY_ROOT_LABEL = "Internal storage"
        const val SHARED_ROOT_ID_PREFIX = "shared:"
        const val SHARED_ROOT_LABEL = "Dual app Downloads"
        const val DOWNLOADS_LABEL = "Downloads"
    }
}

private val StorageVolume.isReadableMounted: Boolean
    get() = state == Environment.MEDIA_MOUNTED || state == Environment.MEDIA_MOUNTED_READ_ONLY
