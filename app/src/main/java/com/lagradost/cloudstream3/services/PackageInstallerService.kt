package com.lagradost.cloudstream3.services

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.os.Build.VERSION.SDK_INT
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.PendingIntentCompat
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.MainActivity.Companion.deleteFileOnExit
import java.io.File
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.ApkInstaller
import com.lagradost.cloudstream3.utils.ApkUpdateVerifier
import com.lagradost.cloudstream3.utils.ExpectedApk
import com.lagradost.cloudstream3.utils.AppContextUtils.createNotificationChannel
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToInt

class PackageInstallerService : Service() {
    private var installer: ApkInstaller? = null

    private val baseNotification by lazy {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent =
            PendingIntentCompat.getActivity(this, 0, intent, 0, false)

        NotificationCompat.Builder(this, UPDATE_CHANNEL_ID)
            .setAutoCancel(false)
            .setColorized(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            // If low priority then the notification might not show :(
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setColor(this.colorFromAttribute(R.attr.colorPrimary))
            .setContentTitle(getString(R.string.update_notification_downloading))
            .setContentIntent(pendingIntent)
            .setSmallIcon(R.drawable.rdload)
    }

    override fun onCreate() {
        this.createNotificationChannel(
            UPDATE_CHANNEL_ID,
            UPDATE_CHANNEL_NAME,
            UPDATE_CHANNEL_DESCRIPTION
        )
        if (SDK_INT >= 29)
        startForeground(UPDATE_NOTIFICATION_ID, baseNotification.build(), FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(UPDATE_NOTIFICATION_ID, baseNotification.build())
    }

    private val updateLock = Mutex()

    private suspend fun downloadUpdate(url: String, expected: ExpectedApk): Boolean {
        try {
            ApkUpdateVerifier.requireAllowedUrl(url)
            Log.d("PackageInstallerService", "Downloading verified update: $url")

            // Delete all old updates
            ioSafe {
                val appUpdateName = "CloudStream"
                val appUpdateSuffix = "apk"

                this@PackageInstallerService.cacheDir.listFiles()?.filter {
                    it.name.startsWith(appUpdateName) && it.extension == appUpdateSuffix
                }?.forEach {
                    deleteFileOnExit(it)
                }
            }

            updateLock.withLock {
                updateNotificationProgress(
                    0f,
                    ApkInstaller.InstallProgressStatus.Downloading
                )

                val body = app.get(url).body
                val totalSize = body.contentLength().takeIf { it > 0 } ?: expected.size ?: -1L
                expected.size?.let { declared ->
                    require(totalSize < 0 || totalSize == declared) { "Unexpected update download size" }
                }
                val downloadedFile = File.createTempFile("CloudStream", ".apk", cacheDir)
                body.byteStream().use { input ->
                    downloadedFile.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var currentSize = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            currentSize += count
                            val percentage = if (totalSize > 0) currentSize / totalSize.toFloat() else 0f
                            updateNotificationProgress(
                                percentage,
                                ApkInstaller.InstallProgressStatus.Downloading
                            )
                        }
                    }
                }
                ApkUpdateVerifier.verify(this, downloadedFile, expected)
                installer = ApkInstaller(this)
                downloadedFile.inputStream().use { input ->
                    installer?.installApk(this, input, downloadedFile.length(), {}, { status ->
                        updateNotificationProgress(0f, status)
                    })
                }
                deleteFileOnExit(downloadedFile)
            }
            return true
        } catch (e: Exception) {
            logError(e)
            updateNotificationProgress(0f, ApkInstaller.InstallProgressStatus.Failed)
            return false
        }
    }

    private fun updateNotificationProgress(
        percentage: Float,
        state: ApkInstaller.InstallProgressStatus
    ) {
//        Log.d(LOG_TAG, "Downloading app update progress $percentage | $state")
        val text = when (state) {
            ApkInstaller.InstallProgressStatus.Installing -> R.string.update_notification_installing
            ApkInstaller.InstallProgressStatus.Preparing, ApkInstaller.InstallProgressStatus.Downloading -> R.string.update_notification_downloading
            ApkInstaller.InstallProgressStatus.Failed -> R.string.update_notification_failed
        }

        val newNotification = baseNotification
            .setContentTitle(getString(text))
            .apply {
                if (state == ApkInstaller.InstallProgressStatus.Failed) {
                    setSmallIcon(R.drawable.rderror)
                    setAutoCancel(true)
                } else {
                    setProgress(
                        10000, (10000 * percentage).roundToInt(),
                        state != ApkInstaller.InstallProgressStatus.Downloading
                    )
                }
            }
            .build()

        val notificationManager =
            getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        // Persistent notification on failure
        val id =
            if (state == ApkInstaller.InstallProgressStatus.Failed) UPDATE_NOTIFICATION_ID + 1 else UPDATE_NOTIFICATION_ID
        notificationManager.notify(id, newNotification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
        val expected = ExpectedApk(
            packageName = intent.getStringExtra(EXTRA_PACKAGE) ?: return START_NOT_STICKY,
            versionCode = intent.getLongExtra(EXTRA_VERSION_CODE, -1L).takeIf { it > 0 }
                ?: return START_NOT_STICKY,
            sha256 = intent.getStringExtra(EXTRA_SHA256) ?: return START_NOT_STICKY,
            certificateSha256 = intent.getStringExtra(EXTRA_CERT_SHA256)
                ?: return START_NOT_STICKY,
            size = intent.getLongExtra(EXTRA_SIZE, -1L).takeIf { it > 0 },
        )
        ioSafe {
            downloadUpdate(url, expected)
            // Close the service after the update is done
            // If no sleep then the install prompt may not appear and the notification
            // will disappear instantly
            delay(10_000)
            this@PackageInstallerService.stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        installer?.unregisterInstallActionReceiver()
        installer = null
        if (SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(UPDATE_NOTIFICATION_ID)
        super.onDestroy()
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onTimeout(reason: Int) {
        stopSelf()
        Log.e("PackageInstallerService", "Service stopped due to timeout: $reason")
    }

    companion object {
        private const val EXTRA_URL = "EXTRA_URL"
        private const val EXTRA_PACKAGE = "EXTRA_PACKAGE"
        private const val EXTRA_VERSION_CODE = "EXTRA_VERSION_CODE"
        private const val EXTRA_SHA256 = "EXTRA_SHA256"
        private const val EXTRA_CERT_SHA256 = "EXTRA_CERT_SHA256"
        private const val EXTRA_SIZE = "EXTRA_SIZE"

        const val UPDATE_CHANNEL_ID = "cloudstream3.updates"
        const val UPDATE_CHANNEL_NAME = "App Updates"
        const val UPDATE_CHANNEL_DESCRIPTION = "App updates notification channel"
        const val UPDATE_NOTIFICATION_ID = -68454136 // Random unique

        fun getIntent(
            context: Context,
            url: String,
            expected: ExpectedApk,
        ): Intent {
            return Intent(context, PackageInstallerService::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_PACKAGE, expected.packageName)
                .putExtra(EXTRA_VERSION_CODE, expected.versionCode)
                .putExtra(EXTRA_SHA256, expected.sha256)
                .putExtra(EXTRA_CERT_SHA256, expected.certificateSha256)
                .apply { expected.size?.let { putExtra(EXTRA_SIZE, it) } }
        }
    }
}