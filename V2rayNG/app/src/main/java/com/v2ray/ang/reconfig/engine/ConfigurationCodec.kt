package com.v2ray.ang.reconfig.engine

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.fmt.TrojanFmt
import com.v2ray.ang.fmt.VlessFmt
import com.v2ray.ang.fmt.VmessFmt

/**
 * Sérialisation d'un profil vers son URI de partage et retour.
 *
 * Le module ne réimplémente pas les parseurs du projet : il délègue aux
 * formateurs existants (`VlessFmt`, `VmessFmt`, `TrojanFmt`), qui gèrent déjà le
 * décodage Base64 et le JSON VMess ainsi que la conservation des paramètres.
 */
internal interface ConfigurationCodec {
    fun encode(profile: ProfileItem): String?

    fun decode(value: String): ProfileItem?
}

internal object AppConfigurationCodec : ConfigurationCodec {
    override fun encode(profile: ProfileItem): String? = runCatching {
        when (profile.configType) {
            EConfigType.VLESS -> VlessFmt.toUri(profile)
            EConfigType.VMESS -> VmessFmt.toUri(profile)
            EConfigType.TROJAN -> TrojanFmt.toUri(profile)
            else -> null
        }
    }.getOrNull()

    override fun decode(value: String): ProfileItem? = runCatching {
        when {
            value.startsWith(EConfigType.VLESS.protocolScheme) -> VlessFmt.parse(value)
            value.startsWith(EConfigType.VMESS.protocolScheme) -> VmessFmt.parse(value)
            value.startsWith(EConfigType.TROJAN.protocolScheme) -> TrojanFmt.parse(value)
            else -> null
        }
    }.getOrNull()
}
