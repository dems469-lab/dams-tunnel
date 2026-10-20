package com.v2ray.ang.ipfinder

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.v2ray.ang.R
import com.v2ray.ang.enums.NotificationChannelType
import com.v2ray.ang.helper.NotificationHelper

/** Keeps an active search visible and awake while the user temporarily backgrounds the app. */
class IpFinderSearchService : Service() {
    private var searchWakeLock: PowerManager.WakeLock? = null
    private val stopAction by lazy {
        val intent = Intent(this, IpFinderSearchService::class.java).setAction(ACTION_STOP)
        val pendingIntent = PendingIntent.getService(
            this,
            NotificationChannelType.IP_FINDER_SEARCH.notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        NotificationCompat.Action.Builder(R.drawable.ic_stop_24dp, getString(R.string.ip_finder_stop), pendingIntent).build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            sendBroadcast(Intent(ACTION_CANCEL_SEARCH).setPackage(packageName))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        NotificationHelper.startForeground(
            this,
            NotificationChannelType.IP_FINDER_SEARCH,
            getString(R.string.ip_finder_notification_title),
            getString(R.string.ip_finder_notification_running),
            stopAction
        )
        acquireSearchWakeLock()
        return START_NOT_STICKY
    }

    private fun acquireSearchWakeLock() {
        if (searchWakeLock?.isHeld == true) return
        val powerManager = getSystemService(PowerManager::class.java) ?: return
        searchWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:IpFinderSearch").apply {
            setReferenceCounted(false)
            // Upper bound: 1,000 cycles at 60 seconds plus 999 pauses of 120 seconds.
            acquire(MAX_SEARCH_DURATION_MILLIS)
        }
    }

    private fun releaseSearchWakeLock() {
        searchWakeLock?.let { wakeLock ->
            if (wakeLock.isHeld) wakeLock.release()
        }
        searchWakeLock = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseSearchWakeLock()
        NotificationHelper.stopForeground(this)
        super.onDestroy()
    }

    companion object {
        private const val MAX_SEARCH_DURATION_MILLIS =
            1_000L * IpFinderSearchCoordinator.CYCLE_TIMEOUT_MILLIS + 999L * 120_000L
        const val ACTION_STOP = "com.v2ray.ang.ipfinder.STOP_SEARCH"
        const val ACTION_CANCEL_SEARCH = "com.v2ray.ang.ipfinder.CANCEL_SEARCH"
    }
}
