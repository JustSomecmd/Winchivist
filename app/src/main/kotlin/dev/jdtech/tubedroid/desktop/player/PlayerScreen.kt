package dev.jdtech.tubedroid.desktop.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import com.sun.jna.Native
import dev.jdtech.jellyfin.tubearchivist.TaVideo
import dev.jdtech.tubedroid.desktop.AppSettings
import dev.jdtech.tubedroid.desktop.LocalApp
import dev.jdtech.tubedroid.desktop.LocalAppWindow
import dev.jdtech.tubedroid.desktop.LocalNavigator
import dev.jdtech.tubedroid.desktop.LocalWindowState
import dev.jdtech.tubedroid.desktop.Route
import dev.jdtech.tubedroid.desktop.jellyfin.Languages
import dev.jdtech.tubedroid.desktop.jrwi.JrwiTheme
import dev.jdtech.tubedroid.desktop.ui.OnComingBack
import dev.jdtech.tubedroid.desktop.ui.Spacings
import dev.jdtech.tubedroid.desktop.ui.TubedroidTheme
import dev.jdtech.tubedroid.desktop.ui.errorText
import dev.jdtech.tubedroid.desktop.ui.formatDuration
import java.awt.Canvas
import java.awt.GraphicsEnvironment
import java.awt.IllegalComponentStateException
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyEvent
import java.util.Locale
import javax.swing.RootPaneContainer
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A SponsorBlock segment that can be skipped, in seconds. */
internal data class SkipSegment(
    val start: Double,
    val end: Double,
    val category: String,
    /** Skipped without asking, rather than with a button. */
    val auto: Boolean = false,
)

/** For saving progress after the player has gone, such as when it closes. */
private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Waits a moment for the saves that are under way, for when the app is closing and would otherwise
 * take them with it.
 */
fun awaitPlaybackSaves() {
    val saves = backgroundScope.coroutineContext[Job]?.children?.toList().orEmpty()
    if (saves.isEmpty()) return
    runBlocking { withTimeoutOrNull(CLOSING_WAIT_MS) { saves.forEach { it.join() } } }
}

private const val CLOSING_WAIT_MS = 2_000L

/**
 * The video playing, mpv's state as last read, and what happens around it: saving progress where
 * the video came from, SponsorBlock, and going on to the next video or episode.
 */
