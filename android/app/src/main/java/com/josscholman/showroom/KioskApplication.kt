package com.josscholman.showroom

import android.app.Application
import android.content.Context
import android.util.Log
import java.io.File

/**
 * Schrijft elke niet-afgevangen crash naar een bestand, zodat MainActivity
 * hem bij de volgende start op het scherm kan tonen. Op signage-borden is
 * er geen logcat bij de hand; dit is de enige manier om te zien waarom de
 * app "onverwacht gestopt" is.
 */
class KioskApplication : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Zo vroeg mogelijk installeren: ook crashes tijdens de verdere
        // proces-initialisatie worden dan nog vastgelegd.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                File(filesDir, CRASH_FILE).writeText(Log.getStackTraceString(throwable))
            } catch (_: Throwable) {
                // Niets meer aan te doen; laat de standaardafhandeling zijn werk doen.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // De automatische WorkManager-init (androidx.startup) is in de
        // manifest verwijderd omdat die op sommige signage-firmwares het
        // proces laat crashen vóór er iets op het scherm staat. Hier doen we
        // dezelfde init, maar afgeschermd: mislukt hij, dan draait de kiosk
        // gewoon (alleen zonder achtergrond-sync).
        try {
            androidx.work.WorkManager.initialize(
                this,
                androidx.work.Configuration.Builder().build()
            )
        } catch (t: Throwable) {
            Log.w("JsKiosk", "WorkManager init failed: ${t.message}")
        }
    }

    companion object {
        const val CRASH_FILE = "last-crash.txt"
    }
}
