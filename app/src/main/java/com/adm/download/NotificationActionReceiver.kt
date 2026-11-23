package com.adm.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Handles the action buttons attached to download notifications. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(DownloadService.EXTRA_ID) ?: return
        when (intent.action) {
            DownloadService.ACTION_PAUSE -> DownloadEngine.pause(id)
            DownloadService.ACTION_RESUME -> DownloadEngine.resume(id)
            DownloadService.ACTION_CANCEL -> DownloadEngine.cancel(id)
        }
    }
}
