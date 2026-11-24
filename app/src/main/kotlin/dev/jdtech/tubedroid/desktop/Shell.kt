package dev.jdtech.tubedroid.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jdtech.tubedroid.desktop.jellyfin.JellyfinHomeScreen
import dev.jdtech.tubedroid.desktop.jellyfin.JellyfinItemScreen
import dev.jdtech.tubedroid.desktop.jellyfin.JellyfinLibraryScreen
import dev.jdtech.tubedroid.desktop.jellyfin.JellyfinShowScreen
import dev.jdtech.tubedroid.desktop.jrwi.JrwiArchiverScreen
import dev.jdtech.tubedroid.desktop.jrwi.JrwiHomeScreen
import dev.jdtech.tubedroid.desktop.jrwi.JrwiItemScreen
import dev.jdtech.tubedroid.desktop.jrwi.JrwiPurple
import dev.jdtech.tubedroid.desktop.jrwi.JrwiSheetTheme
import dev.jdtech.tubedroid.desktop.jrwi.JrwiShowScreen
import dev.jdtech.tubedroid.desktop.jrwi.JrwiShowsScreen
import dev.jdtech.tubedroid.desktop.jrwi.JrwiStatsScreen
import dev.jdtech.tubedroid.desktop.player.AudioBar
import dev.jdtech.tubedroid.desktop.player.AudioPlayer
import dev.jdtech.tubedroid.desktop.player.AudioScreen
import dev.jdtech.tubedroid.desktop.player.PlayerRequest
import dev.jdtech.tubedroid.desktop.player.PlayerScreen
import dev.jdtech.tubedroid.desktop.screens.ChannelScreen
import dev.jdtech.tubedroid.desktop.screens.ChannelsScreen
import dev.jdtech.tubedroid.desktop.screens.DownloadsScreen
import dev.jdtech.tubedroid.desktop.screens.HomeScreen
import dev.jdtech.tubedroid.desktop.screens.PlaylistScreen
import dev.jdtech.tubedroid.desktop.screens.PlaylistsScreen
import dev.jdtech.tubedroid.desktop.screens.QueueScreen
import dev.jdtech.tubedroid.desktop.screens.SearchScreen
import dev.jdtech.tubedroid.desktop.screens.SearchService
import dev.jdtech.tubedroid.desktop.screens.SettingsScreen
import dev.jdtech.tubedroid.desktop.screens.StatsScreen
import dev.jdtech.tubedroid.desktop.screens.TaServerSettingsScreen
import dev.jdtech.tubedroid.desktop.screens.VideoScreen
import dev.jdtech.tubedroid.desktop.screens.VideosScreen
import dev.jdtech.tubedroid.desktop.ui.AppIcon
import dev.jdtech.tubedroid.desktop.ui.ServerSideTheme
import dev.jdtech.tubedroid.desktop.ui.WinchivistLogo
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The window's sections and their history, the player when a video plays, and messages at the
 * bottom. Each section keeps its own pages, so switching sections and back keeps your place. The
 * [audio] player is the app's, which the video player takes over from and hands back to.
 */
class Shell(private val audio: AudioPlayer? = null) : Navigator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var section by mutableStateOf(Section.HOME)
        private set

    private val stacks = Section.entries.associateWith { mutableStateListOf(PageEntry(it.root)) }

    var player by mutableStateOf<PlayerRequest?>(null)
        private set

    /** What shows over the whole window, such as a post's pictures, or null. */
    var overlay by mutableStateOf<(@Composable () -> Unit)?>(null)
        private set

    val snackbar = SnackbarHostState()

    val current: PageEntry
        get() = stacks.getValue(section).last()

    val canGoBack: Boolean
        get() = stacks.getValue(section).size > 1

    /** Goes to a section, or back to its first page when it's already showing. */
    override fun select(target: Section) {
        if (target == section) {
            val stack = stacks.getValue(section)
            while (stack.size > 1) stack.removeAt(stack.lastIndex).dispose()
        } else {
            section = target
        }
    }

    override fun open(route: Route) {
        stacks.getValue(section).add(PageEntry(route))
    }

    override fun back() {
        if (overlay != null) {
            overlay = null
            return
        }
        if (player != null) {
            player = null
            return
        }
        if (audio?.expanded == true) {
            audio.expanded = false
            return
        }
        val stack = stacks.getValue(section)
        if (stack.size > 1) stack.removeAt(stack.lastIndex).dispose()
    }

    override fun play(request: PlayerRequest) {
        // The audio player makes way, and hands its place on when it had the same thing
        player = audio?.handOverTo(request) ?: request
    }

    override fun listen(request: PlayerRequest) {
        if (audio == null) {
            player = request
            return
        }
        player = null
        audio.play(request)
    }

    fun closePlayer() {
        player = null
    }

    override fun overlay(content: @Composable () -> Unit) {
        overlay = content
    }

    override fun closeOverlay() {
        overlay = null
    }

    /**
     * Moves to a section that's there when the one showing isn't, such as before its server is set
     * up or after signing out of it.
     */
    fun keepTo(available: List<Section>) {
        if (section !in available) section = available.firstOrNull() ?: Section.SETTINGS
    }

    override fun message(text: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(text)
        }
    }
}

