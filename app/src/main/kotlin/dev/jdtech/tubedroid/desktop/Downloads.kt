package dev.jdtech.tubedroid.desktop

import androidx.compose.runtime.mutableStateListOf
import dev.jdtech.jellyfin.tubearchivist.TaApi
import dev.jdtech.jellyfin.tubearchivist.TaVideo
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
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
) {
    enum class State {
        /** Waiting for one of the downloads before it to finish. */
        QUEUED,
        RUNNING,
        DONE,
        FAILED,
        CANCELLED,
    }

    /** Still to finish: waiting or downloading. */
    val active: Boolean
        get() = state == State.QUEUED || state == State.RUNNING
}

/**
 * Saves files from the servers straight into a folder on this computer, such as Downloads, a few at
 * a time. Nothing more: they aren't tracked for playing offline, and the list is this session's.
 */
class Downloads(private val settings: AppSettings, private val api: TaApi) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val client =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    private val jobs = mutableMapOf<String, Job>()

    // A whole season can be asked for at once; its files then come a few at a time
    private val slots = Semaphore(AT_ONCE)

    /** This session's downloads, newest first. */
    val items = mutableStateListOf<LocalDownload>()

    fun stateOf(id: String): LocalDownload? = items.firstOrNull { it.id == id }

    /** Whether [id] is waiting or downloading. */
    fun isActive(id: String): Boolean = jobs[id]?.isActive == true

    fun start(video: TaVideo) {
        val url = api.mediaUrl(video.mediaUrl) ?: return
        val extension =
            video.mediaUrl?.substringAfterLast('.', "mp4")?.takeIf { it.length <= 4 } ?: "mp4"
        val folder = File(settings.downloadFolder)
        val file = File(folder, "${safeName(video.title)} [${video.youtubeId}].$extension")
        startFile(
            id = video.youtubeId,
            title = video.title,
            url = url,
            header = api.authHeader,
            file = file,
            route = Route.Video(video.youtubeId),
        )
    }

    /** Looks the video up first, for when only its ID is at hand, such as in a playlist. */
    fun startById(videoId: String) {
        if (isActive(videoId)) return
        scope.launch {
            try {
                start(api.getVideo(videoId))
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
     * Saves what's at [url] as [file], asking with [header] as its Authorization when given. Does
     * nothing when [id] is already waiting or downloading.
     */
    fun startFile(
        id: String,
        title: String,
        url: String,
        header: String?,
        file: File,
        route: Route? = null,
    ) {
        if (isActive(id)) return
        update(LocalDownload(id, title, file, route = route))
        jobs[id] = scope.launch {
            try {
                slots.withPermit {
                    stateOf(id)?.let { update(it.copy(state = LocalDownload.State.RUNNING)) }
                    withContext(Dispatchers.IO) { save(url, header, file, id) }
                }
                stateOf(id)?.let {
                    update(it.copy(progress = 1f, state = LocalDownload.State.DONE))
                }
            } catch (e: CancellationException) {
                stateOf(id)?.let { update(it.copy(state = LocalDownload.State.CANCELLED)) }
                throw e
            } catch (e: Exception) {
                stateOf(id)?.let {
                    update(
                        it.copy(
                            state = LocalDownload.State.FAILED,
                            error = e.message ?: e.toString(),
                        )
                    )
                }
            }
        }
    }

    fun cancel(id: String) {
        jobs.remove(id)?.cancel()
    }

    /** Takes a finished, failed or cancelled download off the list. The file stays. */
    fun forget(id: String) {
        items.removeAll { it.id == id && !it.active }
    }

    /** Takes everything that isn't waiting or downloading off the list. The files stay. */
    fun forgetFinished() {
        items.removeAll { !it.active }
    }

    private suspend fun save(url: String, header: String?, file: File, id: String) {
        val request =
            Request.Builder().url(url).apply { header?.let { header("Authorization", it) } }.build()
        file.parentFile?.mkdirs()
        val partial = File(file.parentFile, file.name + ".part")
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful)
                    throw IOException("The server answered with ${response.code}")
                val body = response.body
                val total = body.contentLength().takeIf { it > 0 }
                partial.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(256 * 1024)
                        var done = 0L
                        var lastShown = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            done += read
                            val now = System.currentTimeMillis()
                            if (total != null && now - lastShown > 250) {
                                lastShown = now
                                val progress = done.toFloat() / total
                                withContext(Dispatchers.Main) {
                                    stateOf(id)?.let { update(it.copy(progress = progress)) }
                                }
                            }
                        }
                    }
                }
            }
            if (file.exists()) file.delete()
            if (!partial.renameTo(file)) throw IOException("Couldn't save ${file.name}")
        } finally {
            // Whatever stopped it, nothing half-saved is left behind
            if (partial.exists()) partial.delete()
        }
    }

    private fun update(download: LocalDownload) {
        val index = items.indexOfFirst { it.id == download.id }
        if (index >= 0) items[index] = download else items.add(0, download)
    }

    companion object {
        private const val AT_ONCE = 2

        /** A file name without the characters Windows doesn't allow. */
        fun safeName(title: String): String =
            title
                .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .trimEnd('.')
                .take(150)
                .ifBlank { "file" }
    }
}
