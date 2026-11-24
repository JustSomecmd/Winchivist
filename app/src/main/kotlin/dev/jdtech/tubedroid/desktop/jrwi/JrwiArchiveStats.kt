package dev.jdtech.tubedroid.desktop.jrwi

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jdtech.jellyfin.jrwi.JrwiEvent
import dev.jdtech.jellyfin.jrwi.JrwiFeature
import dev.jdtech.jellyfin.jrwi.JrwiItem
import dev.jdtech.jellyfin.jrwi.JrwiPage
import dev.jdtech.jellyfin.jrwi.JrwiShow
import dev.jdtech.jellyfin.jrwi.JrwiStats
import dev.jdtech.jellyfin.jrwi.JrwiStatus
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ceil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The archive's numbers, worked out as the phone app works them out: from every post and every
// item, the shows, the last days' events, and what the archiver counts itself

/** How many files, how big and how long. */
data class JrwiTotals(val count: Int = 0, val bytes: Long = 0, val seconds: Double = 0.0) {
    operator fun plus(other: JrwiTotals) =
        JrwiTotals(count + other.count, bytes + other.bytes, seconds + other.seconds)
}

/** How much of the archive's episodes were watched or listened to, by playing time. */
data class JrwiWatchTotals(
    /** Finished, with their playing time. */
    val watched: JrwiTotals = JrwiTotals(),
    /** Started and not finished, with the time played so far in [played]. */
    val started: JrwiTotals = JrwiTotals(),
    val unwatched: JrwiTotals = JrwiTotals(),
    /** The time played, in finished episodes and in those started. */
    val played: Double = 0.0,
) {
    val total: Double
        get() = watched.seconds + started.seconds + unwatched.seconds

    /** From 0 to 1, by playing time. */
    val share: Double
        get() = if (total > 0) (played / total).coerceIn(0.0, 1.0) else 0.0
}

/** One show's part of the archive. */
data class JrwiShowTotals(
    val id: String,
    val name: String,
    /** Posts whose own show this is. */
    val posts: Int = 0,
    /** Items with a video or audio. */
    val episodes: Int = 0,
    /** The playing time of its episodes. */
    val seconds: Double = 0.0,
    /** Its archived video and audio files. */
    val bytes: Long = 0,
    /** The time played of its episodes. */
    val played: Double = 0.0,
) {
    val watchedShare: Double
        get() = if (seconds > 0) (played / seconds).coerceIn(0.0, 1.0) else 0.0
}

/** A post with the number it's ranked by. */
data class JrwiRanked(val post: JrwiItem, val value: Long)

/** The numbers worked out from the archive's lists, and those the archiver gives itself. */
data class JrwiArchiveStats(
    val version: String? = null,
    /** Posts in the user's tier. */
    val posts: Int = 0,
    /** Items with a video or audio. */
    val episodes: Int = 0,
    /** The playing time of every episode, each once whatever its versions. */
    val playtime: Double = 0.0,
    val watch: JrwiWatchTotals = JrwiWatchTotals(),
    /** Members-only videos kept in the archive (and in Jellyfin). */
    val videos: JrwiTotals = JrwiTotals(),
    val audio: JrwiTotals = JrwiTotals(),
    /**
     * Videos only on YouTube, which play from TubeArchivist, with the size of TubeArchivist's
     * copies and their length, as the archiver has them from TubeArchivist since 0.14.9.
     */
    val youtube: JrwiTotals = JrwiTotals(),
    /** Members-only videos deleted since TubeArchivist has them from YouTube. */
    val handedOver: Int = 0,
    /** Posts without a video or audio, outside Art. */
    val textPosts: Int = 0,
    val artPosts: Int = 0,
    /** Posts the site has taken down, which the archive keeps. */
    val removedFromSite: Int = 0,
    /** Episodes with both a video and an audio version. */
    val bothVersions: Int = 0,
    /** Of those, the ones the archiver lined up itself. */
    val linedUp: Int = 0,
    /** Archived videos by height, highest first. */
    val heights: List<Pair<Int, Int>> = emptyList(),
    val likes: Long = 0,
    val comments: Long = 0,
    val mostLiked: List<JrwiRanked> = emptyList(),
    val mostCommented: List<JrwiRanked> = emptyList(),
    val longest: JrwiRanked? = null,
    val biggestVideo: JrwiRanked? = null,
    val first: JrwiItem? = null,
    val newest: JrwiItem? = null,
    /** Posts by the year they came out, oldest first. */
    val byYear: List<Pair<Int, Int>> = emptyList(),
    /** Shows by posts, most first. */
    val shows: List<JrwiShowTotals> = emptyList(),
    /** Posts archived on each of the last eight days, today last; null when not known. */
    val archivedDays: List<Pair<LocalDate, Int>>? = null,
    /** Checks that failed in the last eight days. */
    val failedChecks: Int = 0,
    /** What the archiver counts itself (suggested); null on archivers without it. */
    val extra: JrwiStats? = null,
)

