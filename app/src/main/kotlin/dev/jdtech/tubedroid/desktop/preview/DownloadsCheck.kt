package dev.jdtech.tubedroid.desktop.preview

import com.sun.net.httpserver.HttpServer
import dev.jdtech.jellyfin.tubearchivist.TaApi
import dev.jdtech.tubedroid.desktop.AppSettings
import dev.jdtech.tubedroid.desktop.Downloads
import dev.jdtech.tubedroid.desktop.Kept
import dev.jdtech.tubedroid.desktop.LocalDownload
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.prefs.Preferences
import kotlin.random.Random
import kotlinx.coroutines.delay

/**
 * Tries the downloader for real, against a server on this computer: a download that's paused part
 * way, picked up by a second downloader as after a restart, compared byte for byte with what the
 * server has, and taken off again. The build on GitHub runs it with the page renders, as nothing
 * else runs the downloader before a release. It throws when something isn't as it should be.
 */
internal suspend fun checkDownloads() {
    val size = 6 * 1024 * 1024
    val bytes = ByteArray(size).also { Random(7).nextBytes(it) }
    val asked = CopyOnWriteArrayList<String>()
    val folder = Files.createTempDirectory("winchivist-downloads-check").toFile()
    val settings =
        AppSettings(Preferences.userRoot().node("tubedroid-downloads-check")).apply {
            token = "check"
            downloadFolder = folder.path
        }
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    // A slow answer shouldn't hold up the next request
    val threads = Executors.newCachedThreadPool()
    server.executor = threads
    settings.url = "http://127.0.0.1:${server.address.port}"
    val signedIn = TaApi(settings).authHeader
    server.createContext("/media/episode.bin") { exchange ->
        try {
            if (exchange.requestHeaders.getFirst("Authorization") != signedIn) {
                asked += "without the token"
                exchange.sendResponseHeaders(401, -1)
                exchange.close()
                return@createContext
            }
            val range = exchange.requestHeaders.getFirst("Range")
            val ifRange = exchange.requestHeaders.getFirst("If-Range")
            asked += listOfNotNull(range ?: "the whole file", ifRange).joinToString(" if ")
            val from =
                range?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull()?.takeIf {
                    ifRange == null || ifRange == CHECK_ETAG
                } ?: 0
            exchange.responseHeaders.add("ETag", CHECK_ETAG)
            if (from > 0) {
                exchange.responseHeaders.add("Content-Range", "bytes $from-${size - 1}/$size")
                exchange.sendResponseHeaders(206, (size - from).toLong())
            } else {
                exchange.sendResponseHeaders(200, size.toLong())
            }
            exchange.responseBody.use { out ->
                // Slowly, so that there's time to pause it
                var at = from
                while (at < size) {
                    val count = minOf(64 * 1024, size - at)
                    out.write(bytes, at, count)
                    out.flush()
                    at += count
                    Thread.sleep(25)
                }
            }
        } catch (e: IOException) {
            // The downloader hung up, as a pause makes it
        }
    }
    server.createContext("/media/missing.bin") { exchange ->
        exchange.sendResponseHeaders(404, -1)
        exchange.close()
    }
    server.start()
    try {
        val path = "TubeArchivist/A channel/A video [check].bin"
        val kept =
            Kept(
                id = "check",
                service = Kept.TUBEARCHIVIST,
                title = "A video",
                group = "A channel",
                file = path,
                source = "/media/episode.bin",
            )
        val part = File(folder, "$path.part")
        val record = File(folder, ".winchivist/downloads.json")

        // Paused part way: what's here is kept, and the record says so
        val first = Downloads(settings, TaApi(settings))
        first.startKept(kept)
        until("the download to get going") { (first.stateOf("check")?.have ?: 0) > size / 4 }
        first.pause("check")
        delay(800)
        val had = part.length()
        expect(had in 1 until size.toLong()) { "A paused download left $had of $size bytes" }
        expect(first.stateOf("check")?.state == LocalDownload.State.PAUSED) {
            "A paused download is listed as ${first.stateOf("check")?.state}"
        }
        until("the record to be written") { "paused" in read(record) }

        // As after a restart: another downloader reads the record and goes on from there
        val second = Downloads(settings, TaApi(settings))
        expect(second.keptOf("check")?.state == Kept.PAUSED) {
            "After a restart the record has it as ${second.keptOf("check")?.state}"
        }
        expect(second.stateOf("check")?.have == had) {
            "After a restart it's listed with ${second.stateOf("check")?.have} bytes, not $had"
        }
        second.resume("check")
        until("the download to finish", 40_000) { second.isKept("check") }
        val file = second.fileOf("check")
        expect(file != null && file.readBytes().contentEquals(bytes)) {
            "The file on this computer isn't what the server has"
        }
        expect(!part.exists()) { "The part is still there beside the whole file" }
        expect(asked.any { it.startsWith("bytes=$had-") }) {
            "It didn't ask for the rest alone: $asked"
        }
        expect("without the token" !in asked) { "A request went without the token: $asked" }
        until("the record to say it's whole") { "\"done\"" in read(record) }

        // What the server doesn't have fails at once, and isn't asked for again
        second.startKept(
            kept.copy(
                id = "missing",
                file = "TubeArchivist/A channel/Missing.bin",
                source = "/media/missing.bin",
            )
        )
        until("the missing file to fail") {
            second.stateOf("missing")?.state == LocalDownload.State.FAILED
        }
        expect(second.stateOf("missing")?.error?.contains("404") == true) {
            "The missing file failed with ${second.stateOf("missing")?.error}"
        }
        second.remove("missing")

        // Taking it off this computer takes the file and the folders made for it
        second.remove("check")
        until("the file and its folders to go") {
            file?.exists() != true && !File(folder, "TubeArchivist").exists()
        }
        expect(second.onThisPc(Kept.TUBEARCHIVIST).isEmpty()) { "The record still lists it" }
        println(
            "Downloads check passed: paused at $had of $size bytes, went on from there. " +
                "Asked for: $asked"
        )
    } finally {
        server.stop(0)
        threads.shutdownNow()
        folder.deleteRecursively()
    }
}

private const val CHECK_ETAG = "\"check-1\""

private suspend fun until(what: String, withinMs: Long = 20_000, done: () -> Boolean) {
    val start = System.currentTimeMillis()
    while (!done()) {
        if (System.currentTimeMillis() - start > withinMs) error("Downloads check: no $what")
        delay(50)
    }
}

/** The record's text, or nothing while it's being written anew. */
private fun read(record: File): String = runCatching { record.readText() }.getOrDefault("")

private fun expect(right: Boolean, wrong: () -> String) {
    if (!right) error("Downloads check: ${wrong()}")
}
