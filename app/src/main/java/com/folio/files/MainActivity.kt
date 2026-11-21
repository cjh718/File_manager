package com.folio.files

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentResolver
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.media.MediaScannerConnection
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
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.folio.files.databinding.ActivityMainBinding
import com.folio.files.databinding.DialogInputBinding
import com.folio.files.databinding.ViewCategoryBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

private enum class Screen { HOME, BROWSER }
private enum class SortMode { NAME, MODIFIED, SIZE }

private data class PendingTransfer(val sources: List<File>, val move: Boolean)

/** A swiped-away row whose real delete runs only after the undo window expires. */
private data class PendingDelete(val item: FileItem, val job: Job, var committing: Boolean = false)

private lateinit var binding: ActivityMainBinding
private lateinit var fileAdapter: FileAdapter
private lateinit var recentAdapter: FileAdapter
private val quickAccessStore by lazy { QuickAccessStore(this, storageRoot) }
private val fileOperations by lazy { FileOperations(storageRoot) }
private val contentImporter by lazy { ContentImporter(this) }
private var pendingImportUri: Uri? = null
private var importInspectionJob: Job? = null

private val storageRoot: File by lazy { Environment.getExternalStorageDirectory() }
private var primaryRoot = StorageRoot("primary", storageRoot, "Internal storage", false, true)
private var currentRoot = primaryRoot
private var availableRoots = listOf(primaryRoot)
private var currentDirectory: File = storageRoot
private var screen = Screen.HOME
private var sortMode = SortMode.NAME
private var allItems = emptyList<FileItem>()
private var loadJob: Job? = null
private var recentJob: Job? = null
private var rootDiscoveryJob: Job? = null
private var selectionMode = false
private val selectedPaths = linkedSetOf<String>()
private var pendingTransfer: PendingTransfer? = null
private data class RecentPage(
    val items: List<FileItem>,
    val nextOffset: Int,
    val hasMore: Boolean,
)

private val recentPageSize = 10
private var recentOffset = 0
private var recentHasMore = true
private var recentLoading = false
private val pendingDeletes = LinkedHashMap<String, PendingDelete>()
private var undoSnackbar: Snackbar? = null
private var pendingConsentFiles: List<File> = emptyList()

private val permissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions(),
) {
    if (hasStorageAccess()) continueWithStorageAccess() else showPermission()
}

private val settingsLauncher = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult(),
) {
    if (hasStorageAccess()) {
        continueWithStorageAccess()
    } else {
        showPermission()
        toast(R.string.permission_needed)
    }
}

private val openDocumentLauncher = registerForActivityResult(
    ActivityResultContracts.OpenDocument(),
) { uri ->
    if (uri != null) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // The temporary activity grant still allows the immediate, confirmed import.
        }
        pendingImportUri = uri
        if (hasStorageAccess()) consumePendingImport() else showPermission()
    }
}

private val folderPickerLauncher = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult(),
) { result ->
    val path = result.data?.getStringExtra(FolderPickerActivity.EXTRA_DIRECTORY)
    val transfer = pendingTransfer
    pendingTransfer = null
    if (result.resultCode == RESULT_OK && path != null && transfer != null) {
        executeTransfer(transfer, File(path))
    }
}

private val deleteConsentLauncher = registerForActivityResult(
    ActivityResultContracts.StartIntentSenderForResult(),
) { result ->
    val files = pendingConsentFiles
    pendingConsentFiles = emptyList()
    if (files.isEmpty()) return@registerForActivityResult
    if (result.resultCode == RESULT_OK) {
        finishConsentedDeletes(files)
    } else {
        toast(R.string.delete_not_permitted)
        refreshAfterChange()
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
                selectionMode -> clearSelection()
                screen == Screen.BROWSER && currentDirectory != currentRoot.directory -> {
                    openDirectory(currentDirectory.parentFile ?: currentRoot.directory)
                }
                screen == Screen.BROWSER -> showHome()
                else -> finish()
            }
        }
    })

    pendingImportUri = savedInstanceState?.getString(STATE_PENDING_IMPORT)?.let(Uri::parse)
    pendingTransfer = savedInstanceState?.getStringArrayList(STATE_TRANSFER_PATHS)
        ?.map(::File)
        ?.let { PendingTransfer(it, savedInstanceState.getBoolean(STATE_TRANSFER_MOVE)) }
    if (pendingImportUri == null) captureIncomingIntent(intent)
    if (hasStorageAccess()) continueWithStorageAccess() else showPermission()
}

override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    captureIncomingIntent(intent)
    if (hasStorageAccess()) consumePendingImport() else showPermission()
}

override fun onSaveInstanceState(outState: Bundle) {
    outState.putString(STATE_PENDING_IMPORT, pendingImportUri?.toString())
    pendingTransfer?.let { transfer ->
        outState.putStringArrayList(
            STATE_TRANSFER_PATHS,
            ArrayList(transfer.sources.map { it.absolutePath }),
        )
        outState.putBoolean(STATE_TRANSFER_MOVE, transfer.move)
    }
    super.onSaveInstanceState(outState)
}

