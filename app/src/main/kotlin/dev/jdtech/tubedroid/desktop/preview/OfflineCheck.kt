package dev.jdtech.tubedroid.desktop.preview

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.jdtech.tubedroid.desktop.AppGraph
import dev.jdtech.tubedroid.desktop.AppSettings
import dev.jdtech.tubedroid.desktop.player.PlaybackReport
import dev.jdtech.tubedroid.desktop.player.PlayerRequest
import dev.jdtech.tubedroid.desktop.player.playbackSource
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.prefs.Preferences
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay

/**
 * Tries the app without its servers for real: a TubeArchivist video and an episode's audio are
 * downloaded from two servers on this computer, which then stop answering. The page of each and the
 * players must still get them from this PC, where they're played to must wait here, also across a
 * restart, and the servers must be told once they answer again. The build on GitHub runs it with
 * the page renders. It throws when something isn't as it should be.
 */
internal suspend fun checkOfflineUse() {
    val folder = Files.createTempDirectory("winchivist-offline-check").toFile()
    val kept = File(folder, ".winchivist/pages")
    val away = AtomicBoolean(false)
    // What each server was told, and what it was asked without its token
    val told = CopyOnWriteArrayList<String>()
    val threads = Executors.newCachedThreadPool()
    val tubeArchivist = checkServer(threads, away, told, "Token ta-check", ::tubeArchivistAnswer)
    val archive = checkServer(threads, away, told, "Token jrwi-check", ::archiveAnswer)
    val settings =
        AppSettings(Preferences.userRoot().node("tubedroid-offline-check")).apply {
            url = "http://127.0.0.1:${tubeArchivist.address.port}"
            token = "ta-check"
            jrwiUrl = "http://127.0.0.1:${archive.address.port}"
            jrwiToken = "jrwi-check"
            downloadFolder = folder.path
            hearWhen = AppSettings.HEAR_BY_HAND
            hearAskAfter = null
        }
    try {
        // Downloaded with the servers there, which brings what their pages need
        val first = AppGraph(settings)
        val episode = first.jrwi.item(EPISODE)
        first.downloads.startById(VIDEO)
        first.jrwiDownloads.download(episode, audio = true, withFiles = false, showName = SHOW)
        val audioId = first.jrwiDownloads.idOf(episode, audio = true) ?: fail("The audio has no ID")
        until("both downloads to be whole") {
            first.downloads.isKept(VIDEO) && first.downloads.isKept(audioId)
        }
        until("their pages to be kept") {
            first.downloads.keptOf(VIDEO)?.paged == true &&
                first.downloads.keptOf(audioId)?.paged == true
        }
        val files = kept.list().orEmpty().sorted()
        for (name in
            listOf(
                "ta-video-$VIDEO.json",
                "ta-comments-$VIDEO.json",
                "jrwi-item-$EPISODE.json",
                "jrwi-item-$AUDIO_POST.json",
                "jrwi-post-$AUDIO_POST.json",
                "jrwi-transcript-$AUDIO_POST.json",
            )) {
            expect(name in files) { "$name wasn't kept. Kept: $files" }
        }
        expect(files.count { it.startsWith("file-") } == 3) {
            "Not the video's picture, its subtitles and the episode's picture were kept: $files"
        }

        // The servers stop answering
        away.set(true)
        var fromThisPc = false
        val video = first.pages.video(VIDEO) { fromThisPc = true }
        expect(fromThisPc && video.title == "A video" && video.player?.position == 30.0) {
            "Without TubeArchivist the video's page is $video (from this PC: $fromThisPc)"
        }
        expect(first.pages.comments(VIDEO).size == 1) { "The video's comments weren't kept" }

        // The video player gets the file, its subtitles and its place from this PC
        val videoSource = playbackSource(first, PlayerRequest.Ta(VIDEO))
        val videoPlay = videoSource.first()
        expect(File(videoPlay.url).isFile && videoPlay.resumeSeconds == 30.0) {
            "Without TubeArchivist the video plays ${videoPlay.url} from ${videoPlay.resumeSeconds}"
        }
        expect(videoPlay.subtitles.singleOrNull()?.let { File(it.url).isFile } == true) {
            "Its subtitles come from ${videoPlay.subtitles.map { it.url }}"
        }
        quietly { videoSource.progress(videoPlay, PlaybackReport(seconds = 200.0, paused = true)) }
        val waiting = first.pages.video(VIDEO).player?.position
        expect(waiting == 200.0) {
            "Played to 200 without TubeArchivist, its page has it at $waiting"
        }

        // The audio player gets the episode, its file, its transcript and its place from this PC
        val audioSource = playbackSource(first, PlayerRequest.Jrwi(EPISODE, audio = true))
        val audioPlay = audioSource.first()
        expect(File(audioPlay.url).isFile && audioPlay.resumeSeconds == 60.0) {
            "Without the archive the audio plays ${audioPlay.url} from ${audioPlay.resumeSeconds}"
        }
        expect(audioPlay.subtitle == SHOW) { "Its show is named ${audioPlay.subtitle}" }
        val lines = audioPlay.transcriptPost?.let { first.jrwi.transcript(it).lines }
        expect(lines?.singleOrNull()?.text == "Hello") { "Its transcript is $lines" }
        quietly { audioSource.progress(audioPlay, PlaybackReport(seconds = 500.0, paused = true)) }
        expect(first.jrwi.lastKnown(episode, audio = true)?.position == 500.0) {
            "Played to 500 without the archive, this PC has it at " +
                "${first.jrwi.lastKnown(episode, audio = true)?.position}"
        }
        expect(told.none { "progress" in it }) { "A server that's away was told: $told" }
        until("what waits to be written down") {
            val held = runCatching { File(kept, "held.json").readText() }.getOrDefault("")
            "200" in held && "500" in held
        }

        // As after a restart, still without the servers: what waits is still there
        val second = AppGraph(settings)
        until("the second start to read what's kept") {
            second.jrwi.lastKnown(episode, audio = true)?.position == 500.0
        }
        val stillWaiting = second.pages.video(VIDEO).player?.position
        expect(stillWaiting == 200.0) { "After a restart the video is at $stillWaiting" }
        var episodeFromThisPc = false
        val again = second.jrwi.item(AUDIO_POST) { episodeFromThisPc = true }
        expect(episodeFromThisPc && again.title == "An episode") {
            "After a restart the episode's page is ${again.title} (from this PC: " +
                "$episodeFromThisPc)"
        }

        // The servers answer again, and are told
        away.set(false)
        fromThisPc = false
        val back = second.pages.video(VIDEO) { fromThisPc = true }
        expect(!fromThisPc) { "With TubeArchivist back, the page still came from this PC" }
        until("TubeArchivist to be told where the video is") {
            told.any { it.startsWith("POST /api/video/$VIDEO/progress/") && "200" in it }
        }
        expect(back.player?.position == 200.0) {
            "With TubeArchivist back, the video is at ${back.player?.position}"
        }
        val saved = second.jrwi.progress(episode, audio = true)
        until("the archive to be told where the episode is") {
            told.any {
                it.startsWith("PUT /api/progress/$AUDIO_POST") && "500" in it && "updated_at" in it
            }
        }
        expect(saved?.position == 500.0) { "With the archive back, it has the episode at $saved" }
        expect(told.none { it.startsWith("Without its token") }) {
            "A request went without its server's token, or with the other's: $told"
        }

        // Taken off this PC, they take what was kept for them along
        second.downloads.remove(VIDEO)
        second.downloads.remove(audioId)
        until("what was kept for them to go") {
            kept.list().orEmpty().none { it.startsWith("ta-") || it.startsWith("jrwi-") }
        }
        println(
            "Offline check passed: ${files.size} files kept with two downloads, both played " +
                "from this PC without their servers, and the servers were told afterwards: " +
                told.filter { it.startsWith("POST") || it.startsWith("PUT") }
        )
    } finally {
        tubeArchivist.stop(0)
        archive.stop(0)
        threads.shutdownNow()
        folder.deleteRecursively()
    }
}

