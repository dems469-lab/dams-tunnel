package com.v2ray.ang.ipfinder

import android.content.Context

class AndroidIpFinderLoopSettingsStorage(context: Context) : IpFinderLoopSettingsStorage {
    private val preferences = context.getSharedPreferences(IpFinderRulesRepository.PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun read(key: String): String? = preferences.getString(key, null)

    override fun write(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }
}
