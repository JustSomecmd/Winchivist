package dev.jdtech.tubedroid.desktop.jrwi

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jdtech.tubedroid.desktop.AppSettings
import dev.jdtech.tubedroid.desktop.LocalApp
import dev.jdtech.tubedroid.desktop.LocalNavigator
import dev.jdtech.tubedroid.desktop.Route
import dev.jdtech.tubedroid.desktop.compute.ComputeBox
import dev.jdtech.tubedroid.desktop.compute.HearJob
import dev.jdtech.tubedroid.desktop.compute.Heard
import dev.jdtech.tubedroid.desktop.screens.RefreshButton
import dev.jdtech.tubedroid.desktop.screens.openFolder
import dev.jdtech.tubedroid.desktop.ui.GridScrollbar
import dev.jdtech.tubedroid.desktop.ui.RoundIconButton
import dev.jdtech.tubedroid.desktop.ui.Spacings
import dev.jdtech.tubedroid.desktop.ui.formatDuration
import java.util.Locale
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * The compute box: this PC hearing episodes for the archiver's timed transcripts. What it's doing,
 * starting and pausing it, which model hears and when, whether the PC is ready, and what it heard.
 */
@Composable
fun JrwiComputeScreen() {
    val app = LocalApp.current
    val navigator = LocalNavigator.current
    val box = app.compute
    // The archiver is asked once; the refresh button asks again
    LaunchedEffect(box) { if (box.jobs == null) box.refresh() }
    val open: (Long) -> Unit = { navigator.open(Route.JrwiPost(it)) }
    JrwiSheetTheme {
        Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            JrwiBar(
                title = "Compute box",
                onBack = null,
                color = Color.Transparent,
                showSearch = false,
            ) {
                RefreshButton(onClick = box::refresh)
                RoundIconButton(
                    icon = "ic_external_link",
                    label = "Open the archiver's record of every timing",
                    onClick = { openUrl(app.jrwi.api.fileUrl(TRANSCRIPTS_PATH)) },
                )
            }
            val gridState = rememberLazyGridState()
            Box(modifier = Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = CARD_MIN_WIDTH),
                    contentPadding = PaddingValues(Spacings.default),
                    horizontalArrangement = Arrangement.spacedBy(Spacings.medium),
                    verticalArrangement = Arrangement.spacedBy(Spacings.medium),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(key = "status", span = { GridItemSpan(maxLineSpan) }) {
                        ComputeStatusCard(box, app.settings)
                    }
                    item(key = "actions", span = { GridItemSpan(maxLineSpan) }) {
                        ComputeActions(box, app.settings)
                    }
                    item(key = "settings") { ComputeSettingsCard(app.settings) }
                    item(key = "pc") { ComputePcCard(box, app.settings) }
                    item(key = "heard") { ComputeHeardCard(box.heard, open) }
                    item(key = "waiting") { ComputeWaitingCard(box, open) }
                }
                GridScrollbar(gridState)
            }
        }
    }
}

private const val TRANSCRIPTS_PATH = "/admin/transcripts"

/** What the box is doing: the episode being heard and how far, or what waits and when it starts. */
@Composable
private fun ComputeStatusCard(box: ComputeBox, settings: AppSettings) {
    JrwiCard(title = "This PC hears episodes for the archiver") {
        JrwiMutedLine(
            "Hearing an episode is the slow part of timing its transcript. This PC does it and " +
                "sends the words back; the archiver matches them to the transcript and keeps " +
                "the timing that found the most of its words."
        )
        Column(modifier = Modifier.padding(top = Spacings.small)) {
            val current = box.current
            when {
                box.supported == false ->
                    Text(
                        text =
                            "This archiver doesn't hand out episodes to hear. That needs " +
                                "archiver 0.14.1 or newer.",
                        color = JrwiColors.BodyText,
                    )
                box.state == ComputeBox.State.HEARING && current != null ->
                    ComputeHearing(box, current)
                box.state == ComputeBox.State.LISTING -> {
                    Text(
                        text = "Asking the archiver which episodes can be heard",
                        style = MaterialTheme.typography.titleSmall,
                        color = JrwiColors.Text,
                    )
                    LinearProgressIndicator(
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    )
                }
                box.jobs != null -> ComputeWaiting(box, settings)
            }
            box.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = Spacings.small),
                )
            }
        }
    }
}

