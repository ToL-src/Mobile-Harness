package com.jarves.mh.network

import android.content.Context
import com.jarves.mh.R
import com.jarves.mh.ui.AppStrings
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

data class GitLabAccount(val login: String, val avatarUrl: String)

data class GitLabRepository(
    val pathWithNamespace: String,
    val httpUrlToRepo: String,
    val private: Boolean,
    val defaultBranch: String,
    val description: String,
    val updatedAt: String,
)

/** [context] is optional; when present, user-facing errors use the language picked in the app. */
class GitLabClient(private val context: Context? = null) {
    private fun text(@androidx.annotation.StringRes id: Int, fallback: String, vararg args: Any): String =
        context?.let { AppStrings.get(it, id, *args) } ?: fallback

    fun account(host: String, token: String): GitLabAccount {
        val json = getJson(apiBase(host) + "/user", token) as JSONObject
        val login = json.optString("username").ifBlank { json.optString("name") }
        require(login.isNotBlank()) { text(R.string.vm_gitlab_account_unknown, "GitLab connected, but the account could not be identified") }
        return GitLabAccount(login = login, avatarUrl = json.optString("avatar_url"))
    }

    fun repositories(host: String, token: String): List<GitLabRepository> {
        val result = LinkedHashMap<String, GitLabRepository>()
        var page = 1
        while (page <= 10) {
            val array = getJson(
                apiBase(host) + "/projects?membership=true&simple=true&order_by=last_activity_at&sort=desc&per_page=100&page=$page",
                token,
            ) as JSONArray
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val path = item.getString("path_with_namespace")
                val httpUrl = item.optString("http_url_to_repo").ifBlank {
                    "https://${normalizeHost(host)}/$path.git"
                }
                result[path] = GitLabRepository(
                    pathWithNamespace = path,
                    httpUrlToRepo = httpUrl,
                    private = item.optString("visibility").ifBlank { "private" } != "public",
                    defaultBranch = item.optString("default_branch").ifBlank { "main" },
                    description = item.optString("description"),
                    updatedAt = item.optString("last_activity_at").ifBlank { item.optString("updated_at") },
                )
            }
            if (array.length() < 100) break
            page++
        }
        return result.values.toList()
    }

    private fun getJson(endpoint: String, token: String): Any {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("PRIVATE-TOKEN", token)
            setRequestProperty("User-Agent", "PocketDev-Android")
        }
        return readResponse(connection)
    }

    private fun readResponse(connection: HttpURLConnection): Any {
        val status = connection.responseCode
        val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (status !in 200..299) {
            val message = runCatching {
                JSONObject(text).optString("message").ifBlank { JSONObject(text).optString("error") }
            }.getOrNull()
            error(message?.takeIf(String::isNotBlank) ?: text(R.string.rt_gl_http_error, "GitLab returned HTTP $status", status))
        }
        return if (text.trimStart().startsWith("[")) JSONArray(text) else JSONObject(text)
    }

    companion object {
        const val DEFAULT_HOST = "gitlab.com"

        fun normalizeHost(raw: String): String {
            val trimmed = raw.trim().removeSuffix("/")
            require(trimmed.isNotBlank()) { "GitLab host is required" }
            val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
            val uri = URI(withScheme)
            require(uri.scheme.equals("https", ignoreCase = true)) { "Only HTTPS GitLab hosts are supported" }
            require(uri.userInfo == null) { "GitLab host must not include credentials" }
            require(uri.host?.isNotBlank() == true) { "GitLab host is invalid" }
            require(uri.host != "localhost" && uri.host != "127.0.0.1" && uri.host != "::1") {
                "Local GitLab hosts are not supported"
            }
            val port = when {
                uri.port > 0 && uri.port != 443 -> ":${uri.port}"
                else -> ""
            }
            // Strip a pasted API root so apiBase() does not become …/api/v4/api/v4.
            val rawPath = uri.path?.trim('/').orEmpty()
            val strippedPath = when {
                rawPath == "api/v4" || rawPath == "api" -> ""
                rawPath.endsWith("/api/v4") -> rawPath.removeSuffix("/api/v4")
                rawPath.endsWith("/api") -> rawPath.removeSuffix("/api")
                else -> rawPath
            }.trim('/')
            val path = strippedPath.takeIf { it.isNotBlank() }?.let { "/$it" }.orEmpty()
            return uri.host!!.lowercase() + port + path
        }

        fun apiBase(host: String): String = "https://${normalizeHost(host)}/api/v4"
    }
}
