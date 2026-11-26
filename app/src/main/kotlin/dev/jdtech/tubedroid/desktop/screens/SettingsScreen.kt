package dev.jdtech.tubedroid.desktop.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.jdtech.tubedroid.desktop.AppSettings
import dev.jdtech.tubedroid.desktop.LocalApp
import dev.jdtech.tubedroid.desktop.LocalNavigator
import dev.jdtech.tubedroid.desktop.Route
import dev.jdtech.tubedroid.desktop.StartWithWindows
import dev.jdtech.tubedroid.desktop.jellyfin.Languages
import dev.jdtech.tubedroid.desktop.jrwi.jrwiErrorText
import dev.jdtech.tubedroid.desktop.player.StreamQuality
import dev.jdtech.tubedroid.desktop.ui.AppIcon
import dev.jdtech.tubedroid.desktop.ui.ColumnScrollbar
import dev.jdtech.tubedroid.desktop.ui.HeaderTabs
import dev.jdtech.tubedroid.desktop.ui.PageBar
import dev.jdtech.tubedroid.desktop.ui.Spacings
import dev.jdtech.tubedroid.desktop.ui.WinchivistLogo
import dev.jdtech.tubedroid.desktop.ui.accentSeeds
import dev.jdtech.tubedroid.desktop.ui.clearImageCache
import dev.jdtech.tubedroid.desktop.ui.errorText
import dev.jdtech.tubedroid.desktop.ui.formatSize
import dev.jdtech.tubedroid.desktop.ui.imageCacheSize
import java.io.File
import javax.swing.JFileChooser
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The settings in groups, each a tab at the top of the page. */
internal enum class SettingsGroup(val label: String) {
    SERVERS("Servers"),
    JELLYFIN("Jellyfin pages"),
    PLAYBACK("Playback"),
    NOTIFICATIONS("Notifications"),
    FILES("Files"),
    APP("App"),
}

/** The group that shows, kept while the app runs so that coming back to Settings finds it. */
internal var settingsGroup by mutableStateOf(SettingsGroup.SERVERS)

/**
 * The app's settings. On the first start it only asks for TubeArchivist's address and token, since
 * nothing else works without them. Doesn't use the page history, so it also works outside the
 * shell.
 */
