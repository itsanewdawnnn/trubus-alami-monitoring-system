package com.trubus.tams.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.trubus.tams.data.repository.MemberRepository

/**
 * Resumes member tracking immediately after a device reboot, instead of
 * waiting for WorkManager's watchdog (~3-minute pass) or the member to
 * reopen the app. A reboot kills every running process unconditionally --
 * unlike a low-memory kill (START_STICKY) or a swiped-away task
 * (MemberLocationService.onTaskRemoved's alarm), nothing in-app survives to
 * react, so only a fresh BOOT_COMPLETED-triggered process can close the gap.
 * Calling startForegroundService() directly here is one of Android's
 * documented exemptions to the API 26+ background-start restrictions.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val repository = MemberRepository(context.applicationContext)
        if (!repository.isTrackingEnabled) return

        Log.i("BootCompletedReceiver", "Device rebooted with tracking previously active; resuming service.")
        try {
            val serviceIntent = Intent(context, MemberLocationService::class.java).apply {
                action = MemberLocationService.ACTION_START
            }
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (e: Exception) {
            // Best-effort: if this fails, LocationSyncWorker's watchdog chain
            // (persisted by WorkManager across reboot) is still there as a
            // slower, secondary recovery path.
            Log.e("BootCompletedReceiver", "Failed to resume tracking after boot: ${e.message}")
        }
    }
}
