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

    /** A Windows notification when a movie or an episode is new on Jellyfin. */
    var notifyNewItems: Boolean by storedBoolean("notify_new_items", true)

    /** A Windows notification when YouTube's bot detection stops a TubeArchivist task. */
    var notifyBots: Boolean by storedBoolean("notify_bots", true)

    /** Whether closing the window leaves Winchivist running in the system tray. */
    var keepInTray: Boolean by storedBoolean("keep_in_tray", false)

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
    }
}
