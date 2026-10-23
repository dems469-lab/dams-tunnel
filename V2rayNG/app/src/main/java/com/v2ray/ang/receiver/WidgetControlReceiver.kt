package com.v2ray.ang.receiver

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.core.LauncherManager

/** Receives only app-internal widget taps and service-state updates. */
class WidgetControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AppConfig.BROADCAST_ACTION_WIDGET_CLICK -> {
                if (CoreServiceManager.isRunning()) {
                    LauncherManager.stopService(context)
                } else {
                    LauncherManager.startServiceFromToggle(context)
                }
            }

            AppConfig.BROADCAST_ACTION_WIDGET_FAVORITE_CLICK -> {
                if (!CoreServiceManager.isRunning()) LauncherManager.startServiceFromToggle(context)
            }

            AppConfig.BROADCAST_ACTION_ACTIVITY -> {
                val running = when (intent.getIntExtra("key", 0)) {
                    AppConfig.MSG_STATE_RUNNING, AppConfig.MSG_STATE_START_SUCCESS -> true
                    AppConfig.MSG_STATE_NOT_RUNNING,
                    AppConfig.MSG_STATE_START_FAILURE,
                    AppConfig.MSG_STATE_STOP_SUCCESS -> false
                    else -> return
                }
                val manager = AppWidgetManager.getInstance(context)
                val widgetIds = manager.getAppWidgetIds(ComponentName(context, WidgetProvider::class.java))
                WidgetProvider().updateWidgetBackground(context, manager, widgetIds, running)
                FavoriteWidgetProvider.refresh(context)
            }
        }
    }
}
