package dev.jdtech.tubedroid.desktop.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import com.sun.net.httpserver.HttpServer
import dev.jdtech.jellyfin.tubearchivist.TaBiggestChannel
import dev.jdtech.jellyfin.tubearchivist.TaChannel
import dev.jdtech.jellyfin.tubearchivist.TaChannelNav
import dev.jdtech.jellyfin.tubearchivist.TaComment
import dev.jdtech.jellyfin.tubearchivist.TaCountStats
import dev.jdtech.jellyfin.tubearchivist.TaDownloadDay
import dev.jdtech.jellyfin.tubearchivist.TaDownloadItem
import dev.jdtech.jellyfin.tubearchivist.TaDownloadStats
import dev.jdtech.jellyfin.tubearchivist.TaPage
import dev.jdtech.jellyfin.tubearchivist.TaPaginate
import dev.jdtech.jellyfin.tubearchivist.TaPing
import dev.jdtech.jellyfin.tubearchivist.TaPlayer
import dev.jdtech.jellyfin.tubearchivist.TaPlaylist
import dev.jdtech.jellyfin.tubearchivist.TaPlaylistEntry
import dev.jdtech.jellyfin.tubearchivist.TaSearchResponse
import dev.jdtech.jellyfin.tubearchivist.TaSearchResults
import dev.jdtech.jellyfin.tubearchivist.TaStats
import dev.jdtech.jellyfin.tubearchivist.TaSubtitle
import dev.jdtech.jellyfin.tubearchivist.TaVideo
import dev.jdtech.jellyfin.tubearchivist.TaVideoStats
import dev.jdtech.jellyfin.tubearchivist.TaVideoTotals
import dev.jdtech.jellyfin.tubearchivist.TaWatchPart
import dev.jdtech.jellyfin.tubearchivist.TaWatchStats
import dev.jdtech.tubedroid.desktop.AppGraph
import dev.jdtech.tubedroid.desktop.AppSettings
import dev.jdtech.tubedroid.desktop.Kept
import dev.jdtech.tubedroid.desktop.KeptEpisode
import dev.jdtech.tubedroid.desktop.LocalApp
import dev.jdtech.tubedroid.desktop.LocalDownload
import dev.jdtech.tubedroid.desktop.LocalWindowState
import dev.jdtech.tubedroid.desktop.Route
import dev.jdtech.tubedroid.desktop.Section
import dev.jdtech.tubedroid.desktop.Shell
import dev.jdtech.tubedroid.desktop.ShellContent
import dev.jdtech.tubedroid.desktop.jrwi.JrwiPictures
import dev.jdtech.tubedroid.desktop.jrwi.inspectFiles
import dev.jdtech.tubedroid.desktop.jrwi.jrwiViewerItems
import dev.jdtech.tubedroid.desktop.jrwi.showPictures
import dev.jdtech.tubedroid.desktop.player.MiniOsd
import dev.jdtech.tubedroid.desktop.player.MpvLoader
import dev.jdtech.tubedroid.desktop.player.PlayerActions
import dev.jdtech.tubedroid.desktop.player.PlayerOsd
import dev.jdtech.tubedroid.desktop.player.PlayerRequest
import dev.jdtech.tubedroid.desktop.player.PlayerSession
import dev.jdtech.tubedroid.desktop.player.PlayerSettings
import dev.jdtech.tubedroid.desktop.player.PlaylistPanel
import dev.jdtech.tubedroid.desktop.player.SettingsPage
import dev.jdtech.tubedroid.desktop.player.TaPlaybackSource
import dev.jdtech.tubedroid.desktop.player.playbackSource
import dev.jdtech.tubedroid.desktop.screens.ChannelModel
import dev.jdtech.tubedroid.desktop.screens.SearchModel
import dev.jdtech.tubedroid.desktop.screens.SettingsGroup
import dev.jdtech.tubedroid.desktop.screens.SettingsScreen
import dev.jdtech.tubedroid.desktop.screens.VideoModel
import dev.jdtech.tubedroid.desktop.screens.settingsGroup
import dev.jdtech.tubedroid.desktop.ui.LocalOwnWindowBar
import dev.jdtech.tubedroid.desktop.ui.TubedroidTheme
import dev.jdtech.tubedroid.desktop.ui.WindowBar
import dev.jdtech.tubedroid.desktop.ui.installImageLoader
import java.awt.Color
import java.awt.GradientPaint
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.util.prefs.Preferences
import javax.imageio.ImageIO
import kotlin.coroutines.cancellation.CancellationException
import kotlin.system.exitProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.jetbrains.skia.EncodedImageFormat