@Composable
fun SettingsScreen(firstRun: Boolean, onDone: () -> Unit, onSkip: (() -> Unit)? = null) {
    val settings = LocalApp.current.settings
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        if (firstRun) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.widthIn(max = 520.dp).padding(Spacings.large),
                ) {
                    WinchivistLogo(modifier = Modifier.size(72.dp))
                    Spacer(Modifier.height(Spacings.default))
                    Text("Welcome to Winchivist", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(Spacings.small))
                    Text(
                        text =
                            "Connect to your TubeArchivist server. The API token is on TubeArchivist's " +
                                "settings page, under Application.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Spacings.large))
                    ServerForm(settings = settings, onSaved = onDone)
                    if (onSkip != null) {
                        Spacer(Modifier.height(Spacings.medium))
                        TextButton(onClick = onSkip) {
                            Text("Set up Jellyfin or the JRWI archiver instead")
                        }
                    }
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                PageBar(title = "Settings", onBack = null)
                HeaderTabs(
                    tabs = SettingsGroup.entries.map { it.label },
                    selected = settingsGroup.ordinal,
                    onSelect = { settingsGroup = SettingsGroup.entries[it] },
                    modifier = Modifier.padding(horizontal = Spacings.small),
                )
                // Each group scrolls by itself, from its top
                key(settingsGroup) {
                    val scroll = rememberScrollState()
                    Box(modifier = Modifier.fillMaxSize()) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(Spacings.default),
                            modifier =
                                Modifier.verticalScroll(scroll)
                                    .padding(Spacings.default)
                                    .widthIn(max = 760.dp)
                                    .fillMaxWidth(),
                        ) {
                            when (settingsGroup) {
                                SettingsGroup.SERVERS -> ServerGroup(settings, onDone)
                                SettingsGroup.JELLYFIN -> {
                                    SettingsSection("Home") { JellyfinHomeSettings(settings) }
                                    SettingsSection("Shows and episodes") {
                                        EpisodeSettings(settings)
                                    }
                                    SettingsSection("Movie, show and episode pages") {
                                        JellyfinPageSettings(settings)
                                    }
                                }
                                SettingsGroup.PLAYBACK -> {
                                    SettingsSection("Player") { PlaybackSettings(settings) }
                                    SettingsSection("Languages") { LanguageSettings(settings) }
                                    SettingsSection("Skipping") { SkipSettings(settings) }
                                    SettingsSection("mpv") { MpvSettings(settings) }
                                }
                                SettingsGroup.NOTIFICATIONS -> {
                                    SettingsSection("Notifications") {
                                        NotificationSettings(settings)
                                    }
                                    SettingsSection("In the background") {
                                        BackgroundSettings(settings)
                                    }
                                }
                                SettingsGroup.FILES -> {
                                    SettingsSection("Downloads") { DownloadSettings(settings) }
                                    SettingsSection("Pictures") { ImageSettings(settings) }
                                }
                                SettingsGroup.APP -> {
                                    SettingsSection("Appearance") {
                                        AccentPicker(settings)
                                        SwitchRow(
                                            title = "Winchivist's own window bar",
                                            subtitle =
                                                "In place of Windows' own bar along the top. " +
                                                    "Changes the next time Winchivist starts",
                                            checked = settings.ownWindowBar,
                                            onChange = { settings.ownWindowBar = it },
                                        )
                                    }
                                    SettingsSection("About") {
                                        Text(
                                            text = "Winchivist ${appVersion()}",
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        Text(
                                            text = "Video playback by mpv (libmpv).",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        UpdateCheck()
                                    }
                                }
                            }
                        }
                        ColumnScrollbar(scroll)
                    }
                }
            }
        }
    }
}

/** The three servers Winchivist reads from, each set up by itself. */
@Composable
private fun ServerGroup(settings: AppSettings, onDone: () -> Unit) {
    SettingsSection("TubeArchivist") {
        ServerForm(settings = settings, onSaved = onDone)
        if (settings.isConfigured) {
            // Kept on TubeArchivist's server, so on a page of their own
            val navigator = LocalNavigator.current
            TextButton(onClick = { navigator.open(Route.TaSettings) }) {
                Text("TubeArchivist's own settings and schedules")
            }
        }
    }
    SettingsSection("JRWI archiver") { JrwiForm(settings) }
    SettingsSection("Jellyfin") { JellyfinForm() }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(Spacings.medium),
            modifier = Modifier.padding(Spacings.default),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            content()
        }
    }
}

/** The address and token, saved only once TubeArchivist answers with them. */
@Composable
private fun ServerForm(settings: AppSettings, onSaved: () -> Unit) {
    val api = LocalApp.current.api
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(settings.url.orEmpty()) }
    var token by remember { mutableStateOf(settings.token.orEmpty()) }
    var showToken by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val changed =
        url.trim().trimEnd('/') != settings.url.orEmpty() ||
            token.trim() != settings.token.orEmpty()

    fun connect() {
        if (testing || url.isBlank() || token.isBlank()) return
        testing = true
        status = null
        scope.launch {
            try {
                val version = api.ping(url, token)
                settings.url = url.trim().trimEnd('/')
                settings.token = token.trim()
                failed = false
                status = "Connected" + (version?.let { " to TubeArchivist $it" } ?: "")
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
                status =
                    if (e is IllegalArgumentException) "That address isn't valid" else errorText(e)
            } finally {
                testing = false
            }
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(Spacings.medium),
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Server address") },
            placeholder = { Text("https://tube.example.com") },
            singleLine = true,
            keyboardOptions =
                KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("API token") },
            singleLine = true,
            visualTransformation =
                if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { showToken = !showToken }) {
                    Text(if (showToken) "Hide" else "Show")
                }
            },
            keyboardOptions =
                KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { connect() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = ::connect,
                enabled = !testing && url.isNotBlank() && token.isNotBlank() && changed,
            ) {
                Text(if (settings.isConfigured) "Save" else "Connect")
            }
            Spacer(Modifier.width(Spacings.medium))
            if (testing) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                status?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color =
                            if (failed) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/**
 * The JRWI archiver's address and API token, saved only once the archiver answers with them: its
 * ping gives its version, and its status checks the token.
 */
