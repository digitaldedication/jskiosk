package com.josscholman.showroom

import android.content.Context
import android.webkit.MimeTypeMap
import android.webkit.WebResourceResponse
import java.io.File
import java.io.FileInputStream
import java.net.URI

/**
 * On-disk cache that mirrors the kiosk's web tree (HTML, JS, CSS, images, videos, JSON).
 *
 * URL https://host/<root>/media/infra/Foo.jpg → <files>/kiosk-cache/media/infra/Foo.jpg
 * URL https://host/<root>/index.html          → <files>/kiosk-cache/index.html
 * URL https://host/<root>/                    → <files>/kiosk-cache/index.html
 *
 * MediaSyncWorker writes; MainActivity's WebView reads via tryServeFromCache().
 */
class MediaCache(private val context: Context) {

    /** Host + path prefix that identifies the kiosk, e.g. "digitaldedication.github.io/jskiosk". */
    private val kioskHostAndRoot: String
    private val kioskHost: String
    private val kioskRootPath: String  // "/jskiosk" or "" if hosted at the root

    init {
        val uri = URI(BuildConfig.KIOSK_URL)
        kioskHost = uri.host ?: ""
        val rawPath = (uri.path ?: "/").trimEnd('/')
        kioskRootPath = rawPath
        kioskHostAndRoot = kioskHost + kioskRootPath
    }

    private val rootDir: File = File(context.filesDir, "kiosk-cache").apply { mkdirs() }

    /**
     * Map a remote URL to the local file that should mirror it.
     * Returns null when the URL is outside the kiosk's tree (e.g. external analytics).
     */
    fun localFileFor(remoteUrl: String): File? {
        val u = try { URI(remoteUrl) } catch (_: Throwable) { return null }
        if (u.host == null || u.host != kioskHost) return null
        var path = u.path ?: return null
        if (!path.startsWith("$kioskRootPath/") && path != kioskRootPath && path != "$kioskRootPath/") {
            return null
        }
        // Strip the root prefix
        var rel = path.removePrefix(kioskRootPath).removePrefix("/")
        if (rel.isEmpty() || rel.endsWith("/")) {
            rel += "index.html"
        }
        return File(rootDir, rel)
    }

    fun indexFile(category: String): File = File(rootDir, "media/$category/index.json")

    fun tryServeFromCache(remoteUrl: String): WebResourceResponse? {
        val file = localFileFor(remoteUrl) ?: return null
        if (!file.exists() || file.length() == 0L) return null
        val mime = mimeFor(file.name)
        val headers = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Cache-Control" to "no-cache",
        )
        return WebResourceResponse(
            mime,
            if (mime.startsWith("text/") || mime.endsWith("json") || mime.endsWith("javascript")) "UTF-8" else null,
            200,
            "OK",
            headers,
            FileInputStream(file)
        )
    }

    private fun mimeFor(filename: String): String {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "json" -> "application/json"
            "html", "htm" -> "text/html"
            "js" -> "application/javascript"
            "css" -> "text/css"
            "" -> "text/html"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }

    fun ensureParent(file: File) {
        file.parentFile?.mkdirs()
    }

    /** Drop files in media/<cat>/ that are no longer referenced. Shell files are kept. */
    fun pruneOrphanMedia(keepRelativePaths: Set<String>) {
        val mediaDir = File(rootDir, "media")
        if (!mediaDir.exists()) return
        mediaDir.listFiles()?.forEach { catDir ->
            if (!catDir.isDirectory) return@forEach
            catDir.listFiles()?.forEach { f ->
                val rel = "media/${catDir.name}/${f.name}"
                if (!keepRelativePaths.contains(rel)) {
                    f.delete()
                }
            }
        }
    }
}