/**
 * Draws the pages, filled from a made-up TubeArchivist on this computer, into PNG files. The build
 * on GitHub runs it so the layout can be looked at without Windows.
 */
fun main(args: Array<String>) {
    System.setProperty(MpvLoader.NO_MPV_PROPERTY, "true")
    val output = File(args.firstOrNull() ?: "build/screens").also { it.mkdirs() }
    val server = SampleServer()
    val archiver = SampleArchiver()
    val settings =
        AppSettings(Preferences.userRoot().node("tubedroid-preview")).apply {
            url = server.url
            token = "preview"
            jrwiUrl = archiver.url
            jrwiToken = "preview"
            downloadFolder = File(output, "downloads").path
            // The compute box asks nothing by itself while the pages are drawn
            hearWhen = AppSettings.HEAR_BY_HAND
            hearAskAfter = null
        }
    val graph = AppGraph(settings)
    graph.pages.showForPreview()
    installImageLoader(graph.api, graph.jrwi.api)
    checkJellyfinSignIn(graph, server.url)
    showDownloads(graph)
    // A playlist part way through, for Continue watching
    SampleData.playlists[1].let { playlist ->
        graph.history.updatePlaylist(playlist.id, playlist.name, SampleData.videos[2].youtubeId)
    }

    runBlocking(Dispatchers.Main) {
        // The downloader and the app without its servers, tried for real before anything is drawn
        checkDownloads()
        checkOfflineUse()
        val shell = Shell(graph.audio)
        val scene =
            ImageComposeScene(width = 1360, height = 860, density = Density(1f)) {
                CompositionLocalProvider(
                    LocalApp provides graph,
                    LocalWindowState provides WindowState(),
                    LocalOwnWindowBar provides true,
                ) {
                    TubedroidTheme(accent = settings.accent) { WindowWithBar(shell) }
                }
            }
        var time = 0L

        suspend fun capture(name: String) {
            repeat(16) {
                scene.render(time)
                time += 200_000_000L
                delay(200)
            }
            val data = scene.render(time).encodeToData(EncodedImageFormat.PNG)
            if (data != null) File(output, "$name.png").writeBytes(data.bytes)
            println("Rendered $name")
        }

        capture("home")
        shell.select(Section.CHANNELS)
        capture("channels")
        shell.open(Route.Channel(SampleData.channels[0].id))
        capture("channel")
        shell.current.retain<ChannelModel>("channel") { error("no channel") }.onServer = true
        capture("channel-manage")
        shell.open(Route.Video(SampleData.videos[0].youtubeId))
        capture("video")
        shell.current.retain<VideoModel>("video") { error("no video") }.onServer = true
        capture("video-manage")
        shell.open(Route.Playlist(SampleData.playlists[0].id))
        capture("playlist")
        shell.select(Section.PLAYLISTS)
        capture("playlists")
        shell.select(Section.DOWNLOADS)
        capture("downloads")
        shell.select(Section.VIDEOS)
        capture("videos")
        shell.select(Section.HOME)
        shell.open(Route.Search)
        capture("search-empty")
        shell.current.retain<SearchModel>("search") { error("no search") }.onQueryChange("sample")
        capture("search")
        shell.select(Section.STATS)
        capture("stats")
        shell.select(Section.QUEUE)
        capture("queue")
        shell.select(Section.ON_THIS_PC)
        capture("on-this-pc")
        // A video's page as it's shown without TubeArchivist, from what's kept with its download
        graph.pages.showForPreview(asKept = true)
        shell.open(Route.Video(SampleData.videos[1].youtubeId))
        capture("video-from-this-pc")
        shell.back()
        graph.pages.showForPreview()
        shell.select(Section.SETTINGS)
        capture("settings")
        for (group in SettingsGroup.entries.drop(1)) {
            settingsGroup = group
            capture("settings-${group.name.lowercase()}")
        }
        settingsGroup = SettingsGroup.SERVERS
        shell.open(Route.TaSettings)
        capture("ta-settings")
        shell.back()
        // The JRWI archive's pages, from a made-up archiver
        shell.select(Section.JRWI_HOME)
        capture("jrwi-home")
        shell.select(Section.JRWI_SHOWS)
        capture("jrwi-shows")
        shell.select(Section.JRWI_ON_THIS_PC)
        capture("jrwi-on-this-pc")
        graph.pages.showForPreview(asKept = true)
        shell.open(Route.JrwiPost(SampleArchive.episodes[0].id))
        capture("jrwi-item-from-this-pc")
        shell.back()
        graph.pages.showForPreview()
        shell.open(Route.JrwiShowPage(SampleArchive.shows[0].id))
        capture("jrwi-show")
        shell.open(Route.JrwiPost(SampleArchive.episodes[1].id))
        capture("jrwi-item")
        shell.open(Route.JrwiSearch(show = SampleArchive.shows[0].id))
        capture("jrwi-search")
        shell.select(Section.JRWI_ARCHIVER)
        capture("jrwi-archiver")
        graph.compute.showForPreview()
        shell.select(Section.JRWI_COMPUTE)
        capture("jrwi-compute")
        // The compute box asking whether now is a good time, over a JRWI page and one that isn't
        graph.compute.askForPreview()
        shell.select(Section.JRWI_HOME)
        capture("ask-jrwi")
        shell.select(Section.HOME)
        capture("ask")
        graph.compute.showForPreview()
        shell.select(Section.JRWI_STATS)
        capture("jrwi-stats")
        shell.showPictures(
            JrwiPictures(jrwiViewerItems(SampleArchive.episodes[0], SampleArchive.files), 1)
        )
        capture("jrwi-viewer")
        shell.closeOverlay()
        shell.inspectFiles(listOf(SampleArchive.files[1]), 0)
        capture("jrwi-inspector")
        shell.closeOverlay()
        // The audio player: its bar under the episode's page, the full player, and the transcript
        shell.select(Section.JRWI_SHOWS)
        shell.back()
        graph.audio.showForPreview(
            PlayerRequest.Jrwi(SampleArchive.episodes[1].id, audio = true),
            position = 4_192.0,
            duration = 11_395.0,
        )
        capture("audio-bar")
        graph.audio.expanded = true
        capture("audio-player")
        graph.audio.session?.toggleTranscript()
        capture("audio-transcript")
        graph.audio.close()
        // A playlist's sound: the bar with its playlist button, then the player with the list
        graph.audio.showForPreview(
            PlayerRequest.Ta(
                videoId = SampleData.videos[2].youtubeId,
                playlistId = SampleData.playlists[1].id,
            ),
            position = 312.0,
            duration = 1_284.0,
        )
        capture("audio-bar-playlist")
        graph.audio.expanded = true
        graph.audio.session?.togglePlaylist()
        capture("audio-playlist")
        graph.audio.close()
        // The player, from a playlist, with its controls; without mpv the video's place is empty
        shell.play(
            PlayerRequest.Ta(
                videoId = SampleData.videos[2].youtubeId,
                playlistId = SampleData.playlists[1].id,
            )
        )
        capture("player")
        // The same video as the mini player, in a corner of the page it was started from
        shell.shrinkPlayer()
        capture("player-mini")
        shell.closePlayer()
        scene.close()

        // The pages made of blocks, whole: in a tall window, and in a wide one with three columns
        for ((suffix, width, height) in
            listOf(Triple("tall", 1360, 2600), Triple("wide", 1760, 1800))) {
            val blockShell = Shell(graph.audio)
            val blockScene =
                ImageComposeScene(width = width, height = height, density = Density(1f)) {
                    CompositionLocalProvider(
                        LocalApp provides graph,
                        LocalWindowState provides WindowState(),
                        LocalOwnWindowBar provides true,
                    ) {
                        TubedroidTheme(accent = settings.accent) { WindowWithBar(blockShell) }
                    }
                }
            val pages =
                listOf(
                    Section.STATS to "stats",
                    Section.JRWI_STATS to "jrwi-stats",
                    Section.JRWI_ARCHIVER to "jrwi-archiver",
                    Section.JRWI_COMPUTE to "jrwi-compute",
                )
            suspend fun shoot(name: String) {
                repeat(16) {
                    blockScene.render(time)
                    time += 200_000_000L
                    delay(200)
                }
                blockScene.render(time).encodeToData(EncodedImageFormat.PNG)?.let {
                    File(output, "$name-$suffix.png").writeBytes(it.bytes)
                }
                println("Rendered $name-$suffix")
            }
            for ((section, name) in pages) {
                blockShell.select(section)
                shoot(name)
            }
            if (suffix == "tall") {
                // The Compute box page while it asks first, after the answer was "not now"
                graph.compute.askForPreview()
                settings.hearWhen = AppSettings.HEAR_ASK
                graph.compute.askLater(tomorrow = false)
                shoot("jrwi-compute-ask")
                settings.hearWhen = AppSettings.HEAR_BY_HAND
                settings.hearAskAfter = null
                graph.compute.showForPreview()
            }
            blockScene.close()
        }

        // The playlist beside the video, as the player shows it, with the video's place left black
        val playlistScene =
            ImageComposeScene(width = 1360, height = 860, density = Density(1f)) {
                CompositionLocalProvider(LocalApp provides graph) {
                    TubedroidTheme(accent = settings.accent) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            Box(
                                modifier =
                                    Modifier.weight(1f)
                                        .fillMaxHeight()
                                        .background(ComposeColor.Black)
                            )
                            PlaylistPanel(
                                name = SampleData.playlists[1].name,
                                videos = SampleData.videos.take(12),
                                currentVideoId = SampleData.videos[2].youtubeId,
                                loop = true,
                                onPlay = {},
                                onLoopChange = {},
                                onOpenPage = {},
                                onClose = {},
                            )
                        }
                    }
                }
            }
        repeat(16) {
            playlistScene.render(time)
            time += 200_000_000L
            delay(200)
        }
        playlistScene.render(time).encodeToData(EncodedImageFormat.PNG)?.let {
            File(output, "player-playlist.png").writeBytes(it.bytes)
        }
        println("Rendered player-playlist")
        playlistScene.close()

        // The controls over the video, with a made-up picture behind them
        val osdSession =
            PlayerSession(
                TaPlaybackSource(
                    graph.api,
                    settings,
                    graph.history,
                    PlayerRequest.Ta(
                        videoId = SampleData.videos[2].youtubeId,
                        playlistId = SampleData.playlists[1].id,
                    ),
                ),
                settings,
                this,
            )
        osdSession.load()
        osdSession.showForPreview(
            position = 312.0,
            duration = 790.0,
            chapters = listOf(0.0, 150.0, 612.0),
            names = listOf("What you need", "Mixing and kneading", "Into the oven"),
        )
        fun osdSceneOf(session: PlayerSession) =
            ImageComposeScene(width = 1360, height = 860, density = Density(1f)) {
                CompositionLocalProvider(LocalApp provides graph) {
                    TubedroidTheme(accent = settings.accent) {
                        Box(
                            modifier =
                                Modifier.fillMaxSize()
                                    .background(
                                        Brush.linearGradient(
                                            listOf(
                                                ComposeColor(0xFF4A6B8C),
                                                ComposeColor(0xFF22331F),
                                            )
                                        )
                                    )
                        ) {
                            PlayerOsd(session = session, actions = PlayerActions(onClose = {}))
                        }
                    }
                }
            }
        // Draws [scene] for [turns] fifths of a second, then keeps the picture. With [mouse], the
        // mouse is said to lie there at every turn, as Compose says it of a still one
        suspend fun osdShot(
            scene: ImageComposeScene,
            name: String,
            turns: Int,
            mouse: Offset? = null,
        ) {
            repeat(turns) {
                mouse?.let { scene.sendPointerEvent(PointerEventType.Move, it) }
                scene.render(time)
                time += 200_000_000L
                delay(200)
            }
            scene.render(time).encodeToData(EncodedImageFormat.PNG)?.let {
                File(output, "$name.png").writeBytes(it.bytes)
            }
            println("Rendered $name")
        }
        val osdScene = osdSceneOf(osdSession)
        // In their first second
        osdShot(osdScene, "player-osd", turns = 5)
        // The mouse over the seek bar, in the second chapter: its time and the chapter's name
        osdShot(osdScene, "player-osd-chapter", turns = 2, mouse = Offset(700f, 782f))
        // Four seconds on, with the mouse lying still on the bottom bar: faded, though it's paused
        val restingAt = Offset(300f, 824f)
        osdShot(osdScene, "player-osd-idle", turns = 20, mouse = restingAt)
        // One of the player's keys brings them back
        osdSession.onKey(KeyEvent.VK_C, onClose = {})
        osdShot(osdScene, "player-osd-key", turns = 4, mouse = restingAt)
        osdScene.close()

        // A JRWI episode's video: the show under its title in the archive's purple, as its bars
        val jrwiOsdSession =
            PlayerSession(
                playbackSource(
                    graph,
                    PlayerRequest.Jrwi(SampleArchive.episodes[1].id, audio = false),
                ),
                settings,
                this,
            )
        jrwiOsdSession.load()
        jrwiOsdSession.showForPreview(position = 4192.0, duration = 11395.0, chapters = emptyList())
        val jrwiOsdScene = osdSceneOf(jrwiOsdSession)
        osdShot(jrwiOsdScene, "player-osd-jrwi", turns = 5)
        jrwiOsdScene.close()

        // The window's own bar as a maximized window has it, with the button that brings it back
        val barScene =
            ImageComposeScene(width = 1360, height = 32, density = Density(1f)) {
                CompositionLocalProvider(LocalApp provides graph) {
                    TubedroidTheme(accent = settings.accent) {
                        WindowBar(
                            state = WindowState(placement = WindowPlacement.Maximized),
                            window = null,
                            onClose = {},
                        )
                    }
                }
            }
        osdShot(barScene, "window-bar-maximized", turns = 2)
        barScene.close()

        // The mini player's controls over its picture, twice its size to be seen well
        val miniScene =
            ImageComposeScene(width = 800, height = 450, density = Density(2f)) {
                CompositionLocalProvider(LocalApp provides graph) {
                    TubedroidTheme(accent = settings.accent) {
                        Box(
                            modifier =
                                Modifier.fillMaxSize()
                                    .background(
                                        Brush.linearGradient(
                                            listOf(
                                                ComposeColor(0xFF4A6B8C),
                                                ComposeColor(0xFF22331F),
                                            )
                                        )
                                    )
                        ) {
                            MiniOsd(session = osdSession, actions = PlayerActions(onClose = {}))
                        }
                    }
                }
            }
        repeat(16) {
            miniScene.render(time)
            time += 200_000_000L
            delay(200)
        }
        miniScene.render(time).encodeToData(EncodedImageFormat.PNG)?.let {
            File(output, "player-mini-osd.png").writeBytes(it.bytes)
        }
        println("Rendered player-mini-osd")
        miniScene.close()

        // The settings menu's pages side by side, each as the menu draws it
        val menuScene =
            ImageComposeScene(width = 960, height = 480, density = Density(1f)) {
                CompositionLocalProvider(LocalApp provides graph) {
                    TubedroidTheme(accent = settings.accent) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(24.dp),
                            modifier =
                                Modifier.fillMaxSize()
                                    .background(ComposeColor(0xFF101216))
                                    .padding(24.dp),
                        ) {
                            listOf(SettingsPage.MAIN, SettingsPage.SLEEP, SettingsPage.CHAPTERS)
                                .forEach { page ->
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainer,
                                        modifier = Modifier.width(280.dp),
                                    ) {
                                        Column {
                                            PlayerSettings(
                                                session = osdSession,
                                                page = page,
                                                onPage = {},
                                                onClose = {},
                                            )
                                        }
                                    }
                                }
                        }
                    }
                }
            }
        repeat(4) {
            menuScene.render(time)
            time += 200_000_000L
            delay(100)
        }
        menuScene.render(time).encodeToData(EncodedImageFormat.PNG)?.let {
            File(output, "player-menu.png").writeBytes(it.bytes)
        }
        println("Rendered player-menu")
        menuScene.close()

        val firstRun =
            ImageComposeScene(width = 1360, height = 860, density = Density(1f)) {
                CompositionLocalProvider(LocalApp provides graph) {
                    TubedroidTheme(accent = settings.accent) {
                        SettingsScreen(firstRun = true, onDone = {}, onSkip = {})
                    }
                }
            }
        repeat(4) {
            firstRun.render(time)
            time += 200_000_000L
            delay(100)
        }
        firstRun.render(time).encodeToData(EncodedImageFormat.PNG)?.let {
            File(output, "first-run.png").writeBytes(it.bytes)
        }
        firstRun.close()
    }
    server.stop()
    archiver.stop()
    exitProcess(0)
}

