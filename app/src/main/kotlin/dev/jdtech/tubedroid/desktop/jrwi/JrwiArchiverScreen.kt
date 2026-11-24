package dev.jdtech.tubedroid.desktop.jrwi

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.jrwi.JrwiBackground
import dev.jdtech.jellyfin.jrwi.JrwiCheckProgress
import dev.jdtech.jellyfin.jrwi.JrwiCheckRun
import dev.jdtech.jellyfin.jrwi.JrwiEvent
import dev.jdtech.jellyfin.jrwi.JrwiFeature
import dev.jdtech.jellyfin.jrwi.JrwiPodcast
import dev.jdtech.jellyfin.jrwi.JrwiProblem
import dev.jdtech.jellyfin.jrwi.JrwiStatus
import dev.jdtech.tubedroid.desktop.LocalApp
import dev.jdtech.tubedroid.desktop.LocalNavigator
import dev.jdtech.tubedroid.desktop.Route
import dev.jdtech.tubedroid.desktop.rememberPageModel
import dev.jdtech.tubedroid.desktop.screens.RefreshButton
import dev.jdtech.tubedroid.desktop.ui.AppIcon
import dev.jdtech.tubedroid.desktop.ui.GridScrollbar
import dev.jdtech.tubedroid.desktop.ui.RoundIconButton
import dev.jdtech.tubedroid.desktop.ui.Spacings
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The archiver at work: what it's doing, read every few seconds during a check or while it works in
 * the background and once a minute otherwise, and with it what happened lately, the podcast feed it
 * reads and the posts it couldn't fully save, which are read again once a check has finished.
 */
class JrwiArchiverModel(private val repository: JrwiRepository, private val scope: CoroutineScope) {
    private val api = repository.api

    var status by mutableStateOf<JrwiStatus?>(null)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    /** The archiver's optional parts, or null when it doesn't say. */
    var features by mutableStateOf<Set<String>?>(null)
        private set

    /** The last finished check, from the archive's own stats, for what it found and saved. */
    var lastRun by mutableStateOf<JrwiCheckRun?>(null)
        private set

    /** The last week's events, newest first, or null when they couldn't be read. */
    var events by mutableStateOf<List<JrwiEvent>?>(emptyList())
        private set

    var podcast by mutableStateOf<JrwiPodcast?>(null)
        private set

    var problems by mutableStateOf<List<JrwiProblem>>(emptyList())
        private set

    val gridState = LazyGridState()

    // The check the details were last read after
    private var detailsFor: String? = null
    private var detailsRead = false

    fun has(feature: String): Boolean = features?.contains(feature) == true

    /** Follows the archiver for as long as the page shows. */
    suspend fun follow() {
        while (true) {
            read()
            val current = status
            val busy = current?.running == true || current?.background != null
            delay(if (busy) BUSY_REFRESH_MS else IDLE_REFRESH_MS)
        }
    }

