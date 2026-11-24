package dev.jdtech.tubedroid.desktop.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jdtech.jellyfin.jrwi.JrwiItem
import dev.jdtech.tubedroid.desktop.AppGraph
import dev.jdtech.tubedroid.desktop.jellyfin.Languages
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The app's audio player, apart from the video player, as a podcast app has it: a JRWI episode's
 * audio from the archive, or the sound of a video from TubeArchivist or Jellyfin. It plays on while
 * the pages are used, with a bar along the bottom of the window, and goes on to the next episode or
 * video. One mpv without a picture plays it, started when something is first played and stopped
 * when the player is closed, so nothing runs while it isn't used.
 *
 * Its progress goes where the video player's does, through the same sources, so a JRWI episode's
 * place is kept in the archive and handed on to its video.
 */
class AudioPlayer(private val app: AppGraph) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** What's loaded, playing or not, or null when the player is closed. */
    internal var session by mutableStateOf<PlayerSession?>(null)
        private set

    private var request: PlayerRequest? = null
    private var running: CoroutineScope? = null

    // The sound devices when it last looked, when one of them went away, and when the sound output
    // was last opened again: both at once means the device in use went away
    private var devices: Set<String> = emptySet()
    private var deviceGoneAt = 0L
    private var outputChangedAt = 0L

    /** Whether the full player shows, rather than only the bar. */
    var expanded by mutableStateOf(false)

    val active: Boolean
        get() = session != null

    /**
     * Plays [request]'s sound. What's loaded already only plays on, from its start or the moment
     * asked for when the request says so.
     */
    fun play(request: PlayerRequest) {
        val loaded = session
        if (loaded != null && sameThing(request)) {
            when {
                request.fromStart -> loaded.seekTo(0.0)
                request.startSeconds != null -> loaded.seekTo(request.startSeconds ?: 0.0)
            }
            if (loaded.paused) loaded.togglePause()
            tick()
            return
        }
        close()
        val lib = MpvLoader.load()
        val running = CoroutineScope(scope.coroutineContext + Job(scope.coroutineContext[Job]))
        val session =
            PlayerSession(playbackSource(app, request), app.settings, running, audio = true)
        this.running = running
        this.request = request
        this.session = session
        session.onStopped = { if (this.session === session) close() }
        if (lib == null) {
            session.loading = false
            session.error = MpvLoader.loadError ?: "Couldn't load mpv"
            return
        }
        try {
            val player =
                MpvPlayer.create(lib = lib, wid = null, options = options()) { event ->
                    scope.launch { onEvent(session, event) }
                }
            session.player = player
            deviceGoneAt = 0L
            outputChangedAt = 0L
            devices = player.audioDevices()
            player.watchAudioDevices()
        } catch (e: Exception) {
            session.loading = false
            session.error = e.message ?: e.toString()
            return
        }
        running.launch {
            session.load()
            session.startIfReady()
            while (isActive) {
                session.tick {}
                // Paused, there's little to follow
                delay(if (session.paused) PAUSED_TICK_MS else TICK_MS)
            }
        }
    }

    /** Shows the player as at a point of [request], for the page renders, which have no mpv. */
    internal suspend fun showForPreview(
        request: PlayerRequest,
        position: Double,
        duration: Double,
    ) {
        close()
        val running = CoroutineScope(scope.coroutineContext + Job(scope.coroutineContext[Job]))
        val session =
            PlayerSession(playbackSource(app, request), app.settings, running, audio = true)
        session.load()
        session.showForPreview(position, duration, chapters = emptyList())
        this.running = running
        this.request = request
        this.session = session
    }

    private fun onEvent(session: PlayerSession, event: MpvEvent) {
        if (this.session !== session) return
        when (event) {
            MpvEvent.FileLoaded -> {
                session.onFileLoaded()
                showPicture(session)
            }
            is MpvEvent.LoadFailed -> session.onLoadFailed(event.message)
            MpvEvent.Shutdown -> close()
            MpvEvent.AudioDevicesChanged -> {
                val now = session.player?.audioDevices() ?: return
                if (devices.any { it !in now }) deviceGoneAt = System.currentTimeMillis()
                devices = now
                pauseIfUnplugged(session)
            }
            MpvEvent.AudioOutputChanged -> {
                outputChangedAt = System.currentTimeMillis()
                pauseIfUnplugged(session)
            }
        }
    }

    /**
     * Pauses when the device the sound was on went away, such as headphones unplugged or switched
     * off, rather than carrying on from the speakers. A device going away while the output stays as
     * it is was another device, such as a screen's speakers when the screen goes to sleep.
     */
    private fun pauseIfUnplugged(session: PlayerSession) {
        if (deviceGoneAt == 0L || outputChangedAt == 0L) return
        if (abs(deviceGoneAt - outputChangedAt) > UNPLUGGED_WITHIN_MS) return
        deviceGoneAt = 0L
        outputChangedAt = 0L
        if (session.paused || session.loading) return
        session.pause()
        tick()
    }

    /**
     * Gives mpv the picture of what plays, as cover art, which it shows in Windows' media controls.
     * Windows reads the picture itself, so it's handed over as a file of the app's own rather than
     * as an address, which for the archive and TubeArchivist would need their token.
     */
    private fun showPicture(session: PlayerSession) {
        val item = session.item ?: return
        val address = item.picture ?: return
        running?.launch {
            val file =
                withContext(Dispatchers.IO) { runCatching { fetchPicture(address) }.getOrNull() }
                    ?: return@launch
            if (this@AudioPlayer.session !== session || session.item?.id != item.id) return@launch
            session.player?.command("video-add", file.path, "auto", "Cover", "", "yes")
        }
    }

    private val pictures by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** Saves the picture at [address] in the app's own folder, in place of the one before it. */
    private fun fetchPicture(address: String): File? {
        val url = address.toHttpUrlOrNull() ?: return null
        val tubeArchivist = app.api.baseUrl?.toHttpUrlOrNull()
        // Each server's token only ever goes to that server
        val header =
            when {
                tubeArchivist != null &&
                    url.host == tubeArchivist.host &&
                    url.port == tubeArchivist.port -> app.api.authHeader
                app.jrwi.api.isArchiverUrl(url) -> app.jrwi.api.authHeader
                else -> null
            }
        val request =
            Request.Builder().url(url).apply { header?.let { header("Authorization", it) } }.build()
        val folder =
            File(
                System.getenv("LOCALAPPDATA") ?: System.getProperty("java.io.tmpdir"),
                "Winchivist/now-playing",
            )
        folder.mkdirs()
        val extension =
            url.encodedPath.substringAfterLast('.', "").takeIf { it.length in 3..4 } ?: "jpg"
        val file = File(folder, "cover-${address.hashCode().toUInt()}.$extension")
        pictures.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            file.outputStream().use { out -> response.body.byteStream().use { it.copyTo(out) } }
        }
        folder.listFiles()?.filter { it != file }?.forEach { it.delete() }
        return file
    }

    /** Whether [request] asks for what's loaded now. */
    private fun sameThing(request: PlayerRequest): Boolean {
        val item = session?.item ?: return false
        return when (request) {
            is PlayerRequest.Ta -> item.id == request.videoId
            is PlayerRequest.Jellyfin -> item.id == request.itemId
            is PlayerRequest.Jrwi -> {
                val episode = item.jrwi ?: return false
                request.audio == item.jrwiAudio &&
                    request.postId in
                        listOfNotNull(episode.id, episode.video?.postId, episode.audio?.postId)
            }
        }
    }

    /** Reads the player's state right away, after something was asked of it. */
    private fun tick() {
        session?.tick {}
    }

    fun togglePause() {
        session?.togglePause()
        tick()
    }

    fun seekTo(seconds: Double) {
        session?.seekTo(seconds)
    }

    fun seekBack() {
        session?.seekBack()
        tick()
    }

    fun seekForward() {
        session?.seekForward()
        tick()
    }

    /** How an episode's audio is doing in this player: whether it plays, and the minutes left. */
    data class Live(val playing: Boolean, val minutesLeft: Int?)

    /** How the audio of [item] is doing here, or null when it isn't what's loaded. */
    fun stateOf(item: JrwiItem): Live? {
        val session = session ?: return null
        val playing = session.item ?: return null
        if (!playing.jrwiAudio || playing.jrwi?.id != item.id) return null
        val left = session.duration - session.position
        return Live(
            playing = !session.paused,
            minutesLeft = if (session.duration > 0 && left > 0) ceil(left / 60).toInt() else null,
        )
    }

    /** Whether what plays has a video to go on with. */
    val hasVideo: Boolean
        get() {
            val item = session?.item ?: return false
            val episode = item.jrwi
            return if (episode != null && item.jrwiAudio) episode.video != null else true
        }

    /**
     * The video of what plays, from the same moment: for a JRWI episode's audio, its video at the
     * matching moment. Null when it has none, or none that can be played.
     */
    suspend fun videoRequest(): PlayerRequest? {
        val session = session ?: return null
        val item = session.item ?: return null
        val request = request ?: return null
        val seconds = session.position
        val episode = item.jrwi
        if (episode != null && item.jrwiAudio) {
            return app.jrwi.videoRequest(episode, startSeconds = episode.videoSeconds(seconds))
        }
        return when (request) {
            is PlayerRequest.Ta ->
                PlayerRequest.Ta(item.id, request.playlistId, startSeconds = seconds)
            is PlayerRequest.Jellyfin -> PlayerRequest.Jellyfin(item.id, startSeconds = seconds)
            is PlayerRequest.Jrwi -> request.startingAt(seconds)
        }
    }

    /**
     * Makes way for the video player, which is about to play [request]. When that's the video of
     * what plays here, it goes on from the same moment.
     */
    fun handOverTo(request: PlayerRequest): PlayerRequest {
        val session = session ?: return request
        val item = session.item
        val seconds = session.position
        close()
        if (item == null || seconds <= 1 || request.fromStart || request.startSeconds != null) {
            return request
        }
        val episode = item.jrwi
        if (episode != null && item.jrwiAudio) {
            val video = episode.video ?: return request
            val same =
                when (request) {
                    is PlayerRequest.Ta -> video.youtubeId == request.videoId
                    is PlayerRequest.Jellyfin ->
                        app.jrwi.knownEpisodeId(episode)?.toString() == request.itemId
                    is PlayerRequest.Jrwi ->
                        !request.audio && request.postId in listOf(episode.id, video.postId)
                }
            return if (same) request.startingAt(episode.videoSeconds(seconds)) else request
        }
        val same =
            when (request) {
                is PlayerRequest.Ta -> item.id == request.videoId
                is PlayerRequest.Jellyfin -> item.id == request.itemId
                is PlayerRequest.Jrwi -> false
            }
        return if (same) request.startingAt(seconds) else request
    }

    /** Saves where it got to and stops playing. The bar goes away. */
    fun close() {
        val session = session ?: return
        this.session = null
        request = null
        expanded = false
        running?.cancel()
        running = null
        session.release()
    }

    /**
     * mpv's settings for sound alone: no picture, no scripts and none of the user's video settings,
     * a small buffer, and Windows' media controls and keys.
     */
    private fun options(): List<Pair<String, String>> = buildList {
        add("config" to "no")
        add("terminal" to "no")
        add("load-scripts" to "no")
        add("osc" to "no")
        add("input-default-bindings" to "no")
        add("vo" to "null")
        add("vid" to "no")
        add("audio-display" to "no")
        add("keep-open" to "yes")
        add("idle" to "yes")
        add("ytdl" to "no")
        add("cache" to "yes")
        add("demuxer-max-bytes" to "48MiB")
        add("demuxer-max-back-bytes" to "16MiB")
        // Speeds up without changing the pitch, as mpv does by default, said here to be sure
        add("audio-pitch-correction" to "yes")
        add("speed" to String.format(Locale.ROOT, "%.2f", app.settings.audioSpeed))
        Languages.forMpv(app.settings.audioLanguage)?.let { add("alang" to it) }
        add("media-controls" to "yes")
    }

    private companion object {
        const val TICK_MS = 250L
        const val UNPLUGGED_WITHIN_MS = 3_000L
        const val PAUSED_TICK_MS = 1_000L
    }
}