internal class PlayerSession(
    private val source: PlaybackSource,
    private val settings: AppSettings,
    private val scope: CoroutineScope,
    /** Plays the sound alone, in the audio player. */
    val audio: Boolean = false,
) {
    var player by mutableStateOf<MpvPlayer?>(null)
    var item by mutableStateOf<PlayItem?>(null)
        private set

    /** The title of what plays next, if anything does, and its picture where it has one. */
    var upNext by mutableStateOf<String?>(null)
        private set

    var upNextPicture by mutableStateOf<String?>(null)
        private set

    /** Whether the playlist starts over after its last video, as on YouTube. */
    var loop by mutableStateOf(false)
        private set

    // What the controls under the video show, as last read from mpv
    var paused by mutableStateOf(false)
        private set

    var volume by mutableStateOf(settings.playerVolume.toDouble())
        private set

    var muted by mutableStateOf(false)
        private set

    var speed by mutableStateOf(1.0)
        private set

    /** A subtitle or sound track of the video, as mpv numbers them, with a name to show. */
    data class Track(val id: Int, val label: String)

    var subtitleTracks by mutableStateOf<List<Track>>(emptyList())
        private set

    var audioTracks by mutableStateOf<List<Track>>(emptyList())
        private set

    /** The subtitle track showing, or null when they're off, and the sound track playing. */
    var subtitleId by mutableStateOf<Int?>(null)
        private set

    var audioId by mutableStateOf<Int?>(null)
        private set

    /** Where the video's chapters start, in seconds, for the marks on the seek bar. */
    var chapters by mutableStateOf<List<Double>>(emptyList())
        private set

    /** The chapters' names, one for each of [chapters]. */
    var chapterTitles by mutableStateOf<List<String>>(emptyList())
        private set

    /** Whether mpv's playback info shows over the video. */
    var statsShown by mutableStateOf(false)
        private set

    // Called off from the Up next card, for this video
    private var upNextCancelled by mutableStateOf(false)

    /** Whether the Up next card shows: near the end, when the next video plays on its own. */
    val showUpNext: Boolean
        get() =
            upNext != null &&
                settings.autoplay &&
                !loopVideo &&
                !upNextCancelled &&
                sleep?.atEnd != true &&
                duration > 0 &&
                duration - position in 0.0..UP_NEXT_SECONDS

    /** Stops the next video playing on its own after this one. */
    fun cancelUpNext() {
        upNextCancelled = true
    }

    fun changeSpeed(value: Double) {
        speed = value
        player?.set("speed", value.toString())
        // The audio player's speed is its own, and remembered
        if (audio) settings.audioSpeed = value
    }

    /** A sleep timer: pausing at a time, or at the end of what plays. */
    data class Sleep(val endsAt: Long? = null, val atEnd: Boolean = false)

    var sleep by mutableStateOf<Sleep?>(null)
        private set

    // The volume a sleep timer's fade started from, to go back to once it has paused
    private var fadeFrom: Double? = null

    /** Pauses in [minutes] from now, the sound fading out over the last seconds. */
    fun sleepIn(minutes: Int) {
        endFade()
        sleep = Sleep(endsAt = System.currentTimeMillis() + minutes * 60_000L)
        applyLoop()
    }

    /** Moves a running sleep timer by [minutes], which can be less than none. */
    fun addSleep(minutes: Int) {
        val endsAt = sleep?.endsAt ?: return
        endFade()
        val soonest = System.currentTimeMillis() + MIN_SLEEP_MS
        sleep = Sleep(endsAt = (endsAt + minutes * 60_000L).coerceAtLeast(soonest))
    }

    /** Stops at the end of what plays, rather than going on to the next. */
    fun sleepAtEnd() {
        endFade()
        sleep = Sleep(atEnd = true)
        applyLoop()
    }

    fun cancelSleep() {
        endFade()
        sleep = null
        applyLoop()
    }

    /**
     * Whether what plays starts over when it ends, until this is turned off or the player is
     * closed. Nothing plays after it then.
     */
    var loopVideo by mutableStateOf(false)
        private set

    fun changeLoopVideo(on: Boolean) {
        loopVideo = on
        applyLoop()
    }

    // mpv starts the file over by itself, without a gap. A sleep timer for the end of the video
    // comes first: that end is then the end
    private fun applyLoop() {
        player?.set("loop-file", if (loopVideo && sleep?.atEnd != true) "inf" else "no")
    }

    private fun checkSleep(player: MpvPlayer, now: Long) {
        val endsAt = sleep?.endsAt ?: return
        val left = endsAt - now
        if (left <= 0) {
            player.set("pause", "yes")
            sleep = null
            endFade()
        } else if (left < SLEEP_FADE_MS && !paused) {
            val from = fadeFrom ?: volume.also { fadeFrom = it }
            player.set("volume", String.format(Locale.ROOT, "%.1f", from * left / SLEEP_FADE_MS))
        }
    }

    /** Puts the volume back to what a sleep timer's fade started from. */
    private fun endFade() {
        val from = fadeFrom ?: return
        fadeFrom = null
        player?.set("volume", String.format(Locale.ROOT, "%.0f", from))
    }

    /** Whether the transcript's lines show, beside the video or in the picture's place. */
    var transcriptOpen by mutableStateOf(false)
        private set

    fun toggleTranscript() {
        transcriptOpen = !transcriptOpen && item?.transcriptPost != null
    }

    fun selectSubtitle(id: Int?) {
        subtitleId = id
        player?.set("sid", id?.toString() ?: "no")
    }

    fun selectAudio(id: Int) {
        audioId = id
        player?.set("aid", id.toString())
    }

    /**
     * Shows or hides mpv's own playback info over the video: formats, decoder and dropped frames.
     */
    fun toggleStats() {
        statsShown = !statsShown
        player?.command("script-binding", "stats/display-stats-toggle")
    }

    /** Reads the video's subtitle and sound tracks and its chapters from mpv. */
    private fun readTracks(player: MpvPlayer) {
        val count = player.get("track-list/count")?.toIntOrNull() ?: 0
        val subtitles = mutableListOf<Track>()
        val audio = mutableListOf<Track>()
        for (index in 0 until count) {
            val id = player.get("track-list/$index/id")?.toIntOrNull() ?: continue
            val title = player.get("track-list/$index/title")?.takeIf { it.isNotBlank() }
            val language =
                player
                    .get("track-list/$index/lang")
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::languageName)
            val label =
                when {
                    title != null && language != null && !title.contains(language) ->
                        "$title ($language)"
                    else -> title ?: language ?: "Track $id"
                }
            when (player.get("track-list/$index/type")) {
                "sub" -> subtitles += Track(id, label)
                "audio" -> audio += Track(id, label)
            }
        }
        subtitleTracks = subtitles
        audioTracks = audio
        val chapterCount = player.get("chapter-list/count")?.toIntOrNull() ?: 0
        val read =
            (0 until chapterCount).mapNotNull { index ->
                val time = player.getDouble("chapter-list/$index/time") ?: return@mapNotNull null
                val title = player.get("chapter-list/$index/title")?.takeIf { it.isNotBlank() }
                time to (title ?: "Chapter ${index + 1}")
            }
        chapters = read.map { it.first }
        chapterTitles = read.map { it.second }
    }

    /** A language's name in English, from a code such as en or eng. */
    private fun languageName(code: String): String =
        Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).ifBlank { code }

    /** Shows the controls as at a point of the video, for the page renders without mpv. */
    internal fun showForPreview(position: Double, duration: Double, chapters: List<Double>) {
        this.position = position
        this.duration = duration
        this.chapters = chapters
        loading = false
        paused = true
    }

    fun togglePause() {
        val since = pausedAt
        if (paused && since != null) {
            pausedAt = null
            playedAgainAfter(System.currentTimeMillis() - since)
        }
        player?.command("cycle", "pause")
    }

    // Played again after [pausedMs] of pause
    private fun playedAgainAfter(pausedMs: Long) {
        // After a long one, the sound goes back a little, to pick up the thread
        if (audio && pausedMs >= REWIND_AFTER_PAUSE_MS) seekBy(-REWIND_SECONDS)
        // And another device may have gone on with it meanwhile
        if (pausedMs >= CATCH_UP_AFTER_PAUSE_MS) catchUp()
    }

    /**
     * Something to say to whoever watches, for a few seconds: that it went to where another device
     * left off.
     */
    var notice by mutableStateOf<String?>(null)
        private set

    private var catchingUp: Job? = null

    /**
     * Asks the server whether another device left this somewhere else since this player last saved
     * its place, and goes there if one did. For a player that was left alone: when the window is
     * come back to while it's paused, and when it plays again after a long pause. While it plays
     * there's nothing to ask, as the newest place is then its own.
     */
    fun catchUp() {
        val current = item ?: return
        if (loading || ended || catchingUp?.isActive == true) return
        // Nothing is saved while the answer is on its way: that would put this player's older
        // place over the newer one before it's read
        lastReport = System.currentTimeMillis()
        catchingUp = scope.launch {
            val place =
                try {
                    withTimeoutOrNull(CATCH_UP_WAIT_MS) {
                        withContext(Dispatchers.IO) { source.newerPlace(current) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                } ?: return@launch
            // Something else plays by now, or it's there already
            if (item?.id != current.id || abs(place - position) < CATCH_UP_NEAR_SECONDS) {
                return@launch
            }
            seekTo(place)
            lastReport = System.currentTimeMillis()
            val text = "Went to ${formatDuration(place)}, where another device left off"
            notice = text
            delay(NOTICE_MS)
            if (notice == text) notice = null
        }
    }

    fun pause() {
        player?.set("pause", "yes")
    }

    /** Back and forward by the steps in the settings. */
    fun seekBack() = seekBy(-settings.seekBackSeconds.toDouble())

    fun seekForward() = seekBy(settings.seekForwardSeconds.toDouble())

    fun seekTo(seconds: Double) {
        position = seconds
        player?.command("seek", String.format(Locale.ROOT, "%.2f", seconds), "absolute")
    }

    fun seekBy(seconds: Double) {
        player?.command("seek", String.format(Locale.ROOT, "%.2f", seconds), "relative")
    }

    fun changeVolume(value: Double) {
        volume = value
        rememberVolume(value)
        if (muted) player?.set("mute", "no")
        // While a sleep timer fades the sound out, this is what it fades from
        if (fadeFrom != null) fadeFrom = value
        else player?.set("volume", String.format(Locale.ROOT, "%.0f", value))
    }

    // The volume is the app's rather than one video's: whatever plays next, in either player,
    // starts at it
    private fun rememberVolume(value: Double) {
        val level = value.roundToInt().coerceIn(0, 100)
        if (settings.playerVolume != level) settings.playerVolume = level
    }

    fun toggleMute() {
        player?.command("cycle", "mute")
    }

    /** Goes to the next speed, from normal up to twice as fast and then down to half. */
    fun cycleSpeed() {
        val next = SPEEDS.firstOrNull { it > speed + 0.01 } ?: SPEEDS.first()
        speed = next
        player?.set("speed", next.toString())
    }

    /** Goes to the next subtitle, and after the last one turns them off. */
    fun cycleSubtitles() {
        player?.command("cycle", "sub")
    }

    /**
     * The player's keys, as on YouTube and in mpv. Returns whether [keyCode] was one of them.
     * Escape leaves full screen, or else closes the player.
     */
    fun onKey(keyCode: Int, onClose: () -> Unit): Boolean {
        when (keyCode) {
            KeyEvent.VK_SPACE,
            KeyEvent.VK_K -> togglePause()
            KeyEvent.VK_LEFT -> seekBy(-5.0)
            KeyEvent.VK_RIGHT -> seekBy(5.0)
            KeyEvent.VK_J -> seekBack()
            KeyEvent.VK_L -> seekForward()
            KeyEvent.VK_UP -> changeVolume((volume + 5).coerceAtMost(100.0))
            KeyEvent.VK_DOWN -> changeVolume((volume - 5).coerceAtLeast(0.0))
            KeyEvent.VK_M -> toggleMute()
            KeyEvent.VK_F -> toggleFullscreen()
            KeyEvent.VK_C -> cycleSubtitles()
            KeyEvent.VK_Z -> toggleFill()
            KeyEvent.VK_ENTER -> skip()
            KeyEvent.VK_ESCAPE -> if (fullscreen) player?.set("fullscreen", "no") else onClose()
            else -> return false
        }
        return true
    }

    /** Full screen on or off. mpv's own setting decides it, so the window follows mpv. */
    fun toggleFullscreen() {
        player?.set("fullscreen", if (fullscreen) "no" else "yes")
    }

    /** Whether a playlist is playing, which can loop and be shown beside the video. */
    val hasPlaylist: Boolean
        get() = item != null && source.isPlaylist

    /** Whether the playlist is open beside the video. */
    var playlistOpen by mutableStateOf(false)
        private set

    /** The playlist's name and videos, kept here so the playlist redraws as they load. */
    var playlistName by mutableStateOf("")
        private set

    var playlistVideos by mutableStateOf<List<TaVideo>>(emptyList())
        private set

    /** Opens or closes the playlist, loading it again as it opens. */
    fun togglePlaylist() {
        playlistOpen = !playlistOpen
        if (!playlistOpen) return
        scope.launch {
            try {
                source.refreshPlaylist()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The copy from when the video started will do
            }
            showPlaylist()
        }
    }

    private fun showPlaylist() {
        playlistName = source.playlistName
        playlistVideos = source.playlistVideos
    }

    /** Plays one of the playlist's videos, picked from the playlist. */
    fun playFromPlaylist(videoId: String) {
        val current = item ?: return
        if (current.id == videoId) return
        reportProgress(position, paused = true)
        scope.launch {
            try {
                val picked = source.playlistItem(videoId) ?: return@launch
                item = picked
                started = null
                startIfReady()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = errorText(e)
            }
        }
    }

    /** Turns starting the playlist over after its last video on or off. */
    fun changeLoop(on: Boolean) {
        loop = on
        source.loop = on
        item?.let(::loadUpNext)
    }

    var error by mutableStateOf<String?>(null)
    var loading by mutableStateOf(true)
    var position by mutableStateOf(0.0)
        private set

    var duration by mutableStateOf(0.0)
        private set

    var fullscreen by mutableStateOf(false)
    var segment by mutableStateOf<SkipSegment?>(null)
        private set

    var ended by mutableStateOf(false)
        private set

    private var skippedSegment: SkipSegment? = null
    private var pausedAt: Long? = null
    private var lastReport = 0L
    private var lastReported = -1.0
    private var started: String? = null

    suspend fun load() {
        try {
            val first = source.first()
            showPlaylist()
            item = first
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = errorText(e)
            loading = false
        }
    }

    /** Starts the item once both it and mpv are ready. */
    fun startIfReady() {
        val player = player ?: return
        val item = item ?: return
        if (started == item.id) return
        started = item.id
        skippedSegment = null
        segment = null
        ended = false
        upNext = null
        upNextPicture = null
        upNextCancelled = false
        chapters = emptyList()
        chapterTitles = emptyList()
        subtitleTracks = emptyList()
        audioTracks = emptyList()
        if (item.transcriptPost == null) transcriptOpen = false
        position = item.resumeSeconds ?: 0.0
        lastReported = -1.0
        lastReport = System.currentTimeMillis()
        loading = true
        error = null
        player.set(
            "start",
            item.resumeSeconds?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "none",
        )
        player.set("force-media-title", item.title)
        // mpv pauses at the end of what it played last, and would stay paused for this one
        player.set("pause", "no")
        // A player that played something else before can still be looping that
        applyLoop()
        // One header as it is, commas and all, rather than a list split at the commas
        player.command("change-list", "http-header-fields", "clr", "")
        item.header?.let { player.command("change-list", "http-header-fields", "append", it) }
        player.command("loadfile", item.url, "replace")
        loadUpNext(item)
    }

    /** Finds what plays after [item], for the button that plays it and for autoplay. */
    private fun loadUpNext(item: PlayItem) {
        scope.launch {
            val next =
                try {
                    source.upNext(item)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            upNext = next?.title
            upNextPicture = next?.picture
        }
    }

    /** Whether the stream's quality can be chosen, as Jellyfin's can. */
    val canChooseQuality: Boolean
        get() = item != null && source.hasQualities

    /** Streams at [quality] from now on, and loads what plays again at it, from the same moment. */
    fun changeQuality(quality: StreamQuality) {
        if (settings.streamQuality == quality.key) return
        settings.streamQuality = quality.key
        val current = item ?: return
        val at = position
        scope.launch {
            try {
                val again = source.reload(current, at) ?: return@launch
                item = again
                started = null
                startIfReady()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = errorText(e)
            }
        }
    }

    /** Whether the picture is zoomed to fill the window, cutting off its edges, or shown whole. */
    var fills by mutableStateOf(false)
        private set

    fun toggleFill() {
        fills = !fills
        player?.set("panscan", if (fills) "1.0" else "0.0")
    }

    /** Called when mpv was stopped from outside, such as with the keyboard's Stop key. */
    var onStopped: (() -> Unit)? = null

    /** Adds the subtitle files next to the video, once it has loaded, and says it started. */
    fun onFileLoaded() {
        loading = false
        val player = player ?: return
        val item = item ?: return
        // mpv starts a looping file over from its start option, which was where this one was
        // picked up. It has done its work, and without it the loop goes back to the beginning
        player.set("start", "none")
        item.subtitles.forEach { subtitle ->
            player.command("sub-add", subtitle.url, "auto", subtitle.title, subtitle.language ?: "")
        }
        // The tracks the server picked, such as Jellyfin by the user's language settings
        item.audioTrack?.let { player.set("aid", it.toString()) }
        item.subtitleTrack?.let { player.set("sid", if (it == 0) "no" else it.toString()) }
        readTracks(player)
        subtitleId = player.get("sid")?.toIntOrNull()
        audioId = player.get("aid")?.toIntOrNull()
        val report = report(item.resumeSeconds ?: 0.0, paused = false)
        backgroundScope.launch { runCatching { source.started(item, report) } }
    }

    fun onLoadFailed(message: String) {
        loading = false
        error = "Couldn't play this: $message"
    }

    /** Reads mpv's state, then acts on it. Called a few times a second. */
    fun tick(onFullscreen: (Boolean) -> Unit) {
        val player = player ?: return
        val before = position
        player.getDouble("time-pos")?.let { position = it }
        player.getDouble("duration")?.let { duration = it }
        val wantsFullscreen = player.getFlag("fullscreen")
        if (wantsFullscreen != fullscreen) {
            fullscreen = wantsFullscreen
            onFullscreen(wantsFullscreen)
        }
        val pausedBefore = paused
        paused = player.getFlag("pause")
        muted = player.getFlag("mute")
        // Set from elsewhere too, such as with mpv's own keys; not while a sleep timer fades it
        player.getDouble("volume")?.let {
            if (fadeFrom == null) {
                volume = it
                rememberVolume(it)
            }
        }
        player.getDouble("speed")?.let { speed = it }
        subtitleId = player.get("sid")?.toIntOrNull()
        audioId = player.get("aid")?.toIntOrNull()
        // Until the new file has loaded, mpv's state can still be the last one's
        if (loading) return
        // A looping video that has started over has its segments to skip again
        val startedOver =
            loopVideo &&
                position < before &&
                position < LOOP_EDGE_SECONDS &&
                before > duration - LOOP_EDGE_SECONDS
        if (startedOver) skippedSegment = null
        checkSegment(player)
        val now = System.currentTimeMillis()
        if (paused && !pausedBefore) {
            // Where it was paused is saved right away, rather than with the next regular save
            pausedAt = now
            lastReport = now
            reportProgress(position, paused = true)
        } else if (!paused && pausedBefore) {
            // Played again from elsewhere, such as a media key
            val since = pausedAt
            pausedAt = null
            if (since != null) playedAgainAfter(now - since)
        } else if (!paused && now - lastReport > PROGRESS_INTERVAL_MS) {
            lastReport = now
            reportProgress(position, paused = false)
        }
        checkSleep(player, now)
        if (error == null && player.getFlag("idle-active")) {
            // Stopped from outside, such as with the keyboard's Stop key: there's nothing loaded
            onStopped?.invoke()
            return
        }
        val atEnd = player.getFlag("eof-reached")
        if (atEnd && !ended) {
            ended = true
            onEnded()
        } else if (!atEnd && ended) {
            // Gone back from the end
            ended = false
        }
    }

    private fun checkSegment(player: MpvPlayer) {
        val segments = item?.segments.orEmpty()
        val current = segments.firstOrNull { position >= it.start && position < it.end - 0.5 }
        if (current == null) {
            segment = null
            return
        }
        if (current == skippedSegment) return
        if (current.auto) {
            skippedSegment = current
            skip(current)
            player.command("show-text", "Skipped: ${categoryName(current.category)}", "2000")
        } else if (segment != current) {
            segment = current
            // So it can be skipped from the keyboard, also in full screen
            player.command("keybind", "ENTER", "seek ${current.end} absolute")
            player.command(
                "show-text",
                "${categoryName(current.category)}: press Enter to skip",
                "4000",
            )
        }
    }

    fun skip(target: SkipSegment? = segment) {
        val skipping = target ?: return
        skippedSegment = skipping
        segment = null
        player?.command("seek", skipping.end.toString(), "absolute")
        player?.command("keybind", "ENTER", "ignore")
    }

    private fun onEnded() {
        val current = item ?: return
        val length = duration.takeIf { it > 0 } ?: position
        reportProgress(length, paused = true)
        backgroundScope.launch { runCatching { source.ended(current) } }
        if (sleep?.atEnd == true) {
            // The sleep timer was for the end of this one, so nothing follows it
            sleep = null
            applyLoop()
            return
        }
        if (settings.autoplay && upNext != null && !upNextCancelled) {
            scope.launch {
                delay(1500)
                if (ended && item == current) playNext()
            }
        }
    }

    fun playNext() {
        val current = item ?: return
        reportProgress(position, paused = true)
        scope.launch {
            try {
                val next = source.next(current) ?: return@launch
                item = next
                // A looping playlist of one video plays the same one again
                started = null
                startIfReady()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = errorText(e)
            }
        }
    }

    private fun reportProgress(seconds: Double, paused: Boolean) {
        val current = item ?: return
        if (seconds <= 0 || seconds == lastReported) return
        lastReported = seconds
        val report = report(seconds, paused)
        backgroundScope.launch { runCatching { source.progress(current, report) } }
    }

    /** Where it is and what it plays, with the tracks and volume as last read from mpv. */
    private fun report(seconds: Double, paused: Boolean) =
        PlaybackReport(
            seconds = seconds,
            paused = paused,
            muted = muted,
            volume = volume.roundToInt().coerceIn(0, 100),
            audioId = audioId,
            subtitleId = subtitleId,
            duration = duration.takeIf { it > 0 },
        )

    /** Saves where it got to and stops mpv. Called before its window goes. */
    fun release() {
        val player = player ?: return
        this.player = null
        // Where mpv is, or where it last was when it has stopped already
        val last = player.getDouble("time-pos") ?: position.takeIf { !loading && it > 0 }
        val current = item
        if (last != null && current != null) {
            val report = report(last, paused = true)
            backgroundScope.launch { runCatching { source.stopped(current, report) } }
        }
        player.destroy()
    }

    companion object {
        private const val PROGRESS_INTERVAL_MS = 10_000L
        val SPEEDS = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0)

        /** The audio player's speeds, which go further, as a podcast app's do. */
        val AUDIO_SPEEDS = listOf(0.5, 0.75, 1.0, 1.1, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0)

        // As the phone app's audio player: 5 seconds back after a minute or more paused, and a
        // sleep timer that fades over its last 10 seconds and can't be moved to under a minute
        private const val REWIND_AFTER_PAUSE_MS = 60_000L
        private const val REWIND_SECONDS = 5.0
        private const val SLEEP_FADE_MS = 10_000L
        private const val MIN_SLEEP_MS = 60_000L

        /** How long before the end the Up next card shows, in seconds. */
        private const val UP_NEXT_SECONDS = 20.0

        // How near the end and the beginning a looping video is taken to have started over
        private const val LOOP_EDGE_SECONDS = 3.0

        // Going to another device's place: asked when played again after this long a pause, waited
        // for this long at most, not gone to when it's this near, and said for this long
        private const val CATCH_UP_AFTER_PAUSE_MS = 60_000L
        private const val CATCH_UP_WAIT_MS = 4_000L
        private const val CATCH_UP_NEAR_SECONDS = 2.0
        private const val NOTICE_MS = 6_000L

        fun categoryName(category: String): String =
            when (category) {
                "sponsor" -> "Sponsor"
                "selfpromo" -> "Self promotion"
                "interaction" -> "Interaction reminder"
                "intro" -> "Intro"
                "outro" -> "Outro"
                "preview" -> "Preview"
                "music_offtopic" -> "Non-music section"
                "filler" -> "Filler"
                "recap" -> "Recap"
                "commercial" -> "Commercial"
                else -> category.replaceFirstChar { it.uppercase() }
            }
    }
}

/**
 * Plays a video with mpv, drawn inside the window, with controls as Jellyfin's over it. mpv draws
 * in a native window of its own that nothing in this window can be drawn over, so the controls sit
 * in a see-through window on top, kept over the video as the window moves. Turned off in the
 * settings, they go above and below the video instead.
 *
 * With [mini] it is the mini player: the video alone in a small box with a few controls, for a
 * corner of the window while its pages are used. It's the same player made small, so the video
 * plays on through the change; [onMini] and [onFull] ask for one and the other.
 */
@Composable
fun PlayerScreen(
    request: PlayerRequest,
    onClose: () -> Unit,
    mini: Boolean = false,
    onMini: () -> Unit = {},
    onFull: () -> Unit = {},
) {
    val app = LocalApp.current
    val navigator = LocalNavigator.current
    val windowState = LocalWindowState.current
    val appWindow = LocalAppWindow.current
    val scope = rememberCoroutineScope()
    val session =
        remember(request) { PlayerSession(playbackSource(app, request), app.settings, scope) }
    val close by rememberUpdatedState(onClose)
    val small by rememberUpdatedState(mini)
    val grow by rememberUpdatedState(onFull)
    val lib = remember { MpvLoader.load() }
    var placementBefore by remember { mutableStateOf(windowState.placement) }

    fun setFullscreen(on: Boolean) {
        if (on) {
            if (windowState.placement != WindowPlacement.Fullscreen)
                placementBefore = windowState.placement
            windowState.placement = WindowPlacement.Fullscreen
        } else if (windowState.placement == WindowPlacement.Fullscreen) {
            windowState.placement =
                placementBefore.takeIf { it != WindowPlacement.Fullscreen }
                    ?: WindowPlacement.Floating
        }
    }

    LaunchedEffect(session) {
        session.onStopped = { close() }
        session.load()
    }
    LaunchedEffect(session, session.player, session.item) { session.startIfReady() }
    LaunchedEffect(session, session.player) {
        if (session.player == null) return@LaunchedEffect
        while (isActive) {
            session.tick(
                onFullscreen = { on ->
                    if (on && small) {
                        // Asked of the small box, such as with a double click on mpv's picture:
                        // that's for the whole player, not for a small box on an empty screen
                        session.player?.set("fullscreen", "no")
                        grow()
                    } else {
                        setFullscreen(on)
                    }
                }
            )
            delay(250)
        }
    }
    DisposableEffect(Unit) { onDispose { setFullscreen(false) } }

    // Back at the window with the video paused: another device may have gone on with it since.
    // While it plays, the newest place is this player's own
    OnComingBack(appWindow) { if (session.paused) session.catchUp() }
    // Where it went is said over the video; around the mini player the window's own line shows
    val notice = session.notice
    LaunchedEffect(notice) { if (notice != null && small) navigator.message(notice) }

    // Keys work wherever the focus is in the window. mpv's own keys don't reach it when it draws
    // inside Winchivist, and a button that had the focus can be gone, such as in full screen. Not
    // for the mini player: the keys are then the pages', for typing in a search box, say
    DisposableEffect(session, mini) {
        if (mini) return@DisposableEffect onDispose {}
        val keys = KeyEventDispatcher { event ->
            event.id == KeyEvent.KEY_PRESSED &&
                !event.isAltDown &&
                !event.isControlDown &&
                session.onKey(event.keyCode, onClose = close)
        }
        // Without a screen, such as for the page renders, there are no keys
        val focusManager =
            if (GraphicsEnvironment.isHeadless()) null
            else KeyboardFocusManager.getCurrentKeyboardFocusManager()
        focusManager?.addKeyEventDispatcher(keys)
        onDispose { focusManager?.removeKeyEventDispatcher(keys) }
    }

    val actions =
        PlayerActions(
            onClose = { close() },
            onOpenPlaylistPage = {
                val playlistId = (request as? PlayerRequest.Ta)?.playlistId
                close()
                if (playlistId != null) navigator.open(Route.Playlist(playlistId))
            },
            // The page of what plays, which can be a later video than the one asked for
            onOpenPage = {
                val route = session.item?.route
                close()
                if (route != null) navigator.open(route)
            },
            // The sound goes on in the audio player, from the same moment
            onSoundOnly = {
                val sound = soundRequest(session, request)
                close()
                if (sound != null) navigator.listen(sound)
            },
            // Out of full screen first: the small box is for a window with pages in it
            onMini = {
                if (session.fullscreen) session.player?.set("fullscreen", "no")
                onMini()
            },
            onFull = { grow() },
        )
    val video: @Composable () -> Unit = {
        MpvVideo(lib = lib, session = session, settings = app.settings, onShutdown = { close() })
    }

    val overlay =
        app.settings.playerControlsOverVideo &&
            appWindow != null &&
            lib != null &&
            !GraphicsEnvironment.isHeadless()
    // Whole, it fills the window. Small, it's as wide as the mini player and as tall as a 16:9
    // picture, plus its controls where those can't go over the picture
    val size =
        when {
            !mini -> Modifier.fillMaxSize()
            overlay -> Modifier.width(MINI_PLAYER_WIDTH).aspectRatio(16f / 9f)
            else -> Modifier.width(MINI_PLAYER_WIDTH)
        }
    BoxWithConstraints(modifier = size.background(Color.Black)) {
        // Beside the video, which moves over and stays whole
        val transcriptWidth = minOf(maxWidth * TRANSCRIPT_SHARE, TRANSCRIPT_MAX_WIDTH)
        val transcript: @Composable () -> Unit = {
            if (!mini) PlayerTranscript(session = session, width = transcriptWidth)
        }
        if (overlay) {
            var videoBounds by remember { mutableStateOf<Rect?>(null) }
            Row(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier =
                        Modifier.weight(1f).fillMaxHeight().onGloballyPositioned {
                            videoBounds = it.boundsInWindow()
                        }
                ) {
                    video()
                }
                transcript()
            }
            OverlayWindow(owner = appWindow!!, bounds = videoBounds) {
                CompositionLocalProvider(LocalApp provides app) {
                    TubedroidTheme(accent = app.settings.accent) {
                        if (mini) MiniOsd(session = session, actions = actions)
                        else PlayerOsd(session = session, actions = actions)
                    }
                }
            }
        } else {
            PlayerWithBars(
                session = session,
                actions = actions,
                mini = mini,
                video = video,
                transcript = transcript,
            )
        }
    }
}

