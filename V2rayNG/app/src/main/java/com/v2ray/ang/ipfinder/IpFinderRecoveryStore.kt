package com.v2ray.ang.ipfinder

import android.content.Context
import android.provider.Settings

object IpFinderRecoveryStore : IpFinderRecoveryFlag {
    private const val PREFERENCES = "ip_finder_recovery"
    private const val KEY_RESTORE_PENDING = "airplane_restore_pending"

    private lateinit var applicationContext: Context

    fun initialize(context: Context) {
        applicationContext = context.applicationContext
    }

    override fun isPending(): Boolean = preferences().getBoolean(KEY_RESTORE_PENDING, false)

    override fun markPending(): Boolean =
        preferences().edit().putBoolean(KEY_RESTORE_PENDING, true).commit()

    override fun clear() {
        preferences().edit().remove(KEY_RESTORE_PENDING).commit()
    }

    fun isAirplaneModeEnabled(context: Context): Boolean =
        Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1

    private fun preferences() = applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
