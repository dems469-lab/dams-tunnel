package com.v2ray.ang.ipfinder

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.v2ray.ang.R
import com.v2ray.ang.enums.NotificationChannelType
import com.v2ray.ang.helper.NotificationHelper

/** Keeps an active user-started IP Finder search visible and cancellable. */
class IpFinderSearchService : Service() {
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
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        NotificationHelper.stopForeground(this)
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.v2ray.ang.ipfinder.STOP_SEARCH"
        const val ACTION_CANCEL_SEARCH = "com.v2ray.ang.ipfinder.CANCEL_SEARCH"
    }
}
