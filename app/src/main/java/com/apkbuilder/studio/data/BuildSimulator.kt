package com.apkbuilder.studio.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BuildSimulator(private val repository: BuildRepository) {

    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _logLines.asStateFlow()

    private val _isBuilding = MutableStateFlow(false)
    val isBuilding: StateFlow<Boolean> = _isBuilding.asStateFlow()

    fun startBuild(job: BuildJob, onProgress: (BuildJob) -> Unit = {}) {
        if (_isBuilding.value) return
        _isBuilding.value = true
        _logLines.value = emptyList()

        CoroutineScope(Dispatchers.Main).launch {
            val steps = listOf(
                "Initializing build environment..." to 5,
                "Cloning repository ${job.config.repoUrl}..." to 15,
                "Checking out branch ${job.config.branch}..." to 20,
                "Validating project structure..." to 25,
                "Resolving Gradle dependencies..." to 35,
                "Compiling Kotlin sources..." to 50,
                "Compiling Java sources..." to 60,
                "Processing resources..." to 70,
                "Generating R.java..." to 75,
                "Packaging APK..." to 85,
                "Signing APK (${job.config.buildType.displayName.lowercase()})..." to 92,
                "Aligning APK..." to 96,
                "Build completed successfully!" to 100
            )

            var current = job.copy(status = BuildStatus.BUILDING, progress = 0)
            repository.updateJob(current)
            onProgress(current)

            for ((message, progress) in steps) {
                delay(400)
                _logLines.value = _logLines.value + message
                current = current.copy(
                    progress = progress,
                    log = _logLines.value.joinToString("\n")
                )
                repository.updateJob(current)
                onProgress(current)
            }

            current = current.copy(
                status = BuildStatus.SUCCESS,
                apkUrl = "https://github.com/artifacts/${current.id}/app-${current.config.buildType.displayName.lowercase()}.apk"
            )
            repository.updateJob(current)
            onProgress(current)
            _isBuilding.value = false
        }
    }

    fun cancelBuild() {
        _isBuilding.value = false
        _logLines.value = _logLines.value + "Build cancelled by user."
    }
}
