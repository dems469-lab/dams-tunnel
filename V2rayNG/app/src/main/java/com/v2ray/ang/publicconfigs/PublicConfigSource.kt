package com.v2ray.ang.publicconfigs

data class PublicConfigSource(
    val id: String,
    val name: String,
    val description: String,
    val url: String,
    val protocols: Set<String>,
)

object PublicConfigSources {
    val all: List<PublicConfigSource> = listOf(
        PublicConfigSource("barry-all", "Barry-far · Tout-en-un", "Sources mélangées", "https://raw.githubusercontent.com/barry-far/V2ray-config/main/All_Configs_Sub.txt", setOf("VLESS", "VMess", "Trojan", "SS")),
        PublicConfigSource("mohammad-all", "Mohammadaz · Tout-en-un", "Sources mélangées", "https://raw.githubusercontent.com/mohammadaz2/v2rayConfigsForYou/main/configs.txt", setOf("VLESS", "VMess", "Trojan", "SS")),
        PublicConfigSource("epodonios-all", "Epodonios · Tout-en-un", "Sources mélangées", "https://raw.githubusercontent.com/Epodonios/v2ray-configs/main/All_Configs_Sub.txt", setOf("VLESS", "VMess", "Trojan", "SS")),
        PublicConfigSource("roosterkid-all", "Roosterkid · Tout-en-un", "Sources mélangées", "https://raw.githubusercontent.com/roosterkid/openproxylist/refs/heads/main/V2RAY_RAW.txt", setOf("VLESS", "VMess", "Trojan", "SS")),
        PublicConfigSource("mohammad-vless", "Mohammadaz · VLESS", "VLESS", "https://raw.githubusercontent.com/mohammadaz2/v2rayConfigsForYou/main/sub/vless.txt", setOf("VLESS")),
        PublicConfigSource("mohammad-vmess", "Mohammadaz · VMess", "VMess", "https://raw.githubusercontent.com/mohammadaz2/v2rayConfigsForYou/main/sub/vmess.txt", setOf("VMess")),
        PublicConfigSource("mohammad-trojan", "Mohammadaz · Trojan", "Trojan", "https://raw.githubusercontent.com/mohammadaz2/v2rayConfigsForYou/main/sub/trojan.txt", setOf("Trojan")),
        PublicConfigSource("mohammad-ss", "Mohammadaz · Shadowsocks", "Shadowsocks", "https://raw.githubusercontent.com/mohammadaz2/v2rayConfigsForYou/main/sub/ss.txt", setOf("SS")),
        PublicConfigSource("barry-fresh", "Barry-far · Actualisé", "Mise à jour fréquente", "https://raw.githubusercontent.com/barry-far/V2ray-config/main/Sub1.txt", setOf("VLESS", "VMess", "Trojan", "SS")),
        PublicConfigSource("epodonios-base64", "Epodonios · Base64", "Base64", "https://raw.githubusercontent.com/Epodonios/v2ray-configs/raw/main/All_Configs_base64_Sub.txt", setOf("VLESS", "VMess", "Trojan", "SS")),
        PublicConfigSource("solispirit-all", "SoliSpirit · Tout-en-un", "Mise à jour fréquente", "https://raw.githubusercontent.com/SoliSpirit/v2ray-configs/main/all_configs.txt", setOf("VLESS", "VMess", "Trojan", "SS")),
        PublicConfigSource("surfboard-mixed", "Surfboardv2ray · Mixte", "VLESS, VMess, Trojan, Hysteria2 et TUIC", "https://raw.githubusercontent.com/Surfboardv2ray/TGParse/main/splitted/mixed", setOf("VLESS", "VMess", "Trojan", "Hysteria2", "TUIC")),
    )

    fun find(id: String): PublicConfigSource? = all.firstOrNull { it.id == id }

    fun isAllowed(source: PublicConfigSource): Boolean = all.any {
        it.id == source.id && it.url == source.url
    }
}
