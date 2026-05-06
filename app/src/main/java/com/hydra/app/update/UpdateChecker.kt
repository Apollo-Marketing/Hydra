package com.hydra.app.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class UpdateChecker {

    suspend fun check(currentVersionCode: Int): UpdateState = withContext(Dispatchers.IO) {
        runCatching {
            val body = when (val result = fetchLatestReleaseJson()) {
                is FetchResult.Body -> result.text
                is FetchResult.Http -> return@runCatching when (result.code) {
                    HTTP_NOT_FOUND -> UpdateState.Error("no releases published yet")
                    else -> UpdateState.Error("HTTP ${result.code}")
                }
            }
            val json = JSONObject(body)
            val tag = json.optString("tag_name")
            val remoteCode = parseVersionCode(tag)
                ?: return@runCatching UpdateState.Error("unrecognized tag format: '$tag'")
            if (remoteCode <= currentVersionCode) {
                return@runCatching UpdateState.UpToDate
            }
            val assets = json.optJSONArray("assets")
                ?: return@runCatching UpdateState.Error("release has no assets")
            val apk = (0 until assets.length())
                .asSequence()
                .map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk") }
                ?: return@runCatching UpdateState.Error("no APK asset attached to release")
            val apkUrl = apk.optString("browser_download_url")
            if (apkUrl.isEmpty()) {
                return@runCatching UpdateState.Error("APK asset has no download URL")
            }
            UpdateState.Available(
                versionCode = remoteCode,
                versionName = tag.removePrefix("v"),
                releaseNotes = json.optString("body"),
                apkUrl = apkUrl,
                sizeBytes = apk.optLong("size"),
            )
        }.getOrElse { UpdateState.Error(it.message ?: it::class.simpleName ?: "unknown error") }
    }

    private fun fetchLatestReleaseJson(): FetchResult {
        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_OK) {
                FetchResult.Body(conn.inputStream.bufferedReader().use { it.readText() })
            } else {
                FetchResult.Http(code)
            }
        } finally {
            conn.disconnect()
        }
    }

    private sealed interface FetchResult {
        data class Body(val text: String) : FetchResult
        data class Http(val code: Int) : FetchResult
    }

    companion object {
        private const val ENDPOINT =
            "https://api.github.com/repos/Apollo-Marketing/Hydra/releases/latest"
        private const val USER_AGENT = "Hydra-Android"
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 10_000
        private const val HTTP_NOT_FOUND = 404

        private val TAG_REGEX = Regex("""^v(\d+)\.(\d+)\.(\d+)$""")

        internal fun parseVersionCode(tag: String): Int? =
            TAG_REGEX.matchEntire(tag)?.destructured?.let { (maj, min, pat) ->
                maj.toInt() * 10_000 + min.toInt() * 100 + pat.toInt()
            }
    }
}
