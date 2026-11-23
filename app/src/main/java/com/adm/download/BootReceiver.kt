package com.adm.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Resumes the download service after a reboot or an app update, re-queuing any
 * downloads that were mid-flight (their rows are marked PAUSED by the engine on
 * startup, so the user can resume them with one tap).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val pending = DownloadEngine.downloads.value.filter {
                    it.status == DownloadStatus.PAUSED || it.status == DownloadStatus.QUEUED
                }
                if (pending.isNotEmpty()) DownloadService.start(context)
            }
        }
    }
}
