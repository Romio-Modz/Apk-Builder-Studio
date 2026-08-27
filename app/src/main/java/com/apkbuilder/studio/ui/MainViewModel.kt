package com.apkbuilder.studio.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apkbuilder.studio.data.BuildConfig
import com.apkbuilder.studio.data.BuildJob
import com.apkbuilder.studio.data.BuildRepository
import com.apkbuilder.studio.data.BuildSimulator
import com.apkbuilder.studio.data.BuildStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class FileEntry(
    val name: String,
    val path: String,
    val sizeKB: Double
)

class MainViewModel : ViewModel() {

    private val repository = BuildRepository.getInstance()
    private val simulator = BuildSimulator(repository)

    val jobs: StateFlow<List<BuildJob>> = repository.jobs
    val currentJob: StateFlow<BuildJob?> = repository.currentJob
    val logLines: StateFlow<List<String>> = simulator.logLines
    val isBuilding: StateFlow<Boolean> = simulator.isBuilding

    private val _uploadedFiles = MutableStateFlow<List<FileEntry>>(emptyList())
    val uploadedFiles: StateFlow<List<FileEntry>> = _uploadedFiles.asStateFlow()

    private val _repoUrl = MutableStateFlow("")
    val repoUrl: StateFlow<String> = _repoUrl.asStateFlow()

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

    fun updateRepoUrl(v: String) { _repoUrl.value = v }
    fun updateBranch(v: String) { _branch.value = v }
    fun updateAppName(v: String) { _appName.value = v }
    fun updatePackageName(v: String) { _packageName.value = v }
    fun updateVersionName(v: String) { _versionName.value = v }
    fun updateVersionCode(v: Int) { _versionCode.value = v }
    fun updateMinSdk(v: Int) { _minSdk.value = v }
    fun updateTargetSdk(v: Int) { _targetSdk.value = v }

    fun addFile(name: String, path: String, sizeKB: Double) {
        _uploadedFiles.value = _uploadedFiles.value + FileEntry(name, path, sizeKB)
    }

    fun removeFile(file: FileEntry) {
        _uploadedFiles.value = _uploadedFiles.value.filter { it != file }
    }

    fun clearFiles() {
        _uploadedFiles.value = emptyList()
    }

    fun startBuild(isRelease: Boolean) {
        val config = BuildConfig(
            repoUrl = _repoUrl.value.ifBlank { "https://github.com/user/repo" },
            branch = _branch.value,
            buildType = if (isRelease) com.apkbuilder.studio.data.BuildType.RELEASE else com.apkbuilder.studio.data.BuildType.DEBUG,
            appName = _appName.value,
            packageName = _packageName.value,
            versionName = _versionName.value,
            versionCode = _versionCode.value,
            minSdk = _minSdk.value,
            targetSdk = _targetSdk.value,
            uploadedFiles = _uploadedFiles.value.map { it.name }
        )
        val job = repository.createJob(config)
        simulator.startBuild(job)
    }

    fun cancelBuild() {
        simulator.cancelBuild()
    }

    fun deleteJob(jobId: String) {
        repository.deleteJob(jobId)
    }
}
