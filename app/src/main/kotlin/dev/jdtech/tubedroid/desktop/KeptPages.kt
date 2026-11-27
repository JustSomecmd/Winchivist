package dev.jdtech.tubedroid.desktop

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshotFlow
import dev.jdtech.jellyfin.jrwi.JrwiApi
import dev.jdtech.jellyfin.jrwi.JrwiApiException
import dev.jdtech.jellyfin.jrwi.JrwiComment
import dev.jdtech.jellyfin.jrwi.JrwiItem
import dev.jdtech.jellyfin.jrwi.JrwiPage
import dev.jdtech.jellyfin.jrwi.JrwiPostFile
import dev.jdtech.jellyfin.jrwi.JrwiProgress
import dev.jdtech.jellyfin.jrwi.JrwiTranscript
import dev.jdtech.jellyfin.tubearchivist.TaApi
import dev.jdtech.jellyfin.tubearchivist.TaApiException
import dev.jdtech.jellyfin.tubearchivist.TaComment
import dev.jdtech.jellyfin.tubearchivist.TaPlayer
import dev.jdtech.jellyfin.tubearchivist.TaVideo
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * A save of an episode's place that the archive couldn't be reached for, in the timeline of the
 * post [postId], with when it was made, which the archive goes by once it gets it.
 */
@Serializable
data class HeldSave(
    val postId: Long,
    val position: Double,
    val duration: Double? = null,
    val finished: Boolean? = null,
    val madeAt: String,
)

/**
 * What's kept with the downloads so that they can be shown and played without their servers: what a
 * server answered for a video's or an episode's page, its picture and its subtitles. Each is a file
 * in `.winchivist/pages/` of the downloads folder, named after what was asked.
 *
 * A page asks its server first and gets what's kept here only when the server can't be reached.
 * What the server answers while it can be reached replaces what was kept, so a kept page is as it
 * was when it was last looked at.
 *
 * Also kept here: where things were played to while their server couldn't be told, which it's told
 * once it can be reached again.
 */