@Composable
private fun ComputeHearing(box: ComputeBox, job: HearJob) {
    Text(
        text = "Episode ${box.heardNow + 1} of ${box.runTotal} · ${job.title}",
        style = MaterialTheme.typography.titleSmall,
        color = JrwiColors.Text,
    )
    Text(
        text =
            listOfNotNull(
                    job.seconds?.let(::formatDuration),
                    "${job.lines} lines",
                    job.timedWith?.let { "timed with $it now" } ?: "not timed yet",
                )
                .joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    box.step?.let { step ->
        Text(
            text = step,
            style = MaterialTheme.typography.bodyMedium,
            color = JrwiColors.BodyText,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    val fraction = box.fraction
    if (fraction != null) {
        LinearProgressIndicator(
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            progress = { fraction.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
    } else {
        LinearProgressIndicator(
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
    }
    if (box.pausing) JrwiMutedLine("Pausing once this episode has been sent.")
}

@Composable
private fun ComputeWaiting(box: ComputeBox, settings: AppSettings) {
    val all = box.jobs.orEmpty()
    val waiting = box.toHear()
    val model = settings.hearModel
    Text(
        text =
            when {
                all.isEmpty() -> "The archiver has no episode to hear yet"
                waiting.isEmpty() -> "Every episode has been heard with $model"
                else ->
                    plural(waiting.size, "1 episode", "${waiting.size} episodes") +
                        " to hear with $model, " +
                        hours(waiting.sumOf { it.seconds ?: 0.0 }) +
                        " of sound"
            },
        style = MaterialTheme.typography.titleSmall,
        color = JrwiColors.Text,
    )
    if (all.isNotEmpty()) {
        val untimed = all.count { !it.timed }
        JrwiMutedLine(
            "${box.episodes} episodes have a transcript. ${all.size} of them can be heard, and " +
                (if (untimed == 0) "all are timed." else "$untimed aren't timed yet.")
        )
    }
    JrwiMutedLine(
        when (settings.hearWhen) {
            AppSettings.HEAR_IDLE ->
                "It starts by itself once this PC has been left alone for " +
                    "${settings.hearIdleMinutes} minutes on mains power, while Winchivist runs."
            AppSettings.HEAR_HOURS ->
                "It starts by itself between ${hour(settings.hearFromHour)} and " +
                    "${hour(settings.hearToHour)}, while Winchivist runs."
            else -> "It starts when Start is pressed."
        }
    )
}

private fun hours(seconds: Double): String {
    val hours = seconds / 3600
    return if (hours < 10) String.format(Locale.ROOT, "%.1f hours", hours)
    else "${hours.toInt()} hours"
}

private fun hour(hour: Int): String = String.format(Locale.ROOT, "%02d:00", hour)

/** Start, pause and stop, and the check of this PC, as tiles like the archiver page's. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComputeActions(box: ComputeBox, settings: AppSettings) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacings.medium),
        verticalArrangement = Arrangement.spacedBy(Spacings.medium),
    ) {
        if (box.state == ComputeBox.State.HEARING) {
            if (box.pausing) {
                JrwiActionTile(
                    icon = "ic_play",
                    name = "Carry on",
                    text = "Go on to the next episode after this one after all",
                    onClick = { box.pauseAfterThis(false) },
                )
            } else {
                JrwiActionTile(
                    icon = "ic_pause",
                    name = "Pause after this episode",
                    text = "Finish hearing this one and send it, then wait",
                    onClick = { box.pauseAfterThis() },
                )
            }
            JrwiActionTile(
                icon = "ic_jrwi_stop",
                name = "Stop now",
                text = "Frees the PC at once. This episode is heard from its start next time.",
                tint = JrwiColors.Bad,
                onClick = box::stopNow,
            )
        } else {
            val waiting = box.toHear().size
            JrwiActionTile(
                icon = "ic_play",
                name = "Start",
                text =
                    if (waiting > 0) {
                        "Hear " +
                            plural(waiting, "the episode", "the $waiting episodes") +
                            " waiting with ${settings.hearModel}, newest first"
                    } else {
                        "Nothing is waiting to be heard with ${settings.hearModel}"
                    },
                enabled = waiting > 0 && box.state == ComputeBox.State.WAITING && !box.checking,
                onClick = { box.start() },
            )
            JrwiActionTile(
                icon = "ic_monitor",
                name = "Check this PC",
                text = "Whether Python, faster-whisper and the graphics card are ready to hear",
                enabled = !box.checking && box.state == ComputeBox.State.WAITING,
                onClick = box::check,
            )
        }
    }
}

/** Which model hears, on what, which episodes, and when it starts by itself. */
@Composable
private fun ComputeSettingsCard(settings: AppSettings) {
    JrwiCard(title = "How it hears") {
        Choice(
            title = "Speech model",
            text =
                "Each is downloaded once, the first time it hears. Larger ones need more memory.",
            options = ComputeBox.MODELS.map { it to it },
            selected = settings.hearModel,
            onSelect = { settings.hearModel = it },
        )
        Choice(
            title = "Hears on",
            text =
                "Automatic takes an NVIDIA graphics card when it can be used, which is many " +
                    "times faster.",
            options =
                listOf("auto" to "Automatic", "cuda" to "Graphics card", "cpu" to "Processor"),
            selected = settings.hearDevice,
            onSelect = { settings.hearDevice = it },
        )
        Choice(
            title = "Episodes",
            text =
                "Every episode the model hasn't heard, or only those without a timed " +
                    "transcript. A new timing replaces the one in use only when it found at " +
                    "least as many words.",
            options = listOf("every" to "Every episode", "untimed" to "Not timed yet"),
            selected = if (settings.hearEvery) "every" else "untimed",
            onSelect = { settings.hearEvery = it == "every" },
        )
        Choice(
            title = "Starts",
            text = "By itself it needs Winchivist running, in its window or in the tray.",
            options =
                listOf(
                    AppSettings.HEAR_BY_HAND to "When I press Start",
                    AppSettings.HEAR_IDLE to "When this PC is idle",
                    AppSettings.HEAR_HOURS to "Between set hours",
                ),
            selected = settings.hearWhen,
            onSelect = { settings.hearWhen = it },
        )
        when (settings.hearWhen) {
            AppSettings.HEAR_IDLE ->
                Choice(
                    title = "Idle for",
                    text = "With no key or mouse touched for this long, and on mains power.",
                    options = IDLE_MINUTES.map { it.toString() to "$it min" },
                    selected = settings.hearIdleMinutes.toString(),
                    onSelect = { settings.hearIdleMinutes = it.toInt() },
                )
            AppSettings.HEAR_HOURS ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = Spacings.small),
                ) {
                    Text("From", color = JrwiColors.BodyText)
                    HourMenu(settings.hearFromHour) { settings.hearFromHour = it }
                    Text("to", color = JrwiColors.BodyText)
                    HourMenu(settings.hearToHour) { settings.hearToHour = it }
                }
        }
        JrwiMutedLine("A change counts from the next episode.")
    }
}

private val IDLE_MINUTES = listOf(5, 10, 20, 30, 60)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Choice(
    title: String,
    text: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = Spacings.medium)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = JrwiColors.Text,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacings.small),
            verticalArrangement = Arrangement.spacedBy(Spacings.small),
            modifier = Modifier.padding(top = 6.dp),
        ) {
            options.forEach { (value, label) ->
                OptionPill(label = label, selected = selected == value) { onSelect(value) }
            }
        }
    }
}

