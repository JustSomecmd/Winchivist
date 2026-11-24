package dev.jdtech.tubedroid.desktop.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jdtech.tubedroid.desktop.ui.AppIcon
import dev.jdtech.tubedroid.desktop.ui.Spacings
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** How one of TubeArchivist's settings is edited. */
enum class TaEditor {
    /** On or off. */
    BOOL,

    /** On, off, or left to the global setting: a channel's own switch. */
    TRISTATE,

    /** A number, picked from a few usual ones or typed. */
    NUMBER,

    /** One of a few named values. */
    CHOICE,

    /** yt-dlp's format, with a builder for the usual ones. */
    FORMAT,

    /** yt-dlp's format sorting, with the usual ones to pick. */
    FORMAT_SORT,

    /** A short text, such as language codes. */
    TEXT,
}

/**
 * One of TubeArchivist's settings, as found in its config under [section] and [key]. [none] names
 * the empty value where it may be empty. The same list and wording as the phone app's.
 */
data class TaSetting(
    val section: String,
    val key: String,
    val label: String,
    val editor: TaEditor,
    val none: String? = null,
    val presets: List<Long> = emptyList(),
    val unit: String? = null,
    /** For a choice: each value, null for none, and its name. */
    val options: List<Pair<String?, String>> = emptyList(),
    val hint: String? = null,
) {
    fun read(config: JsonObject?): JsonElement? = (config?.get(section) as? JsonObject)?.get(key)
}

private val SIZE_PRESETS = listOf(0L, 5L, 10L, 25L, 50L, 100L, 250L, 500L)

val TaSubscriptionSettings =
    listOf(
        TaSetting(
            "subscriptions",
            "channel_size",
            "Videos to check per channel",
            TaEditor.NUMBER,
            presets = SIZE_PRESETS,
        ),
        TaSetting(
            "subscriptions",
            "live_channel_size",
            "Streams to check per channel",
            TaEditor.NUMBER,
            presets = SIZE_PRESETS,
        ),
        TaSetting(
            "subscriptions",
            "shorts_channel_size",
            "Shorts to check per channel",
            TaEditor.NUMBER,
            presets = SIZE_PRESETS,
        ),
        TaSetting(
            "subscriptions",
            "playlist_size",
            "Videos to check per playlist",
            TaEditor.NUMBER,
            presets = SIZE_PRESETS,
        ),
        TaSetting(
            "subscriptions",
            "auto_start",
            "Download new videos from a rescan right away",
            TaEditor.BOOL,
        ),
        TaSetting(
            "subscriptions",
            "extract_flat",
            "Fast rescan, with less detail about queued videos",
            TaEditor.BOOL,
        ),
    )

private val FormatSetting =
    TaSetting(
        "downloads",
        "format",
        "Video format",
        TaEditor.FORMAT,
        none = "yt-dlp's default",
        hint = "yt-dlp format, for example bestvideo[height<=1080]+bestaudio/best",
    )

private val AutodeleteSetting =
    TaSetting(
        "downloads",
        "autodelete_days",
        "Delete watched videos after",
        TaEditor.NUMBER,
        none = "Never",
        presets = listOf(1L, 3L, 7L, 14L, 30L, 90L),
        unit = "days",
    )

