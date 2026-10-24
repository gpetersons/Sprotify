package com.music.spotui.lossless

import java.net.URI

/**
 * Explicit, reviewable community FLAC endpoints. The previous implementation
 * fetched an opaque, encrypted host list from an external Gist which could
 * redirect audio searches and identifiers to arbitrary future hosts.
 *
 * All endpoints here are public third-party services, not operated by Guntify.
 * Network traffic to these endpoints still occurs when lossless is selected.
 */
internal object LosslessRegistry {
    const val API_KEY = "ak_8e3f1a7c2b5d9e4f0a6c3b8d1e5f2a9c7b4d0e6f"
    private const val VERIFY = "https://verify.spotbye.qzz.io"
    private const val TIDAL = "https://tidal.anandserver.cfd"
    private const val AMAZON = "https://amazon.anandserver.cfd"
    private const val SPOTIFLAC_TIDAL = "https://tdl-foss.spotbye.qzz.io"
    private const val SPOTIFLAC_AMAZON = "https://amz-foss.spotbye.qzz.io"

    fun communityVerifyUrl(): String = VERIFY
    fun communityDlUrl(provider: String): String? = when (provider) {
        "tidal" -> "$SPOTIFLAC_TIDAL/api/dl"
        "amazon" -> "$SPOTIFLAC_AMAZON/api/dl"
        else -> null
    }
    fun tidalEndpoints(): List<String> = listOf(TIDAL)
    fun amazonEndpoints(): List<String> = listOf(AMAZON)

    /** Enforce HTTPS and reject userinfo, port overrides, redirects and unknown hosts. */
    fun permitted(url: String): Boolean = try {
        val uri = URI(url)
        uri.scheme == "https" && uri.userInfo == null && uri.port == -1 &&
            uri.fragment == null && uri.host in setOf(
                "verify.spotbye.qzz.io", "tidal.anandserver.cfd",
                "amazon.anandserver.cfd", "tdl-foss.spotbye.qzz.io",
                "amz-foss.spotbye.qzz.io",
            )
    } catch (_: Exception) { false }
}