override fun onResume() {
    super.onResume()
    if (::binding.isInitialized && binding.permissionScreen.root.isVisible && hasStorageAccess()) {
        continueWithStorageAccess()
    } else if (::binding.isInitialized && binding.appContainer.isVisible) {
        if (binding.homeScreen.root.isVisible) buildQuickAccess()
        // Permissions may have changed in system settings while we were backgrounded.
        refreshStorageRoots()
    }
}

private fun continueWithStorageAccess() {
    showHome()
    consumePendingImport()
}

private fun setupLists() {
    fileAdapter = FileAdapter(
        ::handleItemClick,
        ::showFileActions,
        onLongClick = { item -> startSelection(item); true },
        isSelected = { selectedPaths.contains(selectionKey(it.file)) },
        isSelectionMode = { selectionMode },
    )
    recentAdapter = FileAdapter(
        ::handleItemClick,
        ::showFileActions,
        onLongClick = { item -> startSelection(item); true },
        isSelected = { selectedPaths.contains(selectionKey(it.file)) },
        isSelectionMode = { selectionMode },
    )

    binding.browserScreen.fileList.apply {
        layoutManager = LinearLayoutManager(this@MainActivity)
        adapter = fileAdapter
        setHasFixedSize(true)
    }
    binding.homeScreen.recentList.apply {
        layoutManager = LinearLayoutManager(this@MainActivity)
        adapter = recentAdapter
        addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                if (lm.findLastVisibleItemPosition() >= recentAdapter.itemCount - 3) {
                    loadRecentFiles(reset = false)
                }
            }
        })
    }

    val canSwipe = { item: FileItem -> !selectionMode && canModify(item.file) }
    ItemTouchHelper(SwipeToDeleteCallback(this, fileAdapter, canSwipe, ::enqueueDeferredDelete))
        .attachToRecyclerView(binding.browserScreen.fileList)
    ItemTouchHelper(SwipeToDeleteCallback(this, recentAdapter, canSwipe, ::enqueueDeferredDelete))
        .attachToRecyclerView(binding.homeScreen.recentList)
}

private fun setupActions() {
    binding.permissionScreen.allowAccessButton.setOnClickListener { requestStorageAccess() }
    binding.homeScreen.browseAllButton.setOnClickListener { openDirectory(storageRoot) }
    binding.homeScreen.customizeQuickAccess.setOnClickListener {
        startActivity(Intent(this, QuickAccessActivity::class.java))
    }
    binding.homeScreen.importFileButton.setOnClickListener {
        openDocumentLauncher.launch(arrayOf("*/*"))
    }
    binding.homeScreen.dualAppHelpButton.setOnClickListener {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dual_app_help_title)
            .setMessage(R.string.dual_app_help_body)
            .setPositiveButton(R.string.got_it, null)
            .show()
    }
    binding.browserScreen.newFolderButton.setOnClickListener { showNewFolderDialog() }
    binding.browserScreen.searchInput.doAfterTextChanged {
        if (selectionMode) clearSelection()
        applyFilter(scrollToTop = true)
    }
    binding.toolbar.setNavigationOnClickListener {
        when {
            selectionMode -> clearSelection()
            currentDirectory == currentRoot.directory -> showHome()
            else -> openDirectory(currentDirectory.parentFile ?: currentRoot.directory)
        }
    }
    binding.toolbar.setOnMenuItemClickListener { item ->
        when (item.itemId) {
            R.id.action_sort_name -> setSortMode(SortMode.NAME)
            R.id.action_sort_modified -> setSortMode(SortMode.MODIFIED)
            R.id.action_sort_size -> setSortMode(SortMode.SIZE)
            R.id.action_select_all -> {
                selectAllVisible()
                true
            }
            R.id.action_storage_settings -> {
                requestStorageAccess()
                true
            }
            else -> false
        }
    }

    binding.selectionBar.barShare.setOnClickListener {
        shareFiles(selectedItems().map { it.file })
    }
    binding.selectionBar.barMove.setOnClickListener {
        beginTransfer(selectedItems(), move = true)
    }
    binding.selectionBar.barDelete.setOnClickListener {
        confirmDelete(selectedItems())
    }
    binding.selectionBar.barMore.setOnClickListener {
        SelectionMoreSheet().show(supportFragmentManager, "selection_more")
    }
}