val TaDownloadSettings =
    listOf(
        TaSetting(
            "downloads",
            "limit_speed",
            "Speed limit",
            TaEditor.NUMBER,
            none = "No limit",
            presets = listOf(1000L, 2500L, 5000L, 10000L, 25000L),
            unit = "KB/s",
        ),
        TaSetting(
            "downloads",
            "sleep_interval",
            "Pause between downloads",
            TaEditor.NUMBER,
            none = "No pause",
            presets = listOf(3L, 5L, 10L, 30L, 60L),
            unit = "s",
        ),
        AutodeleteSetting,
        FormatSetting,
        TaSetting(
            "downloads",
            "format_sort",
            "Format sorting",
            TaEditor.FORMAT_SORT,
            none = "yt-dlp's default",
            hint = "yt-dlp format sort, for example res,codec:avc1",
        ),
        TaSetting("downloads", "add_metadata", "Embed metadata in the files", TaEditor.BOOL),
        TaSetting(
            "downloads",
            "subtitle",
            "Subtitle languages",
            TaEditor.TEXT,
            none = "No subtitles",
            hint = "Languages separated by commas, for example en,de",
        ),
        TaSetting(
            "downloads",
            "subtitle_source",
            "Subtitle source",
            TaEditor.CHOICE,
            options =
                listOf(
                    null to "None",
                    "user" to "Only subtitles from the uploader",
                    "auto" to "Also automatic subtitles",
                ),
        ),
        TaSetting("downloads", "subtitle_index", "Make subtitles searchable", TaEditor.BOOL),
        TaSetting(
            "downloads",
            "comment_max",
            "Comments to save",
            TaEditor.TEXT,
            none = "Don't save comments",
            hint = "For example all,100,10,5. Empty saves none.",
        ),
        TaSetting(
            "downloads",
            "comment_sort",
            "Comment order",
            TaEditor.CHOICE,
            options = listOf("top" to "Top comments", "new" to "Newest comments"),
        ),
        TaSetting(
            "downloads",
            "integrate_sponsorblock",
            "Get SponsorBlock segments",
            TaEditor.BOOL,
        ),
        TaSetting(
            "downloads",
            "integrate_ryd",
            "Get dislike counts (Return YouTube Dislike)",
            TaEditor.BOOL,
        ),
        TaSetting(
            "downloads",
            "extractor_lang",
            "Metadata language",
            TaEditor.TEXT,
            none = "Original language",
            hint = "Language code, for example en",
        ),
    )

val TaApplicationSettings =
    listOf(TaSetting("application", "enable_snapshot", "Daily database snapshots", TaEditor.BOOL))

/** A channel's own setting under [key], which replaces the global one [setting] reads. */
data class TaChannelSetting(val key: String, val setting: TaSetting)

/** A channel's own settings, as on its About page in TubeArchivist's web interface. */
val TaChannelSettings =
    listOf(
        TaChannelSetting("download_format", FormatSetting),
        TaChannelSetting("autodelete_days", AutodeleteSetting),
        TaChannelSetting(
            "integrate_sponsorblock",
            TaSetting(
                "downloads",
                "integrate_sponsorblock",
                "Get SponsorBlock segments",
                TaEditor.TRISTATE,
            ),
        ),
        TaChannelSetting("subscriptions_channel_size", TaSubscriptionSettings[0]),
        TaChannelSetting("subscriptions_shorts_channel_size", TaSubscriptionSettings[2]),
        TaChannelSetting("subscriptions_live_channel_size", TaSubscriptionSettings[1]),
    )

/** How a setting's value reads in a list. */
fun taValueText(setting: TaSetting, value: JsonElement?): String {
    val primitive = (value as? JsonPrimitive)?.takeIf { it !is JsonNull }
    val text = primitive?.contentOrNull?.takeIf { it.isNotBlank() }
    return when (setting.editor) {
        TaEditor.BOOL,
        TaEditor.TRISTATE ->
            when (primitive?.booleanOrNull) {
                true -> "On"
                false -> "Off"
                null -> setting.none ?: "Not set"
            }
        TaEditor.CHOICE ->
            setting.options.firstOrNull { it.first == text }?.second ?: text ?: "Not set"
        TaEditor.NUMBER -> {
            val number = primitive?.longOrNull
            when {
                number == null -> setting.none ?: "Not set"
                // Nothing to wait, limit or delete after reads as its own word
                number == 0L && setting.none != null -> setting.none
                setting.unit != null -> "$number ${setting.unit}"
                else -> number.toString()
            }
        }
        else -> text ?: setting.none ?: "Not set"
    }
}

/**
 * One setting in a list: its name, then how it's set, which is where it's changed. [inherited] says
 * what applies while it isn't set here, for a channel's settings, which can each be handed back to
 * the global one.
 */
