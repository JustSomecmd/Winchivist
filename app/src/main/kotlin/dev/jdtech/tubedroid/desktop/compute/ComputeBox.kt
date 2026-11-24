package dev.jdtech.tubedroid.desktop.compute

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.jdtech.jellyfin.jrwi.JrwiApiException
import dev.jdtech.tubedroid.desktop.AppGraph
import dev.jdtech.tubedroid.desktop.AppSettings
import dev.jdtech.tubedroid.desktop.appDataFolder
import dev.jdtech.tubedroid.desktop.jrwi.jrwiErrorText
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalTime
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** An episode the archiver hands out to be heard, as its job says (contract v0.24 to v0.28). */
data class HearJob(
    val itemId: Long,
    val title: String,
    val lines: Int,
    /** How long its sound is, in seconds. */
    val seconds: Double?,
    /** Whether its transcript is timed from this sound already. */
    val timed: Boolean,
    /** The model of the timing in use, and the share of the transcript's words it found. */
    val timedWith: String?,
    val found: Double?,
    /** Every model that has heard this sound. */
    val heardWith: List<String>,
    /** Who is hearing it right now, when someone is: a computer's name, or "server" (v0.28). */
    val claimedBy: String? = null,
)

/** An episode with a transcript that the archiver can't hand out, and why. */
class LeftOut(val title: String, val why: String)

/** How hearing an episode went here. */
class Heard(
    val itemId: Long,
    val title: String,
    val ok: Boolean,
    /** The share of the transcript's words that were found. */
    val found: Double?,
    /** What became of it: in use, or the timing before it kept, or why it wasn't heard. */
    val text: String,
    /** Whether its timing is the one in use now. */
    val used: Boolean = false,
    /** Who was hearing it when its turn came, so it was left for later: nothing went wrong. */
    val hearingIt: String? = null,
)

/**
 * The compute box: this PC hears episodes for the archiver's timed transcripts, which is the slow
 * part of timing them, and sends back the words heard. The archiver matches them to the transcript
 * itself, so the lines come out as its own timing's would.
 *
 * The hearing is done by the archiver's own PC program, `jrwi_hear.py`, run with a Python on this
 * PC that has faster-whisper, one episode at a time. The copy is the archiver's own, fetched from
 * it with its token whenever its version changes (archiver 0.14.10 and newer), or else the one that
 * came with Winchivist. The archiver's address and token reach it through its environment, never on
 * its command line or in a file, and it sends the token to the archiver alone.
 *
 * The program says what it's doing as one JSON object a line (program 1.4 and newer), which is what
 * the page shows. It claims each episode before hearing it, so the archiver's own timing and other
 * computers leave that one alone; an episode someone else has claimed is left for later.
 *
 * Nothing is heard until Start is pressed, or the PC is idle or the hour is right when Settings
 * says to wait for that.
 */
class ComputeBox(private val app: AppGraph) {
    private val settings: AppSettings
        get() = app.settings

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // The list of every episode can take the archiver a while
    private val client =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .build()

    // For letting go of a claim on the way out, which mustn't hold anything up
    private val quick =
        OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .callTimeout(3, TimeUnit.SECONDS)
            .build()

    enum class State {
        WAITING,
        LISTING,
        HEARING,
    }

    var state by mutableStateOf(State.WAITING)
        private set

    /** Whether the archiver hands out episodes to hear; null until it's been asked. */
    var supported by mutableStateOf<Boolean?>(null)
        private set

    /** Every episode the archiver can hand out, newest first; null until it's been asked. */
    var jobs by mutableStateOf<List<HearJob>?>(null)
        private set

    var leftOut by mutableStateOf<List<LeftOut>>(emptyList())
        private set

    /** How many episodes have a transcript at all. */
    var episodes by mutableStateOf(0)
        private set

    /** The episode being heard, what the program last said about it, and how far that step is. */
    var current by mutableStateOf<HearJob?>(null)
        private set

    var step by mutableStateOf<String?>(null)
        private set

    var fraction by mutableStateOf<Float?>(null)
        private set

    /** How many episodes this run has heard, of how many it set out to. */
    var heardNow by mutableStateOf(0)
        private set

    var runTotal by mutableStateOf(0)
        private set

    /** Whether it stops once the episode being heard is sent. */
    var pausing by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    /** What was heard since Winchivist started, newest first. */
    val heard = mutableStateListOf<Heard>()

    var checking by mutableStateOf(false)
        private set

    /** What the program's own check said about this PC. */
    var checkText by mutableStateOf<String?>(null)
        private set

    /** The Python the program runs with, once it's been looked for. */
    var pythonShown by mutableStateOf<String?>(null)
        private set

    /**
     * Which copy of the program runs, once that's been settled: its version and where it's from.
     */
    var programShown by mutableStateOf<String?>(null)
        private set

    private var running: Job? = null
    @Volatile private var helper: Process? = null
    @Volatile private var stopping = false

    // The claim the program took on the episode it's hearing. It lets go of it itself, unless it's
    // stopped by force, when that's left to Winchivist
    private class Claim(val itemId: Long, val id: String)

    @Volatile private var claim: Claim? = null
    private var python: List<String>? = null

    // Not started by itself before this, so a run that found nothing to do, or was stopped by
    // hand, isn't started over every half minute
    private var notBefore = 0L

    init {
        // The program hears in processes of its own, which mustn't outlive Winchivist
        Runtime.getRuntime()
            .addShutdownHook(
                Thread {
                    stopHelper()
                    letGo()
                }
            )
        scope.launch {
            snapshotFlow { settings.hearWhen }
                .collectLatest { mode ->
                    // Nothing looks at the clock or the mouse while it only starts by hand
                    if (mode == AppSettings.HEAR_BY_HAND) return@collectLatest
                    while (true) {
                        delay(WATCH_EVERY_MS)
                        val now = System.currentTimeMillis()
                        if (!checking && now >= notBefore && wanted()) start(byItself = true)
                    }
                }
        }
    }

    // What was heard and sent since Winchivist started, as "item model". The archiver doesn't
    // count a hearing it couldn't match, and that one isn't heard again every round
    private val sent = mutableSetOf<String>()

    /**
     * The episodes still to hear with the model picked, newest first. Those someone else is hearing
     * right now are among them, and are left until they're free.
     */
    fun toHear(): List<HearJob> {
        val model = settings.hearModel
        return jobs.orEmpty().filter { job ->
            model !in job.heardWith &&
                "${job.itemId} $model" !in sent &&
                (settings.hearEvery || !job.timed)
        }
    }

    /** Whether the time is right for hearing by itself, as Settings says. */
    private fun wanted(): Boolean =
        when (settings.hearWhen) {
            AppSettings.HEAR_IDLE ->
                PcState.onMains() && (PcState.idleSeconds() ?: 0L) >= settings.hearIdleMinutes * 60L
            AppSettings.HEAR_HOURS -> {
                val hour = LocalTime.now().hour
                val from = settings.hearFromHour
                val to = settings.hearToHour
                when {
                    from == to -> true
                    from < to -> hour in from until to
                    // Past midnight, such as from 23:00 to 07:00
                    else -> hour >= from || hour < to
                }
            }
            else -> false
        }

    /** Asks the archiver again which episodes can be heard. */
    fun refresh() {
        if (state != State.WAITING) return
        scope.launch {
            state = State.LISTING
            error = null
            try {
                list()
            } finally {
                state = State.WAITING
            }
        }
    }

    /** Reads the archiver's list of episodes. Returns whether there is one to go by. */
    private suspend fun list(): Boolean {
        try {
            val has = app.jrwi.api.has(FEATURE)
            supported = has
            if (has == false) {
                jobs = emptyList()
                return false
            }
            val answer = withContext(Dispatchers.IO) { get("/api/transcripts/jobs?all=true") }
            jobs = (answer["jobs"] as? JsonArray).orEmpty().mapNotNull { job(it) }
            leftOut =
                (answer["left_out"] as? JsonArray).orEmpty().mapNotNull { entry ->
                    val left = entry as? JsonObject ?: return@mapNotNull null
                    LeftOut(left.text("title").orEmpty(), left.text("why").orEmpty())
                }
            episodes = (answer["episodes"] as? JsonPrimitive)?.intOrNull ?: jobs.orEmpty().size
            supported = true
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = jrwiErrorText(e)
            return false
        }
    }

