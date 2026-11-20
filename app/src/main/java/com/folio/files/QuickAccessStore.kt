package com.folio.files

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** A render-ready Quick Access entry. Resource IDs are nonzero only for built-in entries. */
data class QuickAccessEntry(
    val id: String,
    val relativePath: String,
    val customLabel: String? = null,
    @StringRes val titleRes: Int = 0,
    @StringRes val subtitleRes: Int = 0,
    @DrawableRes val iconRes: Int = R.drawable.ic_folder,
    val isDefault: Boolean = false,
) {
    fun displayTitle(context: Context): String = when {
        titleRes != 0 -> context.getString(titleRes)
        !customLabel.isNullOrBlank() -> customLabel
        else -> relativePath.substringAfterLast('/')
    }

    fun displaySubtitle(context: Context): String =
        if (subtitleRes != 0) context.getString(subtitleRes) else relativePath
}

/** Ordered, persistent Quick Access shortcuts. Removing an entry never touches its directory. */
class QuickAccessStore(
    context: Context,
    private val primaryStorage: File = Environment.getExternalStorageDirectory(),
) {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** Missing directories are deliberately retained so the UI can show and remove them. */
    @Synchronized
    fun load(): List<QuickAccessEntry> {
        val raw = try {
            preferences.getString(KEY_ENTRIES, null)
        } catch (_: ClassCastException) {
            null
        } ?: return defaults()

        return try {
            decode(raw)
        } catch (_: JSONException) {
            defaults()
        }
    }

    /**
     * Adds an existing directory under primary storage. The canonical directory must be unique.
     *
     * @throws IllegalArgumentException for the storage root, outside paths, or duplicate entries.
     */
    @Synchronized
    fun add(directory: File): QuickAccessEntry {
        val root = canonicalPrimary()
        val canonicalDirectory = try {
            directory.canonicalFile
        } catch (e: Exception) {
            throw IllegalArgumentException("The folder path cannot be resolved.", e)
        }
        require(canonicalDirectory.isDirectory) { "Quick Access entries must be existing folders." }
        require(canonicalDirectory != root && isWithin(root, canonicalDirectory)) {
            "Choose a folder inside primary storage, not the storage root."
        }

        val entries = load()
        require(entries.none { resolve(it) == canonicalDirectory }) {
            "That folder is already in Quick Access."
        }
        val relativePath = root.toPath().relativize(canonicalDirectory.toPath())
            .joinToString("/") { it.toString() }
        require(QuickAccessPaths.isValid(relativePath)) { "The folder path is not valid." }

        return QuickAccessEntry(
            id = "custom-${UUID.randomUUID()}",
            relativePath = relativePath,
            customLabel = canonicalDirectory.name,
            iconRes = R.drawable.ic_folder,
        ).also { entry ->
            save(entries + entry)
        }
    }

    @Synchronized
    fun remove(id: String): Boolean {
        val entries = load()
        val updated = entries.filterNot { it.id == id }
        if (updated.size == entries.size) return false
        save(updated)
        return true
    }

    @Synchronized
    fun moveUp(id: String): Boolean = move(id, -1)

    @Synchronized
    fun moveDown(id: String): Boolean = move(id, 1)

    /** Restores the original six entries in their original order. */
    @Synchronized
    fun reset(): List<QuickAccessEntry> = defaults().also(::save)

    /** Resolves a contained path even when the folder no longer exists; returns null on escape. */
    fun resolve(entry: QuickAccessEntry): File? {
        if (!QuickAccessPaths.isValid(entry.relativePath)) return null
        val root = try {
            primaryStorage.canonicalFile
        } catch (_: Exception) {
            return null
        }
        val resolved = try {
            File(root, entry.relativePath).canonicalFile
        } catch (_: Exception) {
            return null
        }
        return resolved.takeIf { it != root && isWithin(root, it) }
    }

    private fun move(id: String, delta: Int): Boolean {
        val entries = load()
        val updated = QuickAccessOrdering.move(entries, id, delta) ?: return false
        save(updated)
        return true
    }

    private fun canonicalPrimary(): File = try {
        primaryStorage.canonicalFile
    } catch (e: Exception) {
        throw IllegalArgumentException("Primary storage cannot be resolved.", e)
    }

    private fun isWithin(parent: File, child: File): Boolean = child.toPath().startsWith(parent.toPath())

    private fun save(entries: List<QuickAccessEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(JSONObject().apply {
                put("id", entry.id)
                put("path", entry.relativePath)
                put("label", entry.customLabel ?: JSONObject.NULL)
            })
        }
        preferences.edit().putString(KEY_ENTRIES, array.toString()).apply()
    }

    private fun decode(raw: String): List<QuickAccessEntry> {
        val array = JSONArray(raw)
        val defaultById = defaults().associateBy { it.id }
        val ids = hashSetOf<String>()
        val paths = hashSetOf<String>()
        val result = arrayListOf<QuickAccessEntry>()

        for (index in 0 until array.length()) {
            val value = array.optJSONObject(index) ?: continue
            val id = value.optString("id").takeIf { it.isNotEmpty() } ?: continue
            val path = value.optString("path").takeIf { it.isNotEmpty() } ?: continue
            if (!QuickAccessIds.isValid(id) || !QuickAccessPaths.isValid(path)) continue
            if (!ids.add(id) || !paths.add(path)) continue

            val builtIn = defaultById[id]
            val entry = if (builtIn != null) {
                builtIn.copy(relativePath = path)
            } else {
                val savedLabel = if (value.isNull("label")) null else value.optString("label")
                QuickAccessEntry(
                    id = id,
                    relativePath = path,
                    customLabel = savedLabel?.takeUnless { it.isBlank() }
                        ?: path.substringAfterLast('/'),
                    iconRes = R.drawable.ic_folder,
                )
            }
            result += entry
        }
        return result
    }

    companion object {
        private const val PREFERENCES_NAME = "quick_access"
        private const val KEY_ENTRIES = "entries"

        fun defaults(): List<QuickAccessEntry> = listOf(
            QuickAccessEntry(
                id = "downloads",
                relativePath = "Download",
                titleRes = R.string.downloads,
                subtitleRes = R.string.downloads_hint,
                iconRes = R.drawable.ic_download,
                isDefault = true,
            ),
            QuickAccessEntry(
                id = "dcim",
                relativePath = "DCIM",
                titleRes = R.string.images,
                subtitleRes = R.string.images_hint,
                iconRes = R.drawable.ic_image,
                isDefault = true,
            ),
            QuickAccessEntry(
                id = "movies",
                relativePath = "Movies",
                titleRes = R.string.videos,
                subtitleRes = R.string.videos_hint,
                iconRes = R.drawable.ic_video,
                isDefault = true,
            ),
            QuickAccessEntry(
                id = "music",
                relativePath = "Music",
                titleRes = R.string.audio,
                subtitleRes = R.string.audio_hint,
                iconRes = R.drawable.ic_audio,
                isDefault = true,
            ),
            QuickAccessEntry(
                id = "documents",
                relativePath = "Documents",
                titleRes = R.string.documents,
                subtitleRes = R.string.documents_hint,
                iconRes = R.drawable.ic_document,
                isDefault = true,
            ),
            QuickAccessEntry(
                id = "recordings",
                relativePath = "Recordings",
                titleRes = R.string.recordings,
                subtitleRes = R.string.recordings_hint,
                iconRes = R.drawable.ic_mic,
                isDefault = true,
            ),
        )
    }
}

/** Pure path checks, kept separate from Android JSON and preferences for local JVM tests. */
internal object QuickAccessPaths {
    fun isValid(path: String): Boolean {
        if (path.isBlank() || path.startsWith('/') || path.endsWith('/') || path.contains('\u0000')) {
            return false
        }
        val segments = path.split('/')
        return segments.none { it.isEmpty() || it == "." || it == ".." }
    }
}

internal object QuickAccessIds {
    private val pattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

    fun isValid(id: String): Boolean = pattern.matches(id)
}

/** Returns null for an unknown ID or a no-op move at either boundary. */
internal object QuickAccessOrdering {
    fun move(entries: List<QuickAccessEntry>, id: String, delta: Int): List<QuickAccessEntry>? {
        require(delta == -1 || delta == 1) { "Quick Access entries move one position at a time." }
        val index = entries.indexOfFirst { it.id == id }
        val target = index + delta
        if (index < 0 || target !in entries.indices) return null
        return entries.toMutableList().apply {
            val entry = removeAt(index)
            add(target, entry)
        }
    }
}
