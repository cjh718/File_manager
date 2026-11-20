package com.folio.files

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.folio.files.databinding.ItemFileBinding

class FileAdapter(
    private val onClick: (FileItem) -> Unit,
    private val onMore: (android.view.View, FileItem) -> Unit,
) : ListAdapter<FileItem, FileAdapter.FileViewHolder>(FileDiff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val binding = ItemFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return FileViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class FileViewHolder(private val binding: ItemFileBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: FileItem) {
            val context = binding.root.context
            binding.fileName.text = item.name
            binding.fileMetadata.text = FileUtils.metadata(context, item)
            binding.fileIcon.setImageResource(iconFor(item))
            binding.fileIcon.setColorFilter(
                ContextCompat.getColor(
                    context,
                    if (item.isDirectory) R.color.folder_icon else R.color.file_icon,
                ),
            )
            binding.fileIconContainer.setBackgroundResource(
                if (item.isDirectory) R.drawable.bg_folder_icon else R.drawable.bg_file_icon,
            )
            binding.fileMore.contentDescription = context.getString(R.string.file_actions, item.name)
            binding.root.setOnClickListener { onClick(item) }
            binding.fileMore.setOnClickListener { onMore(it, item) }
        }

        private fun iconFor(item: FileItem): Int {
            if (item.isDirectory) return R.drawable.ic_folder
            return when (item.file.extension.lowercase()) {
                "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "svg" -> R.drawable.ic_image
                "mp4", "mkv", "mov", "avi", "webm", "3gp" -> R.drawable.ic_video
                "mp3", "wav", "ogg", "m4a", "aac", "flac" -> R.drawable.ic_audio
                "pdf", "doc", "docx", "txt", "rtf", "odt", "md" -> R.drawable.ic_document
                "zip", "rar", "7z", "tar", "gz" -> R.drawable.ic_archive
                "apk" -> R.drawable.ic_android
                else -> R.drawable.ic_file
            }
        }
    }

    private object FileDiff : DiffUtil.ItemCallback<FileItem>() {
        override fun areItemsTheSame(oldItem: FileItem, newItem: FileItem): Boolean {
            return oldItem.file.absolutePath == newItem.file.absolutePath
        }

        override fun areContentsTheSame(oldItem: FileItem, newItem: FileItem): Boolean {
            return oldItem.name == newItem.name &&
                oldItem.isDirectory == newItem.isDirectory &&
                oldItem.size == newItem.size &&
                oldItem.modified == newItem.modified
        }
    }
}