/** One of a setting's options, square-cornered as the archive's pills, filled when it's picked. */
@Composable
private fun OptionPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) MaterialTheme.colorScheme.onPrimary else JrwiColors.PillText,
        modifier =
            Modifier.clip(JrwiPillShape)
                .then(
                    if (selected) Modifier.background(MaterialTheme.colorScheme.primary)
                    else Modifier.border(1.dp, JrwiColors.Line, JrwiPillShape)
                )
                .clickable(onClick = onClick)
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

@Composable
private fun HourMenu(hour: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text(hour(hour)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            (0..23).forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = hour(option),
                            fontWeight = if (option == hour) FontWeight.SemiBold else null,
                        )
                    },
                    onClick = {
                        open = false
                        onPick(option)
                    },
                )
            }
        }
    }
}

/** Whether this PC is ready: what the program's check said, and the Python and program it uses. */
@Composable
private fun ComputePcCard(box: ComputeBox, settings: AppSettings) {
    JrwiCard(title = "This PC") {
        val said = box.checkText
        when {
            box.checking && said == null -> {
                JrwiMutedLine("Checking, which takes up to a minute")
                LinearProgressIndicator(
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
            }
            said != null ->
                // In the program's own words, a finding a line
                Text(
                    text = said,
                    style = MaterialTheme.typography.bodySmall,
                    color = JrwiColors.BodyText,
                    modifier =
                        Modifier.fillMaxWidth()
                            .clip(JrwiShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .padding(Spacings.small),
                )
            else ->
                JrwiMutedLine(
                    "Hearing needs Python 3.13 with faster-whisper on this PC. Check this PC " +
                        "says whether it's ready, and what to install when it isn't."
                )
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = Spacings.small))
        PcLine(
            name = "Python",
            value = settings.hearPython ?: box.pythonShown ?: "Found by itself when it's needed",
            pick = "Pick one",
            onPick = {
                chooseFile("Pick python.exe", null)?.let {
                    settings.hearPython = it
                    box.pythonChanged()
                }
            },
            onReset =
                if (settings.hearPython != null) {
                    {
                        settings.hearPython = null
                        box.pythonChanged()
                    }
                } else {
                    null
                },
        )
        PcLine(
            name = "Program",
            value =
                settings.hearProgram ?: "Winchivist's copy of the archiver's ${ComputeBox.PROGRAM}",
            pick = "Use another copy",
            onPick = {
                chooseFile("Pick ${ComputeBox.PROGRAM}", "py")?.let { settings.hearProgram = it }
            },
            onReset =
                if (settings.hearProgram != null) {
                    { settings.hearProgram = null }
                } else {
                    null
                },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Its speech models, and the sound it's hearing, are kept in its folder.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { openFolder(box.folder()) }) { Text("Open folder") }
        }
    }
}

@Composable
private fun PcLine(
    name: String,
    value: String,
    pick: String,
    onPick: () -> Unit,
    onReset: (() -> Unit)?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall,
                color = JrwiColors.Text,
            )
            // A long path keeps both its ends
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        }
        if (onReset != null) TextButton(onClick = onReset) { Text("Find by itself") }
        TextButton(onClick = onPick) { Text(pick) }
    }
}

