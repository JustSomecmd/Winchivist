package dev.jdtech.tubedroid.desktop

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshotFlow
import dev.jdtech.jellyfin.tubearchivist.TaApi
import dev.jdtech.jellyfin.tubearchivist.TaVideo
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** A file being saved to this computer, waiting its turn, or saved already. */
data class LocalDownload(
    /** What it is, such as a TubeArchivist video's ID. */
    val id: String,
    val title: String,
    val file: File,
    /** From 0 to 1, or null while the size isn't known. */
    val progress: Float? = null,
    val state: State = State.QUEUED,
    val error: String? = null,
    /** The page of what's being saved, for the list of downloads to open. */
    val route: Route? = null,
    /** What the record of this PC's downloads holds of it. Null for a file that's only saved. */
    val kept: Kept? = null,
    /** How many bytes are on the PC so far. */
    val have: Long = 0,
) {
    enum class State {
        /** Waiting for one of the downloads before it to finish. */
        QUEUED,
        RUNNING,

        /** Stopped by hand, with what it has kept for going on. */
        PAUSED,
        DONE,
        FAILED,
        CANCELLED,
    }

    /** Still to finish: waiting or downloading. */
    val active: Boolean
        get() = state == State.QUEUED || state == State.RUNNING

    /** Asked for and not whole yet: waiting, downloading, paused or failed. */
    val unfinished: Boolean
        get() = state != State.DONE && state != State.CANCELLED
}

/** The archive's episode a download is, for listing it under its show. */
@Serializable
data class KeptEpisode(
    val item: Long,
    val show: String = "",
    val showId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
)

/**
 * A video or an episode's audio that Winchivist downloaded and keeps track of: one line of the
 * record that's kept beside the files, in the downloads folder.
 */
@Serializable
data class Kept(
    /** A TubeArchivist video's ID, or `jrwi:` and the archive's address of the file. */
    val id: String,
    /** [TUBEARCHIVIST] or [JRWI]: whose file it is, and so whose token fetches it. */
    val service: String,
    val audio: Boolean = false,
    val title: String,
    /** The channel or the show, which it's listed under and filed in a folder of. */
    val group: String = "",
    /** The channel's or the show's ID, for its page. */
    val groupId: String? = null,
    /** Where the file is, from the downloads folder, with forward slashes. */
    val file: String,
    /** Where it comes from, as its server gave it. Never with a token. */
    val source: String,
    /** Its whole size in bytes, once known. */
    val size: Long? = null,
    /** What the server gave to tell this file from a later one, for going on with a download. */
    val validator: String? = null,
    /** [WAITING], [PAUSED], [DONE] or [FAILED]. */
    val state: String = WAITING,
    val error: String? = null,
    /** When it was asked for. */
    val added: Long = 0,
    /** How long it plays, in seconds. */
    val seconds: Double? = null,
    /** Its picture, as its server gives it. */
    val picture: String? = null,
    /** When it came out, as its server writes it. */
    val date: String? = null,
    /** The archive's episode it is. A TubeArchivist video can be one too. */
    val episode: KeptEpisode? = null,
    /** The post's files saved with it, from the downloads folder. */
    val files: List<String> = emptyList(),
    /** What's kept for showing and playing it without its server, by file name. See [KeptPages]. */
    val pages: List<String> = emptyList(),
    /** Whether [pages] is all there is to keep for it. */
    val paged: Boolean = false,
) {
    val whole: Boolean
        get() = state == DONE

    /** The page of what it is: the archive's for an episode, else TubeArchivist's. */
    val route: Route
        get() = episode?.let { Route.JrwiPost(it.item) } ?: Route.Video(id)

    /**
     * Whether [service]'s list of downloads has it: an episode is in the archive's whoever holds
     * it.
     */
    fun listedBy(service: String): Boolean =
        this.service == service || (service == JRWI && episode != null)

    companion object {
        const val TUBEARCHIVIST = "tubearchivist"
        const val JRWI = "jrwi"

        const val WAITING = "waiting"
        const val PAUSED = "paused"
        const val DONE = "done"
        const val FAILED = "failed"
    }
}

@Serializable private data class Record(val downloads: List<Kept> = emptyList())

/**
 * Downloads from the servers into the downloads folder, a few at a time.
 *
 * A video or an episode is kept track of: it's filed under its channel or show as a file any
 * program can open, a record of it is kept beside the files (`.winchivist/downloads.json` in the
 * downloads folder), it goes on from where it stopped after a pause, a dropped connection or a
 * restart, and the players use the copy on this PC. Anything else, such as one of a post's files,
 * is only saved, and listed for this session.
 */
