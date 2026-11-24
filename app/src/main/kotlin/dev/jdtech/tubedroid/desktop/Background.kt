package dev.jdtech.tubedroid.desktop

import dev.jdtech.jellyfin.tubearchivist.TaTaskResult
import dev.jdtech.tubedroid.desktop.player.episodeTitle
import java.io.File
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind

/**
 * What Winchivist looks for by itself while it runs, as the phone app does in the background:
 * movies and episodes new on Jellyfin, and YouTube's bot detection stopping one of TubeArchivist's
 * tasks. Each has its own switch in Settings. What's found is told as a Windows notification.
 *
 * Jellyfin says so itself when its library changes, so a new item is told about at once.
 * TubeArchivist tells nothing by itself, and is asked every minute.
 */
class BackgroundChecks(private val app: AppGraph) {
    // One check for new items at a time, as they share what's remembered
    private val newItems = Mutex()

    /** Listens and looks until cancelled, telling [notify] what's new. */
    suspend fun run(notify: (title: String, text: String) -> Unit) {
        coroutineScope {
            // The moment Jellyfin says it added something. A scan adds several in a row, so it's
            // given a few seconds, and what's added meanwhile is looked for once more after
            launch {
                while (true) {
                    app.jellyfin
                        .libraryChanges()
                        .filter { it.added }
                        .conflate()
                        .collect {
                            delay(ADDED_SETTLE_MS)
                            lookForNewItems(notify)
                        }
                    // The connection gave up: it's listened to again in a while
                    delay(LISTEN_AGAIN_MS)
                }
            }
            // And every 15 minutes all the same, for what a dropped connection missed
            launch {
                // Not in the way of the app starting up
                delay(FIRST_AFTER_MS)
                while (true) {
                    lookForNewItems(notify)
                    delay(NEW_ITEMS_EVERY_MS)
                }
            }
            launch {
                delay(FIRST_AFTER_MS)
                while (true) {
                    lookForBots(notify)
                    delay(BOTS_EVERY_MS)
                }
            }
        }
    }

    // TubeArchivist answers with an error while it has no results, so the same one isn't written
    // down every minute
    private var botError: String? = null

    private suspend fun lookForBots(notify: (String, String) -> Unit) {
        if (!app.settings.notifyBots) return
        try {
            checkBots(notify)
            botError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val text = e.toString()
            if (text != botError) System.err.println("Bot detection check: $text")
            botError = text
        }
    }

    private suspend fun lookForNewItems(notify: (String, String) -> Unit) {
        quietly { if (app.settings.notifyNewItems) newItems.withLock { checkNewItems(notify) } }
    }

    // A check that fails is tried again with the next one
    private suspend fun quietly(check: suspend () -> Unit) {
        try {
            check()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            System.err.println("Background check: $e")
        }
    }

    // New on Jellyfin

