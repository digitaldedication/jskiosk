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

    fun tryServeFromCache(
        remoteUrl: String,
        requestHeaders: Map<String, String>? = null
    ): WebResourceResponse? {
        val file = localFileFor(remoteUrl) ?: return null
        if (!file.exists() || file.length() == 0L) return null
        val mime = mimeFor(file.name)
        val encoding =
            if (mime.startsWith("text/") || mime.endsWith("json") || mime.endsWith("javascript")) "UTF-8" else null

        // De videospeler vraagt om byte-ranges (starten, doorspoelen). Zonder
        // 206-antwoord valt hij terug op de volledige stream en start een
        // video merkbaar trager.
        val range = requestHeaders?.entries
            ?.firstOrNull { it.key.equals("Range", ignoreCase = true) }?.value
        if (range != null) {
            val m = Regex("bytes=(\\d+)-(\\d*)").find(range)
            if (m != null) {
                val total = file.length()
                val start = m.groupValues[1].toLong()
                val end = m.groupValues[2].toLongOrNull()?.coerceAtMost(total - 1) ?: (total - 1)
                if (start in 0 until total && start <= end) {
                    val stream = FileInputStream(file)
                    var skipped = 0L
                    while (skipped < start) {
                        val s = stream.skip(start - skipped)
                        if (s <= 0) break
                        skipped += s
                    }
                    val headers = mapOf(
                        "Access-Control-Allow-Origin" to "*",
                        "Accept-Ranges" to "bytes",
                        "Content-Range" to "bytes $start-$end/$total",
                        "Content-Length" to (end - start + 1).toString(),
                    )
                    return WebResourceResponse(mime, encoding, 206, "Partial Content", headers, stream)
                }
            }
        }

        val headers = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Accept-Ranges" to "bytes",
            "Cache-Control" to "no-cache",
        )
        return WebResourceResponse(mime, encoding, 200, "OK", headers, FileInputStream(file))
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

    /**
     * Hoeveel van de media uit de gesyncte indexen staat al (volledig) op
     * schijf? Gebruikt door de JS-brug zodat de kioskpagina een
     * downloadvoortgang kan tonen. total=0 zolang er nog geen index is.
     */
    fun statusJson(): String {
        var total = 0
        var cached = 0
        for (cat in listOf("infra", "groen", "sport")) {
            val idx = indexFile(cat)
            if (!idx.exists()) continue
            val arr = try {
                org.json.JSONArray(idx.readText())
            } catch (_: Throwable) {
                continue
            }
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optString("file")
                if (name.isNullOrEmpty()) continue
                total++
                val f = File(idx.parentFile, name)
                val want = obj.optLong("size", -1L)
                if (f.exists() && f.length() > 0 && (want <= 0L || f.length() == want)) {
                    cached++
                }
            }
        }
        return "{\"cached\":$cached,\"total\":$total}"
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
