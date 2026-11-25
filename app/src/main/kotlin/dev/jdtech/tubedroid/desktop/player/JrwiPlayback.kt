package dev.jdtech.tubedroid.desktop.player

import dev.jdtech.jellyfin.jrwi.JrwiItem
import dev.jdtech.tubedroid.desktop.AppGraph
import dev.jdtech.tubedroid.desktop.Route
import dev.jdtech.tubedroid.desktop.jrwi.JrwiMatch
import dev.jdtech.tubedroid.desktop.jrwi.JrwiRepository
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * A JRWI episode played straight from the archive: its audio, or its archived video when Jellyfin
 * doesn't have it. Where it got to is kept in the archive, under the version's own post, and for
 * the audio it's handed on to the video's copy when it's paused or left. The audio goes on to the
 * next episode of its show.
 */
internal class JrwiPlaybackSource(
    private val repository: JrwiRepository,
    private val request: PlayerRequest.Jrwi,
) : PlaybackSource {
    // Each thing played with its episode, as a save can come in after the next one has started
    private val items = ConcurrentHashMap<String, JrwiItem>()
    private var lastSaved = 0L

    // The archive's time on each thing's place when it last took this player's save
    private val told = ConcurrentHashMap<String, String>()

    // The episode after the one playing, looked up once: its ID, then what follows it, if anything
    private var following: Pair<Long, JrwiItem?>? = null

    override suspend fun first(): PlayItem {
        val item = repository.api.getItem(request.postId)
        return play(item, fromStart = request.fromStart, startAt = request.startSeconds)
    }

    private suspend fun play(item: JrwiItem, fromStart: Boolean, startAt: Double?): PlayItem {
        val api = repository.api
        val audio = request.audio
        val address =
            (if (audio) item.audio?.url else item.video?.url)
                ?: error("The archive has no file for this version")
        val resume =
            when {
                startAt != null -> startAt.takeIf { it > 1 }
                fromStart -> null
                else ->
                    repository
                        .progress(item, audio)
                        ?.takeIf { !it.finished && it.position > 5 }
                        ?.position
            }
        val postId = (if (audio) item.audio?.postId else item.video?.postId) ?: item.id
        val id = "jrwi:${item.id}:${if (audio) "audio" else "video"}"
        items[id] = item
        return PlayItem(
            id = id,
            title = item.title,
            subtitle = repository.showName(item.show),
            url = api.fileUrl(address) ?: error("The JRWI archiver isn't set up"),
            header = api.authHeader?.let { "Authorization: $it" },
            resumeSeconds = resume,
            picture = api.fileUrl(item.thumbnail),
            route = Route.JrwiPost(postId),
            jrwi = item,
            jrwiAudio = audio,
            transcriptPost = item.transcriptPost(audio),
        )
    }

    /**
     * The episode after [current] in its show and tab, as the show's page lists it. Only the audio
     * goes on, and only to an episode that has audio: it stops before one without.
     */
    private suspend fun nextEpisode(current: PlayItem): JrwiItem? {
        if (!request.audio) return null
        val item = items[current.id] ?: return null
        following?.let { (id, next) -> if (id == item.id) return next }
        val next = repository.nextInShow(item)?.takeIf { it.audio != null && !it.locked }
        following = item.id to next
        return next
    }

    override suspend fun nextTitle(current: PlayItem): String? = nextEpisode(current)?.title

    override suspend fun upNext(current: PlayItem): UpNext? =
        nextEpisode(current)?.let { UpNext(it.title, repository.api.fileUrl(it.thumbnail)) }

    override suspend fun next(current: PlayItem): PlayItem? {
        val next = nextEpisode(current) ?: return null
        // From where it was left, or else from its start
        return play(next, fromStart = false, startAt = null)
    }

    override suspend fun progress(item: PlayItem, report: PlaybackReport) {
        val now = System.currentTimeMillis()
        if (!report.paused && now - lastSaved < JRWI_SAVE_INTERVAL_MS) return
        lastSaved = now
        save(item, report, ended = false, handOff = report.paused)
    }

    override suspend fun stopped(item: PlayItem, report: PlaybackReport) {
        save(item, report, ended = false, handOff = true)
    }

    override suspend fun ended(item: PlayItem) {
        save(item, PlaybackReport(seconds = 0.0, paused = true), ended = true, handOff = true)
    }

    private suspend fun save(
        play: PlayItem,
        report: PlaybackReport,
        ended: Boolean,
        handOff: Boolean,
    ) {
        val item = items[play.id] ?: return
        if (request.audio) {
            repository.audioPlayed(item, report.seconds, report.duration, ended, handOff)
        } else {
            repository.videoPlayed(item, report.seconds, report.duration, ended)
        }
        told.noteSave(play.id, repository, item)
    }

    override suspend fun newerPlace(item: PlayItem): Double? {
        val episode = items[item.id] ?: return null
        return archiveMovedOn(repository, episode, request.audio, told, item.id)
    }
}

// Remembers the archive's time on [item]'s place after this player's save, or forgets it when the
// save hasn't got through: it then can't be told from another device's
private fun MutableMap<String, String>.noteSave(
    key: String,
    repository: JrwiRepository,
    item: JrwiItem,
) {
    val at = repository.keptFor(item)?.updatedAt
    if (at != null) put(key, at) else remove(key)
}