/**
 * The archive's numbers as last loaded, kept while the app runs, so the stats page shows them
 * straight away each time it opens without loading them again.
 */
class JrwiStatsCache {
    /** For [address]; [check] is when the archiver's last check finished as they were loaded. */
    data class Entry(val address: String, val stats: JrwiArchiveStats, val check: String?)

    @Volatile var entry: Entry? = null
}

/**
 * The archive's numbers for its stats page, worked out from its lists of posts and items, a few
 * pages of 200 each, its events and its own stats. Loaded once while the app runs, and again on a
 * refresh or once a check has finished, when they'll have changed.
 */
class JrwiStatsModel(private val repository: JrwiRepository, private val scope: CoroutineScope) {
    private val api = repository.api
    private val cache = repository.statsCache

    var stats by mutableStateOf(cache.entry?.takeIf { it.address == api.baseUrl }?.stats)
        private set

    /** The archiver's status, for its counts and its disk, and to know when a check finished. */
    var status by mutableStateOf<JrwiStatus?>(null)
        private set

    var loading by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    val gridState = LazyGridState()

    /** Loads the numbers unless they're in for this archiver, then follows the status. */
    suspend fun follow() {
        val kept = cache.entry
        if (!loading && (kept == null || kept.address != api.baseUrl || error != null)) refresh()
        while (true) {
            try {
                val read = api.getStatus()
                status = read
                onStatus(read.running, read.lastCheckFinished)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The numbers stay as they were
            }
            delay(if (status?.running == true) BUSY_REFRESH_MS else IDLE_REFRESH_MS)
        }
    }

    /** Once a check has finished since the numbers were loaded, they're loaded again. */
    private fun onStatus(running: Boolean, lastCheckFinished: String?) {
        val kept = cache.entry ?: return
        if (running || loading || kept.address != api.baseUrl) return
        when {
            // Loaded before the status was first read
            kept.check == null -> cache.entry = kept.copy(check = lastCheckFinished)
            lastCheckFinished != null && lastCheckFinished != kept.check -> {
                // Noted first, so the numbers are loaded once for this check
                cache.entry = kept.copy(check = lastCheckFinished)
                refresh()
            }
        }
    }

    fun refresh() {
        val address = api.baseUrl ?: return
        if (loading) return
        val check = cache.entry?.takeIf { it.address == address }?.check
        loading = true
        scope.launch {
            try {
                val loaded = load()
                cache.entry = JrwiStatsCache.Entry(address, loaded, check)
                stats = loaded
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = jrwiErrorText(e)
            } finally {
                loading = false
            }
        }
    }

