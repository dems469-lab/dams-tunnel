package com.v2ray.ang.util

import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType

/** Plaintext payload placed inside the authenticated/encrypted .dams container for one profile. */
object DamsProfileFileCodec {
    private const val MAGIC = "DAMS-PROFILE/1\n"
    private val gson = Gson()

    data class ProfilePayload(
        val profile: ProfileItem,
        val rawConfig: String? = null,
    )

    class UnsupportedStandaloneProfileException : IllegalArgumentException()

    /** Encodes exactly one standalone profile; subscription and group metadata are not exported. */
    fun encode(profile: ProfileItem, rawConfig: String? = null): ByteArray {
        if (profile.configType == EConfigType.POLICYGROUP || profile.configType == EConfigType.PROXYCHAIN ||
            (profile.configType == EConfigType.CUSTOM && rawConfig.isNullOrBlank())
        ) {
            throw UnsupportedStandaloneProfileException()
        }
        val standalone = profile.copy(subscriptionId = "", policyGroupSubscriptionId = null)
        val json = gson.toJson(ProfilePayload(standalone, rawConfig))
        val payload = (MAGIC + json).toByteArray(Charsets.UTF_8)
        checkBackupFileSize(payload.size.toLong())
        return payload
    }

    /** Returns null for a full-backup payload; malformed profile payloads fail closed. */
    fun decode(payload: ByteArray): ProfilePayload? {
        val text = payload.toString(Charsets.UTF_8)
        if (!text.startsWith(MAGIC)) return null
        return try {
            val json = JsonParser.parseString(text.removePrefix(MAGIC))
            if (!json.isJsonObject || !json.asJsonObject.has("profile") ||
                !json.asJsonObject.get("profile").isJsonObject
            ) {
                throw DamsFileCodec.InvalidFileException("Invalid profile file")
            }
            val profileJson = json.asJsonObject.getAsJsonObject("profile")
            val configType = profileJson.get("configType")
            if (configType == null || !configType.isJsonPrimitive || !configType.asJsonPrimitive.isString ||
                EConfigType.entries.none { it.name == configType.asString }
            ) {
                throw DamsFileCodec.InvalidFileException("Invalid profile file")
            }
            val decoded = gson.fromJson(json, ProfilePayload::class.java)
                ?: throw DamsFileCodec.InvalidFileException("Invalid profile file")
            if (decoded.profile.configType == EConfigType.POLICYGROUP ||
                decoded.profile.configType == EConfigType.PROXYCHAIN ||
                (decoded.profile.configType == EConfigType.CUSTOM && decoded.rawConfig.isNullOrBlank())
            ) {
                throw UnsupportedStandaloneProfileException()
            }
            decoded.copy(profile = decoded.profile.copy(subscriptionId = "", policyGroupSubscriptionId = null))
        } catch (error: DamsFileCodec.InvalidFileException) {
            throw error
        } catch (error: Exception) {
            if (error is JsonParseException || error is IllegalArgumentException) {
                throw DamsFileCodec.InvalidFileException("Invalid profile file", error)
            }
            throw DamsFileCodec.InvalidFileException("Invalid profile file", error)
        }
    }
}