@Composable
private fun JrwiForm(settings: AppSettings) {
    val api = LocalApp.current.jrwi.api
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(settings.jrwiUrl.orEmpty()) }
    var token by remember { mutableStateOf(settings.jrwiToken.orEmpty()) }
    var showToken by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val changed =
        url.trim().trimEnd('/') != settings.jrwiUrl.orEmpty() ||
            token.trim() != settings.jrwiToken.orEmpty()

    fun connect() {
        if (testing || url.isBlank() || token.isBlank()) return
        testing = true
        status = null
        scope.launch {
            try {
                val version = api.check(url, token)
                settings.jrwiUrl = url.trim().trimEnd('/')
                settings.jrwiToken = token.trim()
                failed = false
                status = "Connected to the archiver" + (version?.let { " $it" } ?: "")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
                status =
                    if (e is IllegalArgumentException) "That address isn't valid"
                    else jrwiErrorText(e)
            } finally {
                testing = false
            }
        }
    }

    Text(
        text =
            "The archiver keeps a copy of the members' area of jrwishow.com. With it connected, " +
                "its shows, posts and comments are under JRWI in the bar on the left. Videos " +
                "play through Jellyfin, and audio from the archiver.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = url,
        onValueChange = { url = it },
        label = { Text("Archiver address") },
        placeholder = { Text("http://box:8090") },
        singleLine = true,
        keyboardOptions =
            KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = token,
        onValueChange = { token = it },
        label = { Text("API token") },
        supportingText = { Text("In the archiver's web app under Settings") },
        singleLine = true,
        visualTransformation =
            if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            TextButton(onClick = { showToken = !showToken }) {
                Text(if (showToken) "Hide" else "Show")
            }
        },
        keyboardOptions =
            KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { connect() }),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = ::connect,
            enabled = !testing && url.isNotBlank() && token.isNotBlank() && changed,
        ) {
            Text(if (settings.isJrwiConfigured) "Save" else "Connect")
        }
        if (settings.isJrwiConfigured) {
            Spacer(Modifier.width(Spacings.small))
            OutlinedButton(
                onClick = {
                    settings.jrwiUrl = null
                    settings.jrwiToken = null
                    url = ""
                    token = ""
                    status = null
                }
            ) {
                Text("Disconnect")
            }
        }
        Spacer(Modifier.width(Spacings.medium))
        if (testing) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            status?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color =
                        if (failed) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun DownloadSettings(settings: AppSettings) {
    Text(
        text =
            "The download buttons save straight to this folder. What comes from the JRWI " +
                "archive goes into a JRWI folder inside it.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = settings.downloadFolder,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(
            onClick = {
                chooseFolder(settings.downloadFolder)?.let { settings.downloadFolder = it.path }
            }
        ) {
            Text("Change")
        }
        Spacer(Modifier.width(Spacings.small))
        TextButton(onClick = { openFolder(File(settings.downloadFolder)) }) { Text("Open") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaybackSettings(settings: AppSettings) {
    SwitchRow(
        title = "Autoplay",
        subtitle = "Plays the next video of a playlist, or the next episode, when one ends",
        checked = settings.autoplay,
        onChange = { settings.autoplay = it },
    )
    val steps = SEEK_STEPS.map { it.toString() to "$it s" }
    ChoiceRow(
        title = "Back button",
        subtitle = "How far the players' back button goes",
        options = steps,
        selected = settings.seekBackSeconds.toString(),
        onSelect = { settings.seekBackSeconds = it.toInt() },
    )
    ChoiceRow(
        title = "Forward button",
        subtitle = "How far the players' forward button goes",
        options = steps,
        selected = settings.seekForwardSeconds.toString(),
        onSelect = { settings.seekForwardSeconds = it.toInt() },
    )
    SwitchRow(
        title = "Controls over the video",
        subtitle =
            "As on Jellyfin. Turn this off if the controls show on black instead of over the " +
                "picture; they then sit above and below it",
        checked = settings.playerControlsOverVideo,
        onChange = { settings.playerControlsOverVideo = it },
    )
    SwitchRow(
        title = "Chapter marks",
        subtitle = "Marks on the seek bar where chapters start",
        checked = settings.chapterMarks,
        onChange = { settings.chapterMarks = it },
    )
    SwitchRow(
        title = "Seek previews",
        subtitle = "Jellyfin's preview pictures above the seek bar, for videos that have them",
        checked = settings.trickplay,
        onChange = { settings.trickplay = it },
    )
    SwitchRow(
        title = "Accent-coloured controls",
        subtitle = "The video player's buttons in the accent colour rather than white",
        checked = settings.accentControls,
        onChange = { settings.accentControls = it },
    )
    ChoiceRow(
        title = "Streaming quality",
        subtitle =
            "For Jellyfin. Below Original, Jellyfin converts the video, which suits a slow " +
                "connection. It can be changed while a video plays, too",
        options = StreamQuality.entries.map { it.key to it.label },
        selected = settings.streamQuality,
        onSelect = { settings.streamQuality = it },
    )
    ChoiceRow(
        title = "Hardware decoding",
        subtitle = "Turn it off if videos show green frames or stutter",
        options = listOf("auto-safe" to "On", "auto-copy-safe" to "Compatible", "no" to "Off"),
        selected = settings.hardwareDecoding,
        onSelect = { settings.hardwareDecoding = it },
    )
}

/** The rows of Jellyfin's Home, each of which can be left out. */
@Composable
private fun JellyfinHomeSettings(settings: AppSettings) {
    if (!LocalApp.current.jellyfin.isSignedIn) {
        Text(
            text = "These count once Jellyfin is signed in to, under Servers.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    SwitchRow(
        title = "Continue watching",
        subtitle = "Movies and episodes started but not finished",
        checked = settings.homeContinue,
        onChange = { settings.homeContinue = it },
    )
    SwitchRow(
        title = "Next up",
        subtitle = "The next episode of each show being watched",
        checked = settings.homeNextUp,
        onChange = { settings.homeNextUp = it },
    )
    SwitchRow(
        title = "Suggestions",
        subtitle = "A few movies and shows Jellyfin picks",
        checked = settings.homeSuggestions,
        onChange = { settings.homeSuggestions = it },
    )
    SwitchRow(
        title = "Latest",
        subtitle = "What was added last, a row for each library",
        checked = settings.homeLatest,
        onChange = { settings.homeLatest = it },
    )
    SwitchRow(
        title = "New episodes in Latest",
        subtitle =
            "Every new episode by itself with its own picture, rather than grouped under its " +
                "show's poster",
        checked = settings.homeLatestEpisodes && settings.homeLatest,
        onChange = { settings.homeLatestEpisodes = it },
        enabled = settings.homeLatest,
    )
}

@Composable
private fun EpisodeSettings(settings: AppSettings) {
    SwitchRow(
        title = "Every episode on show pages",
        subtitle =
            "One list of all of a show's episodes by date, newest or oldest first, in place of " +
                "a season at a time",
        checked = settings.allEpisodes,
        onChange = { settings.allEpisodes = it },
    )
    SwitchRow(
        title = "Hide episode numbers",
        subtitle = "Episode names without the season and number in front, such as S1:E2",
        checked = settings.hideEpisodeNumbers,
        onChange = { settings.hideEpisodeNumbers = it },
    )
    SwitchRow(
        title = "Episode name first",
        subtitle = "On cards, the episode's name on top and its show's below it",
        checked = settings.episodeTitleFirst,
        onChange = { settings.episodeTitleFirst = it },
    )
}

@Composable
private fun JellyfinPageSettings(settings: AppSettings) {
    SwitchRow(
        title = "Cast and crew on movie pages",
        subtitle = "A row of the people in it, each opening what else they're in",
        checked = settings.castMovies,
        onChange = { settings.castMovies = it },
    )
    SwitchRow(
        title = "Cast and crew on show and episode pages",
        subtitle = "The same row for shows and their episodes",
        checked = settings.castShows,
        onChange = { settings.castShows = it },
    )
    SwitchRow(
        title = "File details",
        subtitle =
            "The file's size and its video, sound and subtitle tracks, on movie and " +
                "episode pages",
        checked = settings.extraInfo,
        onChange = { settings.extraInfo = it },
    )
}

/** The languages to play sound in and show subtitles in, ahead of Jellyfin's own choice. */
@Composable
private fun LanguageSettings(settings: AppSettings) {
    LanguageRow(
        title = "Sound",
        subtitle =
            "Played when a video has a sound track in it. Without one, or with none picked " +
                "here, Jellyfin's own choice plays",
        selected = settings.audioLanguage,
        onSelect = { settings.audioLanguage = it },
    )
    LanguageRow(
        title = "Subtitles",
        subtitle =
            "Shown when a video has subtitles in it. Without them, or with none picked here, " +
                "Jellyfin's subtitle settings decide",
        selected = settings.subtitleLanguage,
        onSelect = { settings.subtitleLanguage = it },
    )
}

/** A language picked from a list that narrows as its name is typed. */
@Composable
private fun LanguageRow(
    title: String,
    subtitle: String,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    fun pick(code: String?) {
        open = false
        onSelect(code)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(Spacings.default))
        Box {
            OutlinedButton(
                onClick = {
                    typed = ""
                    open = true
                }
            ) {
                Text(Languages.named(selected)?.name ?: NO_LANGUAGE)
            }
            DropdownMenu(
                expanded = open,
                onDismissRequest = { open = false },
                modifier = Modifier.heightIn(max = 440.dp),
            ) {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    placeholder = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).width(240.dp),
                )
                val found =
                    remember(typed) {
                        Languages.all.filter { it.name.contains(typed.trim(), ignoreCase = true) }
                    }
                if (typed.isBlank()) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = NO_LANGUAGE,
                                fontWeight = if (selected == null) FontWeight.SemiBold else null,
                            )
                        },
                        onClick = { pick(null) },
                    )
                }
                found.forEach { language ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = language.name,
                                fontWeight =
                                    if (language.code == selected) FontWeight.SemiBold else null,
                            )
                        },
                        onClick = { pick(language.code) },
                    )
                }
            }
        }
    }
}