    private suspend fun load(): JrwiArchiveStats = coroutineScope {
        val posts = async { allPages { api.getPosts(page = it, pageSize = LIST_PAGE) } }
        val items = async { allPages { api.search(page = it, pageSize = LIST_PAGE) } }
        val shows = async { optional { repository.loadShows(fresh = true) } }
        val version = async { optional { api.getPing().version } }
        val since = LocalDate.now().minusDays(STATS_DAYS - 1L)
        val events = async {
            optional {
                val start = since.atStartOfDay(ZoneId.systemDefault()).toOffsetDateTime()
                // As the archiver writes its times, which it compares as text
                api.getEvents(since = start.withOffsetSameInstant(ZoneOffset.UTC).format(ISO))
            }
        }
        val extra = async {
            // Not asked of an archiver that lists its features without its own stats
            optional { if (api.has(JrwiFeature.STATS) == false) null else api.getStats() }
        }
        val postList = posts.await()
        val itemList = items.await()
        val showList = shows.await().orEmpty()
        val eventList = events.await()
        withContext(Dispatchers.Default) {
            archiveStats(postList, itemList, showList, eventList, since)
                .copy(version = version.await(), extra = extra.await())
        }
    }

    /** Every page of a list, the first to learn how many there are, then the rest together. */
    private suspend fun <T> allPages(load: suspend (Int) -> JrwiPage<T>): List<T> = coroutineScope {
        val first = load(1)
        val pages =
            ceil(first.total.toDouble() / first.pageSize.coerceAtLeast(1))
                .toInt()
                .coerceIn(1, MAX_PAGES)
        first.items + (2..pages).map { async { load(it).items } }.awaitAll().flatten()
    }

