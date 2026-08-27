package com.apkbuilder.studio.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

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
    val isBinary: Boolean
)

class GitHubApiService {

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
                put("description", "Built with APK Builder Studio")
                put("private", false)
                put("auto_init", true)
            }.toString()

            conn.outputStream.use { it.write(body.toByteArray()) }

            val code = conn.responseCode
            conn.disconnect()
            code in 200..299 || code == 422
        } catch (e: Exception) {
            false
        }
    }

    suspend fun pushAllFiles(
        repo: GitHubRepo,
        files: List<FileData>,
        onProgress: (Int, Int, String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val total = files.size
            if (total == 0) return@withContext true

            // Step 1: Get the latest commit SHA on main branch
            onProgress(0, total, "Getting repository state...")
            val refSha = getRefSha(repo) ?: return@withContext false
            val treeSha = getCommitTreeSha(repo, refSha) ?: return@withContext false

            // Step 2: Create blobs for each file (handles large files up to 100MB)
            val treeItems = JSONArray()
            for ((index, file) in files.withIndex()) {
                onProgress(index, total, "Uploading: ${file.path}")

                val blobSha = createBlob(repo, file.content)
                if (blobSha != null) {
                    val item = JSONObject()
                    item.put("path", file.path)
                    item.put("mode", "100644")
                    item.put("type", "blob")
                    item.put("sha", blobSha)
                    treeItems.put(item)
                }
            }

            // Step 3: Create a tree with all blobs
            onProgress(total, total, "Creating commit tree...")
            val newTreeSha = createTree(repo, treeSha, treeItems) ?: return@withContext false

            // Step 4: Create a commit
            onProgress(total, total, "Creating commit...")
            val newCommitSha = createCommit(repo, newTreeSha, refSha, "Upload ${total} files via APK Builder Studio")
                ?: return@withContext false

            // Step 5: Update the ref
            onProgress(total, total, "Finalizing push...")
            val updated = updateRef(repo, newCommitSha)

            updated
        } catch (e: Exception) {
            false
        }
    }

    suspend fun pushSingleFile(
        repo: GitHubRepo,
        path: String,
        content: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val encodedPath = java.net.URLEncoder.encode(path, "UTF-8").replace("+", "%20")
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
            conn.disconnect()
            code in 200..299
        } catch (e: Exception) {
            false
        }
    }

    private fun getRefSha(repo: GitHubRepo): String? {
        try {
            val url = URL("https://api.github.com/repos/${repo.owner}/${repo.name}/git/refs/heads/main")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "token ${repo.token}")
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.setRequestProperty("User-Agent", "APKBuilderStudio")

            val response = readResponse(conn)
            conn.disconnect()

            val json = JSONObject(response)
            return json.getJSONObject("object").getString("sha")
        } catch (e: Exception) {
            return null
        }
    }

    private fun getCommitTreeSha(repo: GitHubRepo, commitSha: String): String? {
        try {
            val url = URL("https://api.github.com/repos/${repo.owner}/${repo.name}/git/commits/$commitSha")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "token ${repo.token}")
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.setRequestProperty("User-Agent", "APKBuilderStudio")

            val response = readResponse(conn)
            conn.disconnect()

            val json = JSONObject(response)
            return json.getJSONObject("tree").getString("sha")
        } catch (e: Exception) {
            return null
        }
    }

    private fun createBlob(repo: GitHubRepo, content: ByteArray): String? {
        try {
            val url = URL("https://api.github.com/repos/${repo.owner}/${repo.name}/git/blobs")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "token ${repo.token}")
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.setRequestProperty("User-Agent", "APKBuilderStudio")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            val body = JSONObject().apply {
                put("content", Base64.encodeToString(content, Base64.NO_WRAP))
                put("encoding", "base64")
            }.toString()

            conn.outputStream.use { it.write(body.toByteArray()) }

            val response = readResponse(conn)
            conn.disconnect()

            val json = JSONObject(response)
            return json.getString("sha")
        } catch (e: Exception) {
            return null
        }
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

            val response = readResponse(conn)
            conn.disconnect()

            val json = JSONObject(response)
            return json.getString("sha")
        } catch (e: Exception) {
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

            val response = readResponse(conn)
            conn.disconnect()

            val json = JSONObject(response)
            return json.getString("sha")
        } catch (e: Exception) {
            return null
        }
    }

    private fun updateRef(repo: GitHubRepo, commitSha: String): Boolean {
        try {
            val url = URL("https://api.github.com/repos/${repo.owner}/${repo.name}/git/refs/heads/main")
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

            val code = conn.responseCode
            conn.disconnect()
            return code in 200..299
        } catch (e: Exception) {
            return false
        }
    }

    suspend fun triggerWorkflow(repo: GitHubRepo): String? = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://api.github.com/repos/${repo.owner}/${repo.name}/actions/workflows")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "token ${repo.token}")
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.setRequestProperty("User-Agent", "APKBuilderStudio")

            val response = readResponse(conn)
            conn.disconnect()

            val json = JSONObject(response)
            val workflows = json.getJSONArray("workflows")
            if (workflows.length() == 0) return@withContext null

            val workflowId = workflows.getJSONObject(0).getLong("id")

            val dispatchUrl = URL("https://api.github.com/repos/${repo.owner}/${repo.name}/actions/workflows/$workflowId/dispatches")
            val dispatchConn = dispatchUrl.openConnection() as HttpURLConnection
            dispatchConn.requestMethod = "POST"
            dispatchConn.setRequestProperty("Authorization", "token ${repo.token}")
            dispatchConn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            dispatchConn.setRequestProperty("User-Agent", "APKBuilderStudio")
            dispatchConn.setRequestProperty("Content-Type", "application/json")
            dispatchConn.doOutput = true

            val body = JSONObject().apply { put("ref", "main") }.toString()
            dispatchConn.outputStream.use { it.write(body.toByteArray()) }

            val code = dispatchConn.responseCode
            dispatchConn.disconnect()

            if (code in 200..299) {
                Thread.sleep(3000)
                getLatestRunId(repo)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun getLatestRunId(repo: GitHubRepo): String? {
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
            return null
        }
    }

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
            null
        }
    }

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