private const val NO_LANGUAGE = "Jellyfin's choice"

@Composable
private fun SkipSettings(settings: AppSettings) {
    ChoiceRow(
        title = "SponsorBlock",
        subtitle = "For videos TubeArchivist has SponsorBlock segments for",
        options =
            listOf(
                AppSettings.SPONSORBLOCK_BUTTON to "Show a skip button",
                AppSettings.SPONSORBLOCK_AUTO to "Skip automatically",
                AppSettings.SPONSORBLOCK_OFF to "Off",
            ),
        selected = settings.sponsorBlock,
        onSelect = { settings.sponsorBlock = it },
    )
    ChoiceRow(
        title = "Intros and credits",
        subtitle = "For Jellyfin's movies and episodes it knows the intro, recap or credits of",
        options =
            listOf(
                AppSettings.SPONSORBLOCK_BUTTON to "Show a skip button",
                AppSettings.SPONSORBLOCK_AUTO to "Skip automatically",
                AppSettings.SPONSORBLOCK_OFF to "Off",
            ),
        selected = settings.mediaSegments,
        onSelect = { settings.mediaSegments = it },
    )
}

@Composable
private fun MpvSettings(settings: AppSettings) {
    Text(
        text =
            "The player is mpv. An mpv.conf, an input.conf or scripts in its folder change it " +
                "further, as with Jellyfin Media Player.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = settings.mpvConfigFolder.path,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { openFolder(settings.mpvConfigFolder) }) { Text("Open") }
    }
}