    private suspend fun <T> optional(load: suspend () -> T): T? =
        try {
            load()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    private companion object {
        const val LIST_PAGE = 200
        const val MAX_PAGES = 100
        const val BUSY_REFRESH_MS = 5_000L
        const val IDLE_REFRESH_MS = 60_000L
        val ISO: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx")
    }
}

/** How far an item was played, in seconds, and whether it's finished. */
private fun JrwiItem.playedSeconds(length: Double): Double {
    val progress = progress ?: return 0.0
    if (progress.finished) return length
    return progress.position.coerceIn(0.0, length)
}

private fun JrwiItem.year(): Int? = date?.take(4)?.toIntOrNull()

private fun localDate(iso: String): LocalDate? =
    try {
        OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate()
    } catch (_: Exception) {
        null
    }

/** The numbers, from every post, every item, the shows and the last days' events. */
internal fun archiveStats(
    posts: List<JrwiItem>,
    items: List<JrwiItem>,
    shows: List<JrwiShow>,
    events: List<JrwiEvent>?,
    since: LocalDate,
): JrwiArchiveStats {
    val showNames = shows.associate { it.id to it.name }

    // Each file once: posts carry only their own video or audio
    var videos = JrwiTotals()
    var audio = JrwiTotals()
    var youtube = JrwiTotals()
    val heights = mutableMapOf<Int, Int>()
    posts.forEach { post ->
        val video = post.video
        if (video != null && video.jellyfinPath != null) {
            videos += JrwiTotals(1, video.size ?: 0L, video.duration ?: 0.0)
            video.height?.let { heights[it] = (heights[it] ?: 0) + 1 }
        } else if (video != null && video.youtubeId != null) {
            youtube += JrwiTotals(1, video.size ?: 0L, video.duration ?: 0.0)
        }
        post.audio?.let { audio += JrwiTotals(1, it.size ?: 0L, it.duration ?: 0.0) }
    }
    val plain = posts.filter { it.video == null && it.audio == null && it.handedOver == null }
    val art = plain.count { "art" in it.ownTabs }

    // Episodes are items with a video or audio, each counted once
    val episodes = items.filter { it.video != null || it.audio != null }
    var watched = JrwiTotals()
    var started = JrwiTotals()
    var unwatched = JrwiTotals()
    var played = 0.0
    episodes.forEach { item ->
        val length = item.duration ?: 0.0
        val progress = item.progress
        when {
            progress?.finished == true -> watched += JrwiTotals(1, 0, length)
            progress != null && progress.position >= STARTED_SECONDS ->
                started += JrwiTotals(1, 0, length)
            else -> unwatched += JrwiTotals(1, 0, length)
        }
        played += item.playedSeconds(length)
    }

    // Shows: posts and files by each post's own show, episodes and time by each item's
    val byShow = mutableMapOf<String, JrwiShowTotals>()
    fun show(id: String) = byShow.getOrPut(id) { JrwiShowTotals(id, showNames[id] ?: id) }
    posts.forEach { post ->
        val bytes =
            (post.video?.takeIf { it.jellyfinPath != null }?.size ?: 0L) + (post.audio?.size ?: 0L)
        val totals = show(post.show)
        byShow[post.show] = totals.copy(posts = totals.posts + 1, bytes = totals.bytes + bytes)
    }
    episodes.forEach { item ->
        val length = item.duration ?: 0.0
        val totals = show(item.show)
        byShow[item.show] =
            totals.copy(
                episodes = totals.episodes + 1,
                seconds = totals.seconds + length,
                played = totals.played + item.playedSeconds(length),
            )
    }

    val dated = posts.filter { it.date != null }.sortedBy { it.date }
    val days = (0 until STATS_DAYS).map { since.plusDays(it.toLong()) }
    val archivedDays = events?.let { list ->
        val perDay =
            list
                .filter { it.type == "new_item" }
                .mapNotNull { localDate(it.at) }
                .groupingBy { it }
                .eachCount()
        days.map { it to (perDay[it] ?: 0) }
    }
    return JrwiArchiveStats(
        posts = posts.size,
        episodes = episodes.size,
        playtime = episodes.sumOf { it.duration ?: 0.0 },
        watch = JrwiWatchTotals(watched, started, unwatched, played),
        videos = videos,
        audio = audio,
        youtube = youtube,
        handedOver = posts.count { it.handedOver != null },
        textPosts = plain.size - art,
        artPosts = art,
        removedFromSite = posts.count { it.removedFromSite != null },
        bothVersions = episodes.count { it.hasBoth },
        linedUp = episodes.count { it.hasBoth && it.sync?.checked == true },
        heights = heights.entries.sortedByDescending { it.key }.map { it.key to it.value },
        likes = posts.sumOf { (it.likeCount ?: 0).toLong() },
        comments = posts.sumOf { (it.commentCount ?: 0).toLong() },
        mostLiked =
            posts
                .filter { (it.likeCount ?: 0) > 0 }
                .sortedByDescending { it.likeCount ?: 0 }
                .take(TOP)
                .map { JrwiRanked(it, (it.likeCount ?: 0).toLong()) },
        mostCommented =
            posts
                .filter { (it.commentCount ?: 0) > 0 }
                .sortedByDescending { it.commentCount ?: 0 }
                .take(TOP)
                .map { JrwiRanked(it, (it.commentCount ?: 0).toLong()) },
        longest =
            episodes
                .maxByOrNull { it.duration ?: 0.0 }
                ?.takeIf { (it.duration ?: 0.0) > 0 }
                ?.let { JrwiRanked(it, (it.duration ?: 0.0).toLong()) },
        biggestVideo =
            posts
                .filter { it.video?.jellyfinPath != null && (it.video?.size ?: 0L) > 0 }
                .maxByOrNull { it.video?.size ?: 0L }
                ?.let { JrwiRanked(it, it.video?.size ?: 0L) },
        first = dated.firstOrNull(),
        newest = dated.lastOrNull(),
        byYear =
            posts
                .mapNotNull { it.year() }
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedBy { it.key }
                .map { it.key to it.value },
        shows = byShow.values.sortedByDescending { it.posts },
        archivedDays = archivedDays,
        failedChecks =
            events.orEmpty().count { it.type == "check_failed" || it.type == "login_failed" },
    )
}

// As the web app's Continue watching: started means 10 seconds or more in
private const val STARTED_SECONDS = 10.0
private const val TOP = 5

// The archived posts are counted for each of the last eight days, as TubeArchivist's downloads
private const val STATS_DAYS = 8
