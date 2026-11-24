package dev.jdtech.tubedroid.desktop.jrwi

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jdtech.jellyfin.jrwi.JrwiApi
import dev.jdtech.jellyfin.jrwi.JrwiApiException
import dev.jdtech.jellyfin.jrwi.JrwiItem
import dev.jdtech.jellyfin.jrwi.JrwiProgress
import dev.jdtech.jellyfin.jrwi.JrwiShow
import dev.jdtech.jellyfin.jrwi.JrwiTranscript
import dev.jdtech.jellyfin.tubearchivist.TaApi
import dev.jdtech.tubedroid.desktop.jellyfin.JellyfinClient
import dev.jdtech.tubedroid.desktop.player.PlayerRequest
import java.io.IOException
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jellyfin.sdk.model.api.BaseItemDto

/**
 * What the pages and the players share about the JRWI archive: its shows by name, how far each
 * episode was played as last saved from here, where its archived videos are on Jellyfin, and the
 * saves themselves, which keep an episode's video and audio on one position and one watched state
 * (the archive's own record, plus Jellyfin's or TubeArchivist's for the video).
 */
class JrwiRepository(
    val api: JrwiApi,
    private val jellyfin: JellyfinClient,
    private val ta: TaApi,
) {
    private val client =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

    /** The archive's numbers as its stats page last worked them out. */
    val statsCache = JrwiStatsCache()

    // Shows

    /** The archive's shows, as last read. Pages show names from here without asking again. */
    var shows by mutableStateOf<List<JrwiShow>>(emptyList())
        private set

    private var showsReadAt = 0L
    private val showsLock = Mutex()

    /** The archive's shows, read again when they're older than a few minutes or with [fresh]. */
    suspend fun loadShows(fresh: Boolean = false): List<JrwiShow> = showsLock.withLock {
        val now = System.currentTimeMillis()
        if (fresh || shows.isEmpty() || now - showsReadAt > SHOWS_KEPT_MS) {
            shows = api.getShows()
            showsReadAt = now
        }
        shows
    }

    /** A show's name, or its ID when the shows can't be read. */
    suspend fun showName(showId: String): String =
        try {
            loadShows().firstOrNull { it.id == showId }?.name ?: showId
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            showId
        }

    // Progress, as last saved from this app, so lists show it without loading again

    private val saved = mutableStateMapOf<Long, JrwiProgress>()

    /**
     * How far [item] was played: what this app saved last, or else what the archive gave with it.
     */
    fun progressOf(item: JrwiItem): JrwiProgress? = saved[item.itemId ?: item.id] ?: item.progress

    /** Whether [item] counts as watched. */
    fun finished(item: JrwiItem): Boolean = progressOf(item)?.finished == true

    /** How far through [item] it was played, from 0 to 1; null unless partly played. */
    fun playedFraction(item: JrwiItem): Float? {
        val progress = progressOf(item)?.takeIf { !it.finished && it.position > 0 } ?: return null
        val versionLength =
            if (progress.postId == item.audio?.postId) item.audio?.duration
            else item.video?.duration
        val length = progress.duration ?: versionLength ?: item.duration ?: return null
        if (length <= 0) return null
        return (progress.position / length).toFloat().coerceIn(0f, 1f)
    }

    /**
     * How far the episode was played, in the timeline of its video or, with [audio], its audio.
     * Null without that version, or when the archive can't be reached.
     */
    suspend fun progress(item: JrwiItem, audio: Boolean): JrwiProgress? {
        val postId = (if (audio) item.audio?.postId else item.video?.postId) ?: return null
        return try {
            // A save that's still waiting goes first, so it isn't read over
            sendWaiting()
            api.getProgress(postId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    // Saves the archive couldn't be reached for, the newest per episode, sent with their time

    private class Waiting(
        val postId: Long,
        val position: Double,
        val duration: Double?,
        val finished: Boolean?,
        val madeAt: String,
    )

    private val waiting = ConcurrentHashMap<Long, Waiting>()

    private suspend fun sendWaiting() {
        for ((itemId, save) in waiting.entries.toList()) {
            try {
                val kept =
                    putProgress(
                        save.postId,
                        save.position,
                        save.duration,
                        save.finished,
                        save.madeAt,
                    )
                waiting.remove(itemId, save)
                saved[itemId] = kept
            } catch (e: CancellationException) {
                throw e
            } catch (_: JrwiApiException) {
                // The archive won't take it, which another try won't change
                waiting.remove(itemId, save)
            } catch (_: IOException) {
                return
            }
        }
    }

    /**
     * The video was played to [seconds]. Saved under the video's own post, which also moves the
     * audio, if there is one, to the same moment. [ended] says it played to its end. Returns
     * whether the episode now counts as watched.
     */
    suspend fun videoPlayed(
        item: JrwiItem,
        seconds: Double,
        playerDuration: Double?,
        ended: Boolean,
    ): Boolean {
        val video = item.video ?: return false
        val before = finished(item)
        val kept =
            save(
                item,
                video.postId,
                if (ended) 0.0 else seconds,
                video.duration ?: playerDuration,
                ended,
            )
        return kept?.finished == true && !before
    }

    /**
     * The audio was played to [seconds]. Saved in the archive every time; [handOff] also moves the
     * video to the same moment, on Jellyfin or TubeArchivist, which happens when it's paused or
     * left rather than every few seconds.
     */
    suspend fun audioPlayed(
        item: JrwiItem,
        seconds: Double,
        playerDuration: Double?,
        ended: Boolean,
        handOff: Boolean,
    ) {
        val audio = item.audio ?: return
        val kept =
            save(
                item,
                audio.postId,
                if (ended) 0.0 else seconds,
                audio.duration ?: playerDuration,
                ended,
            )
        val finished = kept?.finished == true
        if (!handOff && !finished) return
        runCatching { moveVideo(item, item.videoSeconds(seconds), finished) }
            .onFailure { if (it is CancellationException) throw it }
    }

    /** Marks both the video and the audio watched, or both not watched. */
    suspend fun setWatched(item: JrwiItem, watched: Boolean) {
        // The archive's one record covers both versions, so either post will do
        val postId = item.video?.postId ?: item.audio?.postId ?: return
        val duration = if (item.video != null) item.video?.duration else item.audio?.duration
        val kept = putProgress(postId, 0.0, duration, watched, null)
        saved[item.itemId ?: item.id] = kept
        val video = item.video ?: return
        if (video.isYoutube) {
            val youtubeId = video.youtubeId ?: return
            if (ta.isConfigured) ta.setWatched(youtubeId, watched)
            return
        }
        val episodeId = episodeId(item) ?: return
        jellyfin.setPlayed(episodeId, watched)
    }

    /** Marks the video's own copy played, once the archive counts the episode as watched. */
    suspend fun markVideoPlayed(item: JrwiItem) {
        runCatching { moveVideo(item, 0.0, finished = true) }
            .onFailure { if (it is CancellationException) throw it }
    }

    /** Hands a position, or watched, on to where the item's video is kept. */
    private suspend fun moveVideo(item: JrwiItem, videoSeconds: Double, finished: Boolean) {
        val video = item.video ?: return
        if (video.isYoutube) {
            val youtubeId = video.youtubeId ?: return
            if (!ta.isConfigured) return
            if (finished) ta.setWatched(youtubeId, true)
            else ta.postProgress(youtubeId, videoSeconds)
            return
        }
        val episodeId = episodeId(item) ?: return
        if (finished) jellyfin.setPlayed(episodeId, true)
        else jellyfin.setPosition(episodeId, videoSeconds)
    }

    /**
     * Saves progress in the archive and tells the lists showing [item]. When the archive can't be
     * reached, the save waits here and goes with the next one, with the time it was made.
     */
    private suspend fun save(
        item: JrwiItem,
        postId: Long,
        position: Double,
        duration: Double?,
        ended: Boolean,
    ): JrwiProgress? {
        val itemId = item.itemId ?: item.id
        // The archive decides when an episode counts as watched (contract v0.22), unless it played
        // to its end; an older archiver is told by the phone app's rule, at 90%
        val finished: Boolean? =
            when {
                ended -> true
                decidesWatched() -> null
                else -> duration != null && duration > 0 && position >= duration * OWN_WATCHED_AT
            }
        return try {
            sendWaiting()
            waiting.remove(itemId)
            putProgress(postId, position, duration, finished, null).also { saved[itemId] = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: JrwiApiException) {
            System.err.println("The archiver refused progress of $postId: ${e.message}")
            null
        } catch (_: IOException) {
            waiting[itemId] =
                Waiting(postId, position, duration, finished, OffsetDateTime.now().toString())
            // Shown as played to here meanwhile
            JrwiProgress(postId, position, duration, finished == true, null).also {
                saved[itemId] = it
            }
        }
    }

    // What the archiver has, from its ping

    private var pinged: Pinged? = null

    private class Pinged(val base: String, val at: Long, val watched: Boolean)

    /** Whether the archiver marks episodes watched itself when a save doesn't say (v0.22). */
    private suspend fun decidesWatched(): Boolean {
        val base = api.baseUrl ?: return false
        val now = System.currentTimeMillis()
        pinged?.let { if (it.base == base && now - it.at < PING_KEPT_MS) return it.watched }
        val watched =
            try {
                api.has(FEATURE_WATCHED)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        // Not kept when it couldn't be read, so it's asked again with the next save
        if (watched != null) pinged = Pinged(base, now, watched)
        return watched == true
    }

    /**
     * `PUT /api/progress/{postId}`, leaving `finished` out when it's null so the archiver decides.
     * The shared API module always sends it, as the phone app does, so this goes out from here.
     */
    private suspend fun putProgress(
        postId: Long,
        position: Double,
        duration: Double?,
        finished: Boolean?,
        madeAt: String?,
    ): JrwiProgress =
        withContext(Dispatchers.IO) {
            val url =
                api.fileUrl("/api/progress/$postId")?.toHttpUrlOrNull()
                    ?: throw IOException("The JRWI archiver is not set up")
            val body = buildJsonObject {
                put("position", position)
                duration?.let { put("duration", it) }
                finished?.let { put("finished", it) }
                madeAt?.let { put("updated_at", it) }
            }
                .toString()
                .toRequestBody(JSON_TYPE)
            val request =
                Request.Builder()
                    .url(url)
                    .put(body)
                    .apply { api.authHeader?.let { header("Authorization", it) } }
                    .build()
            client.newCall(request).execute().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) {
                    throw JrwiApiException(response.code, "The archiver answered ${response.code}")
                }
                api.json.decodeFromString(JrwiProgress.serializer(), text)
            }
        }

    /** How many seconds before the end an episode counts as watched, from the ping, or null. */
    suspend fun watchedLeft(): Double? =
        withContext(Dispatchers.IO) {
            val url = api.fileUrl("/api/ping")?.toHttpUrlOrNull() ?: return@withContext null
            try {
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    val json = api.json.parseToJsonElement(response.body.string()) as? JsonObject
                    (json?.get("watched_left") as? JsonPrimitive)?.doubleOrNull
                }
            } catch (_: Exception) {
                null
            }
        }

    // Where the archive's videos are on Jellyfin

    private val episodeIds = ConcurrentHashMap<Long, UUID>()
    private val postIds = ConcurrentHashMap<UUID, Long>()

    /** Jellyfin's copy of the item's archived video, or null when Jellyfin doesn't have it. */
    suspend fun episodeId(item: JrwiItem): UUID? {
        val video = item.video?.takeIf { !it.isYoutube } ?: return null
        if (!jellyfin.isSignedIn) return null
        episodeIds[video.postId]?.let {
            return it
        }
        return try {
            val showName = showName(item.show)
            val shows = jellyfin.seriesNamed(showName.trim().take(SEARCH_LENGTH))
            // The show of that name first, in case another library has a show like it
            val inOrder = shows.sortedByDescending { it.name.equals(showName, ignoreCase = true) }
            for (show in inOrder) {
                jellyfin.episodeFiles(show.id).forEach { episode ->
                    episode.jrwiPostId()?.let { postId ->
                        episodeIds[postId] = episode.id
                        postIds[episode.id] = postId
                    }
                }
                episodeIds[video.postId]?.let {
                    return it
                }
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            System.err.println("Couldn't look up ${video.postId} on Jellyfin: $e")
            null
        }
    }

    /** The archive's item of a Jellyfin episode, when that episode is one of its videos. */
    suspend fun itemForEpisode(itemId: UUID, known: BaseItemDto? = null): JrwiItem? {
        if (!api.isConfigured) return null
        return try {
            val postId =
                postIds[itemId]
                    ?: (known ?: jellyfin.item(itemId)).jrwiPostId()?.also {
                        postIds[itemId] = it
                        episodeIds[it] = itemId
                    }
                    ?: return null
            api.getItem(postId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** Jellyfin's copy of the item's archived video, when it was looked up before. */
    fun knownEpisodeId(item: JrwiItem): UUID? = item.video?.let { episodeIds[it.postId] }

    /**
     * What the archive has for a TubeArchivist video. For one of its YouTube-only videos, the item,
     * whose place the archive keeps. For the YouTube upload of an episode whose own video is
     * archived, which plays from Jellyfin and keeps its place there, only the YouTube post's
     * transcript, if it has one. Null for any other video.
     */
    suspend fun matchYoutube(youtubeId: String): JrwiMatch? {
        if (!api.isConfigured) return null
        return try {
            val items = api.search(youtubeId = youtubeId, pageSize = 5).items
            val own = items.firstOrNull { it.video?.youtubeId == youtubeId }
            when {
                own != null -> JrwiMatch(own, own.transcriptPost(audio = false))
                items.any { it.transcript } ->
                    api.getPosts(youtubeId = youtubeId, pageSize = 5)
                        .items
                        .firstOrNull { it.transcript }
                        ?.let { JrwiMatch(null, it.id) }
                else -> null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /**
     * How to play the item's video: from TubeArchivist when it's only on YouTube, from Jellyfin
     * when it has the archived file, or else straight from the archive. Null when it has no video,
     * or one on YouTube with TubeArchivist not set up.
     */
    suspend fun videoRequest(
        item: JrwiItem,
        fromStart: Boolean = false,
        startSeconds: Double? = null,
    ): PlayerRequest? {
        val video = item.video ?: return null
        if (video.isYoutube) {
            val youtubeId = video.youtubeId?.takeIf { ta.isConfigured } ?: return null
            return PlayerRequest.Ta(youtubeId, fromStart = fromStart, startSeconds = startSeconds)
        }
        val episodeId = episodeId(item)
        return if (episodeId != null) {
            PlayerRequest.Jellyfin(episodeId.toString(), fromStart, startSeconds)
        } else {
            PlayerRequest.Jrwi(item.id, audio = false, fromStart, startSeconds)
        }
    }

    /**
     * The item after [item] as its show's page lists it: in its own tab, oldest first, so in
     * Episodes by season and number. Null at the show's end.
     */
    suspend fun nextInShow(item: JrwiItem): JrwiItem? {
        var found = false
        for (page in 1..NEXT_MAX_PAGES) {
            val result =
                api.getShowItems(
                    showId = item.show,
                    tab = item.tab,
                    page = page,
                    order = "asc",
                    pageSize = NEXT_PAGE_SIZE,
                )
            for (entry in result.items) {
                if (found) return entry
                if (entry.id == item.id) found = true
            }
            if (!result.hasMore) break
        }
        return null
    }

    // Transcripts: the last two read, for a while, so one that gets timed meanwhile is read again

    private class KeptTranscript(val transcript: JrwiTranscript, val at: Long)

    private val transcripts = LinkedHashMap<Long, KeptTranscript>()

    /** The transcript of the post [postId], timed in that post's timeline once it's timed. */
    suspend fun transcript(postId: Long): JrwiTranscript {
        val now = System.currentTimeMillis()
        synchronized(transcripts) {
            transcripts[postId]?.let { if (now - it.at < TRANSCRIPT_KEPT_MS) return it.transcript }
        }
        val read = api.getTranscript(postId)
        synchronized(transcripts) {
            transcripts.remove(postId)
            transcripts[postId] = KeptTranscript(read, now)
            while (transcripts.size > TRANSCRIPTS_KEPT) transcripts.remove(transcripts.keys.first())
        }
        return read
    }

    private companion object {
        const val SHOWS_KEPT_MS = 5 * 60_000L
        const val PING_KEPT_MS = 15 * 60_000L
        const val SEARCH_LENGTH = 60
        const val FEATURE_WATCHED = "watched"
        const val OWN_WATCHED_AT = 0.9
        const val NEXT_PAGE_SIZE = 200
        const val NEXT_MAX_PAGES = 10
        const val TRANSCRIPT_KEPT_MS = 10 * 60_000L
        const val TRANSCRIPTS_KEPT = 2
        val JSON_TYPE = "application/json".toMediaType()
    }
}

/**
 * What the archive has for something that plays from elsewhere: its [item], when it keeps the
 * episode's place, and the post whose transcript follows it, if any.
 */
class JrwiMatch(val item: JrwiItem?, val transcriptPost: Long?)

/**
 * The JRWI post ID of a Jellyfin item: the provider ID from the archiver's .nfo file, or else the
 * `[jrwi-<id>]` tag the archiver puts in its file names.
 */
fun BaseItemDto.jrwiPostId(): Long? {
    providerIds
        ?.entries
        ?.firstOrNull { it.key.equals("jrwi", ignoreCase = true) }
        ?.value
        ?.toLongOrNull()
        ?.let {
            return it
        }
    return JrwiApi.postIdInPath(path ?: mediaSources?.firstOrNull()?.path)
}