/** What Winchivist looks for by itself every 15 minutes, and tells as a Windows notification. */
@Composable
private fun NotificationSettings(settings: AppSettings) {
    val jellyfin = LocalApp.current.jellyfin.isSignedIn
    Text(
        text =
            "Shown as Windows notifications while Winchivist runs, with its icon in the tray " +
                "for that. Jellyfin tells Winchivist the moment it adds something; " +
                "TubeArchivist is asked every minute.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SwitchRow(
        title = "New on Jellyfin",
        subtitle =
            if (jellyfin) "Movies and episodes the Jellyfin server didn't have before"
            else "Needs Jellyfin, which is under Servers",
        checked = settings.notifyNewItems && jellyfin,
        onChange = { settings.notifyNewItems = it },
        enabled = jellyfin,
    )
    SwitchRow(
        title = "YouTube's bot detection",
        subtitle =
            if (settings.isConfigured) {
                "When it stops one of TubeArchivist's downloads or rescans. Needs the token of " +
                    "a TubeArchivist admin"
            } else {
                "Needs TubeArchivist, which is under Servers"
            },
        checked = settings.notifyBots && settings.isConfigured,
        onChange = { settings.notifyBots = it },
        enabled = settings.isConfigured,
    )
}

/** Staying in the tray and starting with Windows, both off until switched on here. */
@Composable
private fun BackgroundSettings(settings: AppSettings) {
    val scope = rememberCoroutineScope()
    var refused by remember { mutableStateOf(false) }
    fun startWithWindows(on: Boolean) {
        scope.launch {
            val done = withContext(Dispatchers.IO) { StartWithWindows.set(on) }
            refused = !done
            if (done) settings.startWithWindows = on
        }
    }
    SwitchRow(
        title = "Keep running in the tray",
        subtitle =
            "Closing the window leaves Winchivist in the tray, where it keeps looking and " +
                "keeps playing sound. Quit is in the menu of its tray icon",
        checked = settings.keepInTray,
        onChange = { on ->
            settings.keepInTray = on
            // Started by Windows it has no window, so it needs the tray
            if (!on && settings.startWithWindows) startWithWindows(false)
        },
    )
    SwitchRow(
        title = "Start with Windows",
        subtitle =
            when {
                !StartWithWindows.available -> "Only for a Winchivist that was installed"
                refused -> "Windows didn't take the change"
                !settings.keepInTray -> "Needs Keep running in the tray"
                else -> "Starts in the tray when you sign in to Windows, without a window"
            },
        checked = settings.startWithWindows,
        onChange = ::startWithWindows,
        enabled = StartWithWindows.available && (settings.keepInTray || settings.startWithWindows),
    )
}