/** Made-up videos, channels and playlists, as TubeArchivist would send them. */
private object SampleData {
    private val names =
        listOf(
            "Northern Lights Workshop",
            "Byte Sized Builds",
            "The Slow Kitchen",
            "Field Notes Outdoors",
            "Analog Synth Lab",
            "Retro Repair Bench",
        )

    val channels = names.mapIndexed { index, name ->
        TaChannel(
            id = "UC$index",
            name = name,
            subs = 12_000L * (index + 1) * (index + 3),
            subscribed = index % 3 != 2,
            description = "A channel about ${name.lowercase()}. New videos every week.",
            thumbUrl = "/cache/channels/UC$index-avatar.jpg",
            bannerUrl = "/cache/channels/UC$index-banner.jpg",
            tvArtUrl = "/cache/channels/UC$index-tvart.jpg",
            lastRefresh = "2026-11-18T08:00:00",
            active = true,
        )
    }

    val videos =
        (0 until 24).map { index ->
            val channel = channels[index % channels.size]
            TaVideo(
                youtubeId = "video$index",
                title =
                    listOf(
                        "Building a cabin by hand, part ${index + 1}",
                        "Restoring a 1984 synthesiser",
                        "Bread with three ingredients",
                        "A week of winter camping",
                        "Fixing a broken cassette deck",
                        "Making a desk from one board",
                    )[index % 6],
                description =
                    "In this video we go through the whole process step by step.\n\n" +
                        "Chapters:\n0:00 Start\n2:30 Materials\n10:12 Building\n\nhttps://example.com/notes",
                published = "2026-11-${(18 - index % 17).toString().padStart(2, '0')}",
                dateDownloaded = 1_763_500_000L - index * 86_400L,
                mediaSize = 400_000_000L + index * 37_000_000L,
                thumbUrl = "/cache/videos/video$index.jpg",
                type = "videos",
                commentCount = 12,
                channel = channel,
                player =
                    TaPlayer(
                        watched = index % 4 == 3,
                        duration = 600.0 + index * 95,
                        durationStr = null,
                        progress = if (index % 5 == 1) 40.0 else null,
                        position = if (index % 5 == 1) 300.0 else null,
                    ),
                stats =
                    TaStats(viewCount = 120_000L * (index + 1), likeCount = 4_000L * (index + 1)),
                mediaUrl = "/media/${channel.id}/video$index.mp4",
                subtitles =
                    listOf(TaSubtitle(lang = "en", name = "English", source = "user", ext = "vtt")),
                active = true,
            )
        }

