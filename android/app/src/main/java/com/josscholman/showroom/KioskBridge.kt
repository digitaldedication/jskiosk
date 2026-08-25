package com.josscholman.showroom

import android.webkit.JavascriptInterface

/**
 * Kleine, alleen-lezen brug naar de kioskpagina (window.KioskApp), zodat
 * die de voortgang van de mediasync kan tonen.
 */
class KioskBridge(private val cache: MediaCache) {

    /** JSON: {"cached": n, "total": m}. total=0 zolang er nog geen index is gesynct. */
    @JavascriptInterface
    fun cacheStatus(): String = try {
        cache.statusJson()
    } catch (_: Throwable) {
        "{}"
    }
}