/** Whether the page shown has one before it to go back to. */
val LocalCanGoBack = staticCompositionLocalOf { false }

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ShellContent(shell: Shell) {
    val available = availableSections(shell)
    LaunchedEffect(available) { shell.keepTo(available) }
    CompositionLocalProvider(LocalNavigator provides shell) {
        Box(
            modifier =
                Modifier.fillMaxSize().onPointerEvent(PointerEventType.Press) { event ->
                    // The mouse's back button goes back, as in a browser
                    if (event.button == PointerButton.Back) shell.back()
                }
        ) {
            val request = shell.player
            val audio = LocalApp.current.audio
            if (request != null) {
                PlayerScreen(request = request, onClose = shell::closePlayer)
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        Sidebar(shell, available)
                        Surface(
                            color = MaterialTheme.colorScheme.background,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        ) {
                            val entry = shell.current
                            AnimatedContent(
                                targetState = entry,
                                transitionSpec = { fadeIn() togetherWith fadeOut() },
                                label = "page",
                            ) { page ->
                                CompositionLocalProvider(
                                    LocalPageEntry provides page,
                                    LocalCanGoBack provides (page === entry && shell.canGoBack),
                                ) {
                                    PageContent(page.route)
                                }
                            }
                        }
                    }
                    // While something is loaded in it, the audio player's bar
                    AudioBar(audio)
                }
                if (audio.expanded) AudioScreen(audio)
            }
            shell.overlay?.let { content -> Box(modifier = Modifier.fillMaxSize()) { content() } }
            SnackbarHost(
                hostState = shell.snackbar,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            )
        }
    }
}

/**
 * The sections there are to go to: each server's once it's set up, what was saved to this computer
 * while there's anything to show, and Settings always.
 */
@Composable
private fun availableSections(shell: Shell): List<Section> {
    val app = LocalApp.current
    val tubeArchivist = app.settings.isConfigured
    val jrwi = app.settings.isJrwiConfigured
    val jellyfin = app.jellyfin.isSignedIn
    val downloads = app.downloads.items.isNotEmpty() || shell.section == Section.DOWNLOADS
    return Section.entries.filter { section ->
        when (section.group) {
            RailGroup.TUBEARCHIVIST -> tubeArchivist
            RailGroup.JRWI -> jrwi
            RailGroup.JELLYFIN -> jellyfin
            RailGroup.END ->
                when (section) {
                    Section.DOWNLOADS -> downloads
                    Section.SEARCH -> tubeArchivist || jrwi
                    else -> true
                }
        }
    }
}

/**
 * The sidebar: the app's name at the top, then each server's pages under its name and in its own
 * colours (TubeArchivist's, the JRWI archiver's purple, and the app's accent for Jellyfin), with
 * Search and Settings at the bottom.
 */
@Composable
private fun Sidebar(shell: Shell, available: List<Section>) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var updateOpen by remember { mutableStateOf(false) }
    // Once at the start, quietly: there's nothing to say when GitHub can't be reached
    LaunchedEffect(Unit) {
        runCatching {
            delay(UPDATE_CHECK_DELAY_MS)
            app.updates.checkForUpdate()
        }
    }

    Column(
        modifier =
            Modifier.width(SIDEBAR_WIDTH)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 10.dp),
        ) {
            WinchivistLogo(modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                text = "Winchivist",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
        ) {
            if (available.any { it.group == RailGroup.TUBEARCHIVIST }) {
                // TubeArchivist's own colours, as its server's pages have them
                ServerSideTheme(onServer = true) {
                    SidebarGroup("TubeArchivist", MaterialTheme.colorScheme.surfaceContainerHigh) {
                        SidebarItems(shell, available, RailGroup.TUBEARCHIVIST, inset = 0.dp)
                    }
                }
            }
            if (available.any { it.group == RailGroup.JRWI }) {
                JrwiSheetTheme {
                    SidebarGroup("JRWI", JrwiPurple.Sheet) {
                        SidebarItems(shell, available, RailGroup.JRWI, inset = 0.dp)
                    }
                }
            }
            if (available.any { it.group == RailGroup.JELLYFIN }) {
                Column {
                    SidebarHeader("Jellyfin", top = 6.dp)
                    SidebarItems(shell, available, RailGroup.JELLYFIN)
                }
            }
        }
        app.updates.available?.let { release ->
            SidebarItem(
                label = "Update to ${release.version}",
                icon = "ic_download",
                selected = false,
                accent = true,
                onClick = { updateOpen = true },
            )
            if (updateOpen) {
                UpdateDialog(
                    release = release,
                    progress = app.updates.progress,
                    onInstall = {
                        scope.launch {
                            try {
                                app.updates.install()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                updateOpen = false
                                shell.message("Couldn't download the update: ${e.message}")
                            }
                        }
                    },
                    onDismiss = { if (app.updates.progress == null) updateOpen = false },
                )
            }
        }
        SidebarItems(shell, available, RailGroup.END)
        Spacer(Modifier.height(10.dp))
    }
}