    val playlists =
        (0 until 6).map { index ->
            val channel = channels[index % channels.size]
            TaPlaylist(
                id = "PL$index",
                name = if (index == 0) "Watch later" else "Best of ${channel.name}",
                channelName = if (index == 0) null else channel.name,
                channelId = if (index == 0) null else channel.id,
                description = "A playlist",
                thumbUrl = "/cache/playlists/PL$index.jpg",
                subscribed = index % 2 == 1,
                type = if (index == 0) "custom" else "regular",
                entries =
                    videos.take(8).map {
                        TaPlaylistEntry(
                            youtubeId = it.youtubeId,
                            title = it.title,
                            downloaded = true,
                        )
                    },
            )
        }

    val queue =
        (0 until 8).map { index ->
            TaDownloadItem(
                youtubeId = "queued$index",
                title = "Upcoming video number ${index + 1}",
                channelName = channels[index % channels.size].name,
                duration = "12:3$index",
                published = "2026-11-1$index",
                status = "pending",
                thumbUrl = "/cache/videos/queued$index.jpg",
            )
        }

    val comments =
        (0 until 5).map { index ->
            TaComment(
                id = "c$index",
                text =
                    "This was the most useful video on the subject I've found. Thanks for the detail.",
                author = "@viewer$index",
                likeCount = 40L - index,
                timeText = "${index + 1} days ago",
            )
        }