    /** Reads the status now, and with [details] everything else too. */
    suspend fun read(details: Boolean = false) {
        try {
            val read = api.getStatus()
            status = read
            error = null
            if (details || !detailsRead || read.lastCheckFinished != detailsFor) {
                detailsFor = read.lastCheckFinished
                detailsRead = true
                readDetails()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = jrwiErrorText(e)
        }
    }

    /** The events, the podcast feed, the problems and the last check, all asked at once. */
    private suspend fun readDetails() = coroutineScope {
        features = runCatching { api.features() }.getOrNull()
        val since = OffsetDateTime.now().minusDays(EVENT_DAYS).truncatedTo(ChronoUnit.SECONDS)
        val newEvents = async { part { api.getEvents(since.toString()) } }
        val newProblems = async { part { api.getProblems() } }
        val newPodcast = async {
            if (has(JrwiFeature.PODCAST)) part { api.getPodcast() } else null
        }
        val newStats = async {
            if (features?.contains(JrwiFeature.STATS) != false) part { api.getStats() } else null
        }
        events = newEvents.await()?.sortedByDescending { it.at }?.take(MAX_EVENTS)
        newProblems.await()?.let { problems = it }
        podcast = newPodcast.await()
        lastRun = newStats.await()?.checks?.firstOrNull { it.finished != null }
    }

    private suspend fun <T> part(load: suspend () -> T): T? =
        try {
            load()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    fun refresh() {
        scope.launch { read(details = true) }
    }

    private fun act(onMessage: (String) -> Unit, action: suspend () -> String) {
        scope.launch {
            try {
                onMessage(action())
                read()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onMessage(jrwiErrorText(e))
            }
        }
    }

    fun check(full: Boolean, onMessage: (String) -> Unit) =
        act(onMessage) {
            if (api.startCheck(full)) "The archiver is checking for new posts"
            else "A check is already running"
        }

    fun stop(onMessage: (String) -> Unit) =
        act(onMessage) {
            if (api.stopCheck()) "The check is stopping. The next check carries on from there."
            else "No check is running"
        }

    fun reread(onMessage: (String) -> Unit) =
        act(onMessage) {
            if (api.startReparse()) "Re-reading the saved pages" else "A check is already running"
        }

    /** Has the archiver read the podcast feed now, and reads the card again a moment later. */
    fun readPodcast(onMessage: (String) -> Unit) =
        act(onMessage) {
            api.readPodcast()
            scope.launch {
                delay(PODCAST_READ_MS)
                part { api.getPodcast() }?.let { podcast = it }
            }
            "Reading the podcast feed"
        }

    private companion object {
        const val BUSY_REFRESH_MS = 5_000L
        const val IDLE_REFRESH_MS = 60_000L
        const val PODCAST_READ_MS = 3_000L
        const val EVENT_DAYS = 7L
        const val MAX_EVENTS = 30
    }
}

/**
 * The archiver at work, as its admin page shows it, in its own purples: what it's doing, Check now,
 * Full check and Re-read saved pages (Stop while one runs), what happened lately, the podcast feed
 * it reads, and the posts it couldn't fully save. The bar has a button to the admin page itself.
 */
@Composable
fun JrwiArchiverScreen() {
    val app = LocalApp.current
    val navigator = LocalNavigator.current
    val model = rememberPageModel("jrwi-archiver") { scope -> JrwiArchiverModel(app.jrwi, scope) }
    // Only while it's shown, so the archiver is asked only then
    LaunchedEffect(model) { model.follow() }
    val open: (Long) -> Unit = { navigator.open(Route.JrwiPost(it)) }
    JrwiSheetTheme {
        Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            JrwiBar(
                title = "Archiver",
                onBack = null,
                color = Color.Transparent,
                showSearch = false,
            ) {
                RefreshButton(onClick = model::refresh)
                RoundIconButton(
                    icon = "ic_external_link",
                    label = "Open the archiver's admin page",
                    onClick = { openUrl(app.jrwi.api.fileUrl(JRWI_ADMIN_PATH)) },
                )
            }
            val status = model.status
            if (status == null) {
                JrwiPageState(loading = true, error = model.error, onRetry = model::refresh)
                return@Column
            }
            Box(modifier = Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    state = model.gridState,
                    columns = GridCells.Adaptive(minSize = CARD_MIN_WIDTH),
                    contentPadding = PaddingValues(Spacings.default),
                    horizontalArrangement = Arrangement.spacedBy(Spacings.medium),
                    verticalArrangement = Arrangement.spacedBy(Spacings.medium),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    model.error?.let { error ->
                        item(key = "error", span = { GridItemSpan(maxLineSpan) }) {
                            Text(text = error, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    item(key = "status", span = { GridItemSpan(maxLineSpan) }) {
                        JrwiStatusCard(status, model.lastRun)
                    }
                    item(key = "actions", span = { GridItemSpan(maxLineSpan) }) {
                        JrwiArchiverActions(model = model, status = status)
                    }
                    item(key = "events") { JrwiEventsCard(model.events, open) }
                    item(key = "podcast") {
                        JrwiPodcastCard(
                            supported = model.has(JrwiFeature.PODCAST),
                            podcast = model.podcast,
                            onItemClick = open,
                            onRead = { model.readPodcast(navigator::message) },
                        )
                    }
                    item(key = "problems") { JrwiProblemsCard(model.problems, open) }
                }
                GridScrollbar(model.gridState)
            }
        }
    }
}

internal const val JRWI_ADMIN_PATH = "/admin"
internal val CARD_MIN_WIDTH = 380.dp

/** A card with a title, on the archiver's pages. */
@Composable
internal fun JrwiCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(JrwiShape)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(Spacings.medium)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = JrwiColors.Text,
            modifier = Modifier.padding(bottom = Spacings.small),
        )
        content()
    }
}