class KeptPages(
    private val settings: AppSettings,
    private val downloads: Downloads,
    private val api: TaApi,
    private val jrwi: JrwiApi,
    /** Given the saves the archive is still to get, when they're read at the start. */
    private val heldSaves: suspend (Map<Long, HeldSave>) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val json = Json { ignoreUnknownKeys = true }
    private val client =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

    /** The names of the files kept, so that asking for one doesn't go to the disk. */
    private val names = mutableStateMapOf<String, Boolean>()

    // One download's pages at a time, and never while what's unused is cleared out
    private val oneAtATime = Mutex()
    private val writing = Any()

    @Volatile private var held = Held()

    /** Set by the page renders, which fetch and send nothing. */
    @Volatile private var madeUp = false

    /** Set by the page renders, to draw a page as it looks when its server is away. */
    @Volatile private var shownAsKept = false

    private val folder: File
        get() = File(File(settings.downloadFolder, ".winchivist"), "pages")

    /** [folder] for writing to, made when it isn't there. Null on a drive that's away. */
    private fun made(): File? =
        Downloads.ownFolder(File(settings.downloadFolder))
            ?.let { File(it, "pages") }
            ?.takeIf { it.isDirectory || it.mkdirs() }

    init {
        scope.launch {
            // At the start, and again for another downloads folder
            snapshotFlow { settings.downloadFolder }.collect { look() }
        }
        scope.launch {
            delay(AFTER_START_MS)
            if (madeUp) return@launch
            sendHeld()
            keepMissing()
            sweep()
        }
    }

    /** Whether [error] means the server couldn't be reached, rather than that it said no. */
    fun unreachable(error: Throwable): Boolean =
        error is IOException && error !is TaApiException && error !is JrwiApiException

    /**
     * What [ask] gets from the server, or what's kept under [key] when the server can't be reached,
     * with [onKept] told so. An answer replaces what was kept.
     */
    private suspend fun <T> orKept(
        key: String,
        serializer: KSerializer<T>,
        onKept: () -> Unit = {},
        ask: suspend () -> T,
    ): T {
        val answer =
            try {
                ask()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (!unreachable(e)) throw e
                val kept = withContext(Dispatchers.IO) { read(key, serializer) } ?: throw e
                onKept()
                return kept
            }
        if (names.containsKey(key) && !madeUp) {
            scope.launch(Dispatchers.IO) { write(key, serializer, answer, anew = false) }
        }
        return answer
    }

    /** The copy on this PC of the picture or subtitle file at [url], when one is kept. */
    fun local(url: String?): File? {
        val name = url?.let { fileName(it) } ?: return null
        return if (names.containsKey(name)) File(folder, name) else null
    }

    // Keeping what a download needs

    /** Fetches what [id]'s download needs for being shown and played without its server. */
    fun keepLater(id: String) {
        if (!madeUp) scope.launch { keep(id) }
    }

    /** Returns false when a server couldn't be reached for it, so that it's still to do. */
    private suspend fun keep(id: String): Boolean {
        val kept = downloads.keptOf(id) ?: return true
        return oneAtATime.withLock {
            val keys = linkedSetOf<String>()
            val done =
                withContext(Dispatchers.IO) {
                    try {
                        if (kept.service == Kept.TUBEARCHIVIST) keepVideo(kept, keys)
                        kept.episode?.let { keepEpisode(kept, it, keys) }
                        true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        System.err.println("Couldn't keep the page of ${kept.title}: $e")
                        // What a server refuses, it refuses the next time too
                        !unreachable(e)
                    }
                }
            downloads.notePages(id, keys.toList(), done)
            done
        }
    }

    /** The downloads whose pages aren't kept yet, such as those from before pages were kept. */
    private suspend fun keepMissing() {
        for (kept in downloads.all().filter { !it.paged }) {
            // A server that can't be reached now is left for the next start
            if (!keep(kept.id)) return
        }
    }

    private suspend fun keepVideo(kept: Kept, keys: MutableSet<String>) {
        val video = api.getVideo(kept.id)
        write(videoKey(kept.id), TaVideo.serializer(), video, keys)
        quietly { api.getComments(kept.id) }
            ?.let { write(commentsKey(kept.id), TA_COMMENTS, it, keys) }
        keepFile(api.mediaUrl(video.thumbUrl), keys)
        video.subtitles.forEach { keepFile(api.mediaUrl(it.mediaUrl), keys) }
    }

    private suspend fun keepEpisode(kept: Kept, episode: KeptEpisode, keys: MutableSet<String>) {
        val item = jrwi.getItem(episode.item)
        // Under each of its posts, as its page opens by any of them
        item.posts().forEach { post -> write(itemKey(post), JrwiItem.serializer(), item, keys) }
        keepComments(keys, { itemCommentsKey(item.id, it) }) { jrwi.getComments(item.id, it) }
        quietly { jrwi.getItemFiles(item.id) }
            ?.let { write(itemFilesKey(item.id), POST_FILES, it, keys) }
        // The version that's on this PC has a post of its own when there are two
        val post = if (kept.audio) item.audio?.postId else item.video?.postId
        if (post != null && item.hasBoth) {
            quietly { jrwi.getPost(post) }
                ?.let { write(postKey(post), JrwiItem.serializer(), it, keys) }
            keepComments(keys, { postCommentsKey(post, it) }) { jrwi.getPostComments(post, it) }
            quietly { jrwi.getPostFiles(post) }
                ?.let { write(postFilesKey(post), POST_FILES, it, keys) }
        }
        item.transcriptPost(kept.audio)?.let { id ->
            quietly { jrwi.getTranscript(id) }
                ?.let { write(transcriptKey(id), JrwiTranscript.serializer(), it, keys) }
        }
        keepFile(jrwi.fileUrl(item.thumbnail), keys)
    }

    /** The first pages of comments, each under [key] of its number. */
    private suspend fun keepComments(
        keys: MutableSet<String>,
        key: (Int) -> String,
        ask: suspend (Int) -> JrwiPage<JrwiComment>,
    ) {
        for (page in 1..COMMENT_PAGES) {
            val read = quietly { ask(page) } ?: return
            write(key(page), COMMENT_PAGE, read, keys)
            if (!read.hasMore) return
        }
    }

    /** A picture or a subtitle file, fetched with its own server's token and no other. */
    private fun keepFile(url: String?, keys: MutableSet<String>) {
        val address = url?.toHttpUrlOrNull() ?: return
        val own = api.baseUrl?.toHttpUrlOrNull()
        val header =
            when {
                jrwi.isArchiverUrl(address) -> jrwi.authHeader
                own != null &&
                    own.scheme == address.scheme &&
                    own.host == address.host &&
                    own.port == address.port -> api.authHeader
                else -> null
            }
        val request =
            Request.Builder()
                .url(address)
                .apply { header?.let { header("Authorization", it) } }
                .build()
        val name = fileName(url)
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return
                val home = made() ?: return
                val fresh = File(home, name + NEW)
                fresh.outputStream().use { out -> response.body.byteStream().copyTo(out) }
                Files.move(
                    fresh.toPath(),
                    File(home, name).toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            names[name] = true
            keys += name
        } catch (e: IOException) {
            // The page is kept without this one
        }
    }

    // TubeArchivist's videos

    /**
     * A video from TubeArchivist, or as it's kept on this PC when TubeArchivist can't be reached,
     * with [onKept] told so. A kept one is at the place it was played to here since.
     */
    suspend fun video(id: String, onKept: () -> Unit = {}): TaVideo {
        var kept = false
        val video = orKept(videoKey(id), TaVideo.serializer(), { kept = true }) { api.getVideo(id) }
        if (kept || shownAsKept) {
            onKept()
            val now = held
            return video.withPlace(now.places[id], now.watched[id])
        }
        if (!holdsForTubeArchivist) return video
        // It's back, so it's told what waited for it, which can be about this video
        val mine = id in held.places || id in held.watched
        sendHeld()
        return if (mine) quietly { api.getVideo(id) } ?: video else video
    }

    /** A video's comments, or those kept on this PC when TubeArchivist can't be reached. */
    suspend fun comments(id: String): List<TaComment> =
        orKept(commentsKey(id), TA_COMMENTS) { api.getComments(id) }

    /** TubeArchivist couldn't be told that [id] was played to [seconds], so that waits here. */
    fun holdPlace(id: String, seconds: Double) = change {
        it.copy(places = it.places + (id to seconds))
    }

    /** TubeArchivist couldn't be told that [id] is watched, or isn't, so that waits here. */
    fun holdWatched(id: String, watched: Boolean) = change {
        it.copy(
            places = if (watched) it.places - id else it.places,
            watched = it.watched + (id to watched),
        )
    }

    /**
     * TubeArchivist took where [id] is, so nothing waits for it any more. With [seconds] or
     * [watched], the page kept on this PC says so too.
     */
    fun placeTaken(id: String, seconds: Double? = null, watched: Boolean? = null) {
        if (id in held.places || id in held.watched) {
            change { it.copy(places = it.places - id, watched = it.watched - id) }
        }
        noteInPage(id, seconds, watched)
    }

    private fun noteInPage(id: String, seconds: Double?, watched: Boolean?) {
        val key = videoKey(id)
        if ((seconds == null && watched == null) || !names.containsKey(key) || madeUp) return
        scope.launch(Dispatchers.IO) {
            val video = read(key, TaVideo.serializer()) ?: return@launch
            write(key, TaVideo.serializer(), video.withPlace(seconds, watched), anew = false)
        }
    }

    /** Whether something waits for TubeArchivist. */
    val holdsForTubeArchivist: Boolean
        get() = held.places.isNotEmpty() || held.watched.isNotEmpty()

    /** Tells TubeArchivist what waited for it, as far as it can be reached. */
    suspend fun sendHeld() {
        val now = held
        if (madeUp || (now.places.isEmpty() && now.watched.isEmpty())) return
        try {
            for ((id, watched) in now.watched) {
                try {
                    api.setWatched(id, watched)
                } catch (e: TaApiException) {
                    // It won't take it, which another try won't change
                }
                change { if (it.watched[id] == watched) it.copy(watched = it.watched - id) else it }
                noteInPage(id, seconds = if (watched) 0.0 else null, watched = watched)
            }
            for ((id, seconds) in now.places) {
                try {
                    api.postProgress(id, seconds)
                } catch (e: TaApiException) {}
                // Unless it was played further meanwhile
                change { if (it.places[id] == seconds) it.copy(places = it.places - id) else it }
                noteInPage(id, seconds = seconds, watched = null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // Still out of reach
        }
    }

    // The archive's episodes

    /**
     * An item of the archive by any of its posts, or as it's kept on this PC when the archive can't
     * be reached, with [onKept] told so.
     */
    suspend fun item(postId: Long, onKept: () -> Unit = {}): JrwiItem {
        val item =
            try {
                jrwi.getItem(postId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (!unreachable(e)) throw e
                val kept =
                    withContext(Dispatchers.IO) { read(itemKey(postId), JrwiItem.serializer()) }
                        ?: throw e
                onKept()
                return kept
            }
        if (shownAsKept) onKept()
        // Kept under each of its posts, so each is as it is now
        val keys = item.posts().map { itemKey(it) }.filter { names.containsKey(it) }
        if (keys.isNotEmpty() && !madeUp) {
            scope.launch(Dispatchers.IO) {
                keys.forEach { write(it, JrwiItem.serializer(), item, anew = false) }
            }
        }
        return item
    }

    /** A post on its own, as a version of an episode with two is. */
    suspend fun post(postId: Long): JrwiItem =
        orKept(postKey(postId), JrwiItem.serializer()) { jrwi.getPost(postId) }

    suspend fun itemComments(item: Long, page: Int): JrwiPage<JrwiComment> =
        orKept(itemCommentsKey(item, page), COMMENT_PAGE) { jrwi.getComments(item, page) }

    suspend fun postComments(post: Long, page: Int): JrwiPage<JrwiComment> =
        orKept(postCommentsKey(post, page), COMMENT_PAGE) { jrwi.getPostComments(post, page) }

    suspend fun itemFiles(item: Long): List<JrwiPostFile> =
        orKept(itemFilesKey(item), POST_FILES) { jrwi.getItemFiles(item) }

    suspend fun postFiles(post: Long): List<JrwiPostFile> =
        orKept(postFilesKey(post), POST_FILES) { jrwi.getPostFiles(post) }

    suspend fun transcript(post: Long): JrwiTranscript =
        orKept(transcriptKey(post), JrwiTranscript.serializer()) { jrwi.getTranscript(post) }

    /** The archive couldn't be reached for a save of [item]'s place, which waits here. */
    fun holdSave(item: Long, save: HeldSave) = change { it.copy(saves = it.saves + (item to save)) }

    /** The archive took what waited for [item], or won't have it. */
    fun saveSent(item: Long) {
        if (item in held.saves) change { it.copy(saves = it.saves - item) }
    }

    /**
     * The archive has [item] at [progress] after a save from here, which the pages kept on this PC
     * then say too.
     */
    fun noteProgress(item: Long, progress: JrwiProgress) {
        if (!names.containsKey(itemKey(item)) || madeUp) return
        scope.launch(Dispatchers.IO) {
            val kept = read(itemKey(item), JrwiItem.serializer()) ?: return@launch
            val now = kept.copy(progress = progress)
            kept
                .posts()
                .map { itemKey(it) }
                .filter { names.containsKey(it) }
                .forEach { write(it, JrwiItem.serializer(), now, anew = false) }
        }
    }

    /** Whether the archived video of [item] is whole on this PC, to play without Jellyfin. */
    fun hasVideoOf(item: JrwiItem): Boolean =
        downloads.all().any {
            it.whole && it.service == Kept.JRWI && !it.audio && it.episode?.item == item.id
        }

    /** The archive's episode that TubeArchivist's video [youtubeId] on this PC is, if it's one. */
    suspend fun episodeOfVideo(youtubeId: String): JrwiItem? {
        val episode = downloads.keptOf(youtubeId)?.episode ?: return null
        return withContext(Dispatchers.IO) { read(itemKey(episode.item), JrwiItem.serializer()) }
    }

    /** A show's name as the downloads have it, for when the archive can't be asked. */
    fun showName(showId: String): String? =
        downloads.all().firstNotNullOfOrNull { kept ->
            kept.episode?.takeIf { it.showId == showId }?.show?.takeIf { it.isNotBlank() }
        }

    /**
     * The episode after [item] among those of its show and tab that are on this PC with their
     * audio, by season and number: what the audio player goes on to without the archive.
     */
    suspend fun nextKept(item: JrwiItem): JrwiItem? {
        val episodes =
            downloads
                .all()
                .filter { it.whole && it.audio && it.episode?.showId == item.show }
                .mapNotNull { it.episode?.item }
                .distinct()
        val items =
            withContext(Dispatchers.IO) {
                episodes
                    .mapNotNull { read(itemKey(it), JrwiItem.serializer()) }
                    .filter { it.tab == item.tab }
                    .sortedWith(
                        compareBy(
                            { it.season ?: 0 },
                            { it.episode ?: Int.MAX_VALUE },
                            { it.date.orEmpty() },
                        )
                    )
            }
        val at = items.indexOfFirst { it.id == item.id }
        return if (at >= 0) items.getOrNull(at + 1) else null
    }

    // The files

    @Synchronized
    private fun change(how: (Held) -> Held) {
        val now = how(held)
        if (now == held) return
        held = now
        if (!madeUp) scope.launch(Dispatchers.IO) { write(HELD, Held.serializer(), now) }
    }

    /**
     * Takes everything as given, for the page renders. Nothing is read, fetched or sent. With
     * [asKept], pages are told that what they got is from this PC, as when their server is away.
     */
    fun showForPreview(asKept: Boolean = false) {
        madeUp = true
        shownAsKept = asKept
    }

    /** Reads which files are kept in the downloads folder as it's set now. */
    private suspend fun look() {
        if (madeUp) return
        val (files, waited) =
            withContext(Dispatchers.IO) {
                val files = folder.list()?.filter { !it.endsWith(NEW) && it != HELD }.orEmpty()
                files to (read(HELD, Held.serializer()) ?: Held())
            }
        if (madeUp) return
        names.clear()
        files.forEach { names[it] = true }
        // What was held meanwhile in this session stays
        change { now ->
            Held(
                waited.places + now.places,
                waited.watched + now.watched,
                waited.saves + now.saves,
            )
        }
        if (waited.saves.isNotEmpty()) heldSaves(waited.saves)
    }

    /** Deletes what no download in the record needs any more. */
    fun sweepLater() {
        if (!madeUp) scope.launch { sweep() }
    }

    private suspend fun sweep() {
        oneAtATime.withLock {
            val needed = downloads.all().flatMap { it.pages }.toSet()
            withContext(Dispatchers.IO) {
                // Not while one of them is written anew, which would bring it back
                synchronized(writing) {
                    folder
                        .listFiles()
                        ?.filter { it.name !in needed && it.name != HELD }
                        ?.filter { !it.name.endsWith(NEW) }
                        ?.forEach { if (it.delete()) names.remove(it.name) }
                }
            }
        }
    }

    private fun <T> read(key: String, serializer: KSerializer<T>): T? =
        try {
            val file = File(folder, key)
            if (file.isFile) json.decodeFromString(serializer, file.readText()) else null
        } catch (e: Exception) {
            null
        }

    /**
     * With [anew] false, only over what's kept already: nothing that was cleared out is put back.
     */
    private fun <T> write(
        key: String,
        serializer: KSerializer<T>,
        value: T,
        keys: MutableSet<String>? = null,
        anew: Boolean = true,
    ) {
        try {
            val text = json.encodeToString(serializer, value)
            synchronized(writing) {
                if (!anew && !names.containsKey(key)) return
                val home = made() ?: return
                val fresh = File(home, key + NEW)
                fresh.writeText(text)
                Files.move(
                    fresh.toPath(),
                    File(home, key).toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            if (key != HELD) names[key] = true
            keys?.add(key)
        } catch (e: Exception) {
            System.err.println("Couldn't keep $key: $e")
        }
    }

    /** What [ask] gives, or null when it fails: a page is kept without what can't be had. */
    private suspend fun <T> quietly(ask: suspend () -> T): T? =
        try {
            ask()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    @Serializable
    private data class Held(
        /** Where TubeArchivist's videos were played to, by their ID. */
        val places: Map<String, Double> = emptyMap(),
        /** TubeArchivist's videos that were marked watched, or not watched. */
        val watched: Map<String, Boolean> = emptyMap(),
        /** The archive's episodes, by their item. */
        val saves: Map<Long, HeldSave> = emptyMap(),
    )

    companion object {
        private const val AFTER_START_MS = 8_000L
        private const val COMMENT_PAGES = 5
        private const val HELD = "held.json"
        private const val NEW = ".new"
        private val TA_COMMENTS = ListSerializer(TaComment.serializer())
        private val COMMENT_PAGE = JrwiPage.serializer(JrwiComment.serializer())
        private val POST_FILES = ListSerializer(JrwiPostFile.serializer())

        private fun videoKey(id: String) = "ta-video-${safe(id)}.json"

        private fun commentsKey(id: String) = "ta-comments-${safe(id)}.json"

        private fun itemKey(post: Long) = "jrwi-item-$post.json"

        private fun postKey(post: Long) = "jrwi-post-$post.json"

        private fun itemCommentsKey(item: Long, page: Int) = "jrwi-comments-$item-$page.json"

        private fun postCommentsKey(post: Long, page: Int) = "jrwi-post-comments-$post-$page.json"

        private fun itemFilesKey(item: Long) = "jrwi-files-$item.json"

        private fun postFilesKey(post: Long) = "jrwi-post-files-$post.json"

        private fun transcriptKey(post: Long) = "jrwi-transcript-$post.json"

        /** The item's own post and those of its versions. */
        private fun JrwiItem.posts(): List<Long> =
            listOfNotNull(id, video?.postId, audio?.postId).distinct()

        /** The video as played to [seconds], and as [watched] says, where those are given. */
        private fun TaVideo.withPlace(seconds: Double?, watched: Boolean?): TaVideo {
            if (seconds == null && watched == null) return this
            val before = player ?: TaPlayer()
            val position = seconds ?: before.position
            val length = before.duration
            return copy(
                player =
                    before.copy(
                        watched = watched ?: before.watched,
                        position = position,
                        progress =
                            if (position != null && length > 0) position / length * 100
                            else before.progress,
                    )
            )
        }

        /** The name a picture or subtitle file at [url] is kept under: its address, hashed. */
        private fun fileName(url: String): String {
            val hash =
                MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") {
                    "%02x".format(it)
                }
            val ending =
                url.substringBefore('?')
                    .substringAfterLast('/')
                    .substringAfterLast('.', "")
                    .takeIf { it.length in 1..5 && it.all(Char::isLetterOrDigit) }
            return "file-${hash.take(24)}" + (ending?.let { ".$it" }.orEmpty())
        }

        private fun safe(id: String): String = id.filter { it.isLetterOrDigit() || it in "-_" }
    }
}
