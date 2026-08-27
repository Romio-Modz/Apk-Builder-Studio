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
import java.io.File
import java.io.FileOutputStream
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
                    // Copy individual file to temp cache so we have a stable path
                    val tempFile = copyToTempFile(uri, fileName)
                    if (tempFile != null) {
                        val fileSizeKB = tempFile.length() / 1024.0
                        viewModel.addFile(fileName, tempFile.absolutePath, fileSizeKB, Uri.fromFile(tempFile))
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
            // Clean up temp files
            viewModel.uploadedFiles.value.forEach { file ->
                try {
                    val f = java.io.File(file.path)
                    if (f.exists() && f.absolutePath.contains(requireContext().cacheDir.absolutePath)) {
                        f.delete()
                    }
                } catch (e: Exception) {}
            }
            viewModel.clearFiles()
        }

        binding.switchRelease.setOnCheckedChangeListener { _, isChecked ->
            binding.tvBuildTypeLabel.text = if (isChecked) "Release Build" else "Debug Build"
        }

        binding.btnStartBuild.setOnClickListener {
            viewModel.updateGithubToken(binding.etGithubToken.text.toString())
            viewModel.updateGithubUser(binding.etGithubUser.text.toString())
            viewModel.updateRepoName(binding.etRepoName.text.toString())
            viewModel.updateBranch(binding.etBranch.text.toString())
            viewModel.updateAppName(binding.etAppName.text.toString())
            viewModel.updatePackageName(binding.etPackageName.text.toString())
            viewModel.updateVersionName(binding.etVersionName.text.toString())
            val vCode = binding.etVersionCode.text.toString().toIntOrNull() ?: 1
            viewModel.updateVersionCode(vCode)
            val minS = binding.etMinSdk.text.toString().toIntOrNull() ?: 24
            viewModel.updateMinSdk(minS)
            val tgtS = binding.etTargetSdk.text.toString().toIntOrNull() ?: 34
            viewModel.updateTargetSdk(tgtS)

            val isRelease = binding.switchRelease.isChecked
            binding.buildProgressCard.visibility = View.VISIBLE
            viewModel.startRealBuild(requireContext(), isRelease)
        }

        binding.btnCancelBuild.setOnClickListener {
            viewModel.cancelBuild()
        }

        binding.btnDownloadApk.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${viewModel.githubUser.value}/${viewModel.repoName.value}/actions"))
            startActivity(intent)
        }
    }

    /**
     * Extract ZIP file entries to individual temp files in cache directory.
     * Each extracted file gets its own temp file so we don't need to re-read the ZIP later.
     */
    private suspend fun handleZipFile(zipUri: Uri, zipFileName: String) {
        withContext(Dispatchers.IO) {
            try {
                val cacheDir = requireContext().cacheDir
                val extractDir = File(cacheDir, "extracted_${System.currentTimeMillis()}")
                extractDir.mkdirs()

                val inputStream: InputStream? = requireContext().contentResolver.openInputStream(zipUri)
                inputStream?.use { stream ->
                    val zipStream = ZipInputStream(stream)
                    var entry = zipStream.nextEntry
                    var count = 0
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val entryName = entry.name
                            // Sanitize file name for temp file
                            val safeName = entryName.replace("/", "_").replace("\\", "_")
                            val tempFile = File(extractDir, "${count}_${safeName}")
                            
                            // Extract entry to temp file
                            FileOutputStream(tempFile).use { out ->
                                val buffer = ByteArray(8192)
                                var len: Int
                                while (zipStream.read(buffer).also { len = it } > 0) {
                                    out.write(buffer, 0, len)
                                }
                            }
                            
                            val fileSizeKB = tempFile.length() / 1024.0
                            val fileUri = Uri.fromFile(tempFile)
                            
                            withContext(Dispatchers.Main) {
                                viewModel.addFile(entryName, tempFile.absolutePath, fileSizeKB, fileUri)
                            }
                            count++
                        }
                        entry = zipStream.nextEntry
                    }
                    zipStream.closeEntry()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    viewModel.addFile(zipFileName, "error", 0.0, null)
                }
            }
        }
    }

    /**
     * Copy a single file URI to a temp file in cache directory.
     */
    private suspend fun copyToTempFile(uri: Uri, fileName: String): File? {
        return withContext(Dispatchers.IO) {
            try {
                val cacheDir = requireContext().cacheDir
                val tempFile = File(cacheDir, "upload_${System.currentTimeMillis()}_$fileName")
                val inputStream = requireContext().contentResolver.openInputStream(uri)
                if (inputStream != null) {
                    inputStream.use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    tempFile
                } else {
                    null
                }
            } catch (e: Exception) {
                null
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
                viewModel.artifacts.collect { arts: List<com.apkbuilder.studio.data.ArtifactInfo> ->
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