private fun buildQuickAccess() {
    val grid = binding.homeScreen.quickAccessGrid
    grid.removeAllViews()
    quickAccessStore.load().forEachIndexed { index, entry ->
        val card = ViewCategoryBinding.inflate(layoutInflater, grid, false)
        card.categoryTitle.text = entry.displayTitle(this)
        card.categorySubtitle.text = entry.displaySubtitle(this)
        card.categoryIcon.setImageResource(entry.iconRes)
        card.root.setOnClickListener {
            val directory = quickAccessStore.resolve(entry)
            if (directory == null || !directory.isDirectory) {
                toast(R.string.quick_access_missing)
            } else {
                openDirectory(directory)
            }
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

internal fun addFolderToQuickAccess(directory: File) {
    try {
        quickAccessStore.add(directory)
        toast(R.string.shortcut_added)
        buildQuickAccess()
    } catch (_: IllegalArgumentException) {
        toast(R.string.shortcut_already_exists)
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

/** Live write-permission check; unlike a cached root flag it cannot go stale. */
private fun hasWriteAccess(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
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
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                // Android 10 legacy storage still needs WRITE for raw-path file operations.
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }
}

private fun captureIncomingIntent(source: Intent?) {
    val action = source?.action
    if (action != Intent.ACTION_SEND && action != Intent.ACTION_VIEW) return
    @Suppress("DEPRECATION")
    val uri = if (action == Intent.ACTION_SEND) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            source.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            source.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    } else {
        source.data
    }
    if (uri?.scheme == ContentResolver.SCHEME_CONTENT) {
        pendingImportUri = uri
        // Do not offer the same external intent again after cancellation or recreation.
        setIntent(Intent(Intent.ACTION_MAIN))
    }
}

private fun consumePendingImport() {
    val uri = pendingImportUri ?: return
    if (!hasStorageAccess()) {
        showPermission()
        return
    }
    if (importInspectionJob?.isActive == true) return
    importInspectionJob = lifecycleScope.launch {
        when (val result = contentImporter.inspect(uri)) {
            is ContentImporter.MetadataResult.Failure -> {
                importInspectionJob = null
                if (pendingImportUri == uri) {
                    pendingImportUri = null
                    showImportError(result.error)
                } else {
                    consumePendingImport()
                }
            }
            is ContentImporter.MetadataResult.Success -> {
                importInspectionJob = null
                if (pendingImportUri != uri) {
                    consumePendingImport()
                    return@launch
                }
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(R.string.import_title)
                    .setMessage(getString(R.string.import_message, result.metadata.displayName))
                    .setNegativeButton(android.R.string.cancel) { _, _ ->
                        if (pendingImportUri == uri) pendingImportUri = null
                    }
                    .setPositiveButton(R.string.import_file) { _, _ -> performImport(uri) }
                    .setOnCancelListener {
                        if (pendingImportUri == uri) pendingImportUri = null
                    }
                    .show()
            }
        }
    }
}

private fun performImport(uri: Uri) {
    if (pendingImportUri != uri) return
    val progress = ProgressBar(this)
    val dialog = MaterialAlertDialogBuilder(this)
        .setTitle(R.string.importing_file)
        .setView(progress)
        .setCancelable(false)
        .show()
    lifecycleScope.launch {
        when (val result = contentImporter.importFile(uri)) {
            is ContentImporter.ImportResult.Success -> {
                dialog.dismiss()
                if (pendingImportUri == uri) pendingImportUri = null
                toast(getString(R.string.import_complete, result.destination.name))
                openDirectory(result.destination.parentFile ?: contentImporter.destinationDirectory)
            }
            is ContentImporter.ImportResult.Failure -> {
                dialog.dismiss()
                if (pendingImportUri == uri) pendingImportUri = null
                showImportError(result.error)
            }
        }
    }
}

private fun showImportError(error: ContentImporter.ImportError) {
    val message = when (error) {
        ContentImporter.ImportError.PERMISSION_DENIED -> R.string.import_permission_denied
        ContentImporter.ImportError.SOURCE_UNAVAILABLE,
        ContentImporter.ImportError.METADATA_UNAVAILABLE,
        -> R.string.import_source_unavailable
        ContentImporter.ImportError.DESTINATION_UNAVAILABLE -> R.string.import_destination_unavailable
        else -> R.string.import_failed
    }
    toast(message)
}

private fun showPermission() {
    loadJob?.cancel()
    binding.permissionScreen.root.isVisible = true
    binding.appContainer.isVisible = false
}

private fun showHome() {
    loadJob?.cancel()
    screen = Screen.HOME
    clearSelection()
    binding.permissionScreen.root.isVisible = false
    binding.appContainer.isVisible = true
    binding.homeScreen.root.isVisible = true
    binding.browserScreen.root.isVisible = false
    renderToolbar()
    buildQuickAccess()
    updateStorageOverview()
    loadRecentFiles()
    refreshStorageRoots()
}

private fun refreshStorageRoots() {
    rootDiscoveryJob?.cancel()
    rootDiscoveryJob = lifecycleScope.launch {
        val roots = withContext(Dispatchers.IO) { StorageRootRepository(this@MainActivity).discover() }
        availableRoots = roots
        primaryRoot = roots.first()
        if (currentRoot.isPrimary) currentRoot = primaryRoot
        renderToolbar()
        if (screen != Screen.HOME) return@launch
        val sharedRoots = roots.filterNot { it.isPrimary }
        val grid = binding.homeScreen.dualAppGrid
        grid.removeAllViews()
        binding.homeScreen.dualAppStatus.isVisible = sharedRoots.isEmpty()
        sharedRoots.forEachIndexed { index, root ->
            val card = ViewCategoryBinding.inflate(layoutInflater, grid, false)
            card.categoryTitle.text = root.label
            card.categorySubtitle.text = getString(R.string.read_only)
            card.categoryIcon.setImageResource(R.drawable.ic_download)
            card.root.setOnClickListener { openDirectory(root.directory, root) }
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
}

private fun openDirectory(directory: File, root: StorageRoot? = null) {
    if (!hasStorageAccess()) {
        showPermission()
        return
    }
    val canonical = try {
        directory.canonicalFile
    } catch (_: Exception) {
        toast(R.string.folder_unavailable)
        return
    }
    val targetRoot = root ?: availableRoots
        .filter { it.contains(canonical) }
        .maxByOrNull { it.directory.path.length }
        ?: primaryRoot
    if (!targetRoot.contains(canonical) || !canonical.exists() || !canonical.isDirectory) {
        toast(R.string.folder_unavailable)
        return
    }
    if (!canonical.canRead()) {
        toast(R.string.folder_not_readable)
        return
    }

    screen = Screen.BROWSER
    currentRoot = targetRoot
    currentDirectory = canonical
    clearSelection()
    binding.homeScreen.root.isVisible = false
    binding.browserScreen.root.isVisible = true
    binding.permissionScreen.root.isVisible = false
    binding.appContainer.isVisible = true
    renderToolbar()
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

private fun loadRecentFiles(reset: Boolean = true) {
    if (reset) {
        recentJob?.cancel()
        recentLoading = false
        recentOffset = 0
        recentHasMore = true
        binding.homeScreen.recentProgress.isVisible = true
    }
    if (recentLoading || !recentHasMore) return
    recentLoading = true
    val offset = recentOffset
    recentJob = lifecycleScope.launch {
        val pending = pendingDeletes.keys.toSet()
        val page = withContext(Dispatchers.IO) {
            val raw = queryRecentPage(offset)
            RecentPage(
                raw.items.filterNot { pending.contains(selectionKey(it.file)) },
                raw.nextOffset,
                raw.hasMore,
            )
        }
        recentLoading = false
        recentOffset = page.nextOffset
        recentHasMore = page.hasMore
        if (screen != Screen.HOME) return@launch
        binding.homeScreen.recentProgress.isVisible = false
        val merged = if (reset) page.items else recentAdapter.currentList + page.items
        recentAdapter.submitList(merged)
        binding.homeScreen.recentEmpty.isVisible = merged.isEmpty()
        binding.homeScreen.recentList.isVisible = merged.isNotEmpty()
    }
}

private fun queryRecentPage(offset: Int): RecentPage {
    val projection = arrayOf(MediaStore.Files.FileColumns.DATA)
    val rootPrefix = try {
        storageRoot.canonicalPath
    } catch (_: Exception) {
        storageRoot.absolutePath
    }
    val args = Bundle().apply {
        putStringArray(
            ContentResolver.QUERY_ARG_SORT_COLUMNS,
            arrayOf(MediaStore.Files.FileColumns.DATE_MODIFIED),
        )
        putInt(
            ContentResolver.QUERY_ARG_SORT_DIRECTION,
            ContentResolver.QUERY_SORT_DIRECTION_DESCENDING,
        )
        // Skip other volumes in SQL; page through the rest as the list scrolls.
        putString(
            ContentResolver.QUERY_ARG_SQL_SELECTION,
            "${MediaStore.Files.FileColumns.DATA} LIKE ?",
        )
        putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("$rootPrefix/%"))
        putInt(ContentResolver.QUERY_ARG_LIMIT, recentPageSize)
        putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
    }
    val result = mutableListOf<FileItem>()
    var rawCount = 0
    try {
        contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            projection,
            args,
            null,
        )?.use { cursor ->
            val pathColumn = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
            while (cursor.moveToNext()) {
                rawCount++
                if (pathColumn < 0 || cursor.isNull(pathColumn)) continue
                val file = File(cursor.getString(pathColumn))
                if (file.isFile && file.canRead() && primaryRoot.contains(file)) result += FileItem(file)
            }
        }
    } catch (_: RuntimeException) {
        // Some devices restrict MediaStore paths even with broad file access.
    }
    return RecentPage(result, offset + rawCount, rawCount == recentPageSize)
}

private fun loadFiles() {
    loadJob?.cancel()
    val directory = currentDirectory
    binding.browserScreen.loadingIndicator.isVisible = true
    binding.browserScreen.emptyView.isVisible = false
    loadJob = lifecycleScope.launch {
        val pending = pendingDeletes.keys.toSet()
        val items = withContext(Dispatchers.IO) {
            directory.listFiles()
                ?.filterNot { pending.contains(selectionKey(it)) }
                ?.map(::FileItem)
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

private fun applyFilter(scrollToTop: Boolean = false) {
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
        if (scrollToTop) binding.browserScreen.fileList.scrollToPosition(0)
    }
    binding.browserScreen.emptyView.setText(
        if (query.isNotEmpty() && allItems.isNotEmpty()) R.string.no_search_results
        else R.string.empty_folder,
    )
    binding.browserScreen.emptyView.isVisible = visibleItems.isEmpty()
}

private fun setSortMode(mode: SortMode): Boolean {
    sortMode = mode
    applyFilter(scrollToTop = true)
    return true
}

private fun handleItemClick(item: FileItem) {
    if (selectionMode) toggleSelection(item) else openItem(item)
}

private fun startSelection(item: FileItem) {
    selectionMode = true
    selectedPaths.add(selectionKey(item.file))
    renderToolbar()
}

private fun toggleSelection(item: FileItem) {
    val key = selectionKey(item.file)
    if (!selectedPaths.add(key)) selectedPaths.remove(key)
    if (selectedPaths.isEmpty()) selectionMode = false
    renderToolbar()
}

private fun clearSelection() {
    selectionMode = false
    selectedPaths.clear()
    if (::fileAdapter.isInitialized) fileAdapter.refreshSelection()
    if (::recentAdapter.isInitialized) recentAdapter.refreshSelection()
    if (::binding.isInitialized) renderToolbar()
}

internal fun selectAllVisible() {
    val items = if (screen == Screen.BROWSER) fileAdapter.currentList else recentAdapter.currentList
    items.forEach { selectedPaths.add(selectionKey(it.file)) }
    renderToolbar()
}

internal fun selectedItems(): List<FileItem> {
    val items = if (screen == Screen.BROWSER) allItems else recentAdapter.currentList
    return items.filter { selectedPaths.contains(selectionKey(it.file)) }
}

private fun selectionKey(file: File): String {
    return try { file.canonicalPath } catch (_: Exception) { file.absolutePath }
}

internal fun canModify(file: File): Boolean = primaryRoot.contains(file) && hasWriteAccess()

internal fun isInPrimaryStorage(file: File): Boolean = primaryRoot.contains(file)

private fun renderToolbar() {
    fileAdapter.refreshSelection()
    recentAdapter.refreshSelection()
    binding.toolbar.menu.setGroupVisible(R.id.selection_group, selectionMode)
    binding.browserScreen.searchLayout.isEnabled = !selectionMode
    if (selectionMode) {
        binding.toolbar.title = getString(R.string.selected_count, selectedPaths.size)
        binding.toolbar.subtitle = null
        binding.toolbar.setNavigationIcon(R.drawable.ic_close)
        binding.toolbar.setNavigationContentDescription(R.string.close_selection)
        binding.toolbar.menu.setGroupVisible(R.id.sort_group, false)
        binding.toolbar.menu.findItem(R.id.action_storage_settings)?.isVisible = false
        binding.browserScreen.newFolderButton.isVisible = false
        renderSelectionBar(selectedItems())
        return
    }
    renderSelectionBar(emptyList())
    binding.toolbar.menu.setGroupVisible(R.id.selection_group, false)
    if (screen == Screen.HOME) {
        binding.toolbar.title = getString(R.string.app_name)
        binding.toolbar.subtitle = null
        binding.toolbar.navigationIcon = null
        binding.toolbar.menu.setGroupVisible(R.id.sort_group, false)
        binding.toolbar.menu.findItem(R.id.action_storage_settings)?.isVisible = true
        binding.browserScreen.newFolderButton.isVisible = false
    } else {
        binding.toolbar.title = if (currentDirectory == currentRoot.directory) {
            currentRoot.label
        } else {
            currentDirectory.name
        }
        binding.toolbar.subtitle = displayPath(currentDirectory)
        binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        binding.toolbar.setNavigationContentDescription(R.string.navigate_up)
        binding.toolbar.menu.setGroupVisible(R.id.sort_group, true)
        binding.toolbar.menu.findItem(R.id.action_storage_settings)?.isVisible = false
        binding.browserScreen.newFolderButton.isVisible = currentRoot.isPrimary && !currentRoot.readOnly
    }
}

private fun renderSelectionBar(selected: List<FileItem>) {
    val visible = selectionMode && selected.isNotEmpty()
    binding.selectionBar.root.isVisible = visible
    if (!visible) return
    val canModifyAll = selected.all { canModify(it.file) }
    binding.selectionBar.barShare.isEnabled = selected.none { it.isDirectory }
    binding.selectionBar.barMove.isEnabled = canModifyAll
    binding.selectionBar.barDelete.isEnabled = canModifyAll
}

private fun openItem(item: FileItem) {
    if (item.isDirectory) openDirectory(item.file) else openFile(item.file)
}

internal fun openFile(file: File, forceChooser: Boolean = false) {
    val uri = try {
        FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    } catch (_: IllegalArgumentException) {
        toast(R.string.operation_failed)
        return
    }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, FileUtils.mimeType(file))
        clipData = ClipData.newUri(contentResolver, file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        startActivity(if (forceChooser) Intent.createChooser(intent, getString(R.string.open_with)) else intent)
    } catch (_: ActivityNotFoundException) {
        toast(R.string.no_app_to_open)
    }
}

private fun shareFile(file: File) = shareFiles(listOf(file))

private fun shareFiles(files: List<File>) {
    if (files.any { it.isDirectory }) {
        toast(R.string.share_folders_unsupported)
        return
    }
    if (files.isEmpty() || files.any { !it.isFile || !it.canRead() }) {
        toast(R.string.files_no_longer_available)
        return
    }
    val uris = try {
        files.map { FileProvider.getUriForFile(this, "$packageName.fileprovider", it) }
    } catch (_: IllegalArgumentException) {
        toast(R.string.operation_failed)
        return
    }
    val types = files.map(FileUtils::mimeType).distinct()
    val topLevelTypes = types.map { it.substringBefore('/') }.distinct()
    val mimeType = when {
        types.size == 1 -> types.single()
        topLevelTypes.size == 1 -> "${topLevelTypes.single()}/*"
        else -> "*/*"
    }
    val intent = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
        type = mimeType
        if (uris.size == 1) {
            putExtra(Intent.EXTRA_STREAM, uris.single())
        } else {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        clipData = ClipData.newUri(contentResolver, getString(R.string.share_files), uris.first()).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        startActivity(Intent.createChooser(intent, getString(R.string.share_files)))
    } catch (_: ActivityNotFoundException) {
        toast(R.string.no_app_to_share)
    }
}

private fun showFileActions(anchor: View, item: FileItem) {
    val popup = PopupMenu(this, anchor)
    popup.inflate(R.menu.menu_file)
    popup.menu.findItem(R.id.action_share)?.isVisible = !item.isDirectory
    popup.menu.findItem(R.id.action_open)?.isVisible = !item.isDirectory
    popup.menu.findItem(R.id.action_open_with)?.isVisible = !item.isDirectory
    popup.menu.findItem(R.id.action_add_quick_access)?.isVisible =
        item.isDirectory && primaryRoot.contains(item.file)
    popup.menu.findItem(R.id.action_move)?.isVisible = canModify(item.file)
    popup.menu.findItem(R.id.action_rename)?.isVisible = canModify(item.file)
    popup.menu.findItem(R.id.action_delete)?.isVisible = canModify(item.file)
    popup.setOnMenuItemClickListener { action ->
        when (action.itemId) {
            R.id.action_open -> openItem(item)
            R.id.action_open_with -> openFile(item.file, forceChooser = true)
            R.id.action_share -> shareFile(item.file)
            R.id.action_copy -> beginTransfer(listOf(item), move = false)
            R.id.action_move -> beginTransfer(listOf(item), move = true)
            R.id.action_add_quick_access -> addFolderToQuickAccess(item.file)
            R.id.action_rename -> showRenameDialog(item)
            R.id.action_details -> showDetails(item)
            R.id.action_delete -> confirmDelete(item)
            else -> return@setOnMenuItemClickListener false
        }
        true
    }
    popup.show()
}

internal fun beginTransfer(items: List<FileItem>, move: Boolean) {
    if (items.isEmpty()) return
    if (move && items.any { !canModify(it.file) }) {
        toast(R.string.operation_invalid)
        return
    }
    pendingTransfer = PendingTransfer(items.map { it.file }, move)
    val title = getString(if (move) R.string.move_to else R.string.copy_to)
    folderPickerLauncher.launch(FolderPickerActivity.intent(this, title))
}

private fun executeTransfer(transfer: PendingTransfer, destination: File) {
    val progress = ProgressBar(this)
    val dialog = MaterialAlertDialogBuilder(this)
        .setTitle(R.string.working_on_files)
        .setView(progress)
        .setCancelable(false)
        .show()
    lifecycleScope.launch {
        val result = withContext(Dispatchers.IO) {
            if (transfer.move) {
                fileOperations.move(transfer.sources, destination)
            } else {
                fileOperations.copy(transfer.sources, destination)
            }
        }
        dialog.dismiss()
        scanChangedFiles(result)
        clearSelection()
        refreshAfterChange()
        showOperationResult(
            if (transfer.move) FileOperations.Operation.MOVE else FileOperations.Operation.COPY,
            result,
        )
    }
}

private fun deleteItems(items: List<FileItem>) {
    if (items.isEmpty()) return
    if (items.any { !canModify(it.file) }) {
        toast(R.string.delete_not_permitted)
        return
    }
    val progress = ProgressBar(this)
    val dialog = MaterialAlertDialogBuilder(this)
        .setTitle(R.string.working_on_files)
        .setView(progress)
        .setCancelable(false)
        .show()
    lifecycleScope.launch {
        val result = withContext(Dispatchers.IO) { fileOperations.delete(items.map { it.file }) }
        dialog.dismiss()
        scanChangedFiles(result)
        clearSelection()
        refreshAfterChange()
        val failed = result.items.filterNot { it.isSuccess }
        if (failed.isNotEmpty() && failed.all { it.error == FileOperations.Error.PERMISSION_DENIED }) {
            requestDeleteConsent(failed.map { it.source })
        } else {
            showOperationResult(FileOperations.Operation.DELETE, result)
        }
    }
}

private fun scanChangedFiles(result: FileOperations.BatchResult) {
    val paths = result.items.flatMap { item ->
        listOfNotNull(item.source.absolutePath, item.destination?.absolutePath)
    }.toTypedArray()
    if (paths.isNotEmpty()) MediaScannerConnection.scanFile(this, paths, null, null)
}

private fun showOperationResult(operation: FileOperations.Operation, result: FileOperations.BatchResult) {
    if (result.isSuccess) {
        val message = when (operation) {
            FileOperations.Operation.COPY -> R.string.copy_complete
            FileOperations.Operation.MOVE -> R.string.move_complete
            FileOperations.Operation.DELETE -> R.string.delete_complete
        }
        Toast.makeText(this, getString(message, result.successCount), Toast.LENGTH_SHORT).show()
        return
    }
    val partialItems = result.items.filter { it.isPartial }
    if (partialItems.isNotEmpty()) {
        val otherFailures = result.items.filterNot { it.isSuccess || it.isPartial }
        val message = buildString {
            append(getString(R.string.move_partial, partialItems.size))
            append("\n\n")
            append(partialItems.joinToString("\n") { "• ${it.source.name}" })
            if (otherFailures.isNotEmpty()) {
                append("\n\n")
                append(getString(R.string.operation_partial, result.successCount, otherFailures.size))
                append("\n")
                append(otherFailures.joinToString("\n") { "• ${it.source.name} — ${failureReason(it)}" })
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.operation_needs_attention)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
        return
    }
    val failedNames = result.items.filterNot { it.isSuccess }
        .joinToString("\n") { "• ${it.source.name} — ${failureReason(it)}" }
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.operation_failed)
        .setMessage(
            getString(R.string.operation_partial, result.successCount, result.failureCount) +
                "\n\n" + failedNames,
        )
        .setPositiveButton(android.R.string.ok, null)
        .show()
}

private fun failureReason(item: FileOperations.ItemResult): String {
    item.message?.let { return it }
    return when (item.error) {
        FileOperations.Error.PERMISSION_DENIED -> getString(R.string.error_permission_denied)
        FileOperations.Error.MISSING_SOURCE -> getString(R.string.error_missing_source)
        FileOperations.Error.UNREADABLE_SOURCE -> getString(R.string.error_unreadable_source)
        FileOperations.Error.SOURCE_OUTSIDE_PRIMARY,
        FileOperations.Error.DESTINATION_OUTSIDE_PRIMARY,
        -> getString(R.string.error_outside_storage)
        else -> item.error?.name ?: getString(R.string.operation_failed)
    }
}

private fun showNewFolderDialog() {
    if (!currentRoot.isPrimary || !canModify(currentDirectory)) return
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
    if (!currentRoot.isPrimary || !canModify(currentDirectory)) return
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

internal fun showRenameDialog(item: FileItem) {
    if (!canModify(item.file)) return
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
    if (!canModify(item.file)) return
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
    if (!canModify(item.file)) {
        toast(R.string.delete_not_permitted)
        return
    }
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

private fun confirmDelete(items: List<FileItem>) {
    if (items.isEmpty()) return
    if (items.any { !canModify(it.file) }) {
        toast(R.string.delete_not_permitted)
        return
    }
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.delete)
        .setMessage(getString(R.string.delete_selected_message, items.size))
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(R.string.delete) { _, _ -> deleteItems(items) }
        .show()
}

private fun deleteItem(item: FileItem) = deleteItems(listOf(item))

// region Swipe-to-delete with a deferred, undoable commit

private fun enqueueDeferredDelete(item: FileItem) {
    val key = selectionKey(item.file)
    if (pendingDeletes.containsKey(key)) return
    val job = lifecycleScope.launch {
        delay(UNDO_WINDOW_MS)
        commitPendingDelete(key)
    }
    pendingDeletes[key] = PendingDelete(item, job)
    if (screen == Screen.BROWSER) {
        allItems = allItems.filterNot { selectionKey(it.file) == key }
        applyFilter()
    } else {
        val remaining = recentAdapter.currentList.filterNot { selectionKey(it.file) == key }
        recentAdapter.submitList(remaining)
        binding.homeScreen.recentEmpty.isVisible = remaining.isEmpty()
        binding.homeScreen.recentList.isVisible = remaining.isNotEmpty()
    }
    updateUndoSnackbar()
}

private suspend fun commitPendingDelete(key: String) {
    val entry = pendingDeletes[key] ?: return
    entry.committing = true
    // Per-item delete: prepare() rejects a whole batch when any single source is invalid.
    val result = withContext(Dispatchers.IO + NonCancellable) {
        fileOperations.delete(listOf(entry.item.file))
    }
    pendingDeletes.remove(key)
    scanChangedFiles(result)
    refreshAfterChange()
    val failed = result.items.singleOrNull()
    if (failed != null && !failed.isSuccess) {
        if (failed.error == FileOperations.Error.PERMISSION_DENIED) {
            requestDeleteConsent(listOf(entry.item.file))
        } else {
            showOperationResult(FileOperations.Operation.DELETE, result)
        }
    }
    updateUndoSnackbar()
}

private fun undoPendingDeletes() {
    val restored = pendingDeletes.filterValues { !it.committing }
    restored.forEach { (key, entry) ->
        entry.job.cancel()
        pendingDeletes.remove(key)
    }
    undoSnackbar?.dismiss()
    undoSnackbar = null
    if (restored.isNotEmpty()) {
        // The files never left the disk; reloading brings their rows back.
        refreshAfterChange()
        toast(getString(R.string.deletes_restored, restored.size))
    }
    updateUndoSnackbar()
}

private fun updateUndoSnackbar() {
    val undoable = pendingDeletes.filterValues { !it.committing }
    undoSnackbar?.dismiss()
    undoSnackbar = null
    if (undoable.isEmpty()) return
    val text = if (undoable.size == 1) {
        getString(R.string.delete_pending_single, undoable.values.first().item.name)
    } else {
        resources.getQuantityString(R.plurals.delete_pending_plural, undoable.size, undoable.size)
    }
    val snackbar = Snackbar.make(binding.root, text, UNDO_WINDOW_MS.toInt() + 500)
        .setAction(R.string.undo) { undoPendingDeletes() }
    if (screen == Screen.BROWSER && binding.browserScreen.newFolderButton.isVisible) {
        snackbar.setAnchorView(binding.browserScreen.newFolderButton)
    }
    snackbar.show()
    undoSnackbar = snackbar
}

// endregion

// region Scoped-storage delete consent fallback

private fun requestDeleteConsent(files: List<File>) {
    if (files.isEmpty()) return
    when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
            val uris = DeleteFallback.mediaStoreUris(contentResolver, files)
            val sender = if (uris.isEmpty()) {
                null
            } else {
                DeleteFallback.deleteRequestSender(contentResolver, uris)
            }
            if (sender == null) {
                toast(R.string.delete_consent_unavailable)
                return
            }
            pendingConsentFiles = files
            deleteConsentLauncher.launch(IntentSenderRequest.Builder(sender).build())
        }
        Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> requestDeleteConsentQ(files)
        else -> toast(R.string.delete_not_permitted)
    }
}

private fun requestDeleteConsentQ(files: List<File>) {
    lifecycleScope.launch {
        var consentSender: IntentSender? = null
        val needsConsent = mutableListOf<File>()
        val deleted = mutableListOf<File>()
        withContext(Dispatchers.IO) {
            files.forEach { file ->
                val uri = DeleteFallback.mediaStoreUri(contentResolver, file)
                if (uri == null) {
                    needsConsent += file
                    return@forEach
                }
                when (val outcome = DeleteFallback.deleteViaMediaStoreQ(contentResolver, uri)) {
                    is DeleteFallback.QDeleteResult.Deleted -> deleted += file
                    is DeleteFallback.QDeleteResult.Consent -> {
                        if (consentSender == null) consentSender = outcome.sender
                        needsConsent += file
                    }
                    is DeleteFallback.QDeleteResult.Failed -> needsConsent += file
                }
            }
        }
        if (deleted.isNotEmpty()) {
            scanChangedFiles(
                FileOperations.BatchResult(
                    deleted.map {
                        FileOperations.ItemResult(
                            FileOperations.Operation.DELETE,
                            it,
                            null,
                            FileOperations.Status.DELETED,
                        )
                    },
                ),
            )
            refreshAfterChange()
        }
        val sender = consentSender
        if (needsConsent.isEmpty()) {
            if (deleted.isEmpty()) toast(R.string.delete_consent_unavailable)
            return@launch
        }
        if (sender == null) {
            toast(R.string.delete_consent_unavailable)
            return@launch
        }
        pendingConsentFiles = needsConsent
        deleteConsentLauncher.launch(IntentSenderRequest.Builder(sender).build())
    }
}

private fun finishConsentedDeletes(files: List<File>) {
    lifecycleScope.launch {
        val results = withContext(Dispatchers.IO) {
            files.map { file ->
                if (!file.exists()) {
                    // The system consent dialog already removed it.
                    FileOperations.ItemResult(
                        FileOperations.Operation.DELETE,
                        file,
                        null,
                        FileOperations.Status.DELETED,
                    )
                } else {
                    // Consent grants a one-time raw-delete allowance for this file.
                    fileOperations.delete(file)
                }
            }
        }
        val batch = FileOperations.BatchResult(results)
        scanChangedFiles(batch)
        refreshAfterChange()
        showOperationResult(FileOperations.Operation.DELETE, batch)
    }
}

// endregion

internal fun showDetails(item: FileItem) {
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
    val relative = currentRoot.relativePath(file).orEmpty()
    return if (relative.isEmpty()) currentRoot.label
    else getString(R.string.shared_path_format, currentRoot.label, relative)
}

private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

private fun toast(message: Int) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

private fun toast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

private companion object {
    const val STATE_PENDING_IMPORT = "pending_import_uri"
    const val STATE_TRANSFER_PATHS = "pending_transfer_paths"
    const val STATE_TRANSFER_MOVE = "pending_transfer_move"
    const val UNDO_WINDOW_MS = 3_000L
}

}