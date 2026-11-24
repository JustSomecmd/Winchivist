package dev.jdtech.tubedroid.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import dev.jdtech.jellyfin.jrwi.JrwiApi
import dev.jdtech.jellyfin.tubearchivist.TaApi
import dev.jdtech.tubedroid.desktop.jellyfin.JellyfinClient
import dev.jdtech.tubedroid.desktop.jrwi.JrwiDownloader
import dev.jdtech.tubedroid.desktop.jrwi.JrwiRepository
import dev.jdtech.tubedroid.desktop.player.PlayerRequest

/**
 * What the pages share: the settings, TubeArchivist, Jellyfin, the JRWI archive, the downloads to
 * this computer and the check for a newer Winchivist.
 */
class AppGraph(val settings: AppSettings) {
    val api = TaApi(settings)
    val jellyfin = JellyfinClient(settings)
    val jrwi = JrwiRepository(JrwiApi(settings.jrwi), jellyfin, api)
    val downloads = Downloads(settings, api)
    val updates = Updates()
    val history = WatchHistory(settings, store = { jellyfin.playlistSessionStore })
    val jrwiDownloads = JrwiDownloader(this)
}

val LocalApp = staticCompositionLocalOf<AppGraph> { error("No app") }

/** The ways pages move around the app, given to each page by the shell. */
interface Navigator {
    fun open(route: Route)

    fun back()

    fun play(request: PlayerRequest)

    /** Goes to one of the sidebar's sections. */
    fun select(section: Section)

    /** Shows a short message at the bottom of the window. */
    fun message(text: String)

    /**
     * Shows [content] over the whole window, such as a post's pictures, until [closeOverlay] or
     * going back.
     */
    fun overlay(content: @Composable () -> Unit)

    fun closeOverlay()
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("No navigator") }
