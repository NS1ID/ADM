package com.adm.browser

import android.app.Notification
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
 * Keeps the media sniffer alive while the browser is in the background.
 *
 * The sniffer itself ([MediaSniffer]) is a plain in-process object; this service only
 * pins the process with a low-importance foreground notification so a long media
 * scan is not killed while the user is in another app. It stops itself as soon as the
 * candidate list is empty.
 */
class MediaSnifferService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            MediaSniffer.items.collectLatest { items ->
                if (items.isEmpty()) {
                    stopForegroundCompat()
                    stopSelf()
                    return@collectLatest
                }
                startForeground(NOTIFICATION_ID, buildNotification(items.size))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            startForeground(NOTIFICATION_ID, buildNotification(MediaSniffer.items.value.size))
        }
        return START_STICKY
    }

    private fun buildNotification(count: Int): Notification {
        val open = android.app.PendingIntent.getActivity(
            this, 0,
            Intent(this, BrowserActivity::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = android.app.PendingIntent.getService(
            this, 1,
            Intent(this, MediaSnifferService::class.java).setAction(ACTION_STOP),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, AdmApplication.CHANNEL_MISC)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentTitle(getString(R.string.media_sniffer_title))
            .setContentText(resources.getQuantityString(R.plurals.media_found, count, count))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.download_cancel), stop)
            .build()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        runCatching { NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.adm.action.STOP_SNIFFER"
        const val NOTIFICATION_ID = 3001

        fun start(context: Context) {
            val i = Intent(context, MediaSnifferService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MediaSnifferService::class.java).setAction(ACTION_STOP))
        }
    }
}
