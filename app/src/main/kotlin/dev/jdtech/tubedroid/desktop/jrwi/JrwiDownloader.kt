package dev.jdtech.tubedroid.desktop.jrwi

import dev.jdtech.jellyfin.jrwi.JrwiItem
import dev.jdtech.tubedroid.desktop.AppGraph
import dev.jdtech.tubedroid.desktop.Downloads
import dev.jdtech.tubedroid.desktop.LocalDownload
import dev.jdtech.tubedroid.desktop.Route
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** What a page's download would save: how many of its episodes have audio, and a video. */
data class JrwiPageContents(val items: List<JrwiItem>) {
    val audio: Int
        get() = items.count { it.audio != null }

    val video: Int
        get() = items.count { it.video != null }
}

/**
 * Saves the archive's episodes to this computer: the audio from the archiver, an archived video
 * from the archiver too, a video that's only on YouTube from TubeArchivist, and the post's own
 * files next to them when asked. They go to the downloads folder, under JRWI and the show's name,
 * and nothing is kept track of afterwards.
 */
class JrwiDownloader(private val app: AppGraph) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Where an episode's own file would be saved, for showing how its download is doing. */
    fun downloadOf(item: JrwiItem, audio: Boolean): LocalDownload? {
        if (!audio && item.video?.isYoutube == true) {
            return item.video?.youtubeId?.let { app.downloads.stateOf(it) }
        }
        val address = (if (audio) item.audio?.url else item.video?.url) ?: return null
        return jrwiDownloadId(app, address)?.let { app.downloads.stateOf(it) }
    }

    /** Stops an episode's download. */
    fun cancel(item: JrwiItem, audio: Boolean) {
        downloadOf(item, audio)?.let { app.downloads.cancel(it.id) }
    }

    /**
     * Saves the item's audio, or with [audio] false its video, and with [withFiles] the files of
     * all its posts next to it. Returns the folder it goes to, or null when there's nothing to
     * save.
     */
    fun download(item: JrwiItem, audio: Boolean, withFiles: Boolean, showName: String): File? {
        val folder = jrwiDownloadFolder(app, showName)
        val base = "${Downloads.safeName(item.title).take(110)} [jrwi-${item.id}]"
        val route = Route.JrwiPost(item.id)
        val started =
            if (audio) {
                val version = item.audio ?: return null
                saveJrwiFile(
                    app = app,
                    name = "$base.${audioExtension(version.mime)}",
                    address = version.url,
                    folder = folder,
                    title = item.title,
                    route = route,
                ) != null
            } else {
                val version = item.video ?: return null
                val youtubeId = version.youtubeId
                val address = version.url
                when {
                    version.isYoutube && youtubeId != null && app.api.isConfigured -> {
                        app.downloads.startById(youtubeId)
                        true
                    }
                    address != null -> {
                        // Under the name the archiver filed it by, which Jellyfin knows it as
                        val filed = version.jellyfinPath?.substringAfterLast('/')
                        saveJrwiFile(
                            app = app,
                            name = filed?.takeIf { it.isNotBlank() } ?: "$base.mp4",
                            address = address,
                            folder = folder,
                            title = item.title,
                            route = route,
                        ) != null
                    }
                    else -> false
                }
            }
        if (!started) return null
        if (withFiles) saveFiles(item, File(folder, "$base files"))
        return folder
    }

    /**
     * Saves every episode of a page, oldest first. Returns how many were added, leaving out those
     * without that version.
     */
    fun downloadAll(
        contents: JrwiPageContents,
        audio: Boolean,
        withFiles: Boolean,
        showName: String,
    ): Int = contents.items.count { item -> download(item, audio, withFiles, showName) != null }

    /** Reads every item of a show's page, oldest first, leaving out what the tier can't see. */
    suspend fun pageContents(showId: String, tab: String, season: Int?): JrwiPageContents {
        val items = mutableListOf<JrwiItem>()
        var page = 1
        while (page <= MAX_PAGES) {
            val next =
                app.jrwi.api.getShowItems(
                    showId = showId,
                    tab = tab,
                    season = season,
                    page = page,
                    order = "asc",
                    pageSize = 200,
                )
            items += next.items
            if (!next.hasMore || next.items.isEmpty()) break
            page += 1
        }
        return JrwiPageContents(items.filter { !it.locked }.distinctBy { it.id })
    }

    /**
     * Fetches a file of the archive and opens it in the program Windows opens such files with,
     * saying so through [onError] when that doesn't work.
     */
    fun openElsewhere(name: String, address: String, onError: (String) -> Unit) {
        scope.launch {
            try {
                openJrwiFileElsewhere(app, name, address)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError("Couldn't open $name: ${e.message}")
            }
        }
    }

    /** The files of all an item's posts, apart from the episode itself, into [folder]. */
    private fun saveFiles(item: JrwiItem, folder: File) {
        scope.launch {
            try {
                app.jrwi.api
                    .getItemFiles(item.id)
                    .filter {
                        it.saved &&
                            it.url != null &&
                            it.role != "video" &&
                            it.role != "audio" &&
                            it.role != "thumbnail"
                    }
                    .forEach { file ->
                        saveJrwiFile(
                            app = app,
                            name = file.name.ifBlank { pictureSaveName(file.url.orEmpty()) },
                            address = file.url.orEmpty(),
                            folder = folder,
                            title = "${file.name} · ${item.title}",
                            route = Route.JrwiPost(item.id),
                        )
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                System.err.println("Couldn't list the files of ${item.id}: $e")
            }
        }
    }

    private fun audioExtension(mime: String?): String =
        when (mime?.substringBefore(';')?.trim()?.lowercase()) {
            "audio/mpeg" -> "mp3"
            "audio/ogg" -> "ogg"
            "audio/opus" -> "opus"
            "audio/flac" -> "flac"
            "audio/wav",
            "audio/x-wav" -> "wav"
            else -> "m4a"
        }

    private companion object {
        const val MAX_PAGES = 20
    }
}
