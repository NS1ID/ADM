package com.adm

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.adm.browser.AdBlocker
import com.adm.browser.ChromiumEngine
import com.adm.core.NativeCore
import com.adm.data.AppDatabase
import com.adm.data.SettingsStore
import com.adm.download.DownloadEngine
import com.adm.extensions.UserscriptManager

/**
 * Application entry point. Initialises the things every screen depends on:
 * preferences, the SQLite store, the native (Rust) core, the browser engine's
 * content blocker, the userscript store and the notification channels used by the
 * download and VPN services.
 */
class AdmApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // The bundled Chromium engine's renderer/GPU/utility processes run this same
        // Application class. They cannot open our files or preferences, and doing
        // main-process work there kills the engine so no page renders. Return early.
        if (packageName != Application.getProcessName()) return

        installCrashReporter()
        instance = this
        SettingsStore.init(this)
        AppDatabase.init(this)
        NativeCore.init(this)
        AdBlocker.init(this)
        UserscriptManager.init(this)
        DownloadEngine.init(this)
        // The engine itself is initialised by the SDK's ContentProvider at launch;
        // this only warms the filter lists.
        ChromiumEngine.init(this)
        createNotificationChannels()
    }

    /** Writes every uncaught crash to a file the user can find in Downloads. */
    private fun installCrashReporter() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val dir = getExternalFilesDir(null) ?: cacheDir
                val file = java.io.File(dir, "crash-report.txt")
                file.appendText(
                    buildString {
                        append("ADM+ crash — ").append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())).append('\n')
                        append("Thread: ").append(thread.name).append('\n')
                        append("Error: ").append(error.javaClass.name).append(": ").append(error.message).append("\n\n")
                        append(error.stackTraceToString())
                    }
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DOWNLOADS,
                getString(R.string.download_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.download_channel_desc) }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_VPN,
                getString(R.string.vpn_channel_name),
                NotificationManager.IMPORTANCE_MIN
            ).apply { description = getString(R.string.vpn_channel_desc) }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MISC,
                "General",
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
    }

    companion object {
        const val CHANNEL_DOWNLOADS = "adm.downloads"
        const val CHANNEL_VPN = "adm.vpn"
        const val CHANNEL_MISC = "adm.misc"

        lateinit var instance: AdmApplication
            private set
    }
}