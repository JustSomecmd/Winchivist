package dev.jdtech.tubedroid.desktop.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.jdtech.tubedroid.desktop.LocalApp
import dev.jdtech.tubedroid.desktop.LocalNavigator
import dev.jdtech.tubedroid.desktop.Route
import dev.jdtech.tubedroid.desktop.jrwi.JrwiColors
import dev.jdtech.tubedroid.desktop.jrwi.JrwiPage
import dev.jdtech.tubedroid.desktop.jrwi.JrwiPurple
import dev.jdtech.tubedroid.desktop.jrwi.JrwiTheme
import dev.jdtech.tubedroid.desktop.ui.AppIcon
import dev.jdtech.tubedroid.desktop.ui.RoundIconButton
import dev.jdtech.tubedroid.desktop.ui.Spacings
import dev.jdtech.tubedroid.desktop.ui.Tooltip
import dev.jdtech.tubedroid.desktop.ui.formatDuration
import dev.jdtech.tubedroid.desktop.ui.pictureFrom
import kotlin.math.abs
import kotlin.math.ceil
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How tall the audio player's bar along the bottom of the window is. */
val AUDIO_BAR_HEIGHT = 72.dp

/**
 * What the audio player draws with. A JRWI episode's player is in the archive's colours, with its
 * bars in the archiver's purple and its buttons in the site's yellow, as in the phone app. Anything
 * else is in the app's own.
 */
private data class AudioLook(val jrwi: Boolean, val bar: Color, val surface: Color)

private val JrwiAudioLook = AudioLook(true, JrwiPurple.Accent, JrwiColors.Header)

@Composable
private fun AudioLooks(jrwi: Boolean, content: @Composable (AudioLook) -> Unit) {
    if (jrwi) {
        JrwiTheme { content(JrwiAudioLook) }
    } else {
        val colors = MaterialTheme.colorScheme
        content(AudioLook(false, colors.primary, colors.surfaceContainer))
    }
}

/** The full player's background: the archive's page with its glow, or the app's own. */
@Composable
private fun AudioPage(look: AudioLook, content: @Composable () -> Unit) {
    if (look.jrwi) {
        JrwiPage(content = content)
    } else {
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxSize(),
            content = content,
        )
    }
}

/**
 * The audio player along the bottom of the window while something is loaded: its picture and title,
 * which open the full player, then back, play and forward, the place in it, the volume, the
 * playlist when it plays from one, and close.
 */