private fun chooseFile(title: String, extension: String?): String? {
    val chooser =
        JFileChooser().apply {
            fileSelectionMode = JFileChooser.FILES_ONLY
            dialogTitle = title
            extension?.let { fileFilter = FileNameExtensionFilter("*.$it", it) }
        }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile?.path
    } else {
        null
    }
}

/** What was heard since Winchivist started, newest first, each opening its post. */
@Composable
private fun ComputeHeardCard(heard: List<Heard>, onItemClick: (Long) -> Unit) {
    JrwiCard(title = "Heard here") {
        if (heard.isEmpty()) {
            JrwiMutedLine(
                "Nothing since Winchivist started. The archiver's record lists every timing, " +
                    "this PC's marked PC."
            )
        }
        heard.take(HEARD_SHOWN).forEach { result ->
            Column(
                modifier =
                    Modifier.fillMaxWidth()
                        .clip(JrwiShape)
                        .clickable { onItemClick(result.itemId) }
                        .padding(vertical = 6.dp)
            ) {
                Text(
                    text = result.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = JrwiColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = result.text,
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (result.ok) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error,
                    maxLines = if (result.ok) 3 else 8,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private const val HEARD_SHOWN = 12

/** The episodes waiting to be heard, newest first, and those the archiver can't hand out. */
@Composable
private fun ComputeWaitingCard(box: ComputeBox, onItemClick: (Long) -> Unit) {
    JrwiCard(title = "Waiting to be heard") {
        val waiting = box.toHear().filter { it.itemId != box.current?.itemId }
        if (box.jobs != null && waiting.isEmpty()) JrwiMutedLine("Nothing is waiting.")
        waiting.take(WAITING_SHOWN).forEach { job ->
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .clip(JrwiShape)
                        .clickable { onItemClick(job.itemId) }
                        .padding(vertical = 5.dp)
            ) {
                Text(
                    text = job.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = JrwiColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = Spacings.small),
                )
                Text(
                    text =
                        listOfNotNull(
                                job.seconds?.let(::formatDuration),
                                job.timedWith ?: "not timed",
                            )
                            .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (waiting.size > WAITING_SHOWN) {
            JrwiMutedLine("and ${waiting.size - WAITING_SHOWN} more")
        }
        if (box.leftOut.isNotEmpty()) {
            HorizontalDivider(modifier = Modifier.padding(vertical = Spacings.small))
            Text(
                text =
                    plural(
                        box.leftOut.size,
                        "1 episode can't be heard",
                        "${box.leftOut.size} episodes can't be heard",
                    ),
                style = MaterialTheme.typography.titleSmall,
                color = JrwiColors.Text,
            )
            box.leftOut.take(LEFT_OUT_SHOWN).forEach { left ->
                Text(
                    text = "${left.title}: ${left.why}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (box.leftOut.size > LEFT_OUT_SHOWN) {
                JrwiMutedLine("and ${box.leftOut.size - LEFT_OUT_SHOWN} more")
            }
        }
    }
}

private const val WAITING_SHOWN = 12
private const val LEFT_OUT_SHOWN = 6