    /**
     * Every movie and episode on the server is remembered by its ID, which Jellyfin makes from the
     * file's path, so only one the server never had is told about: its "date added" alone moves an
     * old item to the front whenever its file changes. The first check only remembers what's there.
     */
    private suspend fun checkNewItems(notify: (String, String) -> Unit) {
        val jellyfin = app.jellyfin
        if (!jellyfin.isSignedIn) return
        val newest = jellyfin.newest(CHECK_LIMIT)
        val newestIds = newest.map { it.id.toString() }
        val file = knownFile()
        val known = withContext(Dispatchers.IO) { readKnown(file) }
        val now = System.currentTimeMillis()
        if (known == null) {
            val all = jellyfin.allIds()
            withContext(Dispatchers.IO) { writeKnown(file, now, all + newestIds) }
            return
        }
        val (syncedAt, ids) = known
        val added = newest.filter { it.id.toString() !in ids }
        if (added.isNotEmpty()) announce(added, notify)
        // Once a week the whole library is read again, so what was deleted is let go of
        val all = if (now - syncedAt >= RESYNC_MS) jellyfin.allIds() else null
        if (all != null || added.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                writeKnown(file, if (all != null) now else syncedAt, (all ?: ids) + newestIds)
            }
        }
    }

    private fun announce(added: List<BaseItemDto>, notify: (String, String) -> Unit) {
        val single = added.singleOrNull()
        when {
            single != null && single.type == BaseItemKind.EPISODE ->
                notify(
                    "New video from ${single.seriesName.orEmpty()}",
                    episodeTitle(single, app.settings.hideEpisodeNumbers),
                )
            single != null -> notify("New movie", single.name.orEmpty())
            else ->
                notify(
                    "${added.size} new videos",
                    // Each show and movie once, newest first
                    added
                        .map {
                            if (it.type == BaseItemKind.EPISODE) it.seriesName.orEmpty()
                            else it.name.orEmpty()
                        }
                        .distinct()
                        .take(MAX_NAMES)
                        .joinToString(", "),
                )
        }
    }

    /** Where the IDs of one server's items, for one user, are kept between starts. */
    private fun knownFile(): File {
        val settings = app.settings
        val account = "${settings.jellyfinUrl}:${settings.jellyfinUserId}".hashCode().toUInt()
        return File(appDataFolder(), "known-items/$account.txt")
    }

    /** When the whole library was last read, and the IDs, or null before the first check. */
    private fun readKnown(file: File): Pair<Long, Set<String>>? {
        if (!file.isFile) return null
        val lines = file.readLines()
        val syncedAt = lines.firstOrNull()?.toLongOrNull() ?: return null
        return syncedAt to lines.drop(1).filterTo(HashSet()) { it.isNotBlank() }
    }

    private fun writeKnown(file: File, syncedAt: Long, ids: Set<String>) {
        file.parentFile?.mkdirs()
        file.writeText(
            buildString {
                append(syncedAt).append('\n')
                ids.forEach { append(it).append('\n') }
            }
        )
    }

    // YouTube's bot detection

    /**
     * TubeArchivist keeps the results of its tasks for a day, and shows a failure in its web
     * interface for about 20 seconds only. Failures from before the first check are old news.
     */
    private suspend fun checkBots(notify: (String, String) -> Unit) {
        val api = app.api
        val settings = app.settings
        if (!api.isConfigured) return
        val base = api.baseUrl.orEmpty()
        val firstCheck = settings.botCheckedServer != base
        if (firstCheck) {
            settings.botCheckedServer = base
            settings.botCheckedSince = System.currentTimeMillis().toString()
            settings.botSeen = ""
        }
        val since = settings.botCheckedSince?.toLongOrNull() ?: 0L
        // An error here means no results yet, or a token that isn't an admin's
        val results = api.getTaskResults()
        val seen = settings.botSeen.orEmpty().split(',').filter { it.isNotBlank() }
        val failures = results.filter { it.taskId.isNotEmpty() && it.isBotDetection() }
        settings.botSeen =
            (failures.map { it.taskId } + seen).distinct().take(MAX_SEEN).joinToString(",")
        val new = failures.filter { result ->
            result.taskId !in seen && (finishedAt(result)?.let { it >= since } ?: !firstCheck)
        }
        // The latest failure names its task
        val latest = new.maxByOrNull { finishedAt(it) ?: 0L } ?: return
        notify(
            "YouTube bot detection stopped TubeArchivist",
            "${taskName(latest.name)} was stopped because YouTube asked to confirm it's not a " +
                "bot. Importing your YouTube cookies in TubeArchivist's settings often helps.",
        )
    }

    private fun TaTaskResult.isBotDetection(): Boolean {
        val text = listOfNotNull(result, traceback).joinToString("\n")
        return BOT_MESSAGES.any { text.contains(it, ignoreCase = true) }
    }

    private fun taskName(name: String): String =
        when (name) {
            "download_pending" -> "The download queue"
            "update_subscribed" -> "The subscription rescan"
            "extract_download" -> "Adding to the download queue"
            "check_reindex" -> "The reindex"
            "subscribe_to" -> "Adding a subscription"
            else -> "A background task"
        }

    private fun finishedAt(result: TaTaskResult): Long? {
        val text = result.dateDone ?: return null
        // Celery writes UTC, with or without the offset depending on its version
        return try {
            OffsetDateTime.parse(text).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            try {
                LocalDateTime.parse(text).toInstant(ZoneOffset.UTC).toEpochMilli()
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }

    private companion object {
        const val FIRST_AFTER_MS = 45_000L
        const val NEW_ITEMS_EVERY_MS = 15 * 60_000L
        const val BOTS_EVERY_MS = 60_000L
        const val ADDED_SETTLE_MS = 5_000L
        const val LISTEN_AGAIN_MS = 5 * 60_000L
        const val CHECK_LIMIT = 100
        const val RESYNC_MS = 7L * 24 * 60 * 60 * 1000
        const val MAX_NAMES = 6
        const val MAX_SEEN = 200
        val BOT_MESSAGES = listOf("bot detection", "not a bot")
    }
}

/** The app's own folder for what it keeps between starts, apart from the settings. */
fun appDataFolder(): File =
    File(System.getenv("LOCALAPPDATA") ?: System.getProperty("java.io.tmpdir"), "Winchivist")
