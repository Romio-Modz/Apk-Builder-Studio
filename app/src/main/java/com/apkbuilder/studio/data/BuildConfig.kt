package com.apkbuilder.studio.data

data class BuildConfig(
    val repoUrl: String,
    val branch: String = "main",
    val buildType: BuildType = BuildType.DEBUG,
    val appName: String = "MyApp",
    val packageName: String = "com.example.myapp",
    val versionName: String = "1.0.0",
    val versionCode: Int = 1,
    val minSdk: Int = 24,
    val targetSdk: Int = 34,
    val uploadedFiles: List<String> = emptyList()
)

enum class BuildType(val displayName: String) {
    DEBUG("Debug"),
    RELEASE("Release")
}

enum class BuildStatus(val displayName: String) {
    IDLE("Idle"),
    QUEUED("Queued"),
    BUILDING("Building"),
    SUCCESS("Success"),
    FAILED("Failed")
}

data class BuildJob(
    val id: String,
    val config: BuildConfig,
    val status: BuildStatus = BuildStatus.IDLE,
    val progress: Int = 0,
    val log: String = "",
    val apkUrl: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