/**
 * What the archiver is doing: checking, on which post and how far, or waiting for the next check,
 * and how the last one went. [lastRun] is the last finished check from the archive's stats, for
 * what it saved.
 */
@Composable
internal fun JrwiStatusCard(status: JrwiStatus, lastRun: JrwiCheckRun? = null) {
    JrwiCard(title = "Status") {
        // Archivers since 0.9.3 word the detail to stand on its own line
        val phase =
            if (status.step != null) status.phase.orEmpty()
            else listOfNotNull(status.phase, status.detail).joinToString(": ")
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacings.small),
        ) {
            // The site's dot: green while it checks
            if (status.running) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(JrwiColors.Ok))
            }
            Text(
                text = if (status.running) "Checking: $phase" else "Waiting for the next check",
                style = MaterialTheme.typography.titleSmall,
                color = JrwiColors.Text,
            )
        }
        if (status.running && status.step != null) {
            status.detail
                ?.takeIf { it.isNotBlank() }
                ?.let { detail ->
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = JrwiColors.BodyText,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
        }
        val progress = status.progress
        val total = progress?.total ?: 0
        if (status.running && progress != null && total > 0) {
            val done = progress.done ?: 0
            // With the file that's downloading now as part of the one being worked on
            val fraction = (done + (progress.current ?: 0.0)).toFloat() / total
            LinearProgressIndicator(
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                progress = { fraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
            // "Post 3 of 12", as the web app shows it
            Text(
                text = countText(progress),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Work in the background, such as timing transcripts: not a check, and alongside one
        status.background?.let { JrwiBackgroundWork(it) }
        if (status.loginOk == false) {
            Text(
                text =
                    "The archiver can't sign in to jrwishow.com. Check its login on the " +
                        "archiver's Settings page.",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = Spacings.small),
            )
        }
        JrwiCheckTimes(status, lastRun)
    }
}

/** "Post 3 of 12": which one of how many is being worked on, by what's counted. */
private fun countText(progress: JrwiCheckProgress): String {
    val total = progress.total ?: 0
    val number = ((progress.done ?: 0) + 1).coerceAtMost(total)
    return when (progress.unit) {
        "post" -> "Post $number of $total"
        "episode" -> "Episode $number of $total"
        "page" -> "Page $number of $total"
        else -> "$number of $total"
    }
}

/**
 * What the archiver does in the background, as the top of its admin page shows it: what it is,
 * which of how many it's on, what it's doing to that one, and a bar for how far that one has got,
 * or for how many are done when it doesn't say.
 */
@Composable
private fun JrwiBackgroundWork(background: JrwiBackground) {
    Column(modifier = Modifier.padding(top = Spacings.small)) {
        val progress = background.progress
        val total = progress?.total ?: 0
        val count = if (progress != null && total > 0) countText(progress) else null
        Text(
            text =
                listOfNotNull(background.phase?.takeIf { it.isNotBlank() }, count)
                    .joinToString(" · "),
            style = MaterialTheme.typography.titleSmall,
            color = JrwiColors.Text,
        )
        background.detail
            ?.takeIf { it.isNotBlank() }
            ?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = JrwiColors.BodyText,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        val current = progress?.current
        val fraction =
            when {
                current != null -> current.toFloat()
                total > 0 -> (progress?.done ?: 0).toFloat() / total
                else -> null
            }
        if (fraction != null) {
            LinearProgressIndicator(
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                progress = { fraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
        } else {
            // Under way, with no length to show yet
            LinearProgressIndicator(
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
        }
    }
}

/**
 * When the running check started, when the last one ran, how long it took, how it went and what it
 * saved, and when the next one is: as times of day with how long ago or how soon, kept up to date
 * while shown.
 */
@Composable
private fun JrwiCheckTimes(status: JrwiStatus, lastRun: JrwiCheckRun?) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(CLOCK_TICK_MS)
        }
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(top = Spacings.small),
    ) {
        val started = epochMillis(status.lastCheckStarted)
        if (status.running && started != null) {
            Text(
                text = "Running since ${clockTime(started, now)} (${relativeTime(started, now)})",
                style = MaterialTheme.typography.bodyMedium,
                color = JrwiColors.BodyText,
            )
        }
        // The last check that finished: while one runs, the status's start is the running one's
        val run = lastRun?.takeIf { it.finished != null }
        val lastStart = if (status.running) epochMillis(run?.started) else started
        val lastEnd =
            if (status.running) epochMillis(run?.finished)
            else epochMillis(status.lastCheckFinished)
        if (lastEnd != null) {
            val took = lastStart?.takeIf { it <= lastEnd }?.let { durationText(lastEnd - it) }
            val at = "${clockTime(lastEnd, now)} (${relativeTime(lastEnd, now)})"
            Text(
                text = if (took != null) "Last check: $at, took $took" else "Last check: $at",
                style = MaterialTheme.typography.bodyMedium,
                color = JrwiColors.BodyText,
            )
            // The stats' run, when it's this one
            val sameRun = run?.takeIf {
                epochMillis(it.finished)?.let { end -> abs(end - lastEnd) < SAME_RUN_MS } == true
            }
            val result = if (status.running) run?.result else status.lastResult ?: run?.result
            val message =
                (if (status.running) run?.message else status.message)?.takeIf { it.isNotBlank() }
            val parts =
                listOfNotNull(
                    result?.let(::checkResultText),
                    sameRun?.let { plural(it.newItems, "new post", "new posts") },
                    sameRun?.let { plural(it.archived, "post saved", "posts saved") },
                    message,
                )
            if (parts.isNotEmpty()) {
                Text(
                    text = parts.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
            }
        }
        val next = epochMillis(status.nextCheck)
        Text(
            text =
                if (next != null) "Next check: ${clockTime(next, now)} (${relativeTime(next, now)})"
                else "Checks only run when started by hand",
            style = MaterialTheme.typography.bodyMedium,
            color = JrwiColors.BodyText,
        )
    }
}

internal fun plural(count: Int, one: String, many: String): String =
    "$count ${if (count == 1) one else many}"

internal fun epochMillis(iso: String?): Long? =
    try {
        iso?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() }
    } catch (_: Exception) {
        null
    }

/** The time of day, the computer's way, with the day too when it's not today. */
private fun clockTime(millis: Long, now: Long): String {
    val zone = ZoneId.systemDefault()
    val time = Instant.ofEpochMilli(millis).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val clock = time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    return if (time.toLocalDate() == today) clock
    else time.format(DateTimeFormatter.ofPattern("EEE d MMM")) + ", " + clock
}

/** "in 3 hours", "24 minutes ago", or for under a minute either way, now. */
private fun relativeTime(millis: Long, now: Long): String {
    val minutes = Duration.ofMillis(abs(millis - now)).toMinutes()
    val ahead = millis >= now
    if (minutes < 1) return if (ahead) "any moment now" else "just now"
    val amount =
        when {
            minutes < 60 -> plural(minutes.toInt(), "minute", "minutes")
            minutes < 48 * 60 -> plural((minutes / 60).toInt(), "hour", "hours")
            else -> plural((minutes / (24 * 60)).toInt(), "day", "days")
        }
    return if (ahead) "in $amount" else "$amount ago"
}

/** How long a check took: "1 h 5 min", "4 min 12 s" or "12 s". */
private fun durationText(millis: Long): String {
    val seconds = millis / 1000
    val hours = seconds / 3600
    val minutes = seconds % 3600 / 60
    return when {
        hours > 0 -> "$hours h $minutes min"
        minutes > 0 -> "$minutes min ${seconds % 60} s"
        else -> "$seconds s"
    }
}

/** How a check ended, in words: ok, login_failed, blocked, error or stopped. */
internal fun checkResultText(result: String): String =
    when (result) {
        "ok" -> "Went fine"
        "login_failed" -> "Couldn't log in"
        "blocked" -> "Blocked by the site"
        "error" -> "Failed"
        "stopped" -> "Stopped"
        "running" -> "Running"
        else -> result.replace('_', ' ')
    }

// How often the times' "ago" and "in" move on while shown
private const val CLOCK_TICK_MS = 30_000L

// The stats' run and the status's last check are the same one when they ended this close together
private const val SAME_RUN_MS = 5_000L

/** A time from the archiver as the computer shows dates and times, in its own time zone. */
internal fun dateAndTime(iso: String?): String? =
    try {
        iso?.let {
            OffsetDateTime.parse(it)
                .atZoneSameInstant(ZoneId.systemDefault())
                .format(
                    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                )
        }
    } catch (_: Exception) {
        null
    }

/**
 * The admin page's controls as tiles, each with its icon, its name and what it does: Check now and
 * Full check, or Stop while a check runs, and Re-read saved pages.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JrwiArchiverActions(model: JrwiArchiverModel, status: JrwiStatus) {
    val navigator = LocalNavigator.current
    val say = navigator::message
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacings.medium),
        verticalArrangement = Arrangement.spacedBy(Spacings.medium),
    ) {
        if (status.running && model.has(JrwiFeature.STOP)) {
            JrwiActionTile(
                icon = "ic_jrwi_stop",
                name = "Stop",
                text = "Stop the check that's running. The next check carries on from there.",
                tint = JrwiColors.Bad,
                onClick = { model.stop(say) },
            )
        } else {
            JrwiActionTile(
                icon = "ic_jrwi_check",
                name = "Check now",
                text = "Look for new posts and save them",
                enabled = !status.running,
                onClick = { model.check(full = false, onMessage = say) },
            )
            JrwiActionTile(
                icon = "ic_jrwi_full_check",
                name = "Full check",
                text = "Read the whole feed again, back to the first post",
                enabled = !status.running,
                onClick = { model.check(full = true, onMessage = say) },
            )
        }
        val canReread = model.has(JrwiFeature.REPARSE)
        JrwiActionTile(
            icon = "ic_jrwi_reread",
            name = "Re-read saved pages",
            text =
                if (canReread) "Apply this version's fixes to what's saved, without downloading"
                else "The archiver can't start this from the app yet; it's on its admin page",
            enabled = canReread && !status.running,
            onClick = { model.reread(say) },
        )
    }
}

@Composable
private fun JrwiActionTile(
    icon: String,
    name: String,
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacings.medium),
        modifier =
            Modifier.width(340.dp)
                .clip(JrwiShape)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .then(
                    if (enabled) {
                        Modifier.clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
                    } else {
                        Modifier
                    }
                )
                .alpha(if (enabled) 1f else DISABLED_ALPHA)
                .padding(Spacings.medium),
    ) {
        AppIcon(
            name = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(28.dp),
        )
        Column {
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = JrwiColors.Text,
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val DISABLED_ALPHA = 0.45f

/** The last week's events, newest first: a new post by its title, which opens it. */
@Composable
private fun JrwiEventsCard(events: List<JrwiEvent>?, onItemClick: (Long) -> Unit) {
    JrwiCard(title = "Recent events") {
        when {
            events == null -> JrwiMutedLine("Couldn't be read from the archiver.")
            events.isEmpty() -> JrwiMutedLine("Nothing in the last week.")
        }
        events.orEmpty().forEach { event ->
            val postId = event.postId
            Column(
                modifier =
                    Modifier.fillMaxWidth()
                        .then(
                            if (postId != null) {
                                Modifier.clip(JrwiShape)
                                    .clickable { onItemClick(postId) }
                                    .pointerHoverIcon(PointerIcon.Hand)
                            } else {
                                Modifier
                            }
                        )
                        .padding(vertical = 6.dp)
            ) {
                Text(
                    text = dateAndTime(event.at) ?: event.at,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = eventText(event),
                    style = MaterialTheme.typography.bodyMedium,
                    color =
                        if (postId != null) MaterialTheme.colorScheme.primary else JrwiColors.Text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun JrwiMutedLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** An event in words: a new post by its title, the others by what happened. */
private fun eventText(event: JrwiEvent): String {
    val about = event.title?.takeIf { it.isNotBlank() } ?: event.message.orEmpty()
    return when (event.type) {
        "new_item" -> about
        "video_handed_over" -> "Handed over to TubeArchivist: $about"
        "check_failed" -> "Check failed: $about"
        "login_failed" -> "Login failed: $about"
        else ->
            listOf(event.type.replace('_', ' '), about)
                .filter { it.isNotBlank() }
                .joinToString(": ")
    }
}

/**
 * The site's private podcast feed as the archiver reads it: when it was read and what it had, the
 * last check a new episode in it started, its newest episodes, and Read it now.
 */
@Composable
private fun JrwiPodcastCard(
    supported: Boolean,
    podcast: JrwiPodcast?,
    onItemClick: (Long) -> Unit,
    onRead: () -> Unit,
) {
    JrwiCard(title = "Podcast feed") {
        when {
            !supported ->
                JrwiMutedLine(
                    "The archiver doesn't share its podcast feed with the app yet; it's on its " +
                        "admin page."
                )
            podcast == null -> JrwiMutedLine("Couldn't be read from the archiver.")
            !podcast.setUp ->
                JrwiMutedLine(
                    "Not set up. With the site's private podcast feed in the archiver's " +
                        "Settings, it reads it every few minutes, starts a check when a new " +
                        "episode is in it, and takes the time of day each episode came out."
                )
            else -> JrwiPodcastDetails(podcast, onItemClick)
        }
        if (supported && podcast?.setUp == true) {
            OutlinedButton(
                shape = JrwiShape,
                onClick = onRead,
                modifier = Modifier.padding(top = Spacings.small),
            ) {
                Text("Read it now")
            }
        }
    }
}

@Composable
private fun JrwiPodcastDetails(podcast: JrwiPodcast, onItemClick: (Long) -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val read = dateAndTime(podcast.lastRead)
        Text(
            text =
                when {
                    read == null -> "Not read yet."
                    !podcast.ok -> "Read $read: ${podcast.error.orEmpty()}"
                    else -> "Read $read: ${podcast.count} episodes."
                },
            style = MaterialTheme.typography.bodyMedium,
            color = if (podcast.ok) JrwiColors.Text else MaterialTheme.colorScheme.error,
        )
        Text(
            text =
                podcast.everyMinutes?.let { "Read every $it minutes between checks." }
                    ?: "Reading it is off in the archiver's Settings.",
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
        podcast.lastTrigger?.let { trigger ->
            Text(
                text =
                    "Last started a check ${dateAndTime(trigger.at) ?: trigger.at}, for " +
                        "${trigger.titles.joinToString(", ")}.",
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
        }
        if (podcast.count > 0) {
            Text(
                text =
                    "${podcast.matched} of ${podcast.count} episodes are posts in the archive; " +
                        "${podcast.withTime} say the time of day they came out.",
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
        }
        if (podcast.latest.isNotEmpty()) {
            Text(
                text = "Newest in it",
                style = MaterialTheme.typography.labelLarge,
                color = JrwiColors.Text,
                modifier = Modifier.padding(top = Spacings.small),
            )
            podcast.latest.forEach { episode ->
                val postId = episode.postId
                Column(
                    modifier =
                        Modifier.fillMaxWidth()
                            .then(
                                if (postId != null) {
                                    Modifier.clip(JrwiShape)
                                        .clickable { onItemClick(postId) }
                                        .pointerHoverIcon(PointerIcon.Hand)
                                } else {
                                    Modifier
                                }
                            )
                            .padding(vertical = 4.dp)
                ) {
                    Text(
                        text = episode.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color =
                            if (postId != null) MaterialTheme.colorScheme.primary
                            else JrwiColors.Text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    dateAndTime(episode.published)?.let {
                        Text(text = it, style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                }
            }
        }
    }
}

/** The posts the archiver couldn't fully save, and why; a post's title opens it. */
@Composable
internal fun JrwiProblemsCard(problems: List<JrwiProblem>, onItemClick: (Long) -> Unit) {
    JrwiCard(title = "Posts that couldn't be fully saved") {
        if (problems.isEmpty()) JrwiMutedLine("Every post was saved")
        problems.forEachIndexed { index, problem ->
            if (index > 0) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(vertical = Spacings.small),
                )
            }
            Text(
                text = problem.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier =
                    Modifier.clip(JrwiShape)
                        .clickable { onItemClick(problem.postId) }
                        .pointerHoverIcon(PointerIcon.Hand),
            )
            problem.problems.forEach {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
