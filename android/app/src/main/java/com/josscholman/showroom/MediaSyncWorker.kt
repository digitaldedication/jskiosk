package com.josscholman.showroom

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pulls the entire kiosk (app shell + all media) into the on-disk cache so
 * the kiosk plays even without network. Re-runs every ~15 min via WorkManager.
 */
class MediaSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val cache = MediaCache(applicationContext)
    private val baseUrl: String = BuildConfig.KIOSK_URL.trimEnd('/')

    // Static assets the kiosk loads at startup; sync these so the app shell
    // also works offline.
    private val shellPaths = listOf(
        "index.html",
        "background-home.jpg",
        "back-button.png",
        "swipe-icon.png",
    )

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val categories = listOf("infra", "groen", "sport")
        try {
            // App shell
            for (rel in shellPaths) {
                val target = File(File(applicationContext.filesDir, "kiosk-cache"), rel)
                downloadIfChanged("$baseUrl/$rel", target)
            }

            // Per category: index + cover + all media
            val keepMedia = mutableSetOf<String>()
            for (cat in categories) {
                val indexUrl = "$baseUrl/media/$cat/index.json"
                val indexFile = cache.indexFile(cat)
                val indexBody = downloadToString(indexUrl) ?: continue
                cache.ensureParent(indexFile)
                indexFile.writeText(indexBody)
                keepMedia.add("media/$cat/index.json")

                val coverFile = File(indexFile.parentFile, "cover.jpg")
                downloadIfChanged("$baseUrl/media/$cat/cover.jpg", coverFile)
                if (coverFile.exists()) keepMedia.add("media/$cat/cover.jpg")

                val arr = JSONArray(indexBody)
                for (i in 0 until arr.length()) {
                    val obj: JSONObject = arr.getJSONObject(i)
                    val filename = obj.optString("file")
                    if (filename.isNullOrEmpty()) continue
                    val target = File(indexFile.parentFile, filename)
                    val remoteUrl = "$baseUrl/media/$cat/" + encodePath(filename)
                    downloadIfChanged(remoteUrl, target)
                    if (target.exists()) keepMedia.add("media/$cat/$filename")
                }
            }
            cache.pruneOrphanMedia(keepMedia)
            Result.success()
        } catch (t: Throwable) {
            android.util.Log.w("JsKiosk-Sync", "sync failed: ${t.message}")
            Result.retry()
        }
    }

    private fun encodePath(name: String): String =
        name.split("/").joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    private fun downloadToString(urlStr: String): String? {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            requestMethod = "GET"
            setRequestProperty("Cache-Control", "no-cache")
        }
        return try {
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Throwable) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /** Returns true if file was (re)downloaded. */
    private fun downloadIfChanged(urlStr: String, target: File): Boolean {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            requestMethod = "GET"
        }
        return try {
            if (conn.responseCode !in 200..299) return false
            val remoteLen = conn.contentLengthLong
            if (target.exists() && remoteLen > 0 && target.length() == remoteLen) {
                return false
            }
            cache.ensureParent(target)
            val tmp = File(target.parentFile, target.name + ".part")
            FileOutputStream(tmp).use { out ->
                conn.inputStream.use { input -> input.copyTo(out) }
            }
            if (target.exists()) target.delete()
            tmp.renameTo(target)
            true
        } catch (_: Throwable) {
            false
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val UNIQUE_NAME = "media-sync"
    }
}
