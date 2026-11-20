package com.folio.files

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.provider.Settings
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.View
import android.widget.GridLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.folio.files.databinding.ActivityMainBinding
import com.folio.files.databinding.DialogInputBinding
import com.folio.files.databinding.ViewCategoryBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private enum class Screen { HOME, BROWSER }
    private enum class SortMode { NAME, MODIFIED, SIZE }

    private data class QuickLocation(
        val title: Int,
        val subtitle: Int,
        val folder: String,
        val icon: Int,
    )

    private lateinit var binding: ActivityMainBinding
    private lateinit var fileAdapter: FileAdapter
    private lateinit var recentAdapter: FileAdapter

    private val storageRoot: File by lazy { Environment.getExternalStorageDirectory() }
    private var currentDirectory: File = storageRoot
    private var screen = Screen.HOME
    private var sortMode = SortMode.NAME
    private var allItems = emptyList<FileItem>()
    private var loadJob: Job? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (hasStorageAccess()) showHome() else showPermission()
    }

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (hasStorageAccess()) {
            showHome()
        } else {
            showPermission()
            toast(R.string.permission_needed)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupLists()
        setupActions()
        buildQuickAccess()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    screen == Screen.BROWSER && currentDirectory != storageRoot -> {
                        openDirectory(currentDirectory.parentFile ?: storageRoot)
                    }
                    screen == Screen.BROWSER -> showHome()
                    else -> finish()
                }
            }
        })

        if (hasStorageAccess()) showHome() else showPermission()
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized && binding.permissionScreen.root.isVisible && hasStorageAccess()) {
            showHome()
        }
    }

    private fun setupLists() {
        fileAdapter = FileAdapter(::openItem, ::showFileActions)
        recentAdapter = FileAdapter(::openItem, ::showFileActions)

        binding.browserScreen.fileList.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = fileAdapter
            setHasFixedSize(true)
        }
        binding.homeScreen.recentList.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = recentAdapter
            isNestedScrollingEnabled = false
        }
    }

    private fun setupActions() {
        binding.permissionScreen.allowAccessButton.setOnClickListener { requestStorageAccess() }
        binding.homeScreen.browseAllButton.setOnClickListener { openDirectory(storageRoot) }
        binding.browserScreen.newFolderButton.setOnClickListener { showNewFolderDialog() }
        binding.browserScreen.searchInput.doAfterTextChanged { applyFilter() }
        binding.toolbar.setNavigationOnClickListener {
            if (currentDirectory == storageRoot) showHome()
            else openDirectory(currentDirectory.parentFile ?: storageRoot)
        }
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_sort_name -> setSortMode(SortMode.NAME)
                R.id.action_sort_modified -> setSortMode(SortMode.MODIFIED)
                R.id.action_sort_size -> setSortMode(SortMode.SIZE)
                R.id.action_storage_settings -> {
                    requestStorageAccess()
                    true
                }
                else -> false
            }
        }
    }

    private fun buildQuickAccess() {
        val locations = listOf(
            QuickLocation(R.string.downloads, R.string.downloads_hint, Environment.DIRECTORY_DOWNLOADS, R.drawable.ic_download),
            QuickLocation(R.string.images, R.string.images_hint, Environment.DIRECTORY_DCIM, R.drawable.ic_image),
            QuickLocation(R.string.videos, R.string.videos_hint, Environment.DIRECTORY_MOVIES, R.drawable.ic_video),
            QuickLocation(R.string.audio, R.string.audio_hint, Environment.DIRECTORY_MUSIC, R.drawable.ic_audio),
            QuickLocation(R.string.documents, R.string.documents_hint, Environment.DIRECTORY_DOCUMENTS, R.drawable.ic_document),
            QuickLocation(R.string.recordings, R.string.recordings_hint, "Recordings", R.drawable.ic_mic),
        )
        val grid = binding.homeScreen.quickAccessGrid
        locations.forEachIndexed { index, location ->
            val card = ViewCategoryBinding.inflate(layoutInflater, grid, false)
            card.categoryTitle.setText(location.title)
            card.categorySubtitle.setText(location.subtitle)
            card.categoryIcon.setImageResource(location.icon)
            card.root.setOnClickListener {
                openDirectory(File(storageRoot, location.folder))
            }
            val margin = dp(5)
            card.root.layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                height = GridLayout.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(margin, margin, margin, margin)
                if (index < 2) topMargin = 0
            }
            grid.addView(card.root)
        }
    }

    private fun hasStorageAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStorageAccess() {
        if (hasStorageAccess()) {
            toast(R.string.storage_access_granted)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val appSettings = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName"),
            )
            try {
                settingsLauncher.launch(appSettings)
            } catch (_: ActivityNotFoundException) {
                settingsLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            val permissions = buildList {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun showPermission() {
        loadJob?.cancel()
        binding.permissionScreen.root.isVisible = true
        binding.appContainer.isVisible = false
    }

    private fun showHome() {
        loadJob?.cancel()
        screen = Screen.HOME
        binding.permissionScreen.root.isVisible = false
        binding.appContainer.isVisible = true
        binding.homeScreen.root.isVisible = true
        binding.browserScreen.root.isVisible = false
        binding.toolbar.title = getString(R.string.app_name)
        binding.toolbar.subtitle = null
        binding.toolbar.navigationIcon = null
        binding.toolbar.menu.setGroupVisible(R.id.sort_group, false)
        binding.toolbar.menu.findItem(R.id.action_storage_settings)?.isVisible = true
        updateStorageOverview()
        loadRecentFiles()
    }

    private fun openDirectory(directory: File) {
        if (!hasStorageAccess()) {
            showPermission()
            return
        }
        if (!directory.exists() || !directory.isDirectory) {
            toast(R.string.folder_unavailable)
            return
        }
        if (!directory.canRead()) {
            toast(R.string.folder_not_readable)
            return
        }

        screen = Screen.BROWSER
        currentDirectory = directory
        binding.homeScreen.root.isVisible = false
        binding.browserScreen.root.isVisible = true
        binding.permissionScreen.root.isVisible = false
        binding.appContainer.isVisible = true
        binding.toolbar.title = if (directory == storageRoot) {
            getString(R.string.internal_storage)
        } else {
            directory.name
        }
        binding.toolbar.subtitle = displayPath(directory)
        binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        binding.toolbar.menu.setGroupVisible(R.id.sort_group, true)
        binding.toolbar.menu.findItem(R.id.action_storage_settings)?.isVisible = false
        binding.browserScreen.searchInput.setText("")
        loadFiles()
    }

    private fun updateStorageOverview() {
        try {
            val stat = StatFs(storageRoot.absolutePath)
            val total = stat.totalBytes
            val available = stat.availableBytes
            val used = (total - available).coerceAtLeast(0L)
            binding.homeScreen.storageUsed.text = getString(
                R.string.storage_used_of_total,
                Formatter.formatShortFileSize(this, used),
                Formatter.formatShortFileSize(this, total),
            )
            binding.homeScreen.storageAvailable.text = getString(
                R.string.storage_available,
                Formatter.formatShortFileSize(this, available),
            )
            binding.homeScreen.storageProgress.progress = if (total > 0) {
                (used * 100 / total).toInt()
            } else {
                0
            }
        } catch (_: RuntimeException) {
            binding.homeScreen.storageUsed.setText(R.string.storage_unavailable)
            binding.homeScreen.storageAvailable.text = ""
        }
    }

    private fun loadRecentFiles() {
        binding.homeScreen.recentProgress.isVisible = true
        lifecycleScope.launch {
            val recent = withContext(Dispatchers.IO) { queryRecentFiles() }
            if (screen != Screen.HOME) return@launch
            binding.homeScreen.recentProgress.isVisible = false
            recentAdapter.submitList(recent)
            binding.homeScreen.recentEmpty.isVisible = recent.isEmpty()
            binding.homeScreen.recentList.isVisible = recent.isNotEmpty()
        }
    }

    private fun queryRecentFiles(): List<FileItem> {
        val projection = arrayOf(MediaStore.Files.FileColumns.DATA)
        val args = Bundle().apply {
            putStringArray(
                ContentResolver.QUERY_ARG_SORT_COLUMNS,
                arrayOf(MediaStore.Files.FileColumns.DATE_MODIFIED),
            )
            putInt(
                ContentResolver.QUERY_ARG_SORT_DIRECTION,
                ContentResolver.QUERY_SORT_DIRECTION_DESCENDING,
            )
            putInt(ContentResolver.QUERY_ARG_LIMIT, 6)
        }
        val result = mutableListOf<FileItem>()
        try {
            contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                projection,
                args,
                null,
            )?.use { cursor ->
                val pathColumn = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                while (cursor.moveToNext() && result.size < 6) {
                    if (pathColumn < 0 || cursor.isNull(pathColumn)) continue
                    val file = File(cursor.getString(pathColumn))
                    if (file.isFile && file.canRead()) result += FileItem(file)
                }
            }
        } catch (_: RuntimeException) {
            // Some devices restrict MediaStore paths even with broad file access.
        }
        return result
    }

    private fun loadFiles() {
        loadJob?.cancel()
        val directory = currentDirectory
        binding.browserScreen.loadingIndicator.isVisible = true
        binding.browserScreen.emptyView.isVisible = false
        loadJob = lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                directory.listFiles()?.map(::FileItem)
            }
            if (directory != currentDirectory || screen != Screen.BROWSER) return@launch
            binding.browserScreen.loadingIndicator.isVisible = false
            if (items == null) {
                allItems = emptyList()
                fileAdapter.submitList(emptyList())
                binding.browserScreen.emptyView.setText(R.string.folder_not_readable)
                binding.browserScreen.emptyView.isVisible = true
            } else {
                allItems = items
                applyFilter()
            }
        }
    }

    private fun applyFilter() {
        if (screen != Screen.BROWSER) return
        val query = binding.browserScreen.searchInput.text?.toString()?.trim().orEmpty()
        val filtered = if (query.isEmpty()) {
            allItems
        } else {
            allItems.filter { it.name.contains(query, ignoreCase = true) }
        }
        val nameComparator = compareBy<FileItem> { it.name.lowercase(Locale.ROOT) }
        val comparator = when (sortMode) {
            SortMode.NAME -> compareByDescending<FileItem> { it.isDirectory }.then(nameComparator)
            SortMode.MODIFIED -> compareByDescending<FileItem> { it.isDirectory }
                .thenByDescending { it.modified }
            SortMode.SIZE -> compareByDescending<FileItem> { it.isDirectory }
                .thenByDescending { it.size }
        }
        val visibleItems = filtered.sortedWith(comparator)
        fileAdapter.submitList(visibleItems) {
            binding.browserScreen.fileList.scrollToPosition(0)
        }
        binding.browserScreen.emptyView.setText(
            if (query.isNotEmpty() && allItems.isNotEmpty()) R.string.no_search_results
            else R.string.empty_folder,
        )
        binding.browserScreen.emptyView.isVisible = visibleItems.isEmpty()
    }

    private fun setSortMode(mode: SortMode): Boolean {
        sortMode = mode
        applyFilter()
        return true
    }

    private fun openItem(item: FileItem) {
        if (item.isDirectory) openDirectory(item.file) else openFile(item.file)
    }

    private fun openFile(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, FileUtils.mimeType(file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.open_with)))
        } catch (_: ActivityNotFoundException) {
            toast(R.string.no_app_to_open)
        }
    }

    private fun shareFile(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = FileUtils.mimeType(file)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.share_file)))
        } catch (_: ActivityNotFoundException) {
            toast(R.string.no_app_to_share)
        }
    }

    private fun showFileActions(anchor: View, item: FileItem) {
        val popup = PopupMenu(this, anchor)
        popup.inflate(R.menu.menu_file)
        popup.menu.findItem(R.id.action_share)?.isVisible = !item.isDirectory
        popup.setOnMenuItemClickListener { action ->
            when (action.itemId) {
                R.id.action_open -> openItem(item)
                R.id.action_share -> shareFile(item.file)
                R.id.action_rename -> showRenameDialog(item)
                R.id.action_details -> showDetails(item)
                R.id.action_delete -> confirmDelete(item)
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        popup.show()
    }

    private fun showNewFolderDialog() {
        val input = DialogInputBinding.inflate(layoutInflater)
        input.inputLayout.setHint(R.string.folder_name)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.new_folder)
            .setView(input.root)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.create) { _, _ ->
                createFolder(input.inputEditText.text?.toString()?.trim().orEmpty())
            }
            .show()
    }

    private fun createFolder(name: String) {
        if (!FileUtils.isValidName(name)) {
            toast(R.string.invalid_name)
            return
        }
        val target = File(currentDirectory, name)
        if (target.exists()) {
            toast(R.string.name_already_exists)
            return
        }
        lifecycleScope.launch {
            val created = withContext(Dispatchers.IO) { target.mkdir() }
            toast(if (created) R.string.folder_created else R.string.operation_failed)
            if (created) loadFiles()
        }
    }

    private fun showRenameDialog(item: FileItem) {
        val input = DialogInputBinding.inflate(layoutInflater)
        input.inputLayout.setHint(R.string.new_name)
        input.inputEditText.setText(item.name)
        input.inputEditText.setSelection(item.name.length)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename)
            .setView(input.root)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.rename) { _, _ ->
                renameItem(item, input.inputEditText.text?.toString()?.trim().orEmpty())
            }
            .show()
    }

    private fun renameItem(item: FileItem, newName: String) {
        if (!FileUtils.isValidName(newName)) {
            toast(R.string.invalid_name)
            return
        }
        val target = File(item.file.parentFile, newName)
        if (target.exists()) {
            toast(R.string.name_already_exists)
            return
        }
        lifecycleScope.launch {
            val renamed = withContext(Dispatchers.IO) { item.file.renameTo(target) }
            toast(if (renamed) R.string.file_renamed else R.string.operation_failed)
            if (renamed) refreshAfterChange()
        }
    }

    private fun confirmDelete(item: FileItem) {
        val message = getString(
            if (item.isDirectory) R.string.delete_folder_message else R.string.delete_file_message,
            item.name,
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> deleteItem(item) }
            .show()
    }

    private fun deleteItem(item: FileItem) {
        lifecycleScope.launch {
            val deleted = withContext(Dispatchers.IO) { item.file.deleteRecursively() }
            toast(if (deleted) R.string.item_deleted else R.string.operation_failed)
            if (deleted) refreshAfterChange()
        }
    }

    private fun showDetails(item: FileItem) {
        val type = if (item.isDirectory) {
            getString(R.string.folder)
        } else {
            FileUtils.mimeType(item.file)
        }
        val size = if (item.isDirectory) {
            FileUtils.metadata(this, item)
        } else {
            Formatter.formatShortFileSize(this, item.size)
        }
        val modified = DateFormat.getMediumDateFormat(this).format(Date(item.modified)) + " " +
            DateFormat.getTimeFormat(this).format(Date(item.modified))
        MaterialAlertDialogBuilder(this)
            .setTitle(item.name)
            .setMessage(getString(R.string.details_message, type, size, modified, item.file.absolutePath))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun refreshAfterChange() {
        if (screen == Screen.BROWSER) loadFiles() else loadRecentFiles()
    }

    private fun displayPath(file: File): String {
        val relative = file.relativeToOrNull(storageRoot)?.path.orEmpty()
        return if (relative.isEmpty()) getString(R.string.internal_storage)
        else getString(R.string.path_format, relative)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
