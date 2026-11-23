
package com.folio.files

import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.text.format.Formatter
import android.view.MenuItem
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.folio.files.databinding.ActivityImageViewerBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ImageViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImageViewerBinding
    private var imageFile: File? = null
    private var displayedBitmap: Bitmap? = null

    private val storageRoot: File
        get() = android.os.Environment.getExternalStorageDirectory().canonicalFile

    private val folderPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult

            val destinationPath = result.data
                ?.getStringExtra(FolderPickerActivity.EXTRA_DIRECTORY)
                ?: return@registerForActivityResult

            val source = imageFile ?: return@registerForActivityResult
            val destination = File(destinationPath)

            val moving = pendingTransferIsMove
            pendingTransferIsMove = false

            performTransfer(source, destination, moving)
        }

    private var pendingTransferIsMove = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityImageViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        imageFile = savedInstanceState?.getString(STATE_FILE_PATH)
            ?.let(::File)
            ?: intent.getStringExtra(EXTRA_FILE_PATH)?.let(::File)

        val file = imageFile
        if (file == null || !file.isFile || !file.canRead()) {
            Toast.makeText(this, R.string.folder_unavailable, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.toolbar.title = file.name
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.actionShare.setOnClickListener { shareImage() }
        binding.actionEdit.setOnClickListener { editImage() }
        binding.actionDelete.setOnClickListener { confirmDelete() }
        binding.actionMore.setOnClickListener { showMoreActions(it) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        })

        loadImage(file)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        imageFile?.let { outState.putString(STATE_FILE_PATH, it.absolutePath) }
    }

    override fun onDestroy() {
        displayedBitmap?.let { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        displayedBitmap = null
        super.onDestroy()
    }

    private fun loadImage(file: File) {
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                if (!file.isFile || !file.canRead()) {
                    null
                } else {
                    BitmapFactory.decodeFile(file.absolutePath)
                }
            }

            if (isFinishing || isDestroyed) {
                bitmap?.recycle()
                return@launch
            }

            if (bitmap == null) {
                Toast.makeText(this@ImageViewerActivity, R.string.operation_failed, Toast.LENGTH_SHORT)
                    .show()
                finish()
                return@launch
            }

            displayedBitmap = bitmap
            binding.imageView.setImageBitmap(bitmap)
            binding.toolbar.title = file.name
        }
    }

    private fun showMoreActions(anchor: android.view.View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.menu_image_viewer, popup.menu)

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_rename -> {
                    showRenameDialog()
                    true
                }

                R.id.action_copy -> {
                    startTransfer(move = false)
                    true
                }

                R.id.action_move -> {
                    startTransfer(move = true)
                    true
                }

                R.id.action_image_details -> {
                    showImageDetails()
                    true
                }

                R.id.action_set_wallpaper -> {
                    setAsWallpaper()
                    true
                }

                R.id.action_open_with -> {
                    openWithAnotherApp()
                    true
                }

                else -> false
            }
        }

        popup.show()
    }

    private fun shareImage() {
        val file = imageFile ?: return
        val uri = fileUri(file) ?: return

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = FileUtils.mimeType(file)
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(contentResolver, file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            startActivity(Intent.createChooser(shareIntent, getString(R.string.share_file)))
        } catch (_: ActivityNotFoundException) {
            showMessage(R.string.no_app_to_share)
        }
    }

    private fun editImage() {
        val file = imageFile ?: return
        val uri = fileUri(file) ?: return

        val editIntent = Intent(Intent.ACTION_EDIT).apply {
            setDataAndType(uri, FileUtils.mimeType(file))
            clipData = ClipData.newUri(contentResolver, file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }

        try {
            startActivity(Intent.createChooser(editIntent, getString(R.string.edit)))
        } catch (_: ActivityNotFoundException) {
            showMessage(R.string.no_app_to_open)
        }
    }

    private fun openWithAnotherApp() {
        val file = imageFile ?: return
        val uri = fileUri(file) ?: return

        val openIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, FileUtils.mimeType(file))
            clipData = ClipData.newUri(contentResolver, file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            startActivity(Intent.createChooser(openIntent, getString(R.string.open_with)))
        } catch (_: ActivityNotFoundException) {
            showMessage(R.string.no_app_to_open)
        }
    }

    private fun confirmDelete() {
        val file = imageFile ?: return

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete)
            .setMessage(getString(R.string.delete_file_message, file.name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                deleteImage(file)
            }
            .show()
    }

    private fun deleteImage(file: File) {
        if (!isInsidePrimaryStorage(file)) {
            showMessage(R.string.delete_not_permitted)
            return
        }

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                FileOperations(storageRoot).delete(file)
            }

            if (result.isSuccess) {
                MediaScannerConnection.scanFile(
                    this@ImageViewerActivity,
                    arrayOf(file.absolutePath),
                    null,
                    null,
                )
                Toast.makeText(
                    this@ImageViewerActivity,
                    R.string.item_deleted,
                    Toast.LENGTH_SHORT,
                ).show()
                setResult(RESULT_IMAGE_DELETED)
                finish()
            } else {
                showMessage(R.string.operation_failed)
            }
        }
    }

    private fun showRenameDialog() {
        val file = imageFile ?: return

        if (!isInsidePrimaryStorage(file)) {
            showMessage(R.string.operation_invalid)
            return
        }

        val input = EditText(this).apply {
            setSingleLine(true)
            setText(file.name)
            setSelection(text.length)
            hint = getString(R.string.new_name)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.rename) { _, _ ->
                renameImage(file, input.text?.toString()?.trim().orEmpty())
            }
            .show()
    }

    private fun renameImage(file: File, newName: String) {
        if (!FileUtils.isValidName(newName)) {
            showMessage(R.string.invalid_name)
            return
        }

        val target = File(file.parentFile, newName)

        if (target.exists()) {
            showMessage(R.string.name_already_exists)
            return
        }

        lifecycleScope.launch {
            val renamed = withContext(Dispatchers.IO) {
                file.renameTo(target)
            }

            if (renamed) {
                imageFile = target
                binding.toolbar.title = target.name
                MediaScannerConnection.scanFile(
                    this@ImageViewerActivity,
                    arrayOf(file.absolutePath, target.absolutePath),
                    null,
                    null,
                )
                setResult(RESULT_IMAGE_CHANGED)
                showMessage(R.string.file_renamed)
            } else {
                showMessage(R.string.operation_failed)
            }
        }
    }

    private fun startTransfer(move: Boolean) {
        val file = imageFile ?: return

        if (move && !isInsidePrimaryStorage(file)) {
            showMessage(R.string.operation_invalid)
            return
        }

        pendingTransferIsMove = move
        val title = getString(if (move) R.string.move_to else R.string.copy_to)

        folderPickerLauncher.launch(
            FolderPickerActivity.intent(this, title),
        )
    }

    private fun performTransfer(
        source: File,
        destination: File,
        move: Boolean,
    ) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val operations = FileOperations(storageRoot)
                if (move) {
                    operations.move(source, destination)
                } else {
                    operations.copy(source, destination)
                }
            }

            if (result.isSuccess) {
                result.destination?.let { copiedFile ->
                    MediaScannerConnection.scanFile(
                        this@ImageViewerActivity,
                        arrayOf(copiedFile.absolutePath),
                        null,
                        null,
                    )
                }

                if (move) {
                    setResult(RESULT_IMAGE_CHANGED)
                    finish()
                } else {
                    showMessage(R.string.copy_complete, 1)
                }
            } else {
                showMessage(R.string.operation_failed)
            }
        }
    }

    private fun showImageDetails() {
        val file = imageFile ?: return

        lifecycleScope.launch {
            val details = withContext(Dispatchers.IO) {
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeFile(file.absolutePath, options)

                val resolution = if (options.outWidth > 0 && options.outHeight > 0) {
                    "${options.outWidth} × ${options.outHeight}"
                } else {
                    getString(R.string.unknown)
                }

                val size = Formatter.formatShortFileSize(this@ImageViewerActivity, file.length())
                val format = FileUtils.mimeType(file)

                getString(
                    R.string.image_details_message,
                    resolution,
                    format,
                    size,
                    file.absolutePath,
                )
            }

            MaterialAlertDialogBuilder(this@ImageViewerActivity)
                .setTitle(file.name)
                .setMessage(details)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun setAsWallpaper() {
        val bitmap = displayedBitmap ?: run {
            showMessage(R.string.operation_failed)
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.set_wallpaper)
            .setMessage(R.string.set_wallpaper_confirmation)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.set_wallpaper) { _, _ ->
                lifecycleScope.launch {
                    val success = withContext(Dispatchers.IO) {
                        try {
                            WallpaperManager.getInstance(this@ImageViewerActivity)
                                .setBitmap(bitmap)
                            true
                        } catch (_: IOException) {
                            false
                        } catch (_: SecurityException) {
                            false
                        }
                    }

                    showMessage(
                        if (success) R.string.wallpaper_set
                        else R.string.operation_failed,
                    )
                }
            }
            .show()
    }

    private fun fileUri(file: File): Uri? {
        return try {
            FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                file,
            )
        } catch (_: IllegalArgumentException) {
            showMessage(R.string.operation_failed)
            null
        }
    }

    private fun isInsidePrimaryStorage(file: File): Boolean {
        return try {
            val rootPath = storageRoot.canonicalPath
            val filePath = file.canonicalPath
            filePath.startsWith(rootPath + File.separator)
        } catch (_: IOException) {
            false
        }
    }

    private fun showMessage(messageRes: Int) {
        Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show()
    }

    private fun showMessage(messageRes: Int, count: Int) {
        Toast.makeText(
            this,
            getString(messageRes, count),
            Toast.LENGTH_SHORT,
        ).show()
    }

    companion object {
        const val EXTRA_FILE_PATH = "com.folio.files.extra.IMAGE_PATH"

        const val RESULT_IMAGE_DELETED = 21
        const val RESULT_IMAGE_CHANGED = 22

        private const val STATE_FILE_PATH = "image_file_path"

        fun intent(context: android.content.Context, file: File): Intent {
            return Intent(context, ImageViewerActivity::class.java)
                .putExtra(EXTRA_FILE_PATH, file.absolutePath)
        }
    }
}
