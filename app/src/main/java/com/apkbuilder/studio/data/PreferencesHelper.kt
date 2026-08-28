package com.apkbuilder.studio.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Simple SharedPreferences helper to save and load GitHub token and username.
 * Token is stored locally on device - never sent anywhere except GitHub API.
 */
object PreferencesHelper {

    private const val PREFS_NAME = "apk_builder_prefs"
    private const val KEY_GITHUB_TOKEN = "github_token"
    private const val KEY_GITHUB_USER = "github_user"
    private const val KEY_REPO_NAME = "repo_name"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveGithubToken(context: Context, token: String) {
        getPrefs(context).edit().putString(KEY_GITHUB_TOKEN, token).apply()
    }

    fun getGithubToken(context: Context): String {
        return getPrefs(context).getString(KEY_GITHUB_TOKEN, "") ?: ""
    }

    fun saveGithubUser(context: Context, user: String) {
        getPrefs(context).edit().putString(KEY_GITHUB_USER, user).apply()
    }

    fun getGithubUser(context: Context): String {
        return getPrefs(context).getString(KEY_GITHUB_USER, "") ?: ""
    }

    fun saveRepoName(context: Context, repo: String) {
        getPrefs(context).edit().putString(KEY_REPO_NAME, repo).apply()
    }

    fun getRepoName(context: Context): String {
        return getPrefs(context).getString(KEY_REPO_NAME, "") ?: ""
    }

    fun clearAll(context: Context) {
        getPrefs(context).edit().clear().apply()
    }
}
