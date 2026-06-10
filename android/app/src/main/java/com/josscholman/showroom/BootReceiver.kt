package com.josscholman.showroom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Relaunches the kiosk when the device boots, so unattended TVs come back automatically. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            val launch = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(launch)
        }
    }
}