@Composable
fun AudioBar(audio: AudioPlayer, modifier: Modifier = Modifier) {
    val session = audio.session ?: return
    val settings = LocalApp.current.settings
    // Where it went when another device had gone on with it, said at the bottom of the window
    val navigator = LocalNavigator.current
    val notice = session.notice
    LaunchedEffect(notice) { notice?.let(navigator::message) }
    val item = session.item
    AudioLooks(jrwi = item?.jrwi != null) { look ->
        Surface(color = look.surface, modifier = modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier.height(AUDIO_BAR_HEIGHT)
                        .padding(horizontal = Spacings.medium, vertical = Spacings.small),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier.weight(1f)
                            .fillMaxHeight()
                            .clip(MaterialTheme.shapes.small)
                            .clickable { audio.expanded = true },
                ) {
                    AudioPicture(
                        picture = item?.picture,
                        modifier = Modifier.fillMaxHeight().aspectRatio(16f / 9f),
                    )
                    Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                        Text(
                            text = item?.title ?: "Loading…",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val error = session.error
                        Text(
                            text = error ?: item?.subtitle.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            // The channel or show in the accent of what plays, as over a video
                            color =
                                if (error != null) MaterialTheme.colorScheme.error else look.bar,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                RoundIconButton(
                    icon = "ic_rewind",
                    label = "${settings.seekBackSeconds} seconds back",
                    onClick = audio::seekBack,
                )
                PlayButton(
                    session = session,
                    look = look,
                    size = 44.dp,
                    onClick = audio::togglePause,
                )
                RoundIconButton(
                    icon = "ic_fast_forward",
                    label = "${settings.seekForwardSeconds} seconds forward",
                    onClick = audio::seekForward,
                )
                session.upNext?.let { next ->
                    RoundIconButton(
                        icon = "ic_skip_forward",
                        label = "Next: $next",
                        onClick = session::playNext,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1.2f).padding(start = Spacings.small),
                ) {
                    AudioTime(formatDuration(session.position))
                    SeekBar(
                        position = session.position,
                        duration = session.duration,
                        chapters = session.chapters,
                        onSeek = audio::seekTo,
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                        accent = look.bar,
                    )
                    AudioTime(timeLeft(session) ?: formatDuration(session.duration))
                }
                Spacer(Modifier.width(Spacings.small))
                // The volume, at hand without opening the player
                RoundIconButton(
                    icon = volumeIcon(session),
                    label = if (session.muted) "Sound on" else "Mute",
                    onClick = session::toggleMute,
                )
                ThinSlider(
                    value = if (session.muted) 0f else (session.volume / 100).toFloat(),
                    onChange = { session.changeVolume(it * 100.0) },
                    modifier = Modifier.width(88.dp),
                    accent = look.bar,
                )
                Spacer(Modifier.width(Spacings.small))
                if (session.hasPlaylist) {
                    RoundIconButton(
                        icon = "ic_list_video",
                        label = "Show the playlist",
                        onClick = {
                            audio.expanded = true
                            if (!session.playlistOpen) togglePlaylist(session)
                        },
                    )
                }
                RoundIconButton(
                    icon = "ic_chevron_up",
                    label = "Open the player",
                    onClick = { audio.expanded = true },
                )
                RoundIconButton(icon = "ic_x", label = "Stop and close", onClick = audio::close)
            }
        }
    }
}

/**
 * The audio player over the whole window: the picture, or the transcript or the playlist in its
 * place, the title and show, a bar to seek with, and under Play the speed, a sleep timer, the
 * transcript, the playlist and the video from the same moment. In a wide window the transcript or
 * the playlist sits beside the rest instead.
 */
@Composable
fun AudioScreen(audio: AudioPlayer) {
    val session = audio.session ?: return
    val navigator = LocalNavigator.current
    val item = session.item
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AudioLooks(jrwi = item?.jrwi != null) { look ->
        AudioPage(look) {
            Column(
                modifier =
                    Modifier.fillMaxSize()
                        // Nothing under the player is clicked through it
                        .pointerInput(Unit) { detectTapGestures {} }
                        .focusRequester(focus)
                        .focusable()
                        .onKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                            when (event.key) {
                                Key.Spacebar,
                                Key.K -> audio.togglePause()
                                Key.DirectionLeft,
                                Key.J -> audio.seekBack()
                                Key.DirectionRight,
                                Key.L -> audio.seekForward()
                                else -> return@onKeyEvent false
                            }
                            true
                        }
            ) {
                AudioTopBar(audio = audio, session = session)
                val transcriptPost = item?.transcriptPost
                // The playlist and the transcript share one place, the playlist first
                val playlist = session.playlistOpen && session.hasPlaylist
                val lines = !playlist && session.transcriptOpen && transcriptPost != null
                val transcript: @Composable (Modifier) -> Unit = { modifier ->
                    if (transcriptPost != null) {
                        TranscriptPanel(
                            postId = transcriptPost,
                            position = { session.position },
                            onSeek = audio::seekTo,
                            modifier = modifier.clip(MaterialTheme.shapes.small),
                        )
                    }
                }
                val videos: @Composable (Modifier) -> Unit = { modifier ->
                    PlaylistPanel(
                        name = session.playlistName,
                        videos = session.playlistVideos,
                        currentVideoId = item?.id,
                        loop = session.loop,
                        onPlay = session::playFromPlaylist,
                        onLoopChange = session::changeLoop,
                        onOpenPage = {
                            audio.playlistId?.let { id ->
                                audio.expanded = false
                                navigator.open(Route.Playlist(id))
                            }
                        },
                        onClose = session::togglePlaylist,
                        modifier = modifier.clip(MaterialTheme.shapes.small),
                    )
                }
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val beside = (lines || playlist) && maxWidth >= BESIDE_FROM
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        modifier =
                            Modifier.fillMaxSize()
                                .padding(horizontal = Spacings.large)
                                .padding(bottom = Spacings.default),
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.widthIn(max = PLAYER_WIDTH).fillMaxHeight(),
                        ) {
                            if (playlist && !beside) {
                                videos(Modifier.weight(1f).fillMaxWidth())
                            } else if (lines && !beside) {
                                transcript(Modifier.weight(1f).fillMaxWidth())
                            } else {
                                // As large as the window leaves room for, and never cut
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                ) {
                                    AudioPicture(
                                        picture = item?.picture,
                                        modifier =
                                            Modifier.aspectRatio(
                                                16f / 9f,
                                                matchHeightConstraintsFirst = true,
                                            ),
                                        contentScale = ContentScale.Fit,
                                    )
                                }
                            }
                            AudioControls(audio = audio, session = session, look = look)
                        }
                        if (beside) {
                            Spacer(Modifier.width(Spacings.large))
                            if (playlist) {
                                videos(Modifier.width(PLAYLIST_PANEL_WIDTH).fillMaxHeight())
                            } else {
                                transcript(Modifier.weight(1f).fillMaxHeight())
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The speaker as loud as the sound is: crossed out, with one wave, or with two. */
private fun volumeIcon(session: PlayerSession): String =
    when {
        session.muted || session.volume <= 0 -> "ic_volume_0"
        session.volume < 50 -> "ic_volume_50"
        else -> "ic_volume"
    }

// The playlist and the transcript share one place in the player, so opening one closes the other
private fun togglePlaylist(session: PlayerSession) {
    if (!session.playlistOpen && session.transcriptOpen) session.toggleTranscript()
    session.togglePlaylist()
}

private fun toggleTranscript(session: PlayerSession) {
    if (!session.transcriptOpen && session.playlistOpen) session.togglePlaylist()
    session.toggleTranscript()
}

/** Back to the page with the bar, and the page of what plays. */
@Composable
private fun AudioTopBar(audio: AudioPlayer, session: PlayerSession) {
    val navigator = LocalNavigator.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = Spacings.small),
    ) {
        RoundIconButton(
            icon = "ic_chevron_down",
            label = "Back to the page",
            onClick = { audio.expanded = false },
        )
        Spacer(Modifier.weight(1f))
        session.item?.route?.let { route ->
            RoundIconButton(
                icon = "ic_info",
                label = "Open its page",
                onClick = {
                    audio.expanded = false
                    navigator.open(route)
                },
            )
        }
        RoundIconButton(icon = "ic_x", label = "Stop and close", onClick = audio::close)
    }
}

/** The title, the bar to seek with, the buttons, and what comes next. */
@Composable
private fun AudioControls(audio: AudioPlayer, session: PlayerSession, look: AudioLook) {
    val app = LocalApp.current
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val item = session.item
    // Clicking the time left shows the whole length instead, and back
    var wholeLength by remember { mutableStateOf(false) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(top = Spacings.medium),
    ) {
        Text(
            text = item?.title ?: "Loading…",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        item?.subtitle?.let { subtitle ->
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = look.bar,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        session.error?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = Spacings.small),
            )
        }
        SeekBar(
            position = session.position,
            duration = session.duration,
            chapters = session.chapters,
            onSeek = audio::seekTo,
            modifier = Modifier.fillMaxWidth().padding(top = Spacings.small),
            accent = look.bar,
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            AudioTime(formatDuration(session.position))
            Spacer(Modifier.weight(1f))
            val left = timeLeft(session)
            Text(
                text = if (wholeLength || left == null) formatDuration(session.duration) else left,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier.clip(MaterialTheme.shapes.small)
                        .clickable { wholeLength = !wholeLength }
                        .padding(horizontal = 4.dp),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacings.medium),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            RoundIconButton(
                icon = "ic_rewind",
                label = "${app.settings.seekBackSeconds} seconds back",
                onClick = audio::seekBack,
                size = 48.dp,
            )
            PlayButton(session = session, look = look, size = 64.dp, onClick = audio::togglePause)
            RoundIconButton(
                icon = "ic_fast_forward",
                label = "${app.settings.seekForwardSeconds} seconds forward",
                onClick = audio::seekForward,
                size = 48.dp,
            )
        }
        session.segment?.let { segment ->
            FilledTonalButton(
                onClick = { session.skip(segment) },
                modifier = Modifier.padding(top = Spacings.small),
            ) {
                Text("Skip ${PlayerSession.categoryName(segment.category).lowercase()}")
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacings.small),
            modifier = Modifier.padding(top = Spacings.small),
        ) {
            SpeedButton(session)
            SleepButton(session)
            if (item?.transcriptPost != null) {
                AudioButton(
                    icon = "ic_transcript",
                    label = if (session.transcriptOpen) "Close the transcript" else "Transcript",
                    lit = session.transcriptOpen,
                    onClick = { toggleTranscript(session) },
                )
            }
            AudioButton(
                icon = "ic_repeat_1",
                label = if (session.loopVideo) "Stop looping this" else "Loop this",
                lit = session.loopVideo,
                onClick = { session.changeLoopVideo(!session.loopVideo) },
            )
            if (session.hasPlaylist) {
                AudioButton(
                    icon = "ic_list_video",
                    label = if (session.playlistOpen) "Close the playlist" else "Show the playlist",
                    lit = session.playlistOpen,
                    onClick = { togglePlaylist(session) },
                )
            }
            if (audio.hasVideo) {
                AudioButton(
                    icon = "ic_film",
                    label = "Watch the video from here",
                    onClick = {
                        scope.launch {
                            val video = audio.videoRequest()
                            if (video != null) navigator.play(video)
                            else navigator.message("There's no video of this that can be played.")
                        }
                    },
                )
            }
            session.upNext?.let { next ->
                AudioButton(
                    icon = "ic_skip_forward",
                    label = "Next: $next",
                    onClick = session::playNext,
                )
            }
            Spacer(Modifier.width(Spacings.small))
            RoundIconButton(
                icon = volumeIcon(session),
                label = if (session.muted) "Sound on" else "Mute",
                onClick = session::toggleMute,
            )
            ThinSlider(
                value = if (session.muted) 0f else (session.volume / 100).toFloat(),
                onChange = { session.changeVolume(it * 100.0) },
                modifier = Modifier.width(96.dp),
                accent = look.bar,
            )
        }
        // What happens when this one ends: it stops, starts over, or the next one plays
        val next = session.upNext
        Text(
            text =
                when {
                    session.sleep?.atEnd == true -> "Stops at the end"
                    session.loopVideo -> "Starts over at the end"
                    next != null && app.settings.autoplay -> "Next: $next"
                    else -> ""
                },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = Spacings.small),
        )
    }
}

