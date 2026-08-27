package com.apkbuilder.studio.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.apkbuilder.studio.databinding.ItemFileBinding
import com.apkbuilder.studio.ui.FileEntry
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class FileListAdapter(
    private val onRemoveClick: (FileEntry) -> Unit
) : ListAdapter<FileEntry, FileListAdapter.FileViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val binding = ItemFileBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return FileViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class FileViewHolder(
        private val binding: ItemFileBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(file: FileEntry) {
            binding.tvFileName.text = file.name
            binding.tvFilePath.text = file.path
            binding.tvFileSize.text = "%.1f KB".format(file.sizeKB)
            binding.btnRemove.setOnClickListener { onRemoveClick(file) }
        }
    }

    companion object DiffCallback = object : DiffUtil.ItemCallback<FileEntry>() {
        override fun areItemsTheSame(a: FileEntry, b: FileEntry) = a.path == b.path
        override fun areContentsTheSame(a: FileEntry, b: FileEntry) = a == b
    }
}
