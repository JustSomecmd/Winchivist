package dev.jdtech.tubedroid.desktop.player

/**
 * How Jellyfin's videos are streamed: the file as it is, or converted by Jellyfin to at most a size
 * and bitrate, for a slow connection. The same steps as the phone app's.
 */
enum class StreamQuality(
    /** Kept in the settings. */
    val key: String,
    val maxWidth: Int,
    val maxHeight: Int,
    val maxBitrate: Int,
) {
    ORIGINAL("original", 0, 0, 0),
    P1080("1080p", 1920, 1080, 8_000_000),
    P720("720p", 1280, 720, 4_000_000),
    P480("480p", 854, 480, 1_500_000),
    P360("360p", 640, 360, 800_000);

    /** Such as "720p · 4 Mbps". */
    val label: String
        get() =
            when {
                this == ORIGINAL -> "Original"
                maxBitrate >= 1_000_000 -> "$key · ${rate(maxBitrate / 1_000_000.0)} Mbps"
                else -> "$key · ${maxBitrate / 1000} kbps"
            }

    private fun rate(mbps: Double): String =
        if (mbps % 1.0 == 0.0) mbps.toInt().toString() else mbps.toString()

    companion object {
        fun fromKey(key: String?): StreamQuality = entries.firstOrNull { it.key == key } ?: ORIGINAL
    }
}