/** How much room the pictures kept on this PC may take, and throwing them away. */
@Composable
private fun ImageSettings(settings: AppSettings) {
    val scope = rememberCoroutineScope()
    var used by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) { used = withContext(Dispatchers.IO) { imageCacheSize() } }
    ChoiceRow(
        title = "Room for pictures",
        subtitle =
            "Thumbnails and posters are kept on this PC so pages open quickly. A new size " +
                "counts from the next time Winchivist starts",
        options =
            IMAGE_CACHE_SIZES.map { size ->
                size.toString() to if (size < 1024) "$size MB" else "${size / 1024} GB"
            },
        selected = settings.imageCacheMb.toString(),
        onSelect = { settings.imageCacheMb = it.toInt() },
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = used?.let { "${formatSize(it, metric = true)} in use" }.orEmpty(),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(
            onClick = {
                scope.launch {
                    used =
                        withContext(Dispatchers.IO) {
                            clearImageCache()
                            imageCacheSize()
                        }
                }
            }
        ) {
            Text("Clear")
        }
    }
}

private val IMAGE_CACHE_SIZES = listOf(128, 256, 512, 1024, 2048)

// As the phone app offers for its players' seek buttons
private val SEEK_STEPS = listOf(5, 10, 15, 30, 60)

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(enabled = enabled) {
                onChange(!checked)
            },
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color =
                    if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(Spacings.default))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceRow(
    title: String,
    subtitle: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacings.small)) {
            options.forEach { (value, label) ->
                FilterChip(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    label = { Text(label) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccentPicker(settings: AppSettings) {
    Text("Accent colour", style = MaterialTheme.typography.bodyLarge)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacings.medium),
        verticalArrangement = Arrangement.spacedBy(Spacings.small),
    ) {
        accentSeeds.forEach { (name, color) ->
            val selected = settings.accent == name
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier =
                    Modifier.clip(MaterialTheme.shapes.small)
                        .clickable { settings.accent = name }
                        .padding(Spacings.small),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier.size(40.dp)
                            .clip(CircleShape)
                            .background(color)
                            .then(
                                if (selected) {
                                    Modifier.border(
                                        3.dp,
                                        MaterialTheme.colorScheme.onSurface,
                                        CircleShape,
                                    )
                                } else {
                                    Modifier
                                }
                            ),
                ) {
                    if (selected)
                        AppIcon(
                            name = "ic_check",
                            contentDescription = "Selected",
                            tint = androidx.compose.ui.graphics.Color.White,
                        )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text =
                        if (name == "tubearchivist") "TubeArchivist"
                        else name.replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

private fun chooseFolder(current: String): File? {
    val chooser =
        JFileChooser(current).apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            dialogTitle = "Where to save videos"
        }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile
    else null
}

internal fun openFolder(folder: File) {
    runCatching {
        folder.mkdirs()
        java.awt.Desktop.getDesktop().open(folder)
    }
}

/** The installed version, which jpackage passes on, or "dev" when run from Gradle. */
private fun appVersion(): String =
    System.getProperty("jpackage.app-version") ?: "(development build)"

/**
 * Signs in to a Jellyfin server, for its movies and shows. Only the access token Jellyfin gives is
 * kept, not the password.
 */
@Composable
private fun JellyfinForm() {
    val client = LocalApp.current.jellyfin
    val scope = rememberCoroutineScope()
    if (client.isSignedIn) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Signed in as ${client.userName.orEmpty()}",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = client.serverUrl.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = client::signOut) { Text("Sign out") }
        }
        return
    }
    var url by remember { mutableStateOf(client.serverUrl.orEmpty()) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }

    fun signIn() {
        if (working || url.isBlank() || username.isBlank()) return
        working = true
        failure = null
        scope.launch {
            try {
                client.signIn(url, username, password)
                password = ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e.message ?: "Couldn't sign in"
            } finally {
                working = false
            }
        }
    }

    Text(
        text = "For your movies and shows. They show up in the bar on the left once you're in.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = url,
        onValueChange = { url = it },
        label = { Text("Server address") },
        placeholder = { Text("https://jellyfin.example.com") },
        singleLine = true,
        keyboardOptions =
            KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = username,
        onValueChange = { username = it },
        label = { Text("User name") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text("Password") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions =
            KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { signIn() }),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = ::signIn,
            enabled = !working && url.isNotBlank() && username.isNotBlank(),
        ) {
            Text("Sign in")
        }
        Spacer(Modifier.width(Spacings.medium))
        if (working) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        failure?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** Looks on GitHub for a newer Winchivist; the bar on the left offers to install it. */
@Composable
private fun UpdateCheck() {
    val updates = LocalApp.current.updates
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = {
                scope.launch {
                    result =
                        try {
                            if (updates.checkForUpdate()) {
                                "Winchivist ${updates.available?.version} is out: " +
                                    "Update in the bar on the left installs it"
                            } else if (updates.current == null) {
                                "This copy wasn't installed, so it can't be updated"
                            } else {
                                "This is the newest version"
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            "Couldn't reach GitHub"
                        }
                }
            },
            enabled = !updates.checking,
        ) {
            Text("Check for updates")
        }
        result?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
