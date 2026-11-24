package dev.jdtech.tubedroid.desktop

/** The pages of the app. Those without anything to show first are the sections in the sidebar. */
sealed interface Route {
    data object Home : Route

    /** All of TubeArchivist's videos, as a library. */
    data object Videos : Route

    data object Channels : Route

    data object Playlists : Route

    /** What was saved to this computer in this session. */
    data object Downloads : Route

    /** Search across the servers that are set up, starting with TubeArchivist. */
    data object Search : Route

    data object Stats : Route

    data object Queue : Route

    data object JellyfinHome : Route

    data object JellyfinMovies : Route

    data object JellyfinShows : Route

    data object Settings : Route

    data class Channel(val channelId: String) : Route

    data class Video(val videoId: String, val playlistId: String? = null) : Route

    data class Playlist(val playlistId: String) : Route

    /** A movie or an episode on Jellyfin. */
    data class JellyfinItem(val itemId: String) : Route

    /** A show on Jellyfin, with its seasons. */
    data class JellyfinShow(val seriesId: String) : Route

    /** The JRWI archive's feed, with what was started and not finished. */
    data object JrwiHome : Route

    data object JrwiShows : Route

    /** What the JRWI archiver is doing, and its controls. */
    data object JrwiArchiver : Route

    /** The JRWI archive's numbers. */
    data object JrwiStats : Route

    /** One of the JRWI archive's shows, with its tabs and seasons. */
    data class JrwiShowPage(val showId: String) : Route

    /** A post in the JRWI archive, by the ID of any of its versions' posts. */
    data class JrwiPost(val postId: Long) : Route

    /** Search of the JRWI archive's posts, kept to a show or a tag when given. */
    data class JrwiSearch(val show: String? = null, val tag: String? = null) : Route
}

/** Where a section sits in the sidebar: under the server it belongs to, or at the bottom. */
enum class RailGroup {
    TUBEARCHIVIST,
    JRWI,
    JELLYFIN,
    END,
}

/**
 * The sections in the sidebar, each with its own pages to go back through, grouped by the server
 * they show: where the phone app has two modes and pages with a server side, each of those is its
 * own entry here.
 */
enum class Section(val root: Route, val label: String, val icon: String, val group: RailGroup) {
    HOME(Route.Home, "Home", "ic_home", RailGroup.TUBEARCHIVIST),
    VIDEOS(Route.Videos, "Videos", "ic_film", RailGroup.TUBEARCHIVIST),
    CHANNELS(Route.Channels, "Channels", "ic_users", RailGroup.TUBEARCHIVIST),
    PLAYLISTS(Route.Playlists, "Playlists", "ic_list_video", RailGroup.TUBEARCHIVIST),
    QUEUE(Route.Queue, "Downloads", "ic_list_plus", RailGroup.TUBEARCHIVIST),
    STATS(Route.Stats, "Server stats", "ic_gauge", RailGroup.TUBEARCHIVIST),
    JRWI_HOME(Route.JrwiHome, "Home", "ic_home", RailGroup.JRWI),
    JRWI_SHOWS(Route.JrwiShows, "Shows", "ic_library", RailGroup.JRWI),
    JRWI_ARCHIVER(Route.JrwiArchiver, "Archiver", "ic_server", RailGroup.JRWI),
    JRWI_STATS(Route.JrwiStats, "Archive stats", "ic_gauge", RailGroup.JRWI),
    JELLYFIN(Route.JellyfinHome, "Home", "ic_home", RailGroup.JELLYFIN),
    MOVIES(Route.JellyfinMovies, "Movies", "ic_film", RailGroup.JELLYFIN),
    SHOWS(Route.JellyfinShows, "Shows", "ic_tv", RailGroup.JELLYFIN),
    DOWNLOADS(Route.Downloads, "Saved to this PC", "ic_download", RailGroup.END),
    SEARCH(Route.Search, "Search", "ic_search", RailGroup.END),
    SETTINGS(Route.Settings, "Settings", "ic_settings", RailGroup.END),
}
