package com.apkbuilder.studio.data

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.ZipInputStream

data class GitHubRepo(
    val owner: String,
    val name: String,
    val token: String
)

data class ArtifactInfo(
    val name: String,
    val downloadUrl: String,
    val sizeKB: Long
)

data class FileData(
    val path: String,
    val content: ByteArray,
    val isBinary: Boolean = true
)

class GitHubApiService {

    private val TAG = "GitHubApiService"

    // ==================== TOKEN VALIDATION ====================

    /**
     * Validates the GitHub token by calling /user endpoint.
     * Returns true if token is valid, false if expired/invalid.
     */
    suspend fun validateToken(token: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://api.github.com/user")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "token $token")
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.setRequestProperty("User-Agent", "APKBuilderStudio")
            conn.connectTimeout = 15000
            conn.readTimeout = 15000

            val code = conn.responseCode
            conn.disconnect()
            Log.d(TAG, "Token validation response: $code")
            code in 200..299
        } catch (e: Exception) {
            Log.e(TAG, "Token validation failed", e)
            false
        }
    }

    // ==================== REPO LIST ====================

    suspend fun getRepoList(token: String): List<String>? = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://api.github.com/user/repos?per_page=100&sort=updated")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "token $token")
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.setRequestProperty("User-Agent", "APKBuilderStudio")

            val response = readResponse(conn)
            val code = conn.responseCode
            conn.disconnect()

            if (code !in 200..299) {
                Log.e(TAG, "getRepoList failed: $code")
                return@withContext null
            }

            val arr = JSONArray(response)
            val result = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val repo = arr.getJSONObject(i)
                result.add(repo.getString("full_name"))
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "getRepoList error", e)
            null
        }
    }

    // ==================== CREATE REPO ====================

    suspend fun createRepo(repo: GitHubRepo): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://api.github.com/user/repos")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "token ${repo.token}")
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.setRequestProperty("User-Agent", "APKBuilderStudio")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            val body = JSONObject().apply {
                put("name", repo.name)
                put("private", false)
                put("auto_init", true)  // Creates README.md so repo is not empty
            }.toString()

            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            conn.disconnect()

            Log.d(TAG, "createRepo response: $code")
            code in 200..299 || code == 422  // 422 = already exists
        } catch (e: Exception) {
            Log.e(TAG, "createRepo error", e)
            false
        }
    }

    // ==================== EMPTY REPO HANDLING ====================

    /**
     * Pushes a README.md to an empty repo via Contents API.
     * This creates the initial commit so getRefSha can work.
     */
    suspend fun createInitialCommit(repo: GitHubRepo): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://api.github.com/repos/${repo.owner}/${repo.name}/contents/README.md")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "PUT"
            conn.setRequestProperty("Authorization", "token ${repo.token}")
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.setRequestProperty("User-Agent", "APKBuilderStudio")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            val content = Base64.encodeToString(
                "# ${repo.name}\nCreated by APK Builder Studio".toByteArray(),
                Base64.NO_WRAP
            )
            val body = JSONObject().apply {
                put("message", "Initial commit")
                put("content", content)
            }.toString()

            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            conn.disconnect()

            Log.d(TAG, "createInitialCommit response: $code")
            code in 200..299 || code == 422  // 422 = file already exists
        } catch (e: Exception) {
            Log.e(TAG, "createInitialCommit error", e)
            false
        }
    }

    // ==================== PUSH ALL FILES (Git Data API) ====================

    suspend fun pushAllFiles(
        repo: GitHubRepo,
        files: List<FileData>,
        onProgress: (Int, Int, String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val total = files.size
            if (total == 0) return@withContext true

            // Step 1: Get the latest commit SHA on main/master branch
            onProgress(0, total, "Getting repository state...")

            // Try to get ref SHA — if repo is empty, create initial commit first
            var refSha = getRefSha(repo)
            if (refSha == null) {
                Log.w(TAG, "getRefSha failed — trying createInitialCommit")
                onProgress(0, total, "Initializing repository...")
                createInitialCommit(repo)
                Thread.sleep(1000)
                refSha = getRefSha(repo) ?: run {
                    Log.e(TAG, "Could not get ref SHA even after createInitialCommit")
                    onProgress(total, total, "Error: Could not access repository branch")
                    return@withContext false
                }
            }

            val treeSha = getCommitTreeSha(repo, refSha) ?: run {
                Log.e(TAG, "Could not get commit tree SHA")
                onProgress(total, total, "Error: Could not get repository tree")
                return@withContext false
            }

            // Step 2: Create blobs — PARALLEL (5 at a time for speed)
            onProgress(0, total, "Uploading files (parallel)...")
            val treeItems = JSONArray()
            var successCount = 0
            var failCount = 0

            val batchSize = 5
            for (batchStart in 0 until total step batchSize) {
                val batchEnd = minOf(batchStart + batchSize, total)
                val batchFiles = files.subList(batchStart, batchEnd)

                // Launch 5 blob creations in parallel
                val blobResults = batchFiles.map { file ->
                    async {
                        val blobSha = createBlob(repo, file.content)
                        Pair(file, blobSha)
                    }
                }.awaitAll()

                for ((file, blobSha) in blobResults) {
                    if (blobSha != null) {
                        val item = JSONObject()
                        item.put("path", file.path)
                        item.put("mode", "100644")
                        item.put("type", "blob")
                        item.put("sha", blobSha)
                        treeItems.put(item)
                        successCount++
                    } else {
                        failCount++
                        Log.e(TAG, "Blob creation FAILED for: ${file.path}")
                    }
                }

                onProgress(batchEnd, total, "Uploaded $successCount/$total files...")
                // Small delay between batches to avoid secondary rate limit
                if (batchEnd < total) {
                    Thread.sleep(300)
                }
            }

            Log.d(TAG, "Blob creation done: $successCount success, $failCount fail")

            // Step 3: Create tree(s) — GitHub allows max 500 items per tree
            onProgress(total, total, "Creating commit tree...")
            if (treeItems.length() == 0) {
                onProgress(total, total, "No files were uploaded successfully")
                return@withContext false
            }

            val finalTreeSha = if (treeItems.length() <= 500) {
                createTree(repo, treeSha, treeItems) ?: return@withContext false
            } else {
                // Batch: create multiple trees, each with up to 500 items
                Log.d(TAG, "Large tree (${treeItems.length()} items) — batching")
                var currentTreeSha = treeSha
                var idx = 0
                while (idx < treeItems.length()) {
                    val batchTree = JSONArray()
                    val end = minOf(idx + 500, treeItems.length())
                    for (i in idx until end) {
                        batchTree.put(treeItems.get(i))
                    }
                    currentTreeSha = createTree(repo, currentTreeSha, batchTree)
                        ?: return@withContext false
                    idx = end
                }
                currentTreeSha
            }

            // Step 4: Create a commit
            onProgress(total, total, "Creating commit...")
            val newCommitSha = createCommit(
                repo, finalTreeSha, refSha,
                "Upload $successCount files via APK Builder Studio"
            ) ?: return@withContext false

            // Step 5: Update the ref
            onProgress(total, total, "Finalizing push...")
            val updated = updateRef(repo, newCommitSha)

            if (updated) {
                Log.d(TAG, "pushAllFiles SUCCESS: $successCount files pushed")
            } else {
                Log.e(TAG, "updateRef FAILED")
            }
            updated
        } catch (e: Exception) {
            Log.e(TAG, "pushAllFiles error", e)
            false
        }
    }

    // ==================== PUSH SINGLE FILE (Contents API) ====================

    /**
     * Push a single binary file using the Contents API.
     * URL encoding: encode each path segment individually, keep "/" as separator.
     */
    suspend fun pushSingleFileBytes(
        repo: GitHubRepo,
        path: String,
        content: ByteArray
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // FIX: Encode each path segment separately, keep "/" intact
            val encodedPath = path.split("/").joinToString("/") { segment ->
                URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
            }
            val url = URL("https://api.github.com/repos/${repo.owner}/${repo.name}/contents/$encodedPath")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "PUT"
            conn.setRequestProperty("Authorization", "token ${repo.token}")
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.setRequestProperty("User-Agent", "APKBuilderStudio")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 30000
            conn.readTimeout = 60000

            val body = JSONObject().apply {
                put("message", "Add $path")
                put("content", Base64.encodeToString(content, Base64.NO_WRAP))
            }.toString()

            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299 && code != 422) {
                val errorBody = readResponse(conn)
                Log.e(TAG, "pushSingleFileBytes FAIL ($code): $path — $errorBody")
            }
            conn.disconnect()
            code in 200..299 || code == 422
        } catch (e: Exception) {
            Log.e(TAG, "pushSingleFileBytes error: $path", e)
            false
        }
    }

    suspend fun pushSingleFile(
        repo: GitHubRepo,
        path: String,
        content: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // FIX: Encode each path segment separately, keep "/" intact
            val encodedPath = path.split("/").joinToString("/") { segment ->
                URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
            }
            val url = URL("https://api.github.com/repos/${repo.owner}/${repo.name}/contents/$encodedPath")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "PUT"
            conn.setRequestProperty("Authorization", "token ${repo.token}")
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.setRequestProperty("User-Agent", "APKBuilderStudio")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            val body = JSONObject().apply {
                put("message", "Add $path")
                put("content", Base64.encodeToString(content.toByteArray(), Base64.NO_WRAP))
            }.toString()

            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299 && code != 422) {
                val errorBody = readResponse(conn)
                Log.e(TAG, "pushSingleFile FAIL ($code): $path — $errorBody")
            }
            conn.disconnect()
            code in 200..299
        } catch (e: Exception) {
            Log.e(TAG, "pushSingleFile error: $path", e)
            false
        }
    }

    // ==================== DELETE FILE ====================

    suspend fun deleteFile(repo: GitHubRepo, path: String, sha: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val encodedPath = path.split("/").joinToString("/") { segment ->
                URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
            }
            val url = URL("https://api.github.com/repos/${repo.owner}/${repo.name}/contents/$encodedPath")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "DELETE"
            conn.setRequestProperty("Authorization", "token ${repo.token}")
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.setRequestProperty("User-Agent", "APKBuilderStudio")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            val body = JSONObject().apply {
                put("message", "Delete $path")
                put("sha", sha)
            }.toString()

            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (e: Exception) {
            Log.e(TAG, "deleteFile error", e)
            false
        }
    }

    // ==================== GIT DATA API HELPERS ====================

    private fun getRefSha(repo: GitHubRepo): String? {
        for (branch in listOf("main", "master")) {
            try {
                val url = URL("https://api.github.com/repos/${repo.owner}/${repo.name}/git/refs/heads/$branch")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Authorization", "token ${repo.token}")
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                conn.setRequestProperty("User-Agent", "APKBuilderStudio")

                val code = conn.responseCode  // FIX: Read code BEFORE disconnect
                val response = readResponse(conn)
                conn.disconnect()

                if (code in 200..299) {
                    val json = JSONObject(response)
                    return json.getJSONObject("object").getString("sha")
                }
            } catch (e: Exception) {
                Log.w(TAG, "getRefSha branch '$branch' failed", e)
            }
        }
        return null
    }

    private fun getCommitTreeSha(repo: GitHubRepo, commitSha: String): String? {
        try {
            val url = URL("https://api.github.com/repos/${repo.owner}/${repo.name}/git/commits/$commitSha")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "token ${repo.token}")
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.setRequestProperty("User-Agent", "APKBuilderStudio")

            val code = conn.responseCode
            val response = readResponse(conn)
            conn.disconnect()

            if (code !in 200..299) {
                Log.e(TAG, "getCommitTreeSha failed: $code")
                return null
            }
            val json = JSONObject(response)
            return json.getJSONObject("tree").getString("sha")
        } catch (e: Exception) {
            Log.e(TAG, "getCommitTreeSha error", e)
            return null
        }
    }

    private suspend fun createBlob(repo: GitHubRepo, content: ByteArray): String? = withContext(Dispatchers.IO) {
        for (attempt in 0 until 3) {
            try {
                val url = URL("https://api.github.com/repos/${repo.owner}/${repo.name}/git/blobs")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Authorization", "token ${repo.token}")
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                conn.setRequestProperty("User-Agent", "APKBuilderStudio")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 30000
                conn.readTimeout = 60000

                val body = JSONObject().apply {
                    put("content", Base64.encodeToString(content, Base64.NO_WRAP))
                    put("encoding", "base64")
                }.toString()

                conn.outputStream.use { it.write(body.toByteArray()) }

                val code = conn.responseCode  // FIX: Read BEFORE disconnect

                // FIX: Check Retry-After header for secondary rate limit
                if (code == 403) {
                    val retryAfter = conn.getHeaderField("Retry-After")
                    val errorBody = readResponse(conn)
                    conn.disconnect()

                    val waitTime = if (retryAfter != null) {
                        (retryAfter.toLong() * 1000)
                    } else {
                        2000L * (attempt + 1)
                    }
                    Log.w(TAG, "Rate limited (403). Waiting ${waitTime}ms. Body: $errorBody")
                    Thread.sleep(waitTime)
                    continue  // retry
                }

                val response = readResponse(conn)
                conn.disconnect()

                if (code in 200..299) {
                    val json = JSONObject(response)
                    return@withContext json.getString("sha")
                } else {
                    Log.e(TAG, "createBlob failed: $code — $response")
                    return@withContext null
                }
            } catch (e: Exception) {
                Log.e(TAG, "createBlob attempt ${attempt + 1} error", e)
                if (attempt == 2) return@withContext null
                Thread.sleep(1000L * (attempt + 1))
            }
        }
        null
    }

    private fun createTree(repo: GitHubRepo, baseTreeSha: String, treeItems: JSONArray): String? {
        try {
            val url = URL("https://api.github.com/repos/${repo.owner}/${repo.name}/git/trees")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "token ${repo.token}")
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.setRequestProperty("User-Agent", "APKBuilderStudio")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            val body = JSONObject().apply {
                put("base_tree", baseTreeSha)
                put("tree", treeItems)
            }.toString()

            conn.outputStream.use { it.write(body.toByteArray()) }

            val code = conn.responseCode
            val response = readResponse(conn)
            conn.disconnect()

            if (code !in 200..299) {
                Log.e(TAG, "createTree failed: $code — $response")
                return null
            }
            val json = JSONObject(response)
            return json.getString("sha")
        } catch (e: Exception) {
            Log.e(TAG, "createTree error", e)
            return null
        }
    }

    private fun createCommit(repo: GitHubRepo, treeSha: String, parentSha: String, message: String): String? {
        try {
            val url = URL("https://api.github.com/repos/${repo.owner}/${repo.name}/git/commits")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "token ${repo.token}")
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.setRequestProperty("User-Agent", "APKBuilderStudio")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            val parents = JSONArray()
            parents.put(parentSha)

            val body = JSONObject().apply {
                put("message", message)
                put("tree", treeSha)
                put("parents", parents)
            }.toString()

            conn.outputStream.use { it.write(body.toByteArray()) }

            val code = conn.responseCode
            val response = readResponse(conn)
            conn.disconnect()

            if (code !in 200..299) {
                Log.e(TAG, "createCommit failed: $code — $response")
                return null
            }
            val json = JSONObject(response)
            return json.getString("sha")
        } catch (e: Exception) {
            Log.e(TAG, "createCommit error", e)
            return null
        }
    }

    private fun updateRef(repo: GitHubRepo, commitSha: String): Boolean {
        for (branch in listOf("main", "master")) {
            try {
                val url = URL("https://api.github.com/repos/${repo.owner}/${repo.name}/git/refs/heads/$branch")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "PATCH"
                conn.setRequestProperty("Authorization", "token ${repo.token}")
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                conn.setRequestProperty("User-Agent", "APKBuilderStudio")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true

                val body = JSONObject().apply {
                    put("sha", commitSha)
                    put("force", true)
                }.toString()

                conn.outputStream.use { it.write(body.toByteArray()) }

                val code = conn.responseCode  // FIX: Read BEFORE disconnect
                conn.disconnect()
                if (code in 200..299) {
                    Log.d(TAG, "updateRef success on branch: $branch")
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "updateRef branch '$branch' failed", e)
            }
        }
        return false
    }

    // ==================== TRIGGER WORKFLOW ====================

    suspend fun triggerWorkflow(repo: GitHubRepo, isRelease: Boolean = false): String? = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://api.github.com/repos/${repo.owner}/${repo.name}/actions/workflows")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "token ${repo.token}")
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.setRequestProperty("User-Agent", "APKBuilderStudio")

            val code = conn.responseCode
            val response = readResponse(conn)
            conn.disconnect()

            if (code !in 200..299) {
                Log.e(TAG, "triggerWorkflow: list workflows failed: $code")
                return@withContext null
            }

            val json = JSONObject(response)
            val workflows = json.getJSONArray("workflows")
            if (workflows.length() == 0) {
                Log.e(TAG, "No workflows found in repo")
                return@withContext null
            }

            // Find the right workflow — prefer release workflow if release build
            var workflowId: Long? = null
            for (i in 0 until workflows.length()) {
                val wf = workflows.getJSONObject(i)
                val wfName = wf.getString("name").lowercase()
                val wfPath = wf.getString("path").lowercase()
                if (isRelease && (wfName.contains("release") || wfPath.contains("release"))) {
                    workflowId = wf.getLong("id")
                    break
                }
                if (!isRelease && !wfName.contains("release") && !wfPath.contains("release")) {
                    workflowId = wf.getLong("id")
                    break
                }
            }
            // Fallback to first workflow
            if (workflowId == null) {
                workflowId = workflows.getJSONObject(0).getLong("id")
            }

            // Determine branch name
            val branch = getDefaultBranch(repo)
            Log.d(TAG, "Triggering workflow $workflowId on branch $branch")

            val dispatchUrl = URL("https://api.github.com/repos/${repo.owner}/${repo.name}/actions/workflows/$workflowId/dispatches")
            val dispatchConn = dispatchUrl.openConnection() as HttpURLConnection
            dispatchConn.requestMethod = "POST"
            dispatchConn.setRequestProperty("Authorization", "token ${repo.token}")
            dispatchConn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            dispatchConn.setRequestProperty("User-Agent", "APKBuilderStudio")
            dispatchConn.setRequestProperty("Content-Type", "application/json")
            dispatchConn.doOutput = true

            val body = JSONObject().apply { put("ref", branch) }.toString()
            dispatchConn.outputStream.use { it.write(body.toByteArray()) }

            val dispatchCode = dispatchConn.responseCode
            dispatchConn.disconnect()

            if (dispatchCode in 200..299) {
                Thread.sleep(5000)  // Wait for run to appear
                getLatestDispatchRunId(repo)
            } else {
                Log.e(TAG, "Workflow dispatch failed: $dispatchCode")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "triggerWorkflow error", e)
            null
        }
    }

    private fun getDefaultBranch(repo: GitHubRepo): String {
        for (branch in listOf("main", "master")) {
            try {
                val url = URL("https://api.github.com/repos/${repo.owner}/${repo.name}/git/refs/heads/$branch")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Authorization", "token ${repo.token}")
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                conn.setRequestProperty("User-Agent", "APKBuilderStudio")
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..299) return branch
            } catch (e: Exception) {
                Log.w(TAG, "getDefaultBranch: '$branch' check failed", e)
            }
        }
        return "main"
    }

    // ==================== RUN STATUS TRACKING ====================

    private fun getLatestDispatchRunId(repo: GitHubRepo): String? {
        try {
            val url = URL("https://api.github.com/repos/${repo.owner}/${repo.name}/actions/runs?per_page=5&event=workflow_dispatch")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "token ${repo.token}")
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.setRequestProperty("User-Agent", "APKBuilderStudio")

            val response = readResponse(conn)
            conn.disconnect()

            val json = JSONObject(response)
            val runs = json.getJSONArray("workflow_runs")
            if (runs.length() == 0) {
                // Fallback: get latest run of any type
                Log.w(TAG, "No dispatch runs found — falling back to latest run")
                return getLatestRunId(repo)
            }
            return runs.getJSONObject(0).getLong("id").toString()
        } catch (e: Exception) {
            Log.e(TAG, "getLatestDispatchRunId error", e)
            return getLatestRunId(repo)
        }
    }

    fun getLatestRunId(repo: GitHubRepo): String? {
        try {
            val url = URL("https://api.github.com/repos/${repo.owner}/${repo.name}/actions/runs?per_page=1")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "token ${repo.token}")
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.setRequestProperty("User-Agent", "APKBuilderStudio")

            val response = readResponse(conn)
            conn.disconnect()

            val json = JSONObject(response)
            val runs = json.getJSONArray("workflow_runs")
            if (runs.length() == 0) return null
            return runs.getJSONObject(0).getLong("id").toString()
        } catch (e: Exception) {
            Log.e(TAG, "getLatestRunId error", e)
            return null
        }
    }

    // ==================== BUILD STATUS & ARTIFACTS ====================

    suspend fun getRunStatus(repo: GitHubRepo, runId: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://api.github.com/repos/${repo.owner}/${repo.name}/actions/runs/$runId")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "token ${repo.token}")
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.setRequestProperty("User-Agent", "APKBuilderStudio")

            val response = readResponse(conn)
            conn.disconnect()

            val json = JSONObject(response)
            val status = json.getString("status")
            val conclusion = if (json.isNull("conclusion")) "null" else json.getString("conclusion")
            Pair(status, conclusion)
        } catch (e: Exception) {
            Log.e(TAG, "getRunStatus error", e)
            null
        }
    }

    suspend fun getArtifacts(repo: GitHubRepo, runId: String): List<ArtifactInfo>? = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://api.github.com/repos/${repo.owner}/${repo.name}/actions/runs/$runId/artifacts")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "token ${repo.token}")
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.setRequestProperty("User-Agent", "APKBuilderStudio")

            val response = readResponse(conn)
            conn.disconnect()

            val json = JSONObject(response)
            val artifacts = json.getJSONArray("artifacts")
            val result = mutableListOf<ArtifactInfo>()
            for (i in 0 until artifacts.length()) {
                val art = artifacts.getJSONObject(i)
                result.add(ArtifactInfo(
                    name = art.getString("name"),
                    downloadUrl = art.getString("archive_download_url"),
                    sizeKB = art.getLong("size_in_bytes") / 1024
                ))
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "getArtifacts error", e)
            null
        }
    }

    // ==================== DOWNLOAD ARTIFACT ZIP ====================

    suspend fun downloadArtifactZip(repo: GitHubRepo, artifact: ArtifactInfo, outputDir: File): File? = withContext(Dispatchers.IO) {
        try {
            outputDir.mkdirs()

            // Step 1: Get redirect URL from GitHub API (302 → Azure blob)
            val apiUrl = URL(artifact.downloadUrl)
            val apiConn = apiUrl.openConnection() as HttpURLConnection
            apiConn.requestMethod = "GET"
            apiConn.setRequestProperty("Authorization", "token ${repo.token}")
            apiConn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            apiConn.setRequestProperty("User-Agent", "APKBuilderStudio")
            apiConn.instanceFollowRedirects = false
            apiConn.connectTimeout = 30000
            apiConn.readTimeout = 30000

            val redirectUrl = apiConn.getHeaderField("Location")
            apiConn.disconnect()

            if (redirectUrl == null) {
                Log.e(TAG, "downloadArtifactZip: no redirect URL")
                return@withContext null
            }

            // Step 2: Download from Azure blob (NO Authorization header)
            val blobUrl = URL(redirectUrl)
            val blobConn = blobUrl.openConnection() as HttpURLConnection
            blobConn.requestMethod = "GET"
            blobConn.setRequestProperty("User-Agent", "APKBuilderStudio")
            blobConn.instanceFollowRedirects = true
            blobConn.connectTimeout = 30000
            blobConn.readTimeout = 300000  // 5 min for large files

            if (blobConn.responseCode !in 200..299) {
                Log.e(TAG, "downloadArtifactZip: blob download failed: ${blobConn.responseCode}")
                blobConn.disconnect()
                return@withContext null
            }

            // Save ZIP directly
            val zipFileName = "${artifact.name}.zip"
            val zipFile = File(outputDir, zipFileName)
            FileOutputStream(zipFile).use { fos ->
                val buffer = ByteArray(8192)
                var len: Int
                val input = blobConn.inputStream
                while (input.read(buffer).also { len = it } > 0) {
                    fos.write(buffer, 0, len)
                }
            }
            blobConn.disconnect()
            Log.d(TAG, "Downloaded: ${zipFile.absolutePath} (${zipFile.length()} bytes)")
            zipFile
        } catch (e: Exception) {
            Log.e(TAG, "downloadArtifactZip error", e)
            null
        }
    }

    // ==================== DOWNLOAD BUILD LOG ====================

    suspend fun downloadLogArtifact(repo: GitHubRepo, artifact: ArtifactInfo, outputDir: File): File? = withContext(Dispatchers.IO) {
        try {
            outputDir.mkdirs()

            // Step 1: Get redirect URL
            val apiUrl = URL(artifact.downloadUrl)
            val apiConn = apiUrl.openConnection() as HttpURLConnection
            apiConn.requestMethod = "GET"
            apiConn.setRequestProperty("Authorization", "token ${repo.token}")
            apiConn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            apiConn.setRequestProperty("User-Agent", "APKBuilderStudio")
            apiConn.instanceFollowRedirects = false
            apiConn.connectTimeout = 30000
            apiConn.readTimeout = 30000

            val redirectUrl = apiConn.getHeaderField("Location")
            apiConn.disconnect()

            if (redirectUrl == null) return@withContext null

            // Step 2: Download from Azure blob (NO auth)
            val blobUrl = URL(redirectUrl)
            val blobConn = blobUrl.openConnection() as HttpURLConnection
            blobConn.requestMethod = "GET"
            blobConn.setRequestProperty("User-Agent", "APKBuilderStudio")
            blobConn.instanceFollowRedirects = true
            blobConn.connectTimeout = 30000
            blobConn.readTimeout = 120000

            if (blobConn.responseCode !in 200..299) {
                blobConn.disconnect()
                return@withContext null
            }

            // Save ZIP to temp file
            val tempZip = File(outputDir, "log_download.zip")
            FileOutputStream(tempZip).use { fos ->
                val buffer = ByteArray(8192)
                var len: Int
                val input = blobConn.inputStream
                while (input.read(buffer).also { len = it } > 0) {
                    fos.write(buffer, 0, len)
                }
            }
            blobConn.disconnect()

            // Extract .txt from ZIP
            val zipStream = ZipInputStream(java.io.FileInputStream(tempZip))
            var logFile: File? = null
            var entry = zipStream.nextEntry
            while (entry != null) {
                if (entry.name.endsWith(".txt")) {
                    val fileName = File(entry.name).name
                    val outFile = File(outputDir, fileName)
                    FileOutputStream(outFile).use { fos ->
                        val buffer = ByteArray(8192)
                        var len: Int
                        while (zipStream.read(buffer).also { len = it } > 0) {
                            fos.write(buffer, 0, len)
                        }
                    }
                    logFile = outFile
                    break
                }
                zipStream.closeEntry()
                entry = zipStream.nextEntry
            }
            zipStream.close()
            tempZip.delete()
            logFile
        } catch (e: Exception) {
            Log.e(TAG, "downloadLogArtifact error", e)
            null
        }
    }

    // ==================== UTIL ====================

    private fun readResponse(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        val reader = BufferedReader(InputStreamReader(stream))
        val sb = StringBuilder()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            sb.append(line).append("\n")
        }
        reader.close()
        return sb.toString()
    }
}
