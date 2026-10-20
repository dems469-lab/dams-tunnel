package com.v2ray.ang.ipfinder

/** Stockage abstrait propre aux règles IP Finder, indépendant des profils VPN. */
interface IpRuleTextStorage {
    fun read(): String?
    fun write(value: String)
}

class IpFinderRulesRepository(private val storage: IpRuleTextStorage) {
    fun load(): String = storage.read() ?: DEFAULT_RULES.also(storage::write)

    fun save(value: String) = storage.write(value)

    fun reset(): String = DEFAULT_RULES.also(storage::write)

    fun prefixUnmarkedRules(value: String): String {
        return IpRuleTextParser.prefixValidUnmarked(value)
    }

    fun prefixAndSave(value: String): String = prefixUnmarkedRules(value).also(storage::write)

    companion object {
        const val PREFERENCES_NAME = "ip_finder_rules"
        const val RULES_KEY = "rules_text"

        const val DEFAULT_RULES = "10.204;10.205;10.206;10.207;10.208;10.209;10.210;10.212;10.213;10.214;10.215;10.216;10.217;10.218;10.219;10.220;10.221;10.222;10.223;10.224;10.225;10.226;10.227;10.228;10.229;10.230;10.143;10.165;10.193;10.77;10.63;10.165;10.67"
    }
}
