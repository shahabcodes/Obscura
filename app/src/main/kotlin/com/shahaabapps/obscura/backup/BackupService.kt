package com.shahaabapps.obscura.backup

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

/** Keeps the app alive while a backup or restore runs. Like imports, it never shows file names. */
@AndroidEntryPoint
class BackupService : Service() {

    @Inject
    lateinit var backupManager: BackupManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Backup and restore", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress while the encrypted vault is copied"
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(backupManager.state.value),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        scope.launch {
            backupManager.state
                .distinctUntilChangedBy { (it.progress.fraction * 100).toInt() to it.running }
                .collect { state ->
                    if (state.running == null) {
                        ServiceCompat.stopForeground(this@BackupService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(state))
                    }
                }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(state: BackupState): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val restoring = state.running == BackupJob.RESTORE
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_seal)
            .setContentTitle(if (restoring) "Restoring vault" else "Backing up vault")
            .setContentText("%d%% · keep the drive connected".format((state.progress.fraction * 100).toInt()))
            .setProgress(100, (state.progress.fraction * 100).toInt(), state.progress.filesTotal == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val CHANNEL = "backup"
        private const val NOTIFICATION_ID = 42
    }
}