private const val VIDEO = "check"
private const val EPISODE = 300L
private const val AUDIO_POST = 301L
private const val SHOW = "Just Roll With It"

/** What a server answers: a type and a body, or null for a 404. */
private typealias Answer = (method: String, path: String, body: String) -> Pair<String, ByteArray>?

/**
 * A server on this computer that answers with [answer] to requests that carry [token], notes what
 * it's told in [told], and hangs up without an answer while [away] is set.
 */
private fun checkServer(
    threads: java.util.concurrent.ExecutorService,
    away: AtomicBoolean,
    told: MutableList<String>,
    token: String,
    answer: Answer,
): HttpServer {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.executor = threads
    server.createContext("/") { exchange: HttpExchange ->
        try {
            if (away.get()) return@createContext
            val path = exchange.requestURI.path
            val body = exchange.requestBody.readBytes().decodeToString()
            val method = exchange.requestMethod
            // The archiver's ping is open to all
            if (path != "/api/ping" && exchange.requestHeaders.getFirst("Authorization") != token) {
                told += "Without its token: $method $path"
                exchange.sendResponseHeaders(401, -1)
                return@createContext
            }
            if (method != "GET") told += "$method $path $body"
            val found = answer(method, path, body)
            if (found == null) {
                exchange.sendResponseHeaders(404, -1)
                return@createContext
            }
            exchange.responseHeaders.add("Content-Type", found.first)
            exchange.sendResponseHeaders(200, found.second.size.toLong())
            exchange.responseBody.use { it.write(found.second) }
        } catch (e: java.io.IOException) {
            // The app hung up
        } finally {
            // Without an answer, this closes the connection, as a server that's away would
            exchange.close()
        }
    }
    server.start()
    return server
}

private const val JSON = "application/json"

// Where the made-up TubeArchivist has the video, as told last
@Volatile private var videoPlace = 30.0

