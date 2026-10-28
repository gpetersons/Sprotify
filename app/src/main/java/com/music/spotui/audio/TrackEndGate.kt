package com.music.spotui.audio

/** Coalesces completion and rejects the retained old stream during a new lookup. */
class TrackEndGate {
    private var notified = false
    var awaitingStream = false
        private set

    fun beginRequest() { awaitingStream = true; notified = false }
    fun streamInstalled() { awaitingStream = false; notified = false }
    // READY after a seek/repeat re-arms completion, but never ends a pending lookup.
    fun reset() { notified = false }
    fun ownsMedia(mediaId: String?, request: String): Boolean =
        !awaitingStream && request.isNotBlank() && mediaId == request

    fun take(ended: Boolean, hasMedia: Boolean, crossfading: Boolean): Boolean {
        if (awaitingStream || notified || !ended || !hasMedia || crossfading) return false
        notified = true
        return true
    }
}
