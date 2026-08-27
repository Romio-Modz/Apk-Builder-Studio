package com.apkbuilder.studio.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class BuildRepository {

    private val _jobs = MutableStateFlow<List<BuildJob>>(emptyList())
    val jobs: StateFlow<List<BuildJob>> = _jobs.asStateFlow()

    private val _currentJob = MutableStateFlow<BuildJob?>(null)
    val currentJob: StateFlow<BuildJob?> = _currentJob.asStateFlow()

    fun createJob(config: BuildConfig): BuildJob {
        val job = BuildJob(
            id = UUID.randomUUID().toString().take(8),
            config = config
        )
        _jobs.value = _jobs.value + job
        _currentJob.value = job
        return job
    }

    fun updateJob(job: BuildJob) {
        _jobs.value = _jobs.value.map { if (it.id == job.id) job else it }
        _currentJob.value = job
    }

    fun deleteJob(jobId: String) {
        _jobs.value = _jobs.value.filter { it.id != jobId }
        if (_currentJob.value?.id == jobId) {
            _currentJob.value = null
        }
    }

    fun getJob(jobId: String): BuildJob? = _jobs.value.find { it.id == jobId }

    companion object {
        @Volatile
        private var instance: BuildRepository? = null

        fun getInstance(): BuildRepository =
            instance ?: synchronized(this) {
                instance ?: BuildRepository().also { instance = it }
            }
    }
}
