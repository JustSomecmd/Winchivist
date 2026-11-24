package dev.jdtech.tubedroid.desktop.player

import dev.jdtech.jellyfin.jrwi.JrwiItem
import dev.jdtech.tubedroid.desktop.jrwi.JrwiRepository
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * A JRWI episode played straight from the archive: its audio, or its archived video when Jellyfin
 * doesn't have it. Where it got to is kept in the archive, under the version's own post, and for
 * the audio it's handed on to the video's copy when it's paused or left.
 */
internal class JrwiPlaybackSource(
    private val repository: JrwiRepository,
    private val request: PlayerRequest.Jrwi,
) : PlaybackSource {
    private var item: JrwiItem? = null
    private var lastSaved = 0L

    override suspend fun first(): PlayItem {
        val api = repository.api
        val loaded = api.getItem(request.postId)
        item = loaded
        val address =
            (if (request.audio) loaded.audio?.url else loaded.video?.url)
                ?: error("The archive has no file for this version")
        val saved = if (request.fromStart) null else repository.progress(loaded, request.audio)
        return PlayItem(
            id = "jrwi:${loaded.id}:${if (request.audio) "audio" else "video"}",
            title = loaded.title,
            subtitle = repository.showName(loaded.show),
            url = api.fileUrl(address) ?: error("The JRWI archiver isn't set up"),
            header = api.authHeader?.let { "Authorization: $it" },
            resumeSeconds = saved?.takeIf { !it.finished && it.position > 5 }?.position,
        )
    }

    override suspend fun nextTitle(current: PlayItem): String? = null

    override suspend fun next(current: PlayItem): PlayItem? = null

    override suspend fun progress(item: PlayItem, report: PlaybackReport) {
        val now = System.currentTimeMillis()
        if (!report.paused && now - lastSaved < JRWI_SAVE_INTERVAL_MS) return
        lastSaved = now
        save(report, ended = false, handOff = report.paused)
    }

    override suspend fun stopped(item: PlayItem, report: PlaybackReport) {
        save(report, ended = false, handOff = true)
    }

    override suspend fun ended(item: PlayItem) {
        save(PlaybackReport(seconds = 0.0, paused = true), ended = true, handOff = true)
    }

    private suspend fun save(report: PlaybackReport, ended: Boolean, handOff: Boolean) {
        val current = item ?: return
        if (request.audio) {
            repository.audioPlayed(current, report.seconds, report.duration, ended, handOff)
        } else {
            repository.videoPlayed(current, report.seconds, report.duration, ended)
        }
    }
}

/**
 * Keeps a JRWI episode's place in the archive as well while its video plays from Jellyfin or
 * TubeArchivist, wherever it was started from. [find] gives the archive's item of what plays, or
 * null when it isn't one of the archive's. The archive's position is the one it starts from, being
 * the newest, as every app and the web app save there.
 */
internal class JrwiLinkedSource(
    private val inner: PlaybackSource,
    private val repository: JrwiRepository,
    private val fromStart: Boolean,
    private val find: suspend (PlayItem) -> JrwiItem?,
) : PlaybackSource by inner {
    private val items = ConcurrentHashMap<String, JrwiItem>()
    private var lastSaved = 0L

    override suspend fun first(): PlayItem = link(inner.first(), resume = !fromStart)

    override suspend fun next(current: PlayItem): PlayItem? =
        inner.next(current)?.let { link(it, resume = false) }

    override suspend fun playlistItem(videoId: String): PlayItem? =
        inner.playlistItem(videoId)?.let { link(it, resume = true) }

    private suspend fun link(play: PlayItem, resume: Boolean): PlayItem {
        val item =
            try {
                find(play)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: return play
        items[play.id] = item
        if (!resume) return play
        val saved = repository.progress(item, audio = false) ?: return play
        return if (!saved.finished && saved.position > 5) {
            play.copy(resumeSeconds = saved.position)
        } else {
            play
        }
    }

    override suspend fun progress(item: PlayItem, report: PlaybackReport) {
        inner.progress(item, report)
        archive(item, report, always = report.paused)
    }

    override suspend fun stopped(item: PlayItem, report: PlaybackReport) {
        inner.stopped(item, report)
        archive(item, report, always = true)
    }

    override suspend fun ended(item: PlayItem) {
        inner.ended(item)
        items[item.id]?.let { repository.videoPlayed(it, 0.0, null, ended = true) }
    }

    private suspend fun archive(play: PlayItem, report: PlaybackReport, always: Boolean) {
        val item = items[play.id] ?: return
        val now = System.currentTimeMillis()
        if (!always && now - lastSaved < JRWI_SAVE_INTERVAL_MS) return
        lastSaved = now
        val nowWatched =
            repository.videoPlayed(item, report.seconds, report.duration, ended = false)
        // The archive decides when an episode counts as watched; the video's own copy follows
        if (nowWatched) repository.markVideoPlayed(item)
    }
}

// How often a playing episode's place is saved in the archive, as the phone app does
private const val JRWI_SAVE_INTERVAL_MS = 15_000L
