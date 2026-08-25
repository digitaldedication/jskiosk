package com.josscholman.showroom

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private val logTag = "JsKiosk"
    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // A FrameLayout root so we can swap to an error view if anything below fails.
        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFF0056A3.toInt())
        }
        setContentView(root)

        try {
            hideSystemUi()

            // Is de app de vorige keer gecrasht? Toon dan eerst de stacktrace
            // op het scherm, zodat die gefotografeerd kan worden.
            val crashFile = java.io.File(filesDir, KioskApplication.CRASH_FILE)
            if (crashFile.exists()) {
                val trace = crashFile.readText()
                crashFile.delete()
                showCrashReport(root, trace)
            } else {
                startKiosk(root)
            }
        } catch (t: Throwable) {
            Log.e(logTag, "Fatal during onCreate", t)
            showFatal(root, t)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun startKiosk(root: FrameLayout) {
        try {
            val mediaCache = MediaCache(applicationContext)

            val wv = WebView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    mediaPlaybackRequiresUserGesture = false
                    allowFileAccess = true
                    allowContentAccess = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                }
                setBackgroundColor(0xFF0056A3.toInt())

                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest
                    ): WebResourceResponse? {
                        return try {
                            mediaCache.tryServeFromCache(request.url.toString(), request.requestHeaders)
                        } catch (t: Throwable) {
                            Log.w(logTag, "intercept failed: ${t.message}")
                            null
                        }
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                        Log.d("$logTag-WebView", "${msg.sourceId()}:${msg.lineNumber()} ${msg.message()}")
                        return true
                    }
                }

                // JS-brug: de kioskpagina toont hiermee "Media downloaden… x/y"
                // tot de hele cache gevuld is.
                addJavascriptInterface(KioskBridge(mediaCache), "KioskApp")
            }

            root.addView(wv)
            webView = wv
            wv.loadUrl(BuildConfig.KIOSK_URL)

            // Defer the WorkManager wiring until after the UI is shown so a
            // failure here can never block the first frame.
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    scheduleMediaSync()
                } catch (t: Throwable) {
                    Log.w(logTag, "WorkManager scheduling failed: ${t.message}", t)
                }
            }, 1500)
        } catch (t: Throwable) {
            Log.e(logTag, "Fatal during onCreate", t)
            showFatal(root, t)
        }
    }

    private fun scheduleMediaSync() {
        // De automatische WorkManager-init is in de manifest uitgeschakeld
        // (crasht op sommige signage-firmwares tijdens het opstarten van het
        // proces); initialiseer hier handmatig, binnen ons eigen vangnet.
        try {
            WorkManager.initialize(applicationContext, Configuration.Builder().build())
        } catch (_: IllegalStateException) {
            // Al geïnitialiseerd — prima.
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        WorkManager.getInstance(applicationContext).enqueue(
            OneTimeWorkRequestBuilder<MediaSyncWorker>()
                .setConstraints(constraints)
                .build()
        )

        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            MediaSyncWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<MediaSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
        )
    }

    /** Toont de stacktrace van de vorige crash; tikken op het scherm start de kiosk alsnog. */
    private fun showCrashReport(root: FrameLayout, trace: String) {
        root.removeAllViews()
        val tv = TextView(this).apply {
            text = "De app is de vorige keer onverwacht gestopt.\n" +
                "Maak een foto van dit scherm en stuur die door.\n" +
                "Tik op het scherm om de kiosk te starten.\n\n" + trace
            setTextColor(Color.WHITE)
            setBackgroundColor(0xFF0056A3.toInt())
            textSize = 13f
            setPadding(48, 48, 48, 48)
            movementMethod = android.text.method.ScrollingMovementMethod()
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        tv.setOnClickListener {
            root.removeAllViews()
            try {
                startKiosk(root)
            } catch (t: Throwable) {
                showFatal(root, t)
            }
        }
        root.addView(tv)
    }

    private fun showFatal(root: FrameLayout, t: Throwable) {
        root.removeAllViews()
        val tv = TextView(this).apply {
            text = "Showroom kon niet starten.\n\n${t.javaClass.simpleName}: ${t.message}"
            setTextColor(Color.WHITE)
            setBackgroundColor(0xFF0056A3.toInt())
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(tv)
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        webView?.onResume()
    }

    override fun onPause() {
        super.onPause()
        webView?.onPause()
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        // Kiosk app: back is intentionally disabled.
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        }
    }
}
