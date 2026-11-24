package dev.jdtech.tubedroid.desktop.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.tubearchivist.TaApi
import dev.jdtech.jellyfin.tubearchivist.TaSchedule
import dev.jdtech.tubedroid.desktop.LocalApp
import dev.jdtech.tubedroid.desktop.LocalCanGoBack
import dev.jdtech.tubedroid.desktop.LocalNavigator
import dev.jdtech.tubedroid.desktop.rememberPageModel
import dev.jdtech.tubedroid.desktop.ui.ColumnScrollbar
import dev.jdtech.tubedroid.desktop.ui.PageBar
import dev.jdtech.tubedroid.desktop.ui.RoundIconButton
import dev.jdtech.tubedroid.desktop.ui.SectionCard
import dev.jdtech.tubedroid.desktop.ui.ServerSideTheme
import dev.jdtech.tubedroid.desktop.ui.Spacings
import dev.jdtech.tubedroid.desktop.ui.errorText
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * TubeArchivist's own settings, as its web interface has them under Settings: what a rescan looks
 * at, how videos are downloaded, and when its tasks run. Changing them needs an admin's token.
 */
class TaServerSettingsModel(private val api: TaApi, private val scope: CoroutineScope) {
    /** The settings by section, or null until they're read. */
    var config by mutableStateOf<JsonObject?>(null)
        private set

    var schedules by mutableStateOf<List<TaSchedule>>(emptyList())
        private set

    var error by mutableStateOf<String?>(null)
        private set

    init {
        load()
    }

    fun load() {
        error = null
        scope.launch {
            try {
                config = api.getConfig()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = errorText(e)
            }
            try {
                schedules = api.getSchedules()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The settings show without them
            }
        }
    }

    /** Changes one setting, shown right away and put back if TubeArchivist refuses it. */
    fun set(setting: TaSetting, value: JsonElement, onMessage: (String) -> Unit) {
        val before = config
        before?.let { all ->
            val section = all[setting.section] as? JsonObject ?: JsonObject(emptyMap())
            config =
                JsonObject(all + (setting.section to JsonObject(section + (setting.key to value))))
        }
        scope.launch {
            try {
                config = api.setConfigValue(setting.section, setting.key, value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                config = before
                onMessage(errorText(e))
            }
        }
    }

    /** Sets when a task runs, as minute, hour and weekday. Empty stops it running on its own. */
    fun setSchedule(task: String, schedule: String, onMessage: (String) -> Unit) {
        scope.launch {
            try {
                if (schedule.isBlank()) api.deleteSchedule(task)
                else api.setSchedule(task, schedule.trim())
                schedules = api.getSchedules()
                onMessage("Saved")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onMessage(errorText(e))
            }
        }
    }
}

@Composable
fun TaServerSettingsScreen() {
    val app = LocalApp.current
    val navigator = LocalNavigator.current
    val model = rememberPageModel("ta-settings") { scope -> TaServerSettingsModel(app.api, scope) }
    ServerSideTheme(onServer = true) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize()) {
                PageBar(
                    title = "TubeArchivist's settings",
                    onBack = if (LocalCanGoBack.current) navigator::back else null,
                    showSearch = false,
                ) {
                    RoundIconButton(
                        icon = "ic_refresh",
                        label = "Read again",
                        onClick = model::load,
                    )
                    OpenInWebButton("/settings/application/")
                }
                val config = model.config
                val error = model.error
                when {
                    config != null -> TaServerSettings(model, config)
                    error != null ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth().padding(Spacings.large),
                        ) {
                            Text(text = error, color = MaterialTheme.colorScheme.error)
                            Text(
                                text = "TubeArchivist only shows its settings to an admin's token.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = Spacings.small),
                            )
                            TextButton(onClick = model::load) { Text("Retry") }
                        }
                    else ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                }
            }
        }
    }
}

@Composable
private fun TaServerSettings(model: TaServerSettingsModel, config: JsonObject) {
    val navigator = LocalNavigator.current
    val scroll = rememberScrollState()
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(Spacings.medium),
            modifier =
                Modifier.verticalScroll(scroll)
                    .padding(Spacings.default)
                    .widthIn(max = 860.dp)
                    .fillMaxWidth(),
        ) {
            listOf(
                    "Subscriptions" to TaSubscriptionSettings,
                    "Downloads" to TaDownloadSettings,
                    "Application" to TaApplicationSettings,
                )
                .forEach { (title, settings) ->
                    SectionCard(title) {
                        settings.forEach { setting ->
                            TaSettingRow(
                                setting = setting,
                                value = setting.read(config),
                                onChange = { model.set(setting, it, navigator::message) },
                            )
                        }
                    }
                }
            SectionCard("Schedules") {
                Text(
                    text = "When TubeArchivist runs each task on its own.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = Spacings.small),
                )
                TaScheduledTasks.forEach { (task, name) ->
                    val schedule = model.schedules.firstOrNull { it.name == task }?.schedule
                    ScheduleRow(
                        name = name,
                        schedule = schedule,
                        onSave = { model.setSchedule(task, it, navigator::message) },
                    )
                }
            }
        }
        ColumnScrollbar(scroll)
    }
}

/** A task and when it runs, which opens the ways to change that. */
@Composable
private fun ScheduleRow(name: String, schedule: String?, onSave: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { open = true }) { Text(scheduleText(schedule)) }
    }
    if (open) {
        TaTextDialog(
            title = name,
            start = schedule.orEmpty(),
            hint = "Minute, hour and weekday, for example 0 8 * for every day at 08:00",
            emptyLabel = "Doesn't run on its own",
            picks = SCHEDULE_PICKS,
            onSave = {
                open = false
                onSave(it)
            },
            onDismiss = { open = false },
        )
    }
}