/** Play or pause, filled in the accent, or a spinner while it loads. */
@Composable
private fun PlayButton(session: PlayerSession, look: AudioLook, size: Dp, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 4.dp)) {
        if (session.loading && session.error == null) {
            CircularProgressIndicator(
                color = look.bar,
                strokeWidth = 3.dp,
                modifier = Modifier.size(size).padding(6.dp),
            )
        } else {
            Tooltip(if (session.paused) "Play" else "Pause") {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier.size(size)
                            .clip(if (look.jrwi) MaterialTheme.shapes.small else CircleShape)
                            .background(colors.primary)
                            .clickable(onClick = onClick),
                ) {
                    AppIcon(
                        name = if (session.paused) "ic_play" else "ic_pause",
                        contentDescription = if (session.paused) "Play" else "Pause",
                        tint = colors.onPrimary,
                        modifier = Modifier.size(size * 0.5f),
                    )
                }
            }
        }
    }
}

/** A button under Play: an icon, or a few words such as the speed, lit while it's on. */
@Composable
private fun AudioButton(
    label: String,
    onClick: () -> Unit,
    icon: String? = null,
    text: String? = null,
    lit: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    Tooltip(label) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier =
                Modifier.height(40.dp)
                    .widthIn(min = 56.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(if (lit) colors.primary else colors.surfaceContainerHighest)
                    .clickable(onClick = onClick)
                    .padding(horizontal = 12.dp),
        ) {
            val tint = if (lit) colors.onPrimary else colors.onSurface
            if (icon != null) {
                AppIcon(
                    name = icon,
                    contentDescription = label,
                    tint = tint,
                    modifier = Modifier.size(20.dp),
                )
            }
            if (text != null) {
                if (icon != null) Spacer(Modifier.width(6.dp))
                Text(text = text, style = MaterialTheme.typography.labelLarge, color = tint)
            }
        }
    }
}

