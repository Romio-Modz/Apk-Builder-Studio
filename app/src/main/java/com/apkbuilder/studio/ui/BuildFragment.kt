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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.zip.ZipInputStream

class BuildFragment : Fragment() {

    private var _binding: FragmentBuildBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var fileAdapter: FileListAdapter

    private val pickMultipleFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult

        viewLifecycleOwner.lifecycleScope.launch {
            for (uri in uris) {
                val fileName = getFileName(uri) ?: "unknown_file"

                if (fileName.endsWith(".zip", ignoreCase = true)) {
                    handleZipFile(uri, fileName)
                } else {
                    val fileSizeKB = getFileSizeKB(uri)
                    withContext(Dispatchers.Main) {
                        viewModel.addFile(fileName, uri.toString(), fileSizeKB, uri)
                    }
                }
            }
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
        setupAnimations()
    }

    private fun setupAnimations() {
        ButtonAnimation.applyTo(binding.btnUploadFile)
        ButtonAnimation.applyTo(binding.btnStartBuild)
        ButtonAnimation.applyTo(binding.btnCancelBuild)
        ButtonAnimation.applyTo(binding.btnClearFiles)
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
            binding.buildProgressCard.visibility = View.VISIBLE
            viewModel.startRealBuild(requireContext(), isRelease)
        }

        binding.btnCancelBuild.setOnClickListener {
            viewModel.cancelBuild()
        }

        binding.btnDownloadApk.setOnClickListener {
            val arts = viewModel.artifacts.value
            if (arts.isNotEmpty()) {
                val url = arts[0].downloadUrl
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${viewModel.githubUser.value}/${viewModel.repoName.value}/actions"))
                startActivity(intent)
            }
        }
    }

    private suspend fun handleZipFile(zipUri: Uri, zipFileName: String) {
        withContext(Dispatchers.IO) {
            try {
                val inputStream: InputStream? = requireContext().contentResolver.openInputStream(zipUri)
                inputStream?.use { stream ->
                    val zipStream = ZipInputStream(stream)
                    var entry = zipStream.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val entryName = entry.name
                            val entrySizeKB = if (entry.size > 0) entry.size / 1024.0 else 1.0
                            withContext(Dispatchers.Main) {
                                viewModel.addFile(entryName, "$zipFileName!/$entryName", entrySizeKB, zipUri)
                            }
                        }
                        entry = zipStream.nextEntry
                    }
                    zipStream.closeEntry()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    viewModel.addFile(zipFileName, zipUri.toString(), getFileSizeKB(zipUri), zipUri)
                }
            }
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
                viewModel.buildProgress.collect { progress ->
                    binding.progressBar.progress = progress
                    binding.tvProgressPercent.text = "$progress%"
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.buildStatus.collect { status ->
                    binding.tvBuildStatus.text = status
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

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.artifacts.collect { arts ->
                    if (arts.isNotEmpty()) {
                        binding.btnDownloadApk.visibility = View.VISIBLE
                        binding.btnDownloadApk.text = "Download APK (${arts[0].name})"
                    } else {
                        binding.btnDownloadApk.visibility = View.GONE
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