@Composable
fun TaSettingRow(
    setting: TaSetting,
    value: JsonElement?,
    onChange: (JsonElement) -> Unit,
    inherited: String? = null,
) {
    val isSet = value != null && value !is JsonNull
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = Spacings.medium)) {
            Text(setting.label, style = MaterialTheme.typography.bodyLarge)
            if (inherited != null) {
                Text(
                    text = if (isSet) "This channel's own" else "Global setting: $inherited",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        when (setting.editor) {
            TaEditor.BOOL ->
                Switch(
                    checked = (value as? JsonPrimitive)?.booleanOrNull == true,
                    onCheckedChange = { onChange(JsonPrimitive(it)) },
                )
            TaEditor.TRISTATE ->
                PickMenu(
                    shown = if (isSet) taValueText(setting, value) else "Use the global setting",
                    options =
                        listOf<Pair<JsonElement, String>>(
                            JsonNull to "Use the global setting",
                            JsonPrimitive(true) to "On",
                            JsonPrimitive(false) to "Off",
                        ),
                    selected = value ?: JsonNull,
                    onPick = onChange,
                )
            TaEditor.CHOICE ->
                PickMenu(
                    shown = taValueText(setting, value),
                    options =
                        setting.options.map { (option, name) ->
                            (option?.let { JsonPrimitive(it) } ?: JsonNull) to name
                        },
                    selected = value ?: JsonNull,
                    onPick = onChange,
                )
            TaEditor.NUMBER -> NumberMenu(setting, value, inherited != null, onChange)
            TaEditor.FORMAT,
            TaEditor.FORMAT_SORT,
            TaEditor.TEXT -> TextSetting(setting, value, inherited != null, onChange)
        }
    }
}

/** What a setting is set to, as a button that opens the ways to change it. */
@Composable
private fun ValueButton(text: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.widthIn(max = 320.dp)
                .clip(MaterialTheme.shapes.small)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            // A long value wraps and leaves the arrow its room
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(6.dp))
        AppIcon(
            name = "ic_chevron_down",
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun PickMenu(
    shown: String,
    options: List<Pair<JsonElement, String>>,
    selected: JsonElement,
    onPick: (JsonElement) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        ValueButton(text = shown, onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, name) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = name,
                            fontWeight = if (value == selected) FontWeight.SemiBold else null,
                        )
                    },
                    onClick = {
                        open = false
                        onPick(value)
                    },
                )
            }
        }
    }
}

/** A number: the usual ones to pick, the empty value when there is one, or one typed in. */
@Composable
private fun NumberMenu(
    setting: TaSetting,
    value: JsonElement?,
    canInherit: Boolean,
    onChange: (JsonElement) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    val unit = setting.unit?.let { " $it" }.orEmpty()
    fun pick(picked: JsonElement) {
        open = false
        onChange(picked)
    }
    Box {
        ValueButton(
            text =
                if (canInherit && (value == null || value is JsonNull)) "Use the global setting"
                else taValueText(setting, value),
            onClick = {
                typed = ""
                open = true
            },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (canInherit) {
                DropdownMenuItem(
                    text = { Text("Use the global setting") },
                    onClick = { pick(JsonNull) },
                )
            } else if (setting.none != null) {
                DropdownMenuItem(text = { Text(setting.none) }, onClick = { pick(JsonNull) })
            }
            setting.presets.forEach { preset ->
                DropdownMenuItem(
                    text = { Text("$preset$unit") },
                    onClick = { pick(JsonPrimitive(preset)) },
                )
            }
            HorizontalDivider()
            val number = typed.toLongOrNull()?.takeIf { it >= 0 }
            OutlinedTextField(
                value = typed,
                onValueChange = { new -> typed = new.filter { it.isDigit() }.take(9) },
                label = { Text("Another number") },
                singleLine = true,
                keyboardOptions =
                    KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions =
                    KeyboardActions(onDone = { number?.let { pick(JsonPrimitive(it)) } }),
                trailingIcon = {
                    TextButton(
                        onClick = { number?.let { pick(JsonPrimitive(it)) } },
                        enabled = number != null,
                    ) {
                        Text("Set")
                    }
                },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).width(220.dp),
            )
        }
    }
}