/** A button under Play that opens a menu over it. */
@Composable
private fun AudioMenu(
    label: String,
    icon: String? = null,
    text: String? = null,
    lit: Boolean = false,
    content: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        AudioButton(label = label, icon = icon, text = text, lit = lit, onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            content { open = false }
        }
    }
}

/** How fast it plays, from half speed to three times, remembered for next time. */
@Composable
private fun SpeedButton(session: PlayerSession) {
    AudioMenu(
        label = "Speed",
        text = speedLabel(session.speed),
        lit = abs(session.speed - 1) > 0.01,
    ) { close ->
        PlayerSession.AUDIO_SPEEDS.forEach { speed ->
            MenuRow(text = speedLabel(speed), ticked = abs(speed - session.speed) < 0.01) {
                session.changeSpeed(speed)
                close()
            }
        }
    }
}

/**
 * The sleep timer: a number of minutes, the sound fading out at the end, or the end of what plays.
 * While one runs it shows the minutes left and can be moved or turned off.
 */
@Composable
private fun SleepButton(session: PlayerSession) {
    val sleep = session.sleep
    val endsAt = sleep?.endsAt
    // Counted down here, as nothing else changes while it's paused
    val minutesLeft by
        produceState<Int?>(initialValue = null, endsAt) {
            while (endsAt != null) {
                val left = endsAt - System.currentTimeMillis()
                value = ceil(left / 60_000.0).toInt().coerceAtLeast(1)
                delay(SLEEP_SHOWN_EVERY_MS)
            }
            value = null
        }
    AudioMenu(
        label = "Sleep timer",
        icon = "ic_moon",
        text = if (sleep?.atEnd == true) "End" else minutesLeft?.let { "$it min" },
        lit = sleep != null,
    ) { close ->
        if (sleep != null) {
            if (endsAt != null) {
                MenuRow("5 minutes more") { session.addSleep(5) }
                MenuRow("15 minutes more") { session.addSleep(15) }
                MenuRow("5 minutes less") { session.addSleep(-5) }
            }
            MenuRow("Turn off") {
                session.cancelSleep()
                close()
            }
            HorizontalDivider()
        }
        SLEEP_MINUTES.forEach { minutes ->
            MenuRow("$minutes minutes") {
                session.sleepIn(minutes)
                close()
            }
        }
        MenuRow(text = "The end of this one", ticked = sleep?.atEnd == true) {
            session.sleepAtEnd()
            close()
        }
    }
}