/** How wide the mini player's picture is. */
val MINI_PLAYER_WIDTH = 400.dp

/** What the player's controls can do beyond the video itself. */
internal class PlayerActions(
    val onClose: () -> Unit,
    val onOpenPlaylistPage: () -> Unit = {},
    val onOpenPage: () -> Unit = {},
    val onSoundOnly: () -> Unit = {},
    /** Makes it the mini player, and the whole player again. */
    val onMini: () -> Unit = {},
    val onFull: () -> Unit = {},
)

/**
 * What the audio player plays to go on with [session]'s sound from the same moment. A JRWI episode
 * with an audio version goes on in that, at the matching moment; anything else in its own sound.
 */
internal fun soundRequest(session: PlayerSession, request: PlayerRequest): PlayerRequest? {
    val item = session.item ?: return null
    val seconds = session.position
    val episode = item.jrwi
    val audio = episode?.audio
    if (episode != null && audio != null && !item.jrwiAudio) {
        return PlayerRequest.Jrwi(
            postId = audio.postId,
            audio = true,
            startSeconds = episode.audioSeconds(seconds),
        )
    }
    // What plays now, which in a playlist or a show can be a later one than was asked for
    return when (request) {
        is PlayerRequest.Ta -> PlayerRequest.Ta(item.id, request.playlistId, startSeconds = seconds)
        is PlayerRequest.Jellyfin -> PlayerRequest.Jellyfin(item.id, startSeconds = seconds)
        is PlayerRequest.Jrwi -> request.startingAt(seconds)
    }
}

