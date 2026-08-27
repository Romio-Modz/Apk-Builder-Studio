package com.apkbuilder.studio.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.apkbuilder.studio.data.BuildJob
import com.apkbuilder.studio.data.BuildStatus
import com.apkbuilder.studio.databinding.ItemJobBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class JobAdapter(
    private val onDeleteClick: (BuildJob) -> Unit
) : ListAdapter<BuildJob, JobAdapter.JobViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): JobViewHolder {
        val binding = ItemJobBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return JobViewHolder(binding)
    }

    override fun onBindViewHolder(holder: JobViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class JobViewHolder(
        private val binding: ItemJobBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private val dateFormat = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())

        fun bind(job: BuildJob) {
            binding.tvAppName.text = job.config.appName
            binding.tvPackage.text = job.config.packageName
            binding.tvBuildType.text = job.config.buildType.displayName
            binding.tvProgress.text = "${job.progress}%"
            binding.tvTimestamp.text = dateFormat.format(Date(job.timestamp))

            val statusText = when (job.status) {
                BuildStatus.IDLE -> "Idle"
                BuildStatus.QUEUED -> "Queued"
                BuildStatus.BUILDING -> "Building"
                BuildStatus.SUCCESS -> "Success"
                BuildStatus.FAILED -> "Failed"
            }
            binding.tvStatus.text = statusText
            binding.progressBar.progress = job.progress

            binding.btnDelete.setOnClickListener { onDeleteClick(job) }
        }
    }

    companion object {
        val DiffCallback = object : DiffUtil.ItemCallback<BuildJob>() {
            override fun areItemsTheSame(a: BuildJob, b: BuildJob) = a.id == b.id
            override fun areContentsTheSame(a: BuildJob, b: BuildJob) = a == b
        }
    }
}