/** A text setting, changed in a dialog with its hint, and for a format the usual ones to pick. */
@Composable
private fun TextSetting(
    setting: TaSetting,
    value: JsonElement?,
    canInherit: Boolean,
    onChange: (JsonElement) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val isSet = value != null && value !is JsonNull
    ValueButton(
        text = if (canInherit && !isSet) "Use the global setting" else taValueText(setting, value),
        onClick = { open = true },
    )
    if (open) {
        TaTextDialog(
            title = setting.label,
            start = (value as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull.orEmpty(),
            hint = setting.hint,
            emptyLabel = if (canInherit) "Use the global setting" else setting.none,
            picks =
                when (setting.editor) {
                    TaEditor.FORMAT -> FORMAT_PICKS
                    TaEditor.FORMAT_SORT -> FORMAT_SORT_PICKS
                    else -> emptyList()
                },
            onSave = { text ->
                open = false
                onChange(if (text.isBlank()) JsonNull else JsonPrimitive(text.trim()))
            },
            onDismiss = { open = false },
        )
    }
}

/**
 * A dialog to change a text: the field, its hint, the usual values as chips that fill it in, and a
 * button for the empty value.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaTextDialog(
    title: String,
    start: String,
    hint: String?,
    emptyLabel: String?,
    picks: List<Pair<String, String>>,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    extra: @Composable ((set: (String) -> Unit) -> Unit)? = null,
) {
    var text by remember { mutableStateOf(start) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacings.small)) {
                if (picks.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacings.small)) {
                        picks.forEach { (name, pick) ->
                            FilterChip(
                                selected = text.trim() == pick,
                                onClick = { text = pick },
                                label = { Text(name) },
                            )
                        }
                    }
                }
                extra?.invoke { text = it }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    supportingText = hint?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSave(text) }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = {
            Row {
                if (emptyLabel != null) {
                    TextButton(onClick = { onSave("") }) { Text(emptyLabel) }
                    Spacer(Modifier.width(Spacings.small))
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** yt-dlp formats for the usual wishes: a largest size, in whatever codec, or in H.264. */
private val FORMAT_PICKS: List<Pair<String, String>> =
    listOf(2160, 1440, 1080, 720, 480).map { height ->
        "Up to ${height}p" to "bestvideo[height<=$height]+bestaudio/best[height<=$height]"
    } +
        listOf(1080, 720).map { height ->
            "Up to ${height}p, H.264" to
                "bestvideo[height<=$height][vcodec^=avc1]+bestaudio[acodec^=mp4a]/" +
                    "best[height<=$height]"
        }

private val FORMAT_SORT_PICKS =
    listOf(
        "Largest picture, H.264 first" to "res,codec:avc1",
        "Largest picture, AV1 first" to "res,codec:av01",
        "Smallest files" to "+size,+br",
    )

/** A gap between settings, where a card holds more than one kind. */
@Composable
fun TaSettingsGap() {
    Spacer(Modifier.height(Spacings.small))
}

// Schedules: when a task runs, as TubeArchivist keeps it: minute, hour and day of the week

/** The tasks TubeArchivist runs on its own, with the phone app's names for them. */
val TaScheduledTasks =
    listOf(
        "update_subscribed" to "Rescan subscriptions",
        "download_pending" to "Start downloading",
        "check_reindex" to "Refresh metadata",
        "thumbnail_check" to "Check thumbnails",
        "run_backup" to "Back up the index",
        "version_check" to "Check for updates",
    )

private val WEEKDAYS =
    listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")

/** A schedule in words where it's one of the usual shapes, or else as it's written. */
fun scheduleText(schedule: String?): String {
    val parts = schedule?.trim()?.split(Regex("\\s+")).orEmpty()
    if (schedule.isNullOrBlank()) return "Doesn't run on its own"
    if (parts.size != 3) return schedule
    val (minute, hour, day) = parts
    val minutes = minute.toIntOrNull()
    val hours = hour.toIntOrNull()
    val everyHours = hour.removePrefix("*/").toIntOrNull()?.takeIf { hour.startsWith("*/") }
    val time = if (minutes != null && hours != null) "%02d:%02d".format(hours, minutes) else null
    return when {
        time != null && day == "*" -> "Every day at $time"
        time != null && day.toIntOrNull() in 0..6 -> "${WEEKDAYS[day.toInt()]}s at $time"
        minutes != null && everyHours != null && day == "*" ->
            if (everyHours == 1) "Every hour" else "Every $everyHours hours"
        minutes != null && hour == "*" && day == "*" -> "Every hour"
        else -> schedule
    }
}

/** The usual schedules, to pick in the dialog. */
val SCHEDULE_PICKS =
    listOf(
        "Every day at 08:00" to "0 8 *",
        "Every day at 20:00" to "0 20 *",
        "Every night at 03:00" to "0 3 *",
        "Every 2 hours" to "0 */2 *",
        "Every 6 hours" to "0 */6 *",
        "Every 12 hours" to "0 */12 *",
        "Sundays at 08:00" to "0 8 0",
    )
