package com.v2ray.ang.ipfinder

import android.content.Context

/** Préférences isolées : ces données ne sont jamais incluses dans les profils VPN ou sauvegardes. */
class AndroidIpRuleTextStorage(context: Context) : IpRuleTextStorage {
    private val preferences = context.getSharedPreferences(
        IpFinderRulesRepository.PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    override fun read(): String? = preferences.getString(IpFinderRulesRepository.RULES_KEY, null)

    override fun write(value: String) {
        preferences.edit().putString(IpFinderRulesRepository.RULES_KEY, value).apply()
    }
}
