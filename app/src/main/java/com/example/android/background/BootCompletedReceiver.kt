package com.example.android.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.PhoneBridgeApplication

/**
 * Restores WorkManager recovery schedules after device reboot or app update.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val app = context.applicationContext as? PhoneBridgeApplication ?: return
            app.container.scheduleRecoveryWorkers()
            app.container.logger.info("SYSTEM", "Recovered delivery schedules after $action")
        }
    }
}
