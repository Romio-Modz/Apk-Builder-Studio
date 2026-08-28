package com.apkbuilder.studio.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apkbuilder.studio.data.ArtifactInfo
import com.apkbuilder.studio.data.BuildConfig
import com.apkbuilder.studio.data.BuildJob
import com.apkbuilder.studio.data.BuildRepository
import com.apkbuilder.studio.data.BuildStatus
import com.apkbuilder.studio.data.FileData
import com.apkbuilder.studio.data.GitHubApiService
import com.apkbuilder.studio.data.GitHubRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FileEntry(
    val name: String,
    val path: String,
    val sizeKB: Double,
    val uri: Uri? = null
)

class MainViewModel : ViewModel() {

    private val repository = BuildRepository.getInstance()
    val githubApi = GitHubApiService()

    val jobs: StateFlow<List<BuildJob>> = repository.jobs
    val currentJob: StateFlow<BuildJob?> = repository.currentJob

    private val _uploadedFiles = MutableStateFlow<List<FileEntry>>(emptyList())
    val uploadedFiles: StateFlow<List<FileEntry>> = _uploadedFiles.asStateFlow()

    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _logLines.asStateFlow()

    private val _isBuilding = MutableStateFlow(false)
    val isBuilding: StateFlow<Boolean> = _isBuilding.asStateFlow()

    private val _buildProgress = MutableStateFlow(0)
    val buildProgress: StateFlow<Int> = _buildProgress.asStateFlow()

    private val _buildStatus = MutableStateFlow("Idle")
    val buildStatus: StateFlow<String> = _buildStatus.asStateFlow()

    private val _artifacts = MutableStateFlow<List<ArtifactInfo>>(emptyList())
    val artifacts: StateFlow<List<ArtifactInfo>> = _artifacts.asStateFlow()

    private val _githubToken = MutableStateFlow("")
    val githubToken: StateFlow<String> = _githubToken.asStateFlow()

    private val _githubUser = MutableStateFlow("")
    val githubUser: StateFlow<String> = _githubUser.asStateFlow()

    private val _repoName = MutableStateFlow("")
    val repoName: StateFlow<String> = _repoName.asStateFlow()

    private val _branch = MutableStateFlow("main")
    val branch: StateFlow<String> = _branch.asStateFlow()

    private val _appName = MutableStateFlow("MyApp")
    val appName: StateFlow<String> = _appName.asStateFlow()

    private val _packageName = MutableStateFlow("com.example.myapp")
    val packageName: StateFlow<String> = _packageName.asStateFlow()

    private val _versionName = MutableStateFlow("1.0.0")
    val versionName: StateFlow<String> = _versionName.asStateFlow()

    private val _versionCode = MutableStateFlow(1)
    val versionCode: StateFlow<Int> = _versionCode.asStateFlow()

    private val _minSdk = MutableStateFlow(24)
    val minSdk: StateFlow<Int> = _minSdk.asStateFlow()

    private val _targetSdk = MutableStateFlow(34)
    val targetSdk: StateFlow<Int> = _targetSdk.asStateFlow()

    fun updateGithubToken(v: String) { _githubToken.value = v }
    fun updateGithubUser(v: String) { _githubUser.value = v }
    fun updateRepoName(v: String) { _repoName.value = v }
    fun updateBranch(v: String) { _branch.value = v }
    fun updateAppName(v: String) { _appName.value = v }
    fun updatePackageName(v: String) { _packageName.value = v }
    fun updateVersionName(v: String) { _versionName.value = v }
    fun updateVersionCode(v: Int) { _versionCode.value = v }
    fun updateMinSdk(v: Int) { _minSdk.value = v }
    fun updateTargetSdk(v: Int) { _targetSdk.value = v }

    fun addFile(name: String, path: String, sizeKB: Double, uri: Uri? = null) {
        _uploadedFiles.value = _uploadedFiles.value + FileEntry(name, path, sizeKB, uri)
    }