/**
 * The transcript of a JRWI episode beside its video, in the archive's colours, following what
 * plays. Clicking a line plays from it.
 */
@Composable
private fun PlayerTranscript(session: PlayerSession, width: Dp) {
    val postId = session.item?.transcriptPost
    AnimatedVisibility(
        visible = session.transcriptOpen && postId != null,
        enter = expandHorizontally(expandFrom = Alignment.Start),
        exit = shrinkHorizontally(shrinkTowards = Alignment.Start),
    ) {
        if (postId != null) {
            JrwiTheme {
                TranscriptPanel(
                    postId = postId,
                    position = { session.position },
                    onSeek = session::seekTo,
                    onClose = session::toggleTranscript,
                    modifier = Modifier.width(width).fillMaxHeight(),
                )
            }
        }
    }
}

private const val TRANSCRIPT_SHARE = 0.4f
private val TRANSCRIPT_MAX_WIDTH = 420.dp

/**
 * The controls above and below the video rather than over it, for when the window over the video
 * can't be shown. The playlist opens beside the video. As the mini player it's the picture with one
 * row of controls under it.
 */
@Composable
private fun PlayerWithBars(
    session: PlayerSession,
    actions: PlayerActions,
    mini: Boolean,
    video: @Composable () -> Unit,
    transcript: @Composable () -> Unit,
) {
    val bars = MaterialTheme.colorScheme.surfaceContainerLowest
    // The video stays where it is in this layout whichever size shows, so mpv's picture isn't
    // taken away and made again between them
    Column(
        modifier = (if (mini) Modifier.fillMaxWidth() else Modifier.fillMaxSize()).background(bars)
    ) {
        if (!mini) {
            OsdTop(
                session = session,
                actions = actions,
                modifier = Modifier.background(bars).padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        Row(
            modifier =
                (if (mini) Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                    else Modifier.weight(1f).fillMaxWidth())
                    .background(Color.Black)
        ) {
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                video()
            }
            AnimatedVisibility(
                visible = session.playlistOpen && !mini,
                enter = expandHorizontally(expandFrom = Alignment.Start),
                exit = shrinkHorizontally(shrinkTowards = Alignment.Start),
            ) {
                PlaylistPanel(
                    name = session.playlistName,
                    videos = session.playlistVideos,
                    currentVideoId = session.item?.id,
                    loop = session.loop,
                    onPlay = session::playFromPlaylist,
                    onLoopChange = session::changeLoop,
                    onOpenPage = actions.onOpenPlaylistPage,
                    onClose = session::togglePlaylist,
                )
            }
            transcript()
        }
        if (mini) {
            MiniBar(session = session, actions = actions)
        } else {
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.background(bars)) {
                OsdNotice(session.notice, modifier = Modifier.padding(top = 8.dp, end = 20.dp))
                OsdExtras(session = session, modifier = Modifier.padding(top = 8.dp, end = 20.dp))
                OsdBottom(
                    session = session,
                    onMenu = {},
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** mpv's picture, in a native window inside this one, or why it can't play. */
@Composable
private fun MpvVideo(
    lib: MpvLib?,
    session: PlayerSession,
    settings: AppSettings,
    onShutdown: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    if (lib == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Message(MpvLoader.loadError ?: "Couldn't load mpv")
        }
        return
    }
    SwingPanel(
        background = Color.Black,
        modifier = Modifier.fillMaxSize(),
        factory = {
            MpvCanvas(
                onReady = { canvas ->
                    try {
                        session.player =
                            MpvPlayer.create(
                                lib = lib,
                                wid = Native.getComponentID(canvas),
                                options = mpvOptions(settings),
                                onEvent = { event ->
                                    scope.launch(Dispatchers.Main) {
                                        when (event) {
                                            MpvEvent.FileLoaded -> session.onFileLoaded()
                                            is MpvEvent.LoadFailed ->
                                                session.onLoadFailed(event.message)
                                            MpvEvent.Shutdown -> onShutdown()
                                            // What only the audio player asks to be told
                                            else -> {}
                                        }
                                    }
                                },
                            )
                    } catch (e: Exception) {
                        session.error = e.message ?: e.toString()
                    }
                },
                onGone = { session.release() },
            )
        },
    )
}

/**
 * A see-through window of its own, kept exactly over [bounds] of [owner]'s content: it moves and
 * resizes with it, stays above it, and never takes the keyboard from it. Only what's drawn in it
 * shows; mpv's picture shows through the rest.
 */
@Composable
private fun OverlayWindow(owner: Window, bounds: Rect?, content: @Composable () -> Unit) {
    val density = LocalDensity.current.density
    val latest = rememberUpdatedState(content)
    var moves by remember { mutableIntStateOf(0) }
    val dialog =
        remember(owner) {
            ComposeDialog(owner, java.awt.Dialog.ModalityType.MODELESS).apply {
                isUndecorated = true
                isTransparent = true
                focusableWindowState = false
                setContent { latest.value() }
            }
        }
    DisposableEffect(owner, dialog) {
        val follow =
            object : ComponentAdapter() {
                override fun componentMoved(e: ComponentEvent) {
                    moves++
                }

                override fun componentResized(e: ComponentEvent) {
                    moves++
                }
            }
        owner.addComponentListener(follow)
        onDispose {
            owner.removeComponentListener(follow)
            dialog.dispose()
        }
    }
    LaunchedEffect(dialog, bounds, moves) {
        val area = bounds ?: return@LaunchedEffect
        val origin =
            try {
                ((owner as? RootPaneContainer)?.contentPane ?: owner).locationOnScreen
            } catch (e: IllegalComponentStateException) {
                return@LaunchedEffect
            }
        dialog.setBounds(
            origin.x + (area.left / density).roundToInt(),
            origin.y + (area.top / density).roundToInt(),
            (area.width / density).roundToInt().coerceAtLeast(1),
            (area.height / density).roundToInt().coerceAtLeast(1),
        )
        if (!dialog.isVisible) dialog.isVisible = true
    }
}

@Composable
private fun Message(text: String) {
    Text(
        text = text,
        color = Color.White,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(Spacings.large),
    )
}

/**
 * mpv's settings, as on its command line. A user's own mpv.conf in the config folder comes on top.
 */
private fun mpvOptions(settings: AppSettings): List<Pair<String, String>> = buildList {
    add("config-dir" to settings.mpvConfigFolder.path)
    add("config" to "yes")
    add("terminal" to "no")
    // Winchivist has its own controls: mpv's don't get the mouse while it draws inside a window
    add("osc" to "no")
    add("input-default-bindings" to "yes")
    add("input-vo-keyboard" to "yes")
    add("keep-open" to "yes")
    add("idle" to "yes")
    add("ytdl" to "no")
    // What was just played stays loaded, so going back a little doesn't fetch it again
    add("cache" to "yes")
    add("demuxer-seekable-cache" to "yes")
    add("demuxer-max-back-bytes" to "96MiB")
    add("hwdec" to settings.hardwareDecoding)
    // As loud as the last thing played was left
    add("volume" to settings.playerVolume.toString())
    // The languages picked in Settings, for the files whose tracks no server picks
    Languages.forMpv(settings.audioLanguage)?.let { add("alang" to it) }
    Languages.forMpv(settings.subtitleLanguage)?.let { add("slang" to it) }
    add("osd-font-size" to "38")
    add("cursor-autohide" to "1000")
    // Windows' media controls and the keyboard's media keys, which mpv leaves off inside an app
    add("media-controls" to "yes")
}

/** The native window mpv draws into. Starts mpv once it exists and stops it before it goes. */
private class MpvCanvas(private val onReady: (Canvas) -> Unit, private val onGone: () -> Unit) :
    Canvas() {
    private var started = false

    init {
        background = java.awt.Color.BLACK
    }

    override fun addNotify() {
        super.addNotify()
        if (!started) {
            started = true
            onReady(this)
        }
    }

    override fun removeNotify() {
        if (started) {
            started = false
            onGone()
        }
        super.removeNotify()
    }
}
