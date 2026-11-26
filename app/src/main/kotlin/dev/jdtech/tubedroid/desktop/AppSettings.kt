package dev.jdtech.tubedroid.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jdtech.jellyfin.jrwi.JrwiConfig
import dev.jdtech.jellyfin.tubearchivist.TaConfig
import java.io.File
import java.util.prefs.Preferences

/**
 * The app's settings, kept in the user's registry by Java's preferences. Each value is also Compose
 * state, so pages follow a change right away. They're still kept under "tubedroid", the app's name
 * before Winchivist, so they carry over.
 */
class AppSettings(private val prefs: Preferences = Preferences.userRoot().node("tubedroid")) :
    TaConfig {

    override var url: String? by stored<String?>("ta_url", null)
    override var token: String? by stored<String?>("ta_token", null)

    /** Where the download button saves videos. The user's Downloads folder unless changed. */
    var downloadFolder: String by
        stored<String>("download_folder", File(System.getProperty("user.home"), "Downloads").path)

    /** Whether the next video of a playlist plays when one ends. */
    var autoplay: Boolean by storedBoolean("autoplay", true)

    /**
     * Whether the player's controls float over the video, as on Jellyfin, rather than sit above and
     * below it.
     */
    var playerControlsOverVideo: Boolean by storedBoolean("player_controls_over_video", true)

    /** How far the players' back and forward buttons go, in seconds. */
    var seekBackSeconds: Int by storedInt("seek_back_seconds", 10)
    var seekForwardSeconds: Int by storedInt("seek_forward_seconds", 10)

    /**
     * How loud the players are, 0 to 100, as last set in either of them. Whatever plays next starts
     * at it.
     */
    var playerVolume: Int by storedInt("player_volume", 100)

    /** How fast the audio player plays, kept apart from the video player's speed. */
    var audioSpeed: Double
        get() = audioSpeedText.toDoubleOrNull()?.coerceIn(0.5, 3.0) ?: 1.0
        set(value) {
            audioSpeedText = value.toString()
        }

    private var audioSpeedText: String by stored<String>("audio_speed", "1.0")

    /** What to do at a SponsorBlock segment: button, auto or off. */
    var sponsorBlock: String by stored<String>("sponsorblock", SPONSORBLOCK_BUTTON)

    /** What to do at Jellyfin's own segments, such as intros and credits: button, auto or off. */
    var mediaSegments: String by stored<String>("media_segments", SPONSORBLOCK_BUTTON)

    /** How Jellyfin's videos are streamed, as a StreamQuality's key: the file itself unless set. */
    var streamQuality: String by stored<String>("stream_quality", "original")

    /** mpv's hardware decoding, as its hwdec option. */
    var hardwareDecoding: String by stored<String>("hwdec", "auto-safe")

    /** Whether the seek bar marks where chapters start. */
    var chapterMarks: Boolean by storedBoolean("chapter_marks", true)

    /** Whether Jellyfin's preview pictures show above the seek bar. */
    var trickplay: Boolean by storedBoolean("trickplay", true)

    /** Whether the video player's buttons are in the accent colour rather than white. */
    var accentControls: Boolean by storedBoolean("accent_controls", false)

    /**
     * The language to play sound in and to show subtitles in when a video has it, as a Language's
     * code. Unset, Jellyfin's own choice for the user is followed.
     */
    var audioLanguage: String? by stored<String?>("audio_language", null)
    var subtitleLanguage: String? by stored<String?>("subtitle_language", null)

    /** The rows on Jellyfin's Home. */
    var homeSuggestions: Boolean by storedBoolean("home_suggestions", true)
    var homeContinue: Boolean by storedBoolean("home_continue", true)
    var homeNextUp: Boolean by storedBoolean("home_next_up", true)
    var homeLatest: Boolean by storedBoolean("home_latest", true)

    /** Whether Home's Latest rows show every new episode by itself, not grouped under its show. */
    var homeLatestEpisodes: Boolean by storedBoolean("home_latest_episodes", true)

    /** Cast and crew on movie pages, and on show and episode pages. */
    var castMovies: Boolean by storedBoolean("cast_movies", true)
    var castShows: Boolean by storedBoolean("cast_shows", true)

    /** Whether movie and episode pages tell the file's size and its tracks. */
    var extraInfo: Boolean by storedBoolean("extra_info", false)

    /** Whether a show's page lists every episode by date instead of a season at a time. */
    var allEpisodes: Boolean by storedBoolean("all_episodes", false)
    var episodesNewestFirst: Boolean by storedBoolean("episodes_newest_first", true)

    /** Whether episode names leave out the season and number, such as "S1:E2". */
    var hideEpisodeNumbers: Boolean by storedBoolean("hide_episode_numbers", false)

    /** Whether cards show an episode's name above its show's, rather than below. */
    var episodeTitleFirst: Boolean by storedBoolean("episode_title_first", false)

    /** How Jellyfin's libraries are ordered, as a JellyfinSort's key, and in which direction. */
    var jellyfinSort: String by stored<String>("jellyfin_sort", "name")
    var jellyfinSortDescending: Boolean by storedBoolean("jellyfin_sort_descending", false)

    /**
     * The compute box: when this PC hears episodes for the archiver's timed transcripts. Unless
     * changed it asks first whether now is a good time, so nothing is heard that wasn't agreed to.
     */
    var hearWhen: String by stored<String>("hear_when", HEAR_ASK)

    /**
     * With asking first: the moment before which it doesn't ask, in milliseconds since 1970, after
     * the answer was "not now".
     */
    var hearAskAfter: String? by stored<String?>("hear_ask_after", null)

    /** The speech model that hears, and where: auto, cuda (the graphics card) or cpu. */
    var hearModel: String by stored<String>("hear_model", "large-v3-turbo")
    var hearDevice: String by stored<String>("hear_device", "auto")

    /** Whether every episode the model hasn't heard is heard, or only those not timed yet. */
    var hearEvery: Boolean by storedBoolean("hear_every", true)

    /** For hearing by itself: how long the PC has to be idle, or between which hours. */
    var hearIdleMinutes: Int by storedInt("hear_idle_minutes", 10)
    var hearFromHour: Int by storedInt("hear_from_hour", 1)
    var hearToHour: Int by storedInt("hear_to_hour", 7)

    /** The Python and the copy of the archiver's program to hear with, when not found by itself. */
    var hearPython: String? by stored<String?>("hear_python", null)
    var hearProgram: String? by stored<String?>("hear_program", null)

    /** A Windows notification when a movie or an episode is new on Jellyfin. */
    var notifyNewItems: Boolean by storedBoolean("notify_new_items", true)

    /** A Windows notification when YouTube's bot detection stops a TubeArchivist task. */
    var notifyBots: Boolean by storedBoolean("notify_bots", true)

    /** Whether closing the window leaves Winchivist running in the system tray. */
    var keepInTray: Boolean by storedBoolean("keep_in_tray", false)

    /**
     * Whether the window has Winchivist's own bar in place of Windows' own. Read when Winchivist
     * starts: a window can't change its frame once it shows.
     */
    var ownWindowBar: Boolean by storedBoolean("own_window_bar", true)

    /** Whether Windows starts Winchivist, in the tray, when the user signs in. */
    var startWithWindows: Boolean by storedBoolean("start_with_windows", false)

    /** How much room the pictures kept on disk may take, in megabytes. Read when the app starts. */
    var imageCacheMb: Int by storedInt("image_cache_mb", 512)

    // What the bot detection check has seen: for which server, since when, and which failures
    var botCheckedServer: String? by stored<String?>("bot_checked_server", null)
    var botCheckedSince: String? by stored<String?>("bot_checked_since", null)
    var botSeen: String? by stored<String?>("bot_seen", null)

    /** The accent preset, by the same names as the phone app. */
    var accent: String by stored<String>("accent", "tubearchivist")

    /** Whether TubeArchivist is set up. */
    val isConfigured: Boolean
        get() = !url.isNullOrBlank() && !token.isNullOrBlank()

    /** The JRWI archiver's address and API token, from its web app's Settings. */
    var jrwiUrl: String? by stored<String?>("jrwi_url", null)
    var jrwiToken: String? by stored<String?>("jrwi_token", null)

    val isJrwiConfigured: Boolean
        get() = !jrwiUrl.isNullOrBlank() && !jrwiToken.isNullOrBlank()

    /** The archiver's address and token as its API reads them, each time they're needed. */
    val jrwi: JrwiConfig =
        object : JrwiConfig {
            override val url: String?
                get() = jrwiUrl

            override val token: String?
                get() = jrwiToken
        }

    /** The Jellyfin server and the user signed in to it. The password itself isn't kept. */
    var jellyfinUrl: String? by stored<String?>("jellyfin_url", null)
    var jellyfinToken: String? by stored<String?>("jellyfin_token", null)
    var jellyfinUserId: String? by stored<String?>("jellyfin_user_id", null)
    var jellyfinUserName: String? by stored<String?>("jellyfin_user_name", null)

    /** What was watched here, as WatchHistory keeps it. */
    var watchHistory: String? by stored<String?>("watch_history", null)

    /** This computer, as Jellyfin's list of devices shows it; made up once. */
    val deviceId: String
        get() =
            prefs.get("device_id", null)
                ?: java.util.UUID.randomUUID().toString().also { prefs.put("device_id", it) }

    /** Where mpv.conf, input.conf and mpv scripts can be put, like Jellyfin Media Player has. */
    val mpvConfigFolder: File
        get() {
            val base =
                System.getenv("APPDATA")?.let(::File) ?: File(System.getProperty("user.home"))
            val folder = File(base, "Winchivist/mpv")
            // Kept from when the app was called Tubedroid on Windows too
            val before = File(base, "Tubedroid/mpv")
            if (!folder.exists() && before.isDirectory) {
                folder.parentFile?.mkdirs()
                before.renameTo(folder)
            }
            return folder.also { it.mkdirs() }
        }

    private fun <T : String?> stored(key: String, default: T) =
        object : kotlin.properties.ReadWriteProperty<Any?, T> {
            private val state = mutableStateOf(read())

            @Suppress("UNCHECKED_CAST") private fun read(): T = prefs.get(key, default) as T

            override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): T =
                state.value

            override fun setValue(
                thisRef: Any?,
                property: kotlin.reflect.KProperty<*>,
                value: T,
            ) {
                if (value == null) prefs.remove(key) else prefs.put(key, value)
                state.value = value
            }
        }

    private fun storedInt(key: String, default: Int) =
        object : kotlin.properties.ReadWriteProperty<Any?, Int> {
            private var state by mutableStateOf(prefs.getInt(key, default))

            override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): Int = state

            override fun setValue(
                thisRef: Any?,
                property: kotlin.reflect.KProperty<*>,
                value: Int,
            ) {
                prefs.putInt(key, value)
                state = value
            }
        }

    private fun storedBoolean(key: String, default: Boolean) =
        object : kotlin.properties.ReadWriteProperty<Any?, Boolean> {
            private var state by mutableStateOf(prefs.getBoolean(key, default))

            override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): Boolean =
                state

            override fun setValue(
                thisRef: Any?,
                property: kotlin.reflect.KProperty<*>,
                value: Boolean,
            ) {
                prefs.putBoolean(key, value)
                state = value
            }
        }

    companion object {
        const val SPONSORBLOCK_BUTTON = "button"
        const val SPONSORBLOCK_AUTO = "auto"
        const val SPONSORBLOCK_OFF = "off"

        /**
         * When the compute box hears: after asking, started by hand, with the PC idle, or between
         * hours.
         */
        const val HEAR_ASK = "ask"
        const val HEAR_BY_HAND = "hand"
        const val HEAR_IDLE = "idle"
        const val HEAR_HOURS = "hours"
    }
}