private fun tubeArchivistAnswer(
    method: String,
    path: String,
    body: String,
): Pair<String, ByteArray>? =
    when (path) {
        "/api/video/$VIDEO/" ->
            JSON to
                ("""{"youtube_id":"$VIDEO","title":"A video","media_size":4096,""" +
                        """"vid_thumb_url":"/cache/videos/c/$VIDEO.jpg",""" +
                        """"media_url":"/media/$VIDEO.mp4",""" +
                        """"channel":{"channel_id":"UCcheck","channel_name":"A channel"},""" +
                        """"player":{"watched":false,"duration":600.0,"position":$videoPlace},""" +
                        """"subtitles":[{"lang":"en","name":"English","source":"user",""" +
                        """"ext":"vtt","media_url":"/media/$VIDEO.en.vtt"}]}""")
                    .toByteArray()
        "/api/video/$VIDEO/comment/" ->
            JSON to
                """[{"comment_id":"c1","comment_text":"A comment","comment_author":"Someone"}]"""
                    .toByteArray()
        "/api/video/$VIDEO/progress/" -> {
            Regex(""""position":\s*([0-9.]+)""").find(body)?.let {
                videoPlace = it.groupValues[1].toDouble()
            }
            JSON to "{}".toByteArray()
        }
        "/api/watched/" -> JSON to "{}".toByteArray()
        "/cache/videos/c/$VIDEO.jpg" -> "image/jpeg" to ByteArray(64) { 1 }
        "/media/$VIDEO.en.vtt" -> "text/vtt" to "WEBVTT\n".toByteArray()
        "/media/$VIDEO.mp4" -> "video/mp4" to ByteArray(4096) { 2 }
        else -> null
    }

// Where the made-up archive has the episode's audio, as told last
@Volatile private var audioPlace = 60.0

private fun archiveAnswer(method: String, path: String, body: String): Pair<String, ByteArray>? {
    val noComments = """{"items":[],"page":1,"page_size":50,"total":0}"""
    val text =
        when (path) {
            "/api/ping" -> """{"name":"check","version":"0.23.0","features":[]}"""
            "/api/shows" -> """[{"id":"jrwi","name":"$SHOW"}]"""
            "/api/items/$EPISODE",
            "/api/items/$AUDIO_POST" ->
                """{"id":$EPISODE,"title":"An episode","show":"jrwi","tab":"episodes",""" +
                    """"kind":"episode","season":1,"episode":2,"transcript":true,""" +
                    """"thumbnail":"/files/posts/$EPISODE/thumbnail.webp",""" +
                    """"video":{"post_id":$EPISODE,"url":"/files/posts/$EPISODE/video.mp4",""" +
                    """"duration":3600.0},""" +
                    """"audio":{"post_id":$AUDIO_POST,"mime":"audio/mpeg","size":2048,""" +
                    """"url":"/files/posts/$AUDIO_POST/audio.mp3","duration":3600.0},""" +
                    """"progress":{"post_id":$AUDIO_POST,"position":$audioPlace,""" +
                    """"finished":false,"updated_at":"2026-11-01T10:00:00+00:00"}}"""
            "/api/items/$EPISODE/comments",
            "/api/posts/$AUDIO_POST/comments" -> noComments
            "/api/items/$EPISODE/files",
            "/api/posts/$AUDIO_POST/files" -> """{"files":[]}"""
            "/api/posts/$AUDIO_POST" ->
                """{"id":$AUDIO_POST,"title":"An episode","item_id":$EPISODE,"version":"audio"}"""
            "/api/transcript/$AUDIO_POST" ->
                """{"post_id":$AUDIO_POST,"aligned":true,""" +
                    """"lines":[{"start":1.0,"end":2.0,"speaker":"GM","text":"Hello"}]}"""
            "/api/progress/$AUDIO_POST" -> {
                if (method == "PUT") {
                    Regex(""""position":\s*([0-9.]+)""").find(body)?.let {
                        audioPlace = it.groupValues[1].toDouble()
                    }
                }
                """{"post_id":$AUDIO_POST,"position":$audioPlace,"finished":false,""" +
                    """"updated_at":"2026-11-27T12:00:00+00:00"}"""
            }
            "/files/posts/$AUDIO_POST/audio.mp3" -> return "audio/mpeg" to ByteArray(2048) { 3 }
            "/files/posts/$EPISODE/thumbnail.webp" -> return "image/webp" to ByteArray(64) { 4 }
            else -> return null
        }
    return JSON to text.toByteArray()
}

private suspend fun until(what: String, withinMs: Long = 20_000, done: suspend () -> Boolean) {
    val start = System.currentTimeMillis()
    while (!done()) {
        if (System.currentTimeMillis() - start > withinMs) fail("no $what")
        delay(50)
    }
}

/** Runs what's expected to fail while the servers are away, as the players do. */
private suspend fun quietly(run: suspend () -> Unit) {
    try {
        run()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {}
}

private fun expect(right: Boolean, wrong: () -> String) {
    if (!right) fail(wrong())
}

private fun fail(what: String): Nothing = error("Offline check: $what")