class Downloads(
    private val settings: AppSettings,
    private val api: TaApi,
    /**
     * The whole address of one of the archive's files and the header that signs in there, which is
     * given only for the archiver's own address.
     */
    private val archive: (String) -> Pair<String, String?>? = { null },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val client =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    private val jobs = mutableMapOf<String, Job>()

    /** Downloads that were told to stop and may still hold their file for a moment. */
    private val stopping = mutableMapOf<String, Job>()

    /** Files saved with a download that the record doesn't have yet, as its video is looked up. */
    private val waitingFiles = mutableMapOf<String, List<String>>()

    /** Set by the page renders, whose made-up downloads aren't fetched or checked. */
    private var madeUp = false

    /** Where the files that are only saved come from, while they're on their way. */
    private val plain = mutableMapOf<String, Pair<String, String?>>()

    // A whole season can be asked for at once; its files then come a few at a time
    private val slots = Semaphore(AT_ONCE)

    /** This session's downloads, newest first, with those left unfinished by the last one. */
    val items = mutableStateListOf<LocalDownload>()

    /** The record: every download that's kept track of, by its ID. */
    private val record = mutableStateMapOf<String, Kept>()

    /** The downloads folder the record was read from. */
    private var root = File(settings.downloadFolder)

    /** Told the ID of a download that was just asked for, which then wants its page kept. */
    var whenAsked: (String) -> Unit = {}

    /** Told when downloads left the record, as what was kept for them may go too. */
    var whenGone: () -> Unit = {}
    private val saves = Channel<Unit>(Channel.CONFLATED)

    init {
        read()
        scope.launch {
            for (ask in saves) {
                val folder = root
                val text = json.encodeToString(Record.serializer(), Record(record.values.toList()))
                withContext(Dispatchers.IO) { write(folder, text) }
            }
        }
        scope.launch {
            // Another downloads folder has a record of its own
            snapshotFlow { settings.downloadFolder }.drop(1).collect { changeFolder(File(it)) }
        }
        scope.launch {
            // What the last session left waiting goes on once the app is up
            delay(START_AFTER_MS)
            goOn()
        }
    }

    /** Drops what's gone from the PC, and starts what's waiting, oldest first. */
    private suspend fun goOn() {
        if (madeUp) return
        tidy()
        items
            .filter { it.kept != null && it.state == LocalDownload.State.QUEUED }
            .reversed()
            .forEach { launchJob(it.id) }
    }

    fun stateOf(id: String): LocalDownload? =
        items.firstOrNull { it.id == id } ?: record[id]?.takeIf { it.whole }?.let(::shown)

    /** Whether [id] is waiting or downloading. */
    fun isActive(id: String): Boolean = jobs[id]?.isActive == true

    /** Whether [id] is whole on this PC, by the record. */
    fun isKept(id: String): Boolean = record[id]?.whole == true

    /** The record's line for [id], whatever state it's in. */
    fun keptOf(id: String): Kept? = record[id]

    /** Every line of the record. */
    fun all(): List<Kept> = record.values.toList()

    /** Notes what's kept for showing [id] without its server, and whether that's all of it. */
    fun notePages(id: String, pages: List<String>, whole: Boolean) {
        val entry = record[id] ?: return
        val all = (entry.pages + pages).distinct()
        if (all == entry.pages && (entry.paged || !whole)) return
        record[id] = entry.copy(pages = all, paged = entry.paged || whole)
        save()
    }

    /** What's whole on this PC of what [service]'s list has. */
    fun onThisPc(service: String): List<Kept> =
        record.values.filter { it.whole && it.listedBy(service) }

    /**
     * What's asked for and not whole yet of what [service]'s list has: what's downloading first,
     * then what waits, in the order it's fetched, then what's paused and what failed.
     */
    fun unfinished(service: String): List<LocalDownload> =
        items
            .filter { it.unfinished && it.kept?.listedBy(service) == true }
            .reversed()
            .sortedBy {
                when (it.state) {
                    LocalDownload.State.RUNNING -> 0
                    LocalDownload.State.QUEUED -> 1
                    LocalDownload.State.PAUSED -> 2
                    else -> 3
                }
            }

    /**
     * The file on this PC for [id], when the record has it as whole and it's still there, for
     * playing it.
     */
    fun fileOf(id: String): File? =
        record[id]?.takeIf { it.whole }?.let { fileIn(root, it.file) }?.takeIf { it.isFile }

    /** Downloads a TubeArchivist video, which is the archive's [episode] when it's one. */
    fun start(video: TaVideo, episode: KeptEpisode? = null) {
        val source = video.mediaUrl ?: return
        val extension = source.substringAfterLast('.', "mp4").takeIf { it.length <= 4 } ?: "mp4"
        val channel = video.channel?.name?.takeIf { it.isNotBlank() }
        val folder = listOfNotNull("TubeArchivist", channel?.let(::safeName)).joinToString("/")
        startKept(
            Kept(
                id = video.youtubeId,
                service = Kept.TUBEARCHIVIST,
                title = video.title,
                group = channel.orEmpty(),
                groupId = video.channel?.id,
                file = "$folder/${safeName(video.title)} [${video.youtubeId}].$extension",
                source = source,
                size = video.mediaSize,
                seconds = video.player?.duration?.takeIf { it > 0 },
                picture = video.thumbUrl,
                date = video.published,
                episode = episode,
            )
        )
    }

    /** Looks the video up first, for when only its ID is at hand, such as in a playlist. */
    fun startById(videoId: String, episode: KeptEpisode? = null) {
        if (isActive(videoId) || isKept(videoId)) return
        scope.launch {
            try {
                start(api.getVideo(videoId), episode)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update(
                    LocalDownload(
                        id = videoId,
                        title = videoId,
                        file = File(settings.downloadFolder),
                        state = LocalDownload.State.FAILED,
                        error = e.message ?: e.toString(),
                        route = Route.Video(videoId),
                    )
                )
            }
        }
    }

    /**
     * Downloads what [kept] describes and keeps track of it. Does nothing when it's on this PC
     * already or on its way; one that was paused or failed goes on.
     */
    fun startKept(kept: Kept) {
        val before = record[kept.id]
        if (isActive(kept.id)) return
        if (before?.whole == true && fileOf(kept.id) != null) return
        val entry =
            kept.copy(
                state = Kept.WAITING,
                error = null,
                added = before?.added?.takeIf { it > 0 } ?: System.currentTimeMillis(),
                // What tells the server's file apart stays with the part that's here
                validator = before?.validator.takeIf { before?.file == kept.file },
                files =
                    (before?.files.orEmpty() + waitingFiles.remove(kept.id).orEmpty()).distinct(),
                pages = before?.pages.orEmpty(),
                paged = before?.paged == true,
            )
        record[entry.id] = entry
        save()
        items.removeAll { it.id == entry.id }
        update(shown(entry))
        launchJob(entry.id)
        whenAsked(entry.id)
    }

    /**
     * Saves what's at [url] as [file], asking with [header] as its Authorization when given, and
     * keeps no track of it. Does nothing when [id] is already waiting or downloading.
     */
    fun startFile(
        id: String,
        title: String,
        url: String,
        header: String?,
        file: File,
        route: Route? = null,
    ) {
        if (isActive(id) || record.containsKey(id)) return
        plain[id] = url to header
        update(LocalDownload(id, title, file, route = route))
        launchJob(id)
    }

    /**
     * Notes that [file] was saved with the download [id], so that it goes when the download does.
     */
    fun noteFile(id: String, file: File) {
        val path = pathIn(root, file) ?: return
        val entry = record[id]
        if (entry == null) {
            waitingFiles[id] = waitingFiles[id].orEmpty() + path
            return
        }
        if (path in entry.files) return
        record[id] = entry.copy(files = entry.files + path)
        save()
    }

    /** Stops a download that's kept track of, with what it has kept for going on. */
    fun pause(id: String) {
        val entry = record[id] ?: return
        if (entry.whole) return
        stop(id)
        record[id] = entry.copy(state = Kept.PAUSED)
        save()
        stateOf(id)?.let { update(it.copy(state = LocalDownload.State.PAUSED)) }
    }

    /** Goes on with a download that was paused or failed, from where it stopped. */
    fun resume(id: String) {
        val entry = record[id] ?: return
        if (entry.whole || isActive(id)) return
        record[id] = entry.copy(state = Kept.WAITING, error = null)
        save()
        update(shown(record.getValue(id)))
        launchJob(id)
    }

    fun pauseAll(service: String) =
        unfinished(service).filter { it.active }.forEach { pause(it.id) }

    fun resumeAll(service: String) =
        unfinished(service).filter { !it.active }.forEach { resume(it.id) }

    /** Stops a download. One that's kept track of is taken off this PC with what it had. */
    fun cancel(id: String) {
        if (record.containsKey(id)) remove(id) else jobs.remove(id)?.cancel()
    }

    /**
     * Takes a download that's kept track of off this PC: its file, what it had of one, and the
     * post's files saved with it.
     */
    fun remove(id: String) {
        val entry = record.remove(id) ?: return
        stop(id)
        items.removeAll { it.id == id }
        save()
        val folder = root
        scope.launch {
            // A download that's being stopped lets go of its file first
            stopping.remove(id)?.join()
            withContext(Dispatchers.IO) {
                (listOf(entry.file, entry.file + PART) + entry.files).forEach { path ->
                    val file = fileIn(folder, path) ?: return@forEach
                    file.delete()
                    // The folders made for it go when they're left empty, up to the downloads
                    // folder
                    var parent = file.parentFile
                    while (parent != null && parent != folder && parent.delete()) {
                        parent = parent.parentFile
                    }
                }
            }
            whenGone()
        }
    }

    /** Tells [id]'s download to stop, and keeps hold of it until it has. */
    private fun stop(id: String) {
        val job = jobs.remove(id) ?: return
        job.cancel()
        if (!job.isCompleted) stopping[id] = job
    }

    /** Takes a finished, failed or cancelled download off the list. The file stays. */
    fun forget(id: String) {
        items.removeAll { it.id == id && !it.active && it.kept == null }
    }

    /** Takes everything that's only saved and no longer on its way off the list. The files stay. */
    fun forgetFinished() {
        items.removeAll { !it.active && it.kept == null }
    }

    /**
     * Puts [entries] in the record as they are, for the page renders. Nothing is read or fetched.
     */
    fun showForPreview(entries: List<Kept>, unfinished: List<LocalDownload> = emptyList()) {
        madeUp = true
        entries.forEach { record[it.id] = it }
        unfinished.forEach { download ->
            download.kept?.let { record[it.id] = it }
            update(download)
        }
    }

    private fun launchJob(id: String) {
        if (isActive(id)) return
        jobs[id] = scope.launch {
            try {
                // One that was just stopped lets go of its file first
                stopping.remove(id)?.join()
                slots.withPermit {
                    stateOf(id)?.let { update(it.copy(state = LocalDownload.State.RUNNING)) }
                    val entry = record[id]
                    if (entry != null) fetchKept(entry) else fetchPlain(id)
                }
                record[id]?.let {
                    record[id] = it.copy(state = Kept.DONE, error = null)
                    save()
                }
                plain.remove(id)
                stateOf(id)?.let {
                    update(
                        it.copy(
                            progress = 1f,
                            state = LocalDownload.State.DONE,
                            kept = record[id],
                        )
                    )
                }
            } catch (e: CancellationException) {
                // A pause or a removal has said so already
                if (!record.containsKey(id)) {
                    plain.remove(id)
                    stateOf(id)?.let { update(it.copy(state = LocalDownload.State.CANCELLED)) }
                }
                throw e
            } catch (e: Exception) {
                val reason = e.message ?: e.toString()
                record[id]?.let {
                    record[id] = it.copy(state = Kept.FAILED, error = reason)
                    save()
                }
                plain.remove(id)
                stateOf(id)?.let {
                    update(it.copy(state = LocalDownload.State.FAILED, error = reason))
                }
            }
        }
    }

    /**
     * A download that's kept track of: tried again a few times, each time from where it stopped.
     */
    private suspend fun fetchKept(entry: Kept) {
        val folder = root
        val file = fileIn(folder, entry.file) ?: throw IOException("No place to save it to")
        val (url, header) = addressOf(entry) ?: throw IOException("Its server isn't set up")
        var tries = 0
        while (true) {
            try {
                val now = record[entry.id] ?: entry
                withContext(Dispatchers.IO) {
                    fetch(entry.id, url, header, file, keepPart = true, now.validator, now.size)
                }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Refused) {
                // Asking again gets the same answer
                throw e
            } catch (e: IOException) {
                tries += 1
                if (tries > TRIES) throw e
                delay(TRY_AGAIN_MS * tries)
            }
        }
    }

    private suspend fun fetchPlain(id: String) {
        val (url, header) = plain[id] ?: throw IOException("Nothing to save")
        val file = stateOf(id)?.file ?: throw IOException("No place to save it to")
        withContext(Dispatchers.IO) {
            fetch(id, url, header, file, keepPart = false, validator = null, expected = null)
        }
    }

    /**
     * Fetches [url] into [file] by way of a `.part` file beside it. With [keepPart] the part is
     * left when the download stops, and the next try asks only for what's missing, with [validator]
     * so that a file the server changed since is fetched whole again.
     */
    private suspend fun fetch(
        id: String,
        url: String,
        header: String?,
        file: File,
        keepPart: Boolean,
        validator: String?,
        expected: Long?,
    ) {
        file.parentFile?.mkdirs()
        val partial = File(file.parentFile, file.name + PART)
        var have = if (keepPart) partial.length() else 0L
        val request =
            Request.Builder()
                .url(url)
                .apply {
                    header?.let { header("Authorization", it) }
                    if (have > 0) {
                        header("Range", "bytes=$have-")
                        validator?.let { header("If-Range", it) }
                    }
                }
                .build()
        try {
            val whole =
                client.newCall(request).execute().use { response ->
                    if (response.code == RANGE_NOT_THERE && have > 0) {
                        // Nothing comes after what's here. That's the whole file when it's as long
                        // as the server said, and otherwise a part of a file the server no longer
                        // has
                        if (expected != null && have == expected) return@use true
                        partial.delete()
                        throw IOException("The file on the server changed")
                    }
                    if (response.code in 400..499) throw Refused(response.code)
                    if (!response.isSuccessful) {
                        throw IOException("The server answered with ${response.code}")
                    }
                    val goingOn = response.code == PART_OF_IT && have > 0
                    if (!goingOn) have = 0
                    val body = response.body
                    val total = body.contentLength().takeIf { it >= 0 }?.let { it + have }
                    if (keepPart) {
                        // A strong ETag, or else the date, is what the next try is checked by
                        val now =
                            response.header("ETag")?.takeIf { !it.startsWith("W/") }
                                ?: response.header("Last-Modified")
                        withContext(Dispatchers.Main) {
                            record[id]?.let {
                                record[id] = it.copy(size = total ?: it.size, validator = now)
                                save()
                            }
                        }
                    }
                    FileOutputStream(partial, goingOn).use { out ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(256 * 1024)
                            var lastShown = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                out.write(buffer, 0, read)
                                have += read
                                val time = System.currentTimeMillis()
                                if (time - lastShown > 250) {
                                    lastShown = time
                                    val progress = total?.let { have.toFloat() / it }
                                    val soFar = have
                                    withContext(Dispatchers.Main) {
                                        items
                                            .firstOrNull { it.id == id }
                                            ?.let {
                                                update(it.copy(progress = progress, have = soFar))
                                            }
                                    }
                                }
                            }
                        }
                    }
                    if (total != null && partial.length() != total) {
                        throw IOException("The download stopped before its end")
                    }
                    true
                }
            if (whole) {
                Files.move(partial.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            // A file that's only saved leaves nothing half-saved behind, whatever stopped it
            if (!keepPart && partial.exists()) partial.delete()
        }
    }

    /** Where [entry] is fetched from, and the header for it, which only its own server gets. */
    private fun addressOf(entry: Kept): Pair<String, String?>? {
        if (entry.service == Kept.JRWI) return archive(entry.source)
        val url = api.mediaUrl(entry.source) ?: return null
        val own = api.baseUrl?.toHttpUrlOrNull()
        val asked = url.toHttpUrlOrNull()
        val sameServer =
            own != null &&
                asked != null &&
                own.scheme == asked.scheme &&
                own.host == asked.host &&
                own.port == asked.port
        return url to api.authHeader.takeIf { sameServer }
    }

    /** How the lists show a line of the record. */
    private fun shown(entry: Kept): LocalDownload {
        val file = fileIn(root, entry.file) ?: File(root, "file")
        val have = if (entry.whole) entry.size ?: 0L else 0L
        return LocalDownload(
            id = entry.id,
            title = entry.title,
            file = file,
            progress = if (entry.whole) 1f else null,
            state =
                when (entry.state) {
                    Kept.DONE -> LocalDownload.State.DONE
                    Kept.PAUSED -> LocalDownload.State.PAUSED
                    Kept.FAILED -> LocalDownload.State.FAILED
                    else -> LocalDownload.State.QUEUED
                },
            error = entry.error,
            route = entry.route,
            kept = entry,
            have = have,
        )
    }

    private fun update(download: LocalDownload) {
        val index = items.indexOfFirst { it.id == download.id }
        if (index >= 0) items[index] = download else items.add(0, download)
    }

    private fun save() {
        saves.trySend(Unit)
    }

    /** Reads the downloads folder's record, and lists what it has as not whole yet. */
    private fun read() {
        record.clear()
        val file = File(File(root, RECORD_FOLDER), RECORD_FILE)
        val read =
            try {
                if (file.isFile) json.decodeFromString(Record.serializer(), file.readText())
                else Record()
            } catch (e: Exception) {
                System.err.println("Couldn't read the record of downloads: $e")
                Record()
            }
        read.downloads.forEach { record[it.id] = it }
        read.downloads
            .filter { !it.whole }
            .sortedBy { it.added }
            .forEach { entry ->
                val partial = fileIn(root, entry.file + PART)
                val have = partial?.length() ?: 0L
                val size = entry.size?.takeIf { it > 0 }
                update(shown(entry).copy(have = have, progress = size?.let { have.toFloat() / it }))
            }
    }

    /**
     * Takes out of the record what's no longer on the PC, such as a file that was deleted in
     * Explorer. Not while the downloads folder itself is away, as a drive that isn't plugged in is.
     */
    private suspend fun tidy() {
        val folder = root
        val gone =
            withContext(Dispatchers.IO) {
                if (!folder.isDirectory) return@withContext emptyList<String>()
                record.values
                    .filter { it.whole && fileIn(folder, it.file)?.isFile != true }
                    .map { it.id }
            }
        if (gone.isEmpty() || folder != root) return
        gone.forEach { record.remove(it) }
        save()
        whenGone()
    }

    /**
     * The downloads folder was changed: what's on its way stops, and the new folder's record is
     * read.
     */
    private fun changeFolder(folder: File) {
        if (folder == root) return
        items.filter { it.kept != null }.forEach { jobs.remove(it.id)?.cancel() }
        items.removeAll { it.kept != null }
        root = folder
        read()
        scope.launch { goOn() }
    }

    private fun write(folder: File, text: String) {
        try {
            val home = ownFolder(folder) ?: return
            val fresh = File(home, "$RECORD_FILE.new")
            fresh.writeText(text)
            Files.move(
                fresh.toPath(),
                File(home, RECORD_FILE).toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: Exception) {
            System.err.println("Couldn't write the record of downloads: $e")
        }
    }

    /** The server said no, which it will say again. */
    private class Refused(code: Int) : IOException("The server answered with $code")

    companion object {
        private const val AT_ONCE = 2
        private const val TRIES = 3
        private const val TRY_AGAIN_MS = 5_000L
        private const val START_AFTER_MS = 3_000L
        private const val PART = ".part"
        private const val RECORD_FOLDER = ".winchivist"
        private const val RECORD_FILE = "downloads.json"
        private const val PART_OF_IT = 206
        private const val RANGE_NOT_THERE = 416

        /**
         * The hidden folder in the downloads [folder] that Winchivist keeps its own files in, made
         * when it isn't there. Null when it can't be made, as on a drive that's away.
         */
        fun ownFolder(folder: File): File? {
            val home = File(folder, RECORD_FOLDER)
            if (!home.isDirectory) {
                if (!home.mkdirs()) return null
                runCatching { Files.setAttribute(home.toPath(), "dos:hidden", true) }
            }
            return home
        }

        /** A file name without the characters Windows doesn't allow. */
        fun safeName(title: String): String =
            title
                .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .trimEnd('.')
                .take(150)
                .ifBlank { "file" }

        /** [path], which the record holds, as a file in [folder], and never one outside it. */
        fun fileIn(folder: File, path: String): File? =
            try {
                val base = folder.toPath().toAbsolutePath().normalize()
                val full = base.resolve(path.replace('/', File.separatorChar)).normalize()
                full.toFile().takeIf { path.isNotBlank() && full.startsWith(base) && full != base }
            } catch (e: Exception) {
                null
            }

        /**
         * [file] as the record holds it: from [folder], with forward slashes. Null when outside.
         */
        fun pathIn(folder: File, file: File): String? {
            val base = folder.toPath().toAbsolutePath().normalize()
            val full = file.toPath().toAbsolutePath().normalize()
            if (!full.startsWith(base) || full == base) return null
            return base.relativize(full).joinToString("/") { it.toString() }
        }
    }
}
