package com.shahaabapps.obscura.importer

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

/**
 * Keeps the app alive while an import runs, with a progress notification.
 * The notification never shows file names: it would leak them to the lock screen.
 */
@AndroidEntryPoint
class ImportService : Service() {

    @Inject
    lateinit var importManager: ImportManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Importing files", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress while files are encrypted into the vault"
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            importManager.cancel()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(importManager.progress.value),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        scope.launch {
            importManager.progress
                .distinctUntilChangedBy { Triple(it.processed, (it.fraction * 100).toInt(), it.running) }
                .collect { progress ->
                    if (!progress.running) {
                        ServiceCompat.stopForeground(this@ImportService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(progress))
                    }
                }
        }
        return START_NOT_STICKY
    }

    /** Android 15+ limits data-sync services to 6 hours a day; stop cleanly if we hit it. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        importManager.cancel()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(progress: ImportProgress): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, ImportService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_seal)
            .setContentTitle("Sealing files")
            .setContentText("%,d of %,d · vault stays open until done".format(progress.processed, progress.total))
            .setProgress(100, (progress.fraction * 100).toInt(), progress.total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(open)
            .addAction(0, "Cancel", cancel)
            .build()
    }

    companion object {
        private const val CHANNEL = "import"
        private const val NOTIFICATION_ID = 41
        private const val ACTION_CANCEL = "com.shahaabapps.obscura.import.CANCEL"
    }
}
