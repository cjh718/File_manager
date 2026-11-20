package com.folio.files

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.folio.files.databinding.ActivityQuickAccessBinding
import com.folio.files.databinding.ItemQuickAccessBinding
import java.io.File

class QuickAccessActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQuickAccessBinding
    private lateinit var store: QuickAccessStore
    private lateinit var adapter: ShortcutAdapter

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val path = result.data?.getStringExtra(FolderPickerActivity.EXTRA_DIRECTORY)
        if (result.resultCode == RESULT_OK && path != null) {
            try {
                store.add(File(path))
                toast(R.string.shortcut_added)
                render()
            } catch (_: IllegalArgumentException) {
                toast(R.string.shortcut_already_exists)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQuickAccessBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = QuickAccessStore(this)
        adapter = ShortcutAdapter()
        binding.shortcutList.layoutManager = LinearLayoutManager(this)
        binding.shortcutList.adapter = adapter
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.addFolder.setOnClickListener {
            folderPicker.launch(FolderPickerActivity.intent(this, getString(R.string.quick_access_picker_title)))
        }
        binding.resetDefaults.setOnClickListener {
            store.reset()
            toast(R.string.defaults_restored)
            render()
        }
        render()
    }

    private fun render() {
        val entries = store.load()
        adapter.submitList(entries)
        binding.emptyView.isVisible = entries.isEmpty()
        setResult(RESULT_OK)
    }

    private fun toast(message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private inner class ShortcutAdapter :
        ListAdapter<QuickAccessEntry, ShortcutAdapter.Holder>(ShortcutDiff) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            return Holder(ItemQuickAccessBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(getItem(position), position)
        }

        inner class Holder(private val row: ItemQuickAccessBinding) : RecyclerView.ViewHolder(row.root) {
            fun bind(entry: QuickAccessEntry, position: Int) {
                val title = entry.displayTitle(this@QuickAccessActivity)
                row.shortcutTitle.text = title
                row.shortcutPath.text = entry.relativePath
                row.shortcutIcon.setImageResource(entry.iconRes)
                row.moveUp.isEnabled = position > 0
                row.moveDown.isEnabled = position < itemCount - 1
                row.moveUp.alpha = if (row.moveUp.isEnabled) 1f else 0.35f
                row.moveDown.alpha = if (row.moveDown.isEnabled) 1f else 0.35f
                row.moveUp.contentDescription = getString(R.string.move_up) + ": " + title
                row.moveDown.contentDescription = getString(R.string.move_down) + ": " + title
                row.remove.contentDescription = getString(R.string.remove_shortcut) + ": " + title
                row.moveUp.setOnClickListener { store.moveUp(entry.id); render() }
                row.moveDown.setOnClickListener { store.moveDown(entry.id); render() }
                row.remove.setOnClickListener {
                    if (store.remove(entry.id)) toast(R.string.shortcut_removed)
                    render()
                }
            }
        }
    }

    private object ShortcutDiff : DiffUtil.ItemCallback<QuickAccessEntry>() {
        override fun areItemsTheSame(oldItem: QuickAccessEntry, newItem: QuickAccessEntry) =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: QuickAccessEntry, newItem: QuickAccessEntry) =
            oldItem == newItem
    }
}
