package com.adm.download

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.adm.AdmApplication
import com.adm.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps [DownloadEngine] alive while downloads are in
 * flight and mirrors their progress into a single grouped notification.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            DownloadEngine.downloads.collectLatest { items ->
                val active = items.filter { !it.isTerminal }
                if (active.isEmpty()) {
                    // Nothing left to track – stop consuming resources.
                    stopForegroundCompat()
                    stopSelf()
                    return@collectLatest
                }
                startForeground(NOTIFICATION_ID, buildSummaryNotification(active))
                active.take(3).forEachIndexed { index, item ->
                    notifyDetail(DETAIL_BASE + index, item)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { handleAction(it, intent.getStringExtra(EXTRA_ID)) }
        if (!started) {
            started = true
            // Promote to foreground immediately so the system does not kill us.
            startForeground(NOTIFICATION_ID, buildSummaryNotification(DownloadEngine.downloads.value))
        }
        return START_STICKY
    }

    private fun handleAction(action: String, id: String?) {
        if (id == null) return
        when (action) {
            ACTION_PAUSE -> DownloadEngine.pause(id)
            ACTION_RESUME -> DownloadEngine.resume(id)
            ACTION_CANCEL -> DownloadEngine.cancel(id)
        }
    }

    private fun buildSummaryNotification(items: List<DownloadItem>): Notification {
        val active = items.count { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.PROBING }
        val done = items.count { it.status == DownloadStatus.COMPLETED }
        val content = when {
            items.isEmpty() -> "Ready"
            active > 0 -> "$active active • $done completed"
            else -> "$done completed"
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, DownloadListActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, AdmApplication.CHANNEL_DOWNLOADS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.downloads))
            .setContentText(content)
            .setContentIntent(open)
            .setOngoing(active > 0)
            .setOnlyAlertOnce(true)
            .setGroup(GROUP)
            .build()
    }

    private fun notifyDetail(notificationId: Int, item: DownloadItem) {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(
            this, notificationId,
            Intent(this, DownloadListActivity::class.java).putExtra(EXTRA_ID, item.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, AdmApplication.CHANNEL_DOWNLOADS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(item.filename)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setGroup(GROUP)

        if (item.total > 0) {
            builder.setProgress(100, (item.progress * 100).toInt(), false)
            builder.setContentText("${humanBytes(item.downloaded)} / ${humanBytes(item.total)}")
        } else {
            builder.setProgress(0, 0, item.status == DownloadStatus.RUNNING)
            builder.setContentText(humanBytes(item.downloaded))
        }

        val pauseIntent = actionPending(ACTION_PAUSE, item.id, 1)
        when (item.status) {
            DownloadStatus.RUNNING, DownloadStatus.PROBING ->
                builder.addAction(android.R.drawable.ic_media_pause, getString(R.string.download_pause), pauseIntent)
            DownloadStatus.PAUSED, DownloadStatus.FAILED ->
                builder.addAction(android.R.drawable.ic_media_play, getString(R.string.download_resume),
                    actionPending(ACTION_RESUME, item.id, 2))
            else -> Unit
        }
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.download_cancel),
            actionPending(ACTION_CANCEL, item.id, 3))

        runCatching { NotificationManagerCompat.from(this).notify(notificationId, builder.build()) }
    }

    private fun actionPending(action: String, id: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, DownloadService::class.java).apply {
            this.action = action
            putExtra(EXTRA_ID, id)
        }
        return PendingIntent.getService(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        const val DETAIL_BASE = 1100
        const val GROUP = "adm.downloads.group"

        const val ACTION_PAUSE = "com.adm.action.PAUSE"
        const val ACTION_RESUME = "com.adm.action.RESUME"
        const val ACTION_CANCEL = "com.adm.action.CANCEL"
        const val EXTRA_ID = "download_id"

        fun start(context: Context) {
            val i = Intent(context, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        fun send(context: Context, action: String, id: String) {
            val i = Intent(context, DownloadService::class.java).apply {
                this.action = action
                putExtra(EXTRA_ID, id)
            }
            context.startService(i)
        }
    }
}

/** Shared byte formatter used by notifications and the UI. */
fun humanBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "$bytes ${units[unit]}" else String.format("%.2f %s", value, units[unit])
}