    val videoStats =
        TaVideoStats(
            count = 4_382,
            mediaSize = 3_100_000_000_000L,
            duration = 11_870_000L,
            regular = TaVideoTotals(3_900, 2_900_000_000_000L, 11_000_000L),
            shorts = TaVideoTotals(400, 40_000_000_000L, 20_000L),
            streams = TaVideoTotals(82, 160_000_000_000L, 850_000L),
            active = TaVideoTotals(4_200, 3_000_000_000_000L, 11_500_000L),
            inactive = TaVideoTotals(182, 100_000_000_000L, 370_000L),
        )
}

/** Answers like TubeArchivist's API, with [SampleData], and draws a picture for every image. */
/**
 * Signs in to Jellyfin at the sample server, which has no Jellyfin, so this fails. It only has to
 * fail the ordinary way: a library missing from the app stops it with an error instead, which fails
 * the build here before it can stop the app.
 */
/**
 * Puts made-up downloads in the record: some videos and episodes on this PC, and a few still on
 * their way in each of the states a download can be in.
 */
private fun showDownloads(graph: AppGraph) {
    fun video(index: Int, state: String = Kept.DONE): Kept {
        val video = SampleData.videos[index]
        val channel = video.channel?.name.orEmpty()
        return Kept(
            id = video.youtubeId,
            service = Kept.TUBEARCHIVIST,
            title = video.title,
            group = channel,
            groupId = video.channel?.id,
            file = "TubeArchivist/$channel/${video.title} [${video.youtubeId}].mp4",
            source = "/media/${video.youtubeId}.mp4",
            size = video.mediaSize,
            state = state,
            added = 1_764_000_000_000L + index,
            seconds = video.player?.duration,
            picture = video.thumbUrl,
            date = video.published,
        )
    }
    fun episode(index: Int, audio: Boolean, state: String = Kept.DONE): Kept? {
        val item = SampleArchive.episodes[index]
        val address = (if (audio) item.audio?.url else item.video?.url) ?: return null
        val id = graph.jrwiDownloads.idOf(item, audio) ?: return null
        return Kept(
            id = id,
            service = Kept.JRWI,
            audio = audio,
            title = item.title,
            group = "Riptide",
            groupId = item.show,
            file = "JRWI/Riptide/${item.title} [jrwi-${item.id}].${if (audio) "m4a" else "mp4"}",
            source = address,
            size = if (audio) item.audio?.size ?: 168_000_000L else item.video?.size,
            state = state,
            added = 1_764_000_100_000L + index,
            seconds = item.duration,
            picture = item.thumbnail,
            date = item.date,
            episode =
                KeptEpisode(
                    item = item.id,
                    show = "Riptide",
                    showId = item.show,
                    season = item.season,
                    episode = item.episode,
                ),
        )
    }
    fun coming(kept: Kept, state: LocalDownload.State, share: Float?): LocalDownload {
        val size = kept.size ?: 0L
        return LocalDownload(
            id = kept.id,
            title = kept.title,
            file = File(graph.settings.downloadFolder, kept.file),
            progress = share,
            state = state,
            error = "The server answered with 503".takeIf { state == LocalDownload.State.FAILED },
            route = kept.route,
            kept = kept,
            have = ((share ?: 0f) * size).toLong(),
        )
    }
    val whole =
        listOf(0, 1, 2, 4, 5, 8, 9).map { video(it) } +
            listOfNotNull(
                episode(0, audio = true),
                episode(1, audio = false),
                episode(1, audio = true),
                episode(3, audio = true),
            )
    val unfinished =
        listOfNotNull(
            coming(video(13, Kept.FAILED), LocalDownload.State.FAILED, 0.1f),
            coming(video(12, Kept.PAUSED), LocalDownload.State.PAUSED, 0.64f),
            coming(video(11, Kept.WAITING), LocalDownload.State.QUEUED, null),
            episode(2, audio = true, state = Kept.WAITING)?.let {
                coming(it, LocalDownload.State.QUEUED, null)
            },
            episode(4, audio = false, state = Kept.WAITING)?.let {
                coming(it, LocalDownload.State.RUNNING, 0.27f)
            },
            coming(video(10, Kept.WAITING), LocalDownload.State.RUNNING, 0.42f),
        )
    graph.downloads.showForPreview(whole, unfinished)
}

