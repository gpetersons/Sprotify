package com.music.spotui.soulseek

/** Both runtime permission and user preference must opt in before connecting to peers. */
internal object SoulseekPrivacyPolicy {
    fun enabled(runtime: Boolean, userOptIn: Boolean): Boolean = runtime && userOptIn
}