/** A server's pages in the sidebar, on a block of the server's own colour under its name. */
@Composable
private fun SidebarGroup(title: String, color: Color, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier =
            Modifier.padding(horizontal = 8.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(color)
                .padding(vertical = 4.dp)
    ) {
        SidebarHeader(title, top = 6.dp)
        content()
    }
}

private val SIDEBAR_WIDTH = 232.dp

@Composable
private fun SidebarItems(
    shell: Shell,
    available: List<Section>,
    group: RailGroup,
    inset: Dp = 8.dp,
) {
    available
        .filter { it.group == group }
        .forEach { section ->
            SidebarItem(
                label = section.label,
                icon = section.icon,
                selected = shell.section == section,
                onClick = { shell.select(section) },
                inset = inset,
            )
        }
}

/** A line of the sidebar: icon and name, lit in the accent while its section shows. */
@Composable
private fun SidebarItem(
    label: String,
    icon: String,
    selected: Boolean,
    onClick: () -> Unit,
    accent: Boolean = false,
    inset: Dp = 8.dp,
) {
    val lit = selected || accent
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = inset, vertical = 1.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(
                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                    else Color.Transparent
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        AppIcon(
            name = icon,
            contentDescription = null,
            tint =
                if (lit) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color =
                if (lit) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

@Composable
private fun SidebarHeader(text: String, top: Dp = 18.dp) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 22.dp, top = top, bottom = 6.dp),
    )
}

/** Offers a newer Winchivist; installing closes the app while the installer runs. */
@Composable
private fun UpdateDialog(
    release: Updates.Release,
    progress: Float?,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Winchivist ${release.version} is out") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Installing downloads it from GitHub and closes Winchivist while the " +
                        "installer runs. Your settings stay."
                )
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onInstall, enabled = progress == null) { Text("Install") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = progress == null) { Text("Later") }
        },
    )
}

private const val UPDATE_CHECK_DELAY_MS = 5_000L

@Composable
private fun PageContent(route: Route) {
    when (route) {
        Route.Home -> HomeScreen()
        Route.Videos -> VideosScreen()
        Route.Channels -> ChannelsScreen()
        Route.Playlists -> PlaylistsScreen()
        Route.Downloads -> DownloadsScreen()
        Route.Search -> SearchScreen(start = null)
        Route.Stats -> StatsScreen()
        Route.Queue -> QueueScreen()
        Route.JellyfinHome -> JellyfinHomeScreen()
        Route.JellyfinMovies -> JellyfinLibraryScreen(movies = true)
        Route.JellyfinShows -> JellyfinLibraryScreen(movies = false)
        Route.Settings -> SettingsScreen(firstRun = false, onDone = {})
        Route.TaSettings -> TaServerSettingsScreen()
        is Route.Channel -> ChannelScreen(route.channelId)
        is Route.Video -> VideoScreen(route.videoId, route.playlistId)
        is Route.Playlist -> PlaylistScreen(route.playlistId)
        is Route.JellyfinItem -> JellyfinItemScreen(route.itemId)
        is Route.JellyfinShow -> JellyfinShowScreen(route.seriesId)
        Route.JrwiHome -> JrwiHomeScreen()
        Route.JrwiShows -> JrwiShowsScreen()
        Route.JrwiArchiver -> JrwiArchiverScreen()
        Route.JrwiStats -> JrwiStatsScreen()
        is Route.JrwiShowPage -> JrwiShowScreen(route.showId)
        is Route.JrwiPost -> JrwiItemScreen(route.postId)
        is Route.JrwiSearch ->
            SearchScreen(start = SearchService.JRWI, show = route.show, tag = route.tag)
    }
}
