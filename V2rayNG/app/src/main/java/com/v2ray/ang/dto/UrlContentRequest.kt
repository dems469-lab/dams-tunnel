package com.v2ray.ang.dto

data class UrlContentRequest(
    val url: String?,
    val timeout: Int = 15000,
    val httpPort: Int = 0,
    val proxyUsername: String? = null,
    val proxyPassword: String? = null,
    val userAgent: String? = null,
    val requestHeaders: String? = null,
    val ifNoneMatch: String? = null,
    val ifModifiedSince: String? = null,
)

data class UrlContentResponse(
    val body: String? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val notModified: Boolean = false,
)