/** The app as its window shows it: the window's own bar, and the pages under it. */
@Composable
private fun WindowWithBar(shell: Shell) {
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        WindowBar(state = LocalWindowState.current, window = null, onClose = {})
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) { ShellContent(shell) }
    }
}

private fun checkJellyfinSignIn(graph: AppGraph, url: String) {
    try {
        runBlocking { graph.jellyfin.signIn(url, "preview", "preview") }
        graph.jellyfin.signOut()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        println("Jellyfin sign-in reached the server: ${e.message}")
    }
}

private class SampleServer {
    private val json = Json { encodeDefaults = true }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val url = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val (type, body) =
                if (path.startsWith("/cache/")) {
                    "image/png" to picture(path)
                } else {
                    "application/json" to (answer(path) ?: "{}").toByteArray()
                }
            exchange.responseHeaders.add("Content-Type", type)
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    fun stop() = server.stop(0)

    private fun <T> page(serializer: KSerializer<T>, items: List<T>): String =
        json.encodeToString(
            TaPage.serializer(serializer),
            TaPage(items, TaPaginate(1, 1, items.size)),
        )

    private fun <T> list(serializer: KSerializer<T>, items: List<T>): String =
        json.encodeToString(ListSerializer(serializer), items)

    private fun answer(path: String): String? {
        val parts = path.trim('/').split('/')
        if (parts.firstOrNull() != "api") return null
        return when (parts.getOrNull(1)) {
            "ping" -> json.encodeToString(TaPing.serializer(), TaPing("pong", "v0.5.8"))
            "video" ->
                when {
                    parts.size == 2 -> page(TaVideo.serializer(), SampleData.videos)
                    parts.getOrNull(3) == "comment" ->
                        list(TaComment.serializer(), SampleData.comments)
                    parts.getOrNull(3) == "similar" ->
                        list(TaVideo.serializer(), SampleData.videos.drop(6).take(6))
                    else ->
                        SampleData.videos
                            .firstOrNull { it.youtubeId == parts[2] }
                            ?.let { json.encodeToString(TaVideo.serializer(), it) }
                }
            "channel" ->
                when {
                    parts.size == 2 -> page(TaChannel.serializer(), SampleData.channels)
                    parts.getOrNull(3) == "nav" ->
                        json.encodeToString(
                            TaChannelNav.serializer(),
                            TaChannelNav(hasPlaylists = true, hasVideos = true, hasShorts = true),
                        )
                    parts.getOrNull(3) == "aggs" ->
                        """{"total_items":{"value":312},"total_size":{"value":250000000000},""" +
                            """"total_duration":{"value":402000}}"""
                    else ->
                        SampleData.channels
                            .firstOrNull { it.id == parts[2] }
                            ?.let { json.encodeToString(TaChannel.serializer(), it) }
                }
            "playlist" ->
                if (parts.size == 2) {
                    page(TaPlaylist.serializer(), SampleData.playlists)
                } else {
                    SampleData.playlists
                        .firstOrNull { it.id == parts[2] }
                        ?.let { json.encodeToString(TaPlaylist.serializer(), it) }
                }
            "download" -> page(TaDownloadItem.serializer(), SampleData.queue)
            "notification" -> "[]"
            "search" ->
                json.encodeToString(
                    TaSearchResponse.serializer(),
                    TaSearchResponse(
                        TaSearchResults(
                            videos = SampleData.videos.take(5),
                            channels = SampleData.channels.take(2),
                            playlists = SampleData.playlists.take(2),
                        )
                    ),
                )
            "stats" ->
                when (parts.getOrNull(2)) {
                    "video" -> json.encodeToString(TaVideoStats.serializer(), SampleData.videoStats)
                    "channel" ->
                        json.encodeToString(TaCountStats.serializer(), TaCountStats(48, 45, 31))
                    "playlist" ->
                        json.encodeToString(TaCountStats.serializer(), TaCountStats(64, 60, 12))
                    "download" ->
                        json.encodeToString(
                            TaDownloadStats.serializer(),
                            TaDownloadStats(8, 3, 6, 1, 1),
                        )
                    "watch" ->
                        json.encodeToString(
                            TaWatchStats.serializer(),
                            TaWatchStats(
                                TaWatchPart(1_200, 3_200_000, 0.27),
                                TaWatchPart(3_182, 8_670_000, 0.73),
                            ),
                        )
                    "downloadhist" ->
                        list(
                            TaDownloadDay.serializer(),
                            (0 until 10).map {
                                TaDownloadDay(
                                    "2026-11-${18 - it}",
                                    3L + it % 4,
                                    2_000_000_000L * (1 + it % 3),
                                )
                            },
                        )
                    "biggestchannels" ->
                        list(
                            TaBiggestChannel.serializer(),
                            SampleData.channels.mapIndexed { index, channel ->
                                TaBiggestChannel(
                                    channel.id,
                                    channel.name,
                                    900L - index * 120,
                                    2_000_000L - index * 250_000,
                                    600_000_000_000L - index * 80_000_000_000L,
                                )
                            },
                        )
                    else -> null
                }
            "user" -> """{"file_size_unit":"binary"}"""
            // TubeArchivist's own settings, and when two of its tasks run
            "appsettings" ->
                """{"subscriptions":{"channel_size":50,"live_channel_size":50,""" +
                    """"shorts_channel_size":50,"playlist_size":50,"auto_start":false,""" +
                    """"extract_flat":false},"downloads":{"limit_speed":null,""" +
                    """"sleep_interval":10,"autodelete_days":null,""" +
                    """"format":"bestvideo[height<=1080]+bestaudio/best[height<=1080]",""" +
                    """"format_sort":null,"add_metadata":true,"subtitle":"en",""" +
                    """"subtitle_source":"auto","subtitle_index":false,"comment_max":null,""" +
                    """"comment_sort":"top","integrate_sponsorblock":true,"integrate_ryd":false,""" +
                    """"extractor_lang":null},"application":{"enable_snapshot":true}}"""
            "task" ->
                if (parts.getOrNull(2) == "schedule") {
                    """[{"name":"update_subscribed","schedule":"0 8 *"},""" +
                        """{"name":"check_reindex","schedule":"0 */6 *"}]"""
                } else {
                    null
                }
            else -> null
        }
    }

    /** A gradient in a colour picked from the path, so each image looks different. */
    private fun picture(path: String): ByteArray {
        val banner = path.contains("banner")
        val square = path.contains("avatar")
        val width = if (banner) 1280 else if (square) 160 else 480
        val height = if (banner) 210 else if (square) 160 else 270
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val hue = (path.hashCode() and 0xFFFF) / 65535f
        val graphics = image.createGraphics()
        graphics.paint =
            GradientPaint(
                0f,
                0f,
                Color.getHSBColor(hue, 0.55f, 0.75f),
                width.toFloat(),
                height.toFloat(),
                Color.getHSBColor((hue + 0.15f) % 1f, 0.6f, 0.35f),
            )
        graphics.fillRect(0, 0, width, height)
        graphics.dispose()
        return ByteArrayOutputStream().use {
            ImageIO.write(image, "png", it)
            it.toByteArray()
        }
    }
}
