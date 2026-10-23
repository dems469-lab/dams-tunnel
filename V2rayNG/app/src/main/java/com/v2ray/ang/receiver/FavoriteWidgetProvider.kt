package com.v2ray.ang.receiver

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.handler.MmkvManager

class FavoriteWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.widget_favorite)
        val selected = MmkvManager.getSelectServer()
            ?.let(MmkvManager::decodeServerConfig)
            ?.remarks
            ?: context.getString(R.string.widget_no_favorite)
        views.setTextViewText(R.id.widget_favorite_name, selected)
        val intent = Intent(context, WidgetControlReceiver::class.java).apply {
            action = AppConfig.BROADCAST_ACTION_WIDGET_FAVORITE_CLICK
            setPackage(context.packageName)
        }
        views.setOnClickPendingIntent(
            R.id.widget_favorite_root,
            PendingIntent.getBroadcast(
                context,
                R.id.widget_favorite_root,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    companion object {
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, FavoriteWidgetProvider::class.java))
            if (ids.isNotEmpty()) FavoriteWidgetProvider().onUpdate(context, manager, ids)
        }
    }
}
