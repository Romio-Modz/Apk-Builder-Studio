package com.apkbuilder.studio.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    suspend fun pushFile(
        repo: GitHubRepo,
        path: String,
        content: String,
        message: String = "Add $path"
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val encodedPath = java.net.URLEncoder.encode(path, "UTF-8")
                .replace("+", "%20")
            val url = URL("https://api.github.com/repos/${repo.owner}/${repo.name}/contents/$encodedPath")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "PUT"
            conn.setRequestProperty("Authorization", "token ${repo.token}")
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.setRequestProperty("User-Agent", "APKBuilderStudio")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            val body = JSONObject().apply {
                put("message", message)
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

    suspend fun pushBinaryFile(
        repo: GitHubRepo,
        path: String,
        bytes: ByteArray,
        message: String = "Add $path"
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val encodedPath = java.net.URLEncoder.encode(path, "UTF-8")
                .replace("+", "%20")
            val url = URL("https://api.github.com/repos/${repo.owner}/${repo.name}/contents/$encodedPath")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "PUT"
            conn.setRequestProperty("Authorization", "token ${repo.token}")
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.setRequestProperty("User-Agent", "APKBuilderStudio")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            val body = JSONObject().apply {
                put("message", message)
                put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
            }.toString()

            conn.outputStream.use { it.write(body.toByteArray()) }

            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (e: Exception) {
            false
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

            val body = JSONObject().apply {
                put("ref", "main")
            }.toString()

            dispatchConn.outputStream.use { it.write(body.toByteArray()) }

            val code = dispatchConn.responseCode
            dispatchConn.disconnect()

            if (code in 200..299) {
                Thread.sleep(3000)
                getLatestRunId(repo)
            } else {
                null
            }
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