@Composable
private fun MenuRow(text: String, ticked: Boolean = false, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text = text, fontWeight = if (ticked) FontWeight.SemiBold else null) },
        leadingIcon = {
            Box(Modifier.size(18.dp)) {
                if (ticked) {
                    AppIcon(
                        name = "ic_check",
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        },
        onClick = onClick,
    )
}

/** What plays, as its picture, on the colour behind pictures while it loads or has none. */
@Composable
private fun AudioPicture(
    picture: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        if (picture != null) {
            AsyncImage(
                model = pictureFrom(picture),
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            AppIcon(
                name = "ic_headphones",
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxHeight(0.4f).aspectRatio(1f),
            )
        }
    }
}

@Composable
private fun AudioTime(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

/**
 * How long is left at the speed it plays at, such as "-1:12:40", or null while the length isn't
 * known.
 */
private fun timeLeft(session: PlayerSession): String? {
    val left = session.duration - session.position
    if (session.duration <= 0 || left < 0) return null
    return "-" + formatDuration(left / session.speed.coerceAtLeast(0.1))
}

private val SLEEP_MINUTES = listOf(5, 10, 15, 30, 45, 60, 90)
private const val SLEEP_SHOWN_EVERY_MS = 5_000L

/** From this width the transcript sits beside the player rather than in the picture's place. */
private val BESIDE_FROM = 1040.dp
private val PLAYER_WIDTH = 720.dp
