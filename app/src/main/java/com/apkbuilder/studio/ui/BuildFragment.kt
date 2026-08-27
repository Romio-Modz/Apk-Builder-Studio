package com.apkbuilder.studio.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.apkbuilder.studio.ui.adapter.FileListAdapter
import com.apkbuilder.studio.databinding.FragmentBuildBinding
import kotlinx.coroutines.launch

class BuildFragment : Fragment() {

    private var _binding: FragmentBuildBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var fileAdapter: FileListAdapter

    private val pickMultipleFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult

        for (uri in uris) {
            val fileName = getFileName(uri) ?: "unknown_file"
            val fileSizeKB = getFileSizeKB(uri)
            viewModel.addFile(fileName, uri.toString(), fileSizeKB)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBuildBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupFileList()
        setupListeners()
        observeViewModel()
    }

    private fun setupFileList() {
        fileAdapter = FileListAdapter { file -> viewModel.removeFile(file) }
        binding.rvUploadedFiles.layoutManager = LinearLayoutManager(requireContext())
        binding.rvUploadedFiles.adapter = fileAdapter
    }

    private fun setupListeners() {
        binding.btnUploadFile.setOnClickListener {
            pickMultipleFiles.launch(arrayOf("*/*"))
        }

        binding.btnClearFiles.setOnClickListener {
            viewModel.clearFiles()
        }

        binding.switchRelease.setOnCheckedChangeListener { _, isChecked ->
            binding.tvBuildTypeLabel.text = if (isChecked) "Release Build" else "Debug Build"
        }

        binding.btnStartBuild.setOnClickListener {
            val isRelease = binding.switchRelease.isChecked
            viewModel.startBuild(isRelease)
            binding.buildProgressCard.visibility = View.VISIBLE
        }

        binding.btnCancelBuild.setOnClickListener {
            viewModel.cancelBuild()
            binding.buildProgressCard.visibility = View.GONE
        }
    }

    private fun getFileName(uri: Uri): String? {
        var name: String? = null
        val cursor = requireContext().contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && it.moveToFirst()) {
                name = it.getString(nameIndex)
            }
        }
        if (name == null) {
            name = uri.lastPathSegment
        }
        return name
    }

    private fun getFileSizeKB(uri: Uri): Double {
        var sizeKB = 0.0
        val cursor = requireContext().contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0) {
                    val sizeBytes = it.getLong(sizeIndex)
                    if (sizeBytes > 0) {
                        sizeKB = sizeBytes / 1024.0
                    }
                }
            }
        }
        return if (sizeKB > 0) sizeKB else 1.0
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uploadedFiles.collect { files ->
                    fileAdapter.submitList(files)
                    binding.tvFileCount.text = "${files.size} files uploaded"
                    binding.rvUploadedFiles.visibility = if (files.isEmpty()) View.GONE else View.VISIBLE
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.currentJob.collect { job ->
                    if (job != null) {
                        binding.progressBar.progress = job.progress
                        binding.tvProgressPercent.text = "${job.progress}%"
                        binding.tvBuildStatus.text = job.status.displayName
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.logLines.collect { lines ->
                    binding.tvBuildLog.text = lines.joinToString("\n")
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.isBuilding.collect { building ->
                    binding.btnStartBuild.isEnabled = !building
                    binding.btnCancelBuild.isEnabled = building
                    binding.btnStartBuild.text = if (building) "Building..." else "Start Build"
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
