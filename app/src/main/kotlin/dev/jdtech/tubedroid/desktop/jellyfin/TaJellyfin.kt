package dev.jdtech.tubedroid.desktop.jellyfin

import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind

/** Name the TubeArchivist Jellyfin plugin stores its IDs under. */
private const val PROVIDER_NAME = "TubeArchivist"

// TubeArchivist keeps each video as <channel ID>/<video ID>.mp4
private val VIDEO_FILE =
    Regex("""(?:^|[/\\])UC[A-Za-z0-9_-]{22}[/\\]([A-Za-z0-9_-]{11})\.[A-Za-z0-9]+$""")
private val CHANNEL_FOLDER = Regex("""(?:^|[/\\])(UC[A-Za-z0-9_-]{22})[/\\]?$""")

/**
 * The TubeArchivist ID of a Jellyfin item, as the phone app reads it: the video ID of an episode or
 * the channel ID of a show. From the plugin's provider ID, or else from the file's name, which
 * TubeArchivist sets to the video ID (and the folder's to the channel ID). Null for anything that
 * isn't TubeArchivist's.
 */
fun BaseItemDto.tubeArchivistId(): String? {
    providerIds
        ?.entries
        ?.firstOrNull { it.key.equals(PROVIDER_NAME, ignoreCase = true) }
        ?.value
        ?.takeIf { it.isNotBlank() }
        ?.let {
            return it
        }
    val itemPath = path ?: mediaSources?.firstOrNull()?.path ?: return null
    return when (type) {
        BaseItemKind.EPISODE,
        BaseItemKind.VIDEO,
        BaseItemKind.MOVIE -> VIDEO_FILE.find(itemPath)?.groupValues?.get(1)
        BaseItemKind.SERIES -> CHANNEL_FOLDER.find(itemPath)?.groupValues?.get(1)
        else -> null
    }
}