    fun removeFile(file: FileEntry) {
        _uploadedFiles.value = _uploadedFiles.value.filter { it != file }
    }

    fun clearFiles() {
        _uploadedFiles.value = emptyList()
    }

    private fun addLog(line: String) {
        _logLines.value = _logLines.value + line
    }

    private fun stripRootFolder(path: String): String {
        // If path has a top-level folder like "ROMITUBE/app/build.gradle.kts",
        // strip the first folder to get "app/build.gradle.kts"
        val parts = path.split("/")
        if (parts.size > 1) {
            return parts.drop(1).joinToString("/")
        }
        return path
    }

    fun startRealBuild(context: Context, isRelease: Boolean) {
        if (_isBuilding.value) return

        viewModelScope.launch {
            _isBuilding.value = true
            _buildProgress.value = 0
            _buildStatus.value = "Building"
            _logLines.value = emptyList()
            _artifacts.value = emptyList()

            val token = _githubToken.value.trim()
            val user = _githubUser.value.trim()
            val repo = _repoName.value.trim()

            if (token.isEmpty() || user.isEmpty() || repo.isEmpty()) {
                addLog("Error: GitHub token, username, and repo name are required!")
                _buildStatus.value = "Failed"
                _isBuilding.value = false
                return@launch
            }

            val githubRepo = GitHubRepo(owner = user, name = repo, token = token)

            // Step 1: Create repo
            addLog("Creating repository $repo...")
            _buildProgress.value = 5
            val repoCreated = githubApi.createRepo(githubRepo)
            if (repoCreated) {
                addLog("Repository ready: $repo")
            } else {
                addLog("Warning: Could not create repo (may already exist)")
            }
            _buildProgress.value = 10

            // Step 2: Push uploaded files using Git Data API (handles large files up to 100MB)
            val files = _uploadedFiles.value
            if (files.isNotEmpty()) {
                addLog("Preparing ${files.size} files for upload...")
                
                // Read all files - each file's path now points to an extracted temp file
                val fileDataList = mutableListOf<FileData>()
                for (file in files) {
                    val cleanPath = stripRootFolder(file.name)
                    val bytes = withContext(Dispatchers.IO) {
                        try {
                            // Read from temp file path (extracted ZIP entry or copied file)
                            val tempFile = java.io.File(file.path)
                            if (tempFile.exists() && tempFile.length() > 0) {
                                tempFile.readBytes()
                            } else if (file.uri != null) {
                                val inputStream = context.contentResolver.openInputStream(file.uri)
                                if (inputStream != null) {
                                    val b = inputStream.readBytes()
                                    inputStream.close()
                                    b
                                } else null
                            } else null
                        } catch (e: Exception) { null }
                    }
                    if (bytes != null) {
                        fileDataList.add(FileData(cleanPath, bytes, true))
                    } else {
                        addLog("  Skipped (could not read): ${file.name}")
                    }
                    _buildProgress.value = 10 + (fileDataList.size * 20 / files.size)
                }

                if (fileDataList.isNotEmpty()) {
                    addLog("Uploading ${fileDataList.size} files to GitHub...")
                    val pushSuccess = githubApi.pushAllFiles(githubRepo, fileDataList) { current, total, msg ->
                        addLog("  [$current/$total] $msg")
                        _buildProgress.value = 30 + (current * 25 / total)
                    }
                    if (pushSuccess) {
                        addLog("All ${fileDataList.size} files uploaded successfully!")
                    } else {
                        addLog("Warning: Some files may not have uploaded correctly")
                        addLog("Trying alternative upload method...")
                        // Fallback: try pushing files one by one using Contents API
                        var successCount = 0
                        for ((idx, fileData) in fileDataList.withIndex()) {
                            val pushed = githubApi.pushSingleFileBytes(githubRepo, fileData.path, fileData.content)
                            if (pushed) successCount++
                            addLog("  [${idx + 1}/${fileDataList.size}] ${if (pushed) "OK" else "SKIP"} ${fileData.path}")
                            _buildProgress.value = 30 + (idx * 25 / fileDataList.size)
                        }
                        addLog("Uploaded $successCount/${fileDataList.size} files via Contents API")
                    }
                }
            }
            _buildProgress.value = 55

            // Step 3: Push workflow file (only if not already in uploaded files)
            val hasWorkflow = files.any { 
                stripRootFolder(it.name).startsWith(".github/workflows/") && it.name.endsWith(".yml") 
            }
            if (hasWorkflow) {
                addLog("Workflow already exists in uploaded files - skipping")
            } else {
                addLog("Adding GitHub Actions workflow...")
                val workflowContent = generateWorkflow(isRelease)
                val workflowPushed = githubApi.pushSingleFile(
                    githubRepo,
                    ".github/workflows/build-apk.yml",
                    workflowContent
                )
                if (workflowPushed) {
                    addLog("Workflow added successfully")
                } else {
                    addLog("Warning: Could not push workflow (token may lack 'workflow' scope)")
                    addLog("Please add the workflow file manually on GitHub")
                }
            }
            _buildProgress.value = 65

            // Step 4: Trigger workflow
            addLog("Triggering build on GitHub Actions...")
            val runId = githubApi.triggerWorkflow(githubRepo, isRelease)
            if (runId != null) {
                addLog("Build triggered! Run ID: $runId")
            } else {
                addLog("Warning: Could not trigger workflow automatically")
                addLog("Please go to Actions tab and run manually")
                _buildProgress.value = 100
                _buildStatus.value = "Action Required"
                _isBuilding.value = false
                return@launch
            }
            _buildProgress.value = 70

            // Step 5: Poll for build status
            addLog("Waiting for build to complete...")
            var pollCount = 0
            while (pollCount < 40) {
                kotlinx.coroutines.delay(15000)
                pollCount++
                val statusResult = githubApi.getRunStatus(githubRepo, runId)
                if (statusResult != null) {
                    val (status, conclusion) = statusResult
                    val progress = 70 + (pollCount * 25 / 40)
                    _buildProgress.value = minOf(progress, 95)
                    addLog("  Build status: $status" + if (conclusion != "null") " ($conclusion)" else "")

                    if (status == "completed") {
                        if (conclusion == "success") {
                            _buildProgress.value = 100
                            _buildStatus.value = "Success"
                            addLog("Build completed successfully!")

                            // Get artifacts
                            val arts = githubApi.getArtifacts(githubRepo, runId)
                            if (arts != null && arts.isNotEmpty()) {
                                _artifacts.value = arts
                                addLog("APK artifacts ready for download!")
                                for (art in arts) {
                                    addLog("  - ${art.name} (${art.sizeKB} KB)")
                                }
                            }
                        } else {
                            _buildStatus.value = "Failed"
                            addLog("Build failed on GitHub. Check Actions tab for details.")
                        }
                        break
                    }
                }
            }

            if (pollCount >= 40) {
                _buildStatus.value = "Timeout"
                addLog("Build is taking too long. Check Actions tab on GitHub.")
            }

            _isBuilding.value = false
        }
    }

    private fun generateWorkflow(isRelease: Boolean): String {
        return """name: Build APK

on:
  push:
    branches: [ main, master ]
  workflow_dispatch:

jobs:
  build:
    name: Build APK
    runs-on: ubuntu-latest

    steps:
      - name: Checkout code
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v3
        with:
          gradle-version: '8.5'

      - name: Build Debug APK
        run: gradle assembleDebug --no-daemon --stacktrace

      - name: Upload Debug APK
        uses: actions/upload-artifact@v4
        with:
          name: apk-debug
          path: app/build/outputs/apk/debug/*.apk
          retention-days: 30
"""
    }

    fun cancelBuild() {
        _isBuilding.value = false
        addLog("Build cancelled by user.")
    }

    fun deleteJob(jobId: String) {
        repository.deleteJob(jobId)
    }
}