/**
 * Where the archive has [episode] now, in the timeline of its video or, with [audio], its audio,
 * when something other than this player saved that: the archive's time on it is then another than
 * the one it gave this player's last save, kept in [told] under [key]. Times are compared rather
 * than places, so it needs no margin and not this PC's clock. Null when nothing else saved since,
 * when this player hasn't saved yet, when the archive counts the episode as finished or has it at
 * its start, and when the archive can't be reached.
 */
private suspend fun archiveMovedOn(
    repository: JrwiRepository,
    episode: JrwiItem,
    audio: Boolean,
    told: MutableMap<String, String>,
    key: String,
): Double? {
    val mine = told[key] ?: return null
    val theirs = repository.progress(episode, audio) ?: return null
    val at = theirs.updatedAt ?: return null
    if (at == mine) return null
    // Known from here on, so it's gone to once
    told[key] = at
    return theirs.position.takeIf { !theirs.finished && it > 0 }
}

/**
 * Keeps a JRWI episode's place in the archive as well while its video plays from Jellyfin or
 * TubeArchivist, wherever it was started from. [find] gives what the archive has for what plays, or
 * null when it isn't one of the archive's. With [resume], it starts from the archive's position,
 * being the newest, as every app and the web app save there.
 */
internal class JrwiLinkedSource(
    private val inner: PlaybackSource,
    private val repository: JrwiRepository,
    private val resume: Boolean,
    private val find: suspend (PlayItem) -> JrwiMatch?,
) : PlaybackSource by inner {
    private val items = ConcurrentHashMap<String, JrwiItem>()
    private var lastSaved = 0L

    // The archive's time on each thing's place when it last took this player's save
    private val told = ConcurrentHashMap<String, String>()

    override suspend fun first(): PlayItem = link(inner.first(), resume = resume)

    override suspend fun next(current: PlayItem): PlayItem? =
        inner.next(current)?.let { link(it, resume = false) }

    override suspend fun playlistItem(videoId: String): PlayItem? =
        inner.playlistItem(videoId)?.let { link(it, resume = true) }

    /** The same thing again, still the archive's episode it was. */
    override suspend fun reload(current: PlayItem, seconds: Double): PlayItem? =
        inner
            .reload(current, seconds)
            ?.copy(
                jrwi = current.jrwi,
                jrwiAudio = current.jrwiAudio,
                transcriptPost = current.transcriptPost,
                route = current.route,
            )

    private suspend fun link(play: PlayItem, resume: Boolean): PlayItem {
        val match =
            try {
                find(play)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: return play
        // Only the lines, for a video whose place the archive doesn't keep
        val item = match.item ?: return play.copy(transcriptPost = match.transcriptPost)
        items[play.id] = item
        val linked =
            play.copy(
                jrwi = item,
                transcriptPost = match.transcriptPost,
                route = Route.JrwiPost(item.video?.postId ?: item.id),
            )
        if (!resume) return linked
        val saved = repository.progress(item, audio = false) ?: return linked
        return if (!saved.finished && saved.position > 5) {
            linked.copy(resumeSeconds = saved.position)
        } else {
            linked
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

    /**
     * For one of the archive's episodes the archive is asked, as its pages start the episode from
     * the archive's place; for anything else, the server it plays from.
     */
    override suspend fun newerPlace(item: PlayItem): Double? {
        val episode = items[item.id] ?: return inner.newerPlace(item)
        return archiveMovedOn(repository, episode, audio = false, told, item.id)
    }

    private suspend fun archive(play: PlayItem, report: PlaybackReport, always: Boolean) {
        val item = items[play.id] ?: return
        val now = System.currentTimeMillis()
        if (!always && now - lastSaved < JRWI_SAVE_INTERVAL_MS) return
        lastSaved = now
        val nowWatched =
            repository.videoPlayed(item, report.seconds, report.duration, ended = false)
        told.noteSave(play.id, repository, item)
        // The archive decides when an episode counts as watched; the video's own copy follows
        if (nowWatched) repository.markVideoPlayed(item)
    }
}

/**
 * Where [request] plays from and reports back to, for either player. With the JRWI archive
 * connected, an episode of its that plays from TubeArchivist or Jellyfin keeps its place in the
 * archive too, and brings its transcript.
 */
internal fun playbackSource(app: AppGraph, request: PlayerRequest): PlaybackSource {
    val linked = app.settings.isJrwiConfigured
    val resume = !request.fromStart && request.startSeconds == null
    return when (request) {
        is PlayerRequest.Ta -> {
            val plain = TaPlaybackSource(app.api, app.settings, app.history, request, app.jellyfin)
            if (linked) {
                JrwiLinkedSource(plain, app.jrwi, resume) { play -> app.jrwi.matchYoutube(play.id) }
            } else {
                plain
            }
        }
        is PlayerRequest.Jellyfin -> {
            val plain = JellyfinPlaybackSource(app.jellyfin, app.settings, request)
            if (linked) {
                JrwiLinkedSource(plain, app.jrwi, resume) { play ->
                    app.jrwi.itemForEpisode(UUID.fromString(play.id))?.let { item ->
                        JrwiMatch(item, item.transcriptPost(audio = false))
                    }
                }
            } else {
                plain
            }
        }
        is PlayerRequest.Jrwi -> JrwiPlaybackSource(app.jrwi, request)
    }
}

// How often a playing episode's place is saved in the archive, as the phone app does
private const val JRWI_SAVE_INTERVAL_MS = 15_000L
