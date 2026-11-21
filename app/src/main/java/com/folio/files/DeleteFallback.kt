package com.folio.files

import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.ContentUris
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File

/**
 * Scoped-storage delete consent helpers for files the raw-path engine cannot remove. Kept out of
 * FileOperations so the engine stays pure JVM and unit-testable.
 */
object DeleteFallback {

    /** MediaStore content URIs for the given paths; files without a MediaStore row are skipped. */
    fun mediaStoreUris(resolver: ContentResolver, files: List<File>): List<Uri> =
        files.mapNotNull { mediaStoreUri(resolver, it) }

    fun mediaStoreUri(resolver: ContentResolver, file: File): Uri? {
        return try {
            resolver.query(
                MediaStore.Files.getContentUri("external"),
                arrayOf(MediaStore.Files.FileColumns._ID),
                "${MediaStore.Files.FileColumns.DATA} = ?",
                arrayOf(file.absolutePath),
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    ContentUris.withAppendedId(
                        MediaStore.Files.getContentUri("external"),
                        cursor.getLong(0),
                    )
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Android 11+: one system consent dialog covering every listed file. */
    @RequiresApi(Build.VERSION_CODES.R)
    fun deleteRequestSender(resolver: ContentResolver, uris: List<Uri>): IntentSender? = try {
        MediaStore.createDeleteRequest(resolver, uris).intentSender
    } catch (_: Exception) {
        null
    }

    /**
     * Android 10: deleting another app's media through MediaStore throws
     * RecoverableSecurityException carrying a one-time consent intent.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun deleteViaMediaStoreQ(resolver: ContentResolver, uri: Uri): QDeleteResult {
        return try {
            resolver.delete(uri, null, null)
            QDeleteResult.Deleted
        } catch (e: RecoverableSecurityException) {
            QDeleteResult.Consent(e.userAction.actionIntent.intentSender)
        } catch (e: Exception) {
            QDeleteResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    sealed class QDeleteResult {
        object Deleted : QDeleteResult()
        data class Consent(val sender: IntentSender) : QDeleteResult()
        data class Failed(val message: String) : QDeleteResult()
    }
}