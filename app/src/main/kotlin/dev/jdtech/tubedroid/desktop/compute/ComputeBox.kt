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
import java.time.LocalTime
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

/** An episode the archiver hands out to be heard, as its job says (contract v0.24 to v0.26). */
class HearJob(
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
    /** What became of it, in the program's words: in use, or the timing before it kept. */
    val text: String,
)

/**
 * The compute box: this PC hears episodes for the archiver's timed transcripts, which is the slow
 * part of timing them, and sends back the words heard. The archiver matches them to the transcript
 * itself, so the lines come out as its own timing's would.
 *
 * The hearing is done by the archiver's own PC program, `jrwi_hear.py`, which Winchivist carries a
 * copy of and runs with a Python on this PC that has faster-whisper, one episode at a time. The
 * archiver's address and token reach it through its environment, never on its command line or in a
 * file, and it sends the token to the archiver alone.
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

    private var running: Job? = null
    @Volatile private var helper: Process? = null
    @Volatile private var stopping = false
    private var python: List<String>? = null

    // Not started by itself before this, so a run that found nothing to do, or was stopped by
    // hand, isn't started over every half minute
    private var notBefore = 0L

    init {
        // The program hears in processes of its own, which mustn't outlive Winchivist
        Runtime.getRuntime().addShutdownHook(Thread { stopHelper() })
        scope.launch {
            snapshotFlow { settings.hearWhen }
                .collectLatest { mode ->
                    // Nothing looks at the clock or the mouse while it only starts by hand
                    if (mode == AppSettings.HEAR_BY_HAND) return@collectLatest
                    while (true) {
                        delay(WATCH_EVERY_MS)
                        val now = System.currentTimeMillis()
                        if (state == State.WAITING && now >= notBefore && wanted()) {
                            start(byItself = true)
                        }
                    }
                }
        }
    }

    // What was heard and sent since Winchivist started, as "item model". The archiver doesn't
    // count a hearing it couldn't match, and that one isn't heard again every round
    private val sent = mutableSetOf<String>()

    /** The episodes still to hear with the model picked, newest first. */
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
        )
    }

    private fun JsonObject.text(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

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
        if (state != State.WAITING) return
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
            val todo = toHear()
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
                val result = hearOne(job, model)
                if (stopping) {
                    again = HELD_AFTER_STOP_MS
                    break
                }
                heard.add(0, result)
                while (heard.size > HEARD_KEPT) heard.removeAt(heard.lastIndex)
                if (result.ok) {
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

    private fun HearJob.heardBy(model: String, result: Heard): HearJob {
        // The archiver keeps the new timing only when it found at least as many words
        val used = result.text.startsWith(IN_USE)
        return HearJob(
            itemId = itemId,
            title = title,
            lines = lines,
            seconds = seconds,
            timed = true,
            timedWith = if (used) model else timedWith,
            found = if (used) result.found ?: found else found,
            heardWith = heardWith + model,
        )
    }

    /** Hears one episode with the program, following what it says. */
    private suspend fun hearOne(job: HearJob, model: String): Heard {
        val last = ArrayDeque<String>()
        var found: Double? = null
        var outcome: String? = null
        val figures = Regex("^" + Regex.escape(model) + """\s+(\d+(?:\.\d+)?)%""")
        val code =
            try {
                program(
                    listOf(
                        "compare",
                        job.itemId.toString(),
                        "--models",
                        model,
                        "--device",
                        settings.hearDevice,
                    ),
                    onLine = { line ->
                        last.addLast(line)
                        while (last.size > LINES_KEPT) last.removeFirst()
                        figures.find(line)?.let {
                            found = it.groupValues[1].toDoubleOrNull()?.div(100)
                        }
                        if (line.startsWith(IN_USE) || line.startsWith(KEPT)) outcome = line
                        show(line)
                    },
                    onPart = ::show,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last.addLast(e.message ?: e.toString())
                -1
            }
        val ok = code == 0
        return Heard(
            itemId = job.itemId,
            title = job.title,
            ok = ok,
            found = found,
            text =
                if (ok) outcome ?: "Heard and sent."
                else last.takeLast(ERROR_LINES).joinToString("\n").ifBlank { "It stopped." },
        )
    }

    // What the program says is shown as it says it, with a bar for the two steps that count
    private fun show(line: String) {
        step = line
        val count = DOWNLOADING.find(line) ?: PIECES.find(line)
        val done = count?.groupValues?.get(1)?.toFloatOrNull()
        val total = count?.groupValues?.get(2)?.toFloatOrNull()
        fraction = if (done != null && total != null && total > 0) done / total else null
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
    }

    /** Runs the program's own check: the archiver, Python, faster-whisper and the graphics card. */
    fun check() {
        if (checking || state == State.HEARING) return
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
                val device = settings.hearDevice
                program(
                    listOf("check") +
                        if (device == "cpu") listOf("--device", "cpu") else emptyList(),
                    onLine = { line ->
                        said.appendLine(line)
                        checkText = said.toString().trim()
                    },
                    onPart = {},
                )
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
    fun folder(): File = script().parentFile

    /** Forgets the Python found, for when another one was picked. */
    fun pythonChanged() {
        python = null
        pythonShown = null
    }

    /** Stops the program and everything else, when Winchivist goes. */
    fun close() {
        stopping = true
        stopHelper()
        scope.cancel()
    }

    // The program

    /** The program: the copy picked in Settings, or Winchivist's own, written out when it's new. */
    private fun script(): File {
        settings.hearProgram
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf { it.isFile }
            ?.let {
                return it
            }
        val file = File(appDataFolder(), "hear/$PROGRAM")
        val own = javaClass.getResourceAsStream("/hear/$PROGRAM")?.use { it.readBytes() }
        if (own != null && !(file.isFile && file.readBytes().contentEquals(own))) {
            file.parentFile?.mkdirs()
            file.writeBytes(own)
        }
        return file
    }

    /**
     * Runs the program with [arguments] and follows what it says: [onLine] for each line it
     * finishes, [onPart] for one it's still writing, as its progress is. Returns its exit code.
     */
    private suspend fun program(
        arguments: List<String>,
        onLine: (String) -> Unit,
        onPart: (String) -> Unit,
    ): Int =
        withContext(Dispatchers.IO) {
            val python = findPython() ?: throw IOException(NO_PYTHON)
            val api = app.jrwi.api
            val script = script()
            if (!script.isFile) throw IOException("The program isn't there: ${script.path}")
            val builder =
                ProcessBuilder(python + script.path + arguments)
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
                    while (true) {
                        val read = reader.read()
                        if (read < 0) break
                        val char = read.toChar()
                        if (char == '\r' || char == '\n') {
                            if (line.isNotBlank()) onLine(line.toString().trim())
                            line.setLength(0)
                        } else {
                            line.append(char)
                            // Progress is written over itself without ending its line
                            if (!reader.ready() && line.isNotBlank()) onPart(line.toString().trim())
                        }
                    }
                    if (line.isNotBlank()) onLine(line.toString().trim())
                }
                process.waitFor()
            } finally {
                if (process.isAlive) stop(process)
                helper = null
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
                HearJob(288, "Blue Jay | Riptide #133", 3120, 9_960.0, false, null, null, none),
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
            )
        checkText =
            "Archiver: http://box:8090, version 0.14.9.\n" +
                "Episodes: 181 with a transcript. 180 can be heard (336 hours of sound), 3 of " +
                "them not timed yet.\n" +
                "Processor: 24 cores, 32 threads.\n" +
                "faster-whisper: 1.2.1, with CTranslate2 4.6.0.\n" +
                "The graphics card works: hearing will use it."
        pythonShown = "C:\\Users\\you\\AppData\\Local\\Python\\pythoncore-3.13-64\\python.exe"
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
        private const val IN_USE = "In use now:"
        private const val KEPT = "Kept the timing in use:"
        private const val NO_PYTHON =
            "No Python was found on this PC. The program needs Python 3.13 with faster-whisper: " +
                "install Python from python.org, then run\n" +
                "py -3.13 -m pip install faster-whisper psutil\n" +
                "or pick a Python below."
        private val DOWNLOADING = Regex("""Downloading the sound: (\d+) of (\d+) MB""")
        private val PIECES = Regex("""(\d+) of (\d+) pieces heard""")
        private val VERSION = Regex("""3\.?(\d+)""")
    }
}