    private fun job(element: JsonElement): HearJob? {
        val job = element as? JsonObject ?: return null
        val sound = job["sound"] as? JsonObject
        return HearJob(
            itemId = (job["item_id"] as? JsonPrimitive)?.longOrNull ?: return null,
            title = job.text("title").orEmpty(),
            lines = (job["lines"] as? JsonPrimitive)?.intOrNull ?: 0,
            seconds = (sound?.get("duration") as? JsonPrimitive)?.doubleOrNull,
            timed = (job["timed"] as? JsonPrimitive)?.booleanOrNull == true,
            timedWith = job.text("timed_with"),
            found = (job["found"] as? JsonPrimitive)?.doubleOrNull,
            heardWith =
                (job["heard_with"] as? JsonArray).orEmpty().mapNotNull {
                    (it as? JsonPrimitive)?.takeIf { name -> name.isString }?.content
                },
            claimedBy = (job["claimed"] as? JsonObject)?.let { it.text("by") ?: ANOTHER },
        )
    }

    private fun JsonObject.text(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

    private fun JsonObject.number(key: String): Double? = (get(key) as? JsonPrimitive)?.doubleOrNull

    /** An answer of the archiver's, asked with its token in a header as everywhere else. */
    private fun get(path: String): JsonObject {
        val api = app.jrwi.api
        val url =
            api.fileUrl(path)?.toHttpUrlOrNull()
                ?: throw IOException("The JRWI archiver is not set up")
        val request =
            Request.Builder()
                .url(url)
                .apply { api.authHeader?.let { header("Authorization", it) } }
                .build()
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                throw JrwiApiException(response.code, "The archiver answered ${response.code}")
            }
            return api.json.parseToJsonElement(text) as? JsonObject
                ?: throw IOException("The archiver's answer couldn't be read")
        }
    }

    /** Hears the episodes waiting, newest first, until there are none or it's paused. */
    fun start(byItself: Boolean = false) {
        // One program at a time: the check runs it too
        if (state != State.WAITING || checking) return
        state = State.LISTING
        running = scope.launch { hearAll(byItself) }
    }

    private suspend fun hearAll(byItself: Boolean) {
        error = null
        pausing = false
        stopping = false
        // By itself it comes back later; stopped early because the PC is in use, straight away
        var again = AUTO_AGAIN_MS
        try {
            if (!list()) return
            // Those someone else is hearing now are left for a later round
            val todo = toHear().filter { it.claimedBy == null }
            if (todo.isEmpty()) return
            val model = settings.hearModel
            if (withContext(Dispatchers.IO) { findPython() } == null) {
                error = NO_PYTHON
                return
            }
            state = State.HEARING
            heardNow = 0
            runTotal = todo.size
            var failed = 0
            for (job in todo) {
                if (pausing || stopping) {
                    again = HELD_AFTER_STOP_MS
                    break
                }
                if (byItself && !wanted()) {
                    again = 0
                    break
                }
                current = job
                step = "Starting"
                fraction = null
                // The archiver's own copy of the program, should it have changed meanwhile
                withContext(Dispatchers.IO) { prepareProgram() }
                val result = hearOne(job, model)
                if (stopping) {
                    again = HELD_AFTER_STOP_MS
                    break
                }
                heard.add(0, result)
                while (heard.size > HEARD_KEPT) heard.removeAt(heard.lastIndex)
                val hearingIt = result.hearingIt
                if (hearingIt != null) {
                    // Someone else got to it first; the list says so until it's read again
                    jobs = jobs?.map {
                        if (it.itemId == job.itemId) it.copy(claimedBy = hearingIt) else it
                    }
                } else if (result.ok) {
                    failed = 0
                    heardNow++
                    sent += "${job.itemId} $model"
                    jobs = jobs?.map {
                        if (it.itemId == job.itemId) it.heardBy(model, result) else it
                    }
                } else if (++failed >= 2) {
                    // The same thing would stop every episode after these
                    error = "Stopped, as two episodes in a row couldn't be heard.\n${result.text}"
                    break
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        } finally {
            current = null
            step = null
            fraction = null
            pausing = false
            stopping = false
            state = State.WAITING
            notBefore = System.currentTimeMillis() + again
        }
    }

    private fun HearJob.heardBy(model: String, result: Heard): HearJob =
        // The archiver keeps the new timing only when it found at least as many words
        copy(
            timed = true,
            timedWith = if (result.used) model else timedWith,
            found = if (result.used) result.found ?: found else found,
            heardWith = heardWith + model,
        )

    /** Hears one episode with the program, following the events it prints. */
    private suspend fun hearOne(job: HearJob, model: String): Heard {
        val last = ArrayDeque<String>()
        var found: Double? = null
        var outcome: String? = null
        var used = false
        var claimedBy: String? = null
        var failure: String? = null
        // Once it's downloading or hearing, the events say how far it is, and what it would have
        // printed besides is only kept for when it stops on an error
        var underWay = false
        fun keep(text: String) {
            last.addLast(text)
            while (last.size > LINES_KEPT) last.removeFirst()
        }
        val arguments =
            listOf("compare", job.itemId.toString(), "--models", model) +
                listOf("--device", settings.hearDevice)
        val code =
            try {
                program(arguments) { event, line ->
                    if (event == null) {
                        // Not an event: something Python or one of its libraries wrote, such as
                        // how far a speech model's download is
                        keep(line)
                        show(line)
                    } else {
                        val name = event.text("event")
                        if (name in PROGRESS_EVENTS) underWay = true
                        when (name) {
                            "message" -> {
                                event.text("text")?.let { text ->
                                    keep(text)
                                    if (!underWay) show(text)
                                }
                            }
                            "claim" -> {
                                event.text("claim")?.let { claim = Claim(job.itemId, it) }
                            }
                            "claimed" -> {
                                claimedBy = event.text("by") ?: ANOTHER
                            }
                            "copying" ->
                                show(
                                    "The archiver is copying the sound out of TubeArchivist's copy"
                                )
                            "download" -> {
                                val done = event.number("done")
                                val total = event.number("total")?.takeIf { it > 0 }
                                if (done != null && total != null) {
                                    show(
                                        "Downloading the sound: ${megabytes(done)} of " +
                                            "${megabytes(total)} MB",
                                        (done / total).toFloat(),
                                    )
                                } else {
                                    show("Downloading the sound")
                                }
                            }
                            "downloaded" -> show("The sound is here")
                            "loading" -> show("Loading ${event.text("model") ?: model}")
                            "hearing" -> {
                                val done = event.number("done")
                                val total = event.number("total")?.takeIf { it > 0 }
                                val times =
                                    listOfNotNull(
                                        event.number("spent")?.let { "${clock(it)} so far" },
                                        event.number("left")?.let { "about ${clock(it)} left" },
                                    )
                                if (done != null && total != null) {
                                    val pieces = "${done.toInt()} of ${total.toInt()} pieces heard"
                                    show(
                                        "${event.text("model") ?: model}: " +
                                            (listOf(pieces) + times).joinToString(", "),
                                        (done / total).toFloat(),
                                    )
                                }
                            }
                            "heard" ->
                                show(
                                    "Heard" +
                                        event.number("took")?.let { " in ${clock(it)}" }.orEmpty()
                                )
                            "sending" ->
                                show(
                                    "Sending what was heard; the archiver matches it to the transcript"
                                )
                            "sent" -> {
                                val mine =
                                    (event["tries"] as? JsonArray)
                                        .orEmpty()
                                        .mapNotNull { it as? JsonObject }
                                        .firstOrNull { it.text("model") == model }
                                found = mine?.number("found")
                                val replaced =
                                    (event["replaced"] as? JsonPrimitive)?.booleanOrNull != false
                                used = replaced && event.text("used") == model
                                outcome =
                                    sentText(model, mine, used, event["before"] as? JsonObject)
                            }
                            "skipped" -> {
                                failure = event.text("why")
                            }
                            "error" -> {
                                failure = event.text("message")
                            }
                            // Programs ignore the events they don't know
                            else -> {}
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e.message ?: e.toString()
                -1
            }
        // Stopped by force, it couldn't let go of its claim itself; otherwise this finds none
        if (code != 0) withContext(Dispatchers.IO) { letGo() }
        claim = null
        val by = claimedBy
        val wrong = failure
        return when {
            by != null || code == CLAIMED_ELSEWHERE ->
                Heard(
                    itemId = job.itemId,
                    title = job.title,
                    ok = false,
                    found = null,
                    text = "Left for later: ${whoHears(by ?: ANOTHER)}.",
                    hearingIt = by ?: ANOTHER,
                )
            code == 0 && wrong == null ->
                Heard(job.itemId, job.title, true, found, outcome ?: "Heard and sent.", used)
            else ->
                Heard(
                    itemId = job.itemId,
                    title = job.title,
                    ok = false,
                    found = found,
                    text =
                        wrong
                            ?: last.takeLast(ERROR_LINES).joinToString("\n").ifBlank {
                                "It stopped."
                            },
                )
        }
    }

    /** What the archiver made of the words sent, from its answer. */
    private fun sentText(model: String, mine: JsonObject?, used: Boolean, before: JsonObject?) =
        buildString {
            val found = mine?.number("found")?.let { " ${percent(it)} of words found" }
            val was =
                before?.text("model")?.let { old ->
                    val where = if (before?.text("source") == "pc") "a PC" else "the server"
                    "$old from $where" +
                        before?.number("found")?.let { " (${percent(it)})" }.orEmpty()
                }
            if (used) {
                append("In use now: $model")
                found?.let { append(",$it") }
                was?.let { append(", in place of $it") }
                append(".")
            } else {
                append("Kept the timing in use")
                was?.let { append(": $it") }
                append(".")
                mine?.text("error")?.let { append(" $model: $it.") }
                    ?: found?.let { append(" $model:$it.") }
            }
        }

    /** Who is hearing an episode, in words. */
    fun whoHears(by: String): String =
        if (by == "server") "the archiver is hearing it itself" else "$by is hearing it"

    private fun percent(share: Double): String = String.format(Locale.ROOT, "%.1f%%", share * 100)

    private fun megabytes(bytes: Double): Long = (bytes / 1_000_000).toLong()

    /** Seconds as 1:03, or 1:02:03 from an hour on. */
    private fun clock(seconds: Double): String {
        val whole = seconds.toLong().coerceAtLeast(0)
        val rest = String.format(Locale.ROOT, "%02d", whole % 60)
        return if (whole >= 3600) {
            "${whole / 3600}:" + String.format(Locale.ROOT, "%02d", whole / 60 % 60) + ":$rest"
        } else {
            "${whole / 60}:$rest"
        }
    }

    // What the program is doing, with a bar for the steps that count
    private fun show(text: String, share: Float? = null) {
        step = text
        fraction = share
    }

    /** Stops once the episode being heard has been sent, or carries on after all. */
    fun pauseAfterThis(pause: Boolean = true) {
        if (state == State.HEARING) pausing = pause
    }

    /** Stops now. The episode being heard starts again next time; its sound is kept until then. */
    fun stopNow() {
        if (state != State.HEARING) return
        stopping = true
        stopHelper()
        // The program can't let go of its claim now, so the episode would stay taken for half an
        // hour: hearOne does it as soon as the program is gone
    }

    /**
     * Lets go of the claim the program took on the episode it was hearing, for when the program was
     * stopped by force and couldn't. Without it the claim runs out by itself in half an hour.
     */
    private fun letGo() {
        val held = claim ?: return
        claim = null
        runCatching {
            val api = app.jrwi.api
            val url =
                api.fileUrl("/api/transcripts/jobs/${held.itemId}/claim")
                    ?.toHttpUrlOrNull()
                    ?.newBuilder()
                    ?.addQueryParameter("claim", held.id)
                    ?.build() ?: return
            val request =
                Request.Builder()
                    .url(url)
                    .delete()
                    .apply { api.authHeader?.let { header("Authorization", it) } }
                    .build()
            quick.newCall(request).execute().close()
        }
    }

    /** Runs the program's own check: the archiver, Python, faster-whisper and the graphics card. */
    fun check() {
        if (checking || state != State.WAITING) return
        checking = true
        checkText = null
        scope.launch {
            val said = StringBuilder()
            try {
                python = null
                if (withContext(Dispatchers.IO) { findPython() } == null) {
                    checkText = NO_PYTHON
                    return@launch
                }
                withContext(Dispatchers.IO) { prepareProgram() }
                val device = settings.hearDevice
                program(
                    listOf("check") +
                        if (device == "cpu") listOf("--device", "cpu") else emptyList()
                ) { event, line ->
                    // In the program's own words, a finding a line
                    val text =
                        if (event == null) {
                            line
                        } else {
                            when (event.text("event")) {
                                "message" -> event.text("text")
                                "error" -> event.text("message")
                                else -> null
                            }
                        }
                    if (text != null) {
                        said.appendLine(text)
                        checkText = said.toString().trim()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                said.appendLine(e.message ?: e.toString())
            } finally {
                checkText =
                    said.toString().trim().ifBlank { checkText ?: "The check said nothing." }
                checking = false
            }
        }
    }

    /** The folder the program keeps its speech models and the sound it's hearing in. */
    fun folder(): File = script().parentFile.also { it.mkdirs() }

    /** Forgets the Python found, for when another one was picked. */
    fun pythonChanged() {
        python = null
        pythonShown = null
    }

    /** Stops the program and everything else, when Winchivist goes. */
    fun close() {
        stopping = true
        stopHelper()
        letGo()
        scope.cancel()
    }

    // The program

    /** The copy picked on the page, when there is one and it's there. */
    private fun pickedProgram(): File? =
        settings.hearProgram?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile }

    /** Where Winchivist keeps the program when none was picked. */
    private fun ownProgram(): File = File(appDataFolder(), "hear/$PROGRAM")

    private fun script(): File = pickedProgram() ?: ownProgram()

    /** The program's version, as the file itself says it. */
    private fun versionOf(text: String): String? = PROGRAM_VERSION.find(text)?.groupValues?.get(1)

    /** Whether version [a] is older than [b], number by number. */
    private fun older(a: String, b: String): Boolean {
        val one = a.split('.').map { it.toIntOrNull() ?: 0 }
        val other = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (index in 0 until maxOf(one.size, other.size)) {
            val left = one.getOrElse(index) { 0 }
            val right = other.getOrElse(index) { 0 }
            if (left != right) return left < right
        }
        return false
    }

    /**
     * Makes sure Winchivist's copy of the program is the one to run. An archiver that hands its
     * program out (0.14.10 and newer) is heard for with its own copy, fetched with its token when
     * the version it names isn't the one here, so the two always fit. For an older archiver it's
     * the copy that came with Winchivist. A copy picked on the page is left as it is.
     */
    private fun prepareProgram() {
        if (pickedProgram() != null) {
            programShown = null
            return
        }
        val file = ownProgram()
        val bundled = javaClass.getResourceAsStream("/hear/$PROGRAM")?.use { it.readBytes() }
        val here = file.takeIf { it.isFile }?.readText()?.let(::versionOf)
        val ping = runCatching { get("/api/ping") }.getOrNull()
        val features =
            (ping?.get("features") as? JsonArray).orEmpty().mapNotNull {
                (it as? JsonPrimitive)?.content
            }
        val offered = ping?.text("pc_program")?.takeIf { PROGRAM_FEATURE in features }
        var fromArchiver = offered != null && offered == here
        if (offered != null && offered != here) {
            fromArchiver =
                try {
                    fetchProgram(file, offered)
                    true
                } catch (e: Exception) {
                    System.err.println("Couldn't fetch the archiver's program: $e")
                    false
                }
        } else if (ping != null && offered == null && bundled != null) {
            if (!(file.isFile && file.readBytes().contentEquals(bundled))) save(file, bundled)
        }
        // With neither, such as before the archiver was ever reached
        if (!file.isFile && bundled != null) save(file, bundled)
        val version = file.takeIf { it.isFile }?.readText()?.let(::versionOf)
        programShown =
            listOfNotNull(
                    version?.let { "Version $it" },
                    if (fromArchiver) "the archiver's own copy"
                    else "the copy that came with Winchivist",
                )
                .joinToString(", ")
    }

    /** Fetches the archiver's copy of the program, keeping it only when it's what was named. */
    private fun fetchProgram(file: File, version: String) {
        val api = app.jrwi.api
        val url =
            api.fileUrl(PROGRAM_PATH)?.toHttpUrlOrNull()
                ?: throw IOException("The JRWI archiver is not set up")
        val request =
            Request.Builder()
                .url(url)
                .apply { api.authHeader?.let { header("Authorization", it) } }
                .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw JrwiApiException(response.code, "The archiver answered ${response.code}")
            }
            if (response.body.contentLength() > MAX_PROGRAM_BYTES) {
                throw IOException("What the archiver sent is too big to be its program")
            }
            val bytes = response.body.bytes()
            if (
                bytes.size > MAX_PROGRAM_BYTES ||
                    versionOf(bytes.toString(Charsets.UTF_8)) != version
            ) {
                throw IOException("What the archiver sent isn't its program $version")
            }
            save(file, bytes)
        }
    }

    // Whole or not at all, so a copy half written is never run
    private fun save(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val fresh = File(file.parentFile, file.name + ".new")
        fresh.writeBytes(bytes)
        Files.move(fresh.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    /**
     * Runs the program with [arguments] and follows what it prints: [onEvent] gets each line, as
     * the event it is when it's one (see "The program's output" in the archiver's API contract),
     * and as it was written either way. Returns the program's exit code.
     */
    private suspend fun program(
        arguments: List<String>,
        onEvent: (event: JsonObject?, line: String) -> Unit,
    ): Int =
        withContext(Dispatchers.IO) {
            val python = findPython() ?: throw IOException(NO_PYTHON)
            val api = app.jrwi.api
            val script = script()
            if (!script.isFile) throw IOException("The program isn't there: ${script.path}")
            // Events came with program 1.4; an older copy can only have been picked by hand
            val version = versionOf(script.readText())
            if (version == null || older(version, FIRST_WITH_EVENTS)) {
                throw IOException(
                    "This copy of the program is ${version?.let { "version $it" } ?: "too old"}. " +
                        "Winchivist needs version $FIRST_WITH_EVENTS or newer: pick a newer " +
                        "copy, or go back to the one found by itself."
                )
            }
            val builder =
                ProcessBuilder(python + script.path + arguments + "--json")
                    .directory(script.parentFile)
                    .redirectErrorStream(true)
            builder.environment().apply {
                // Its address and token, where no other program's list of what runs shows them
                put("JRWI_SERVER", api.baseUrl.orEmpty().trimEnd('/'))
                put("JRWI_TOKEN", settings.jrwiToken.orEmpty())
                // What it says comes as it's said, and in letters any title can be written in
                put("PYTHONUNBUFFERED", "1")
                put("PYTHONIOENCODING", "utf-8")
                put("PYTHONUTF8", "1")
            }
            val process = builder.start()
            helper = process
            try {
                process.outputStream.close()
                process.inputStream.reader(Charsets.UTF_8).use { reader ->
                    val line = StringBuilder()
                    fun finished() {
                        val text = line.toString().trim()
                        line.setLength(0)
                        if (text.isEmpty()) return
                        val event =
                            if (text.startsWith("{")) {
                                runCatching { api.json.parseToJsonElement(text) as? JsonObject }
                                    .getOrNull()
                                    ?.takeIf { it["event"] is JsonPrimitive }
                            } else {
                                null
                            }
                        onEvent(event, text)
                    }
                    while (true) {
                        val read = reader.read()
                        if (read < 0) break
                        val char = read.toChar()
                        // A library's progress, such as a model's download, is written over itself
                        if (char == '\r' || char == '\n') finished() else line.append(char)
                    }
                    finished()
                }
                process.waitFor()
            } finally {
                if (process.isAlive) stop(process)
                if (helper === process) helper = null
            }
        }

    private fun stopHelper() {
        helper?.let(::stop)
    }

    // The program hears in a process of its own, which would carry on without the one that started
    // it, so they're stopped from the last one up
    private fun stop(process: Process) {
        runCatching {
            val handle = process.toHandle()
            handle.descendants().forEach { it.destroyForcibly() }
            handle.destroyForcibly()
        }
    }

    // Python

    /**
     * A Python to run the program with: the one picked in Settings, or else one on this PC that has
     * faster-whisper, or else any, as the program then says what's missing.
     */
    private fun findPython(): List<String>? {
        python?.let {
            return it
        }
        val picked = settings.hearPython?.takeIf { it.isNotBlank() }?.let { listOf(it) }
        val candidates =
            if (PcState.isWindows) {
                installedPythons().map { listOf(it.path) } + listOf(listOf("py"), listOf("python"))
            } else {
                listOf(listOf("python3"), listOf("python"))
            }
        val found =
            picked?.takeIf { runs(it + "--version", 15) }
                ?: candidates.firstOrNull { runs(it + listOf("-c", HAS_WHISPER), 60) }
                ?: candidates.firstOrNull { runs(it + "--version", 15) }
        python = found
        pythonShown = found?.joinToString(" ")
        return found
    }

    private fun runs(command: List<String>, seconds: Long): Boolean =
        try {
            val process =
                ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start()
            process.outputStream.close()
            if (process.waitFor(seconds, TimeUnit.SECONDS)) {
                process.exitValue() == 0
            } else {
                process.destroyForcibly()
                false
            }
        } catch (e: Exception) {
            false
        }

    /** The Pythons installed on this PC the usual ways, newest first. */
    private fun installedPythons(): List<File> {
        fun under(base: String?, folder: String, start: String): List<File> =
            base
                ?.let { if (folder.isEmpty()) File(it) else File(it, folder) }
                ?.listFiles { file -> file.isDirectory && file.name.startsWith(start, true) }
                ?.map { File(it, "python.exe") }
                ?.filter { it.isFile }
                .orEmpty()
        val local = System.getenv("LOCALAPPDATA")
        val all =
            under(local, "Python", "pythoncore-3") +
                under(local, "Programs/Python", "Python3") +
                under(System.getenv("ProgramFiles"), "", "Python3")
        return all.sortedByDescending { python ->
            VERSION.find(python.parentFile.name)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }
    }

    /** Shows the box at work, for the page renders, which hear nothing. */
    internal fun showForPreview() {
        val small = listOf("small.en")
        val turbo = listOf("large-v3-turbo")
        val none = emptyList<String>()
        val waiting =
            listOf(
                HearJob(
                    300,
                    "Through the Fire | Riptide #136",
                    3629,
                    11_695.0,
                    true,
                    small[0],
                    0.794,
                    small,
                ),
                HearJob(
                    296,
                    "Paid In Blood | Riptide #134",
                    3412,
                    10_840.0,
                    false,
                    null,
                    null,
                    none,
                ),
                HearJob(
                    288,
                    "Blue Jay | Riptide #133",
                    3120,
                    9_960.0,
                    false,
                    null,
                    null,
                    none,
                    claimedBy = "server",
                ),
            )
        jobs =
            waiting +
                HearJob(
                    280,
                    "The Long Dark | Riptide #132",
                    2984,
                    9_420.0,
                    true,
                    turbo[0],
                    0.912,
                    turbo,
                )
        leftOut =
            listOf(
                LeftOut(
                    "Hole in the Sea | Tales from The Midnight Rose",
                    "it has no archived audio or video",
                )
            )
        episodes = 181
        supported = true
        state = State.HEARING
        current = waiting.first()
        heardNow = 1
        runTotal = 4
        step = "large-v3-turbo: 7 of 20 pieces heard, 0:34 so far, about 1:03 left"
        fraction = 7f / 20
        heard.clear()
        heard +=
            Heard(
                itemId = 280,
                title = "The Long Dark | Riptide #132",
                ok = true,
                found = 0.912,
                text =
                    "In use now: large-v3-turbo, 91.2% of words found, in place of small.en from " +
                        "the server (78.8%).",
                used = true,
            )
        checkText =
            "Archiver: http://box:8090, version 0.14.10.\n" +
                "Episodes: 181 with a transcript. 180 can be heard (336 hours of sound), 3 of " +
                "them not timed yet.\n" +
                "Processor: 24 cores, 32 threads.\n" +
                "faster-whisper: 1.2.1, with CTranslate2 4.6.0.\n" +
                "The graphics card works: hearing will use it."
        pythonShown = "C:\\Users\\you\\AppData\\Local\\Python\\pythoncore-3.13-64\\python.exe"
        programShown = "Version 1.4, the archiver's own copy"
    }

    companion object {
        const val PROGRAM = "jrwi_hear.py"

        /** The models to pick from, the one this archive's episodes did best with first. */
        val MODELS = listOf("large-v3-turbo", "large-v3", "medium.en", "small.en")

        private const val FEATURE = "transcript_jobs"
        private const val WATCH_EVERY_MS = 30_000L
        private const val AUTO_AGAIN_MS = 30 * 60_000L
        private const val HELD_AFTER_STOP_MS = 60 * 60_000L
        private const val HEARD_KEPT = 50
        private const val LINES_KEPT = 12
        private const val ERROR_LINES = 6
        private const val HAS_WHISPER = "import ctranslate2, faster_whisper"
        private const val PROGRAM_FEATURE = "transcript_program"
        private const val PROGRAM_PATH = "/api/transcripts/program"
        private const val MAX_PROGRAM_BYTES = 2_000_000L
        private const val FIRST_WITH_EVENTS = "1.4"

        // The program's exit code when someone else is hearing every episode it was given
        private const val CLAIMED_ELSEWHERE = 3
        private const val ANOTHER = "another computer"
        private val PROGRESS_EVENTS = setOf("copying", "download", "loading", "hearing")
        private const val NO_PYTHON =
            "No Python was found on this PC. The program needs Python 3.13 with faster-whisper: " +
                "install Python from python.org, then run\n" +
                "py -3.13 -m pip install faster-whisper psutil\n" +
                "or pick a Python below."
        private val PROGRAM_VERSION = Regex("""(?m)^PROGRAM\s*=\s*"([^"]+)"""")
        private val VERSION = Regex("""3\.?(\d+)""")
    }
}
