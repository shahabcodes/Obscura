package com.shahaabapps.obscura.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.shahaabapps.obscura.MainActivity
import com.shahaabapps.obscura.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Keeps the app alive while files are exported. Like imports, it never shows file names. */
@AndroidEntryPoint
class ExportService : Service() {

    @Inject
    lateinit var exportManager: ExportManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Exporting files", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress while copies are saved outside the vault"
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            exportManager.cancel()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(exportManager.progress.value),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        scope.launch {
            exportManager.progress
                .distinctUntilChangedBy { Triple(it.done, (it.fraction * 100).toInt(), it.running) }
                .collect { progress ->
                    if (!progress.running) {
                        ServiceCompat.stopForeground(this@ExportService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(progress))
                    }
                }
        }
        return START_NOT_STICKY
    }

    /**
     * Android gives a background job of this kind a few hours a day and then takes it back. A
     * vault of a couple of hundred gigabytes will not finish inside that, so the run is stopped
     * in a way that says it can be picked up: everything already copied is left in place and
     * the next run skips it.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        exportManager.pauseForTimeLimit()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(progress: ExportProgress): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, ExportService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_seal)
            .setContentTitle("Exporting files")
            .setContentText(
                if (progress.total > 0) {
                    "%,d of %,d · vault stays open until done".format(progress.done, progress.total)
                } else {
                    "Working out what to copy…"
                },
            )
            .setProgress(100, (progress.fraction * 100).toInt(), progress.total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(open)
            .addAction(0, "Cancel", cancel)
            .build()
    }

    companion object {
        private const val CHANNEL = "export"
        private const val NOTIFICATION_ID = 43
        private const val ACTION_CANCEL = "com.shahaabapps.obscura.export.CANCEL"
    }
}
