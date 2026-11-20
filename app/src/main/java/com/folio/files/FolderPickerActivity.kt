package com.folio.files

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.folio.files.databinding.ActivityFolderPickerBinding
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FolderPickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFolderPickerBinding
    private lateinit var adapter: FileAdapter
    private val root: File by lazy { Environment.getExternalStorageDirectory().canonicalFile }
    private var currentDirectory: File? = null
    private var loadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFolderPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.title = intent.getStringExtra(EXTRA_TITLE)
            ?: getString(R.string.folder_picker_title)
        binding.toolbar.setNavigationOnClickListener { navigateBack() }
        adapter = FileAdapter(
            onClick = { openDirectory(it.file) },
            onMore = { _, _ -> },
            showActions = false,
        )
        binding.folderList.layoutManager = LinearLayoutManager(this)
        binding.folderList.adapter = adapter
        binding.selectFolder.setOnClickListener {
            val directory = currentDirectory ?: return@setOnClickListener
            setResult(RESULT_OK, Intent().putExtra(EXTRA_DIRECTORY, directory.absolutePath))
            finish()
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = navigateBack()
        })

        val savedPath = savedInstanceState?.getString(STATE_DIRECTORY)
        val initial = savedPath?.let(::File)?.takeIf(::isInsideRoot) ?: root
        openDirectory(initial)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentDirectory?.let { outState.putString(STATE_DIRECTORY, it.absolutePath) }
    }

    private fun openDirectory(directory: File) {
        val canonical = try {
            directory.canonicalFile
        } catch (_: Exception) {
            return
        }
        if (!isInsideRoot(canonical) || !canonical.isDirectory || !canonical.canRead()) return
        currentDirectory = canonical
        loadJob?.cancel()
        binding.loading.isVisible = true
        binding.emptyView.isVisible = false
        binding.currentPath.text = displayPath(canonical)
        loadJob = lifecycleScope.launch {
            val folders = withContext(Dispatchers.IO) {
                canonical.listFiles()
                    ?.filter { it.isDirectory }
                    ?.sortedBy { it.name.lowercase() }
                    ?.map(::FileItem)
            }
            if (currentDirectory != canonical) return@launch
            binding.loading.isVisible = false
            adapter.submitList(folders.orEmpty())
            binding.emptyView.isVisible = folders.isNullOrEmpty()
        }
    }

    private fun navigateBack() {
        val directory = currentDirectory
        if (directory == null || directory == root) {
            finish()
        } else {
            openDirectory(directory.parentFile ?: root)
        }
    }

    private fun isInsideRoot(directory: File): Boolean {
        return try {
            val path = directory.canonicalPath
            val rootPath = root.canonicalPath
            path == rootPath || path.startsWith(rootPath + File.separator)
        } catch (_: Exception) {
            false
        }
    }

    private fun displayPath(directory: File): String {
        val relative = directory.relativeToOrNull(root)?.path.orEmpty()
        return if (relative.isEmpty()) getString(R.string.internal_storage)
        else getString(R.string.path_format, relative)
    }

    companion object {
        const val EXTRA_DIRECTORY = "com.folio.files.extra.DIRECTORY"
        private const val EXTRA_TITLE = "com.folio.files.extra.TITLE"
        private const val STATE_DIRECTORY = "directory"

        fun intent(context: Context, title: String): Intent {
            return Intent(context, FolderPickerActivity::class.java)
                .putExtra(EXTRA_TITLE, title)
        }
    }
}
