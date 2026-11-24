package dev.jdtech.tubedroid.desktop.jrwi

import dev.jdtech.jellyfin.jrwi.JrwiFile
import dev.jdtech.jellyfin.jrwi.JrwiItem
import dev.jdtech.jellyfin.jrwi.JrwiPostFile
import dev.jdtech.tubedroid.desktop.AppGraph
import dev.jdtech.tubedroid.desktop.Downloads
import dev.jdtech.tubedroid.desktop.Navigator
import dev.jdtech.tubedroid.desktop.Route
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// A post's files: telling what kind each is, saving them to this computer, and opening them in
// another program

/**
 * One of a post's attachments or documents as the file lists have them, for where there's no file
 * list, such as in the feed: its type goes by its name.
 */
fun JrwiFile.asPostFile(role: String): JrwiPostFile {
    val type = fileTypeByName(name.ifBlank { url })
    // The address under /files/, still encoded as an address, which the preview's address takes
    val encoded = url.substringAfter("/files/", "").takeIf { it.isNotEmpty() }
    return JrwiPostFile(
        name = name.ifBlank { url.substringAfterLast('/') },
        role = role,
        type = type,
        size = size,
        url = url,
        path = encoded?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() },
        preview = encoded?.takeIf { type in PREVIEWED }?.let { "/api/preview/$it" },
    )
}

private val PREVIEWED = setOf("pdf", "table", "document", "text", "archive")

/** How to show a file, by its name's ending, as the archiver names the types. */
private fun fileTypeByName(name: String): String =
    when (name.substringBefore('?').substringAfterLast('.', "").lowercase()) {
        "png",
        "jpg",
        "jpeg",
        "gif",
        "webp",
        "bmp",
        "avif" -> "image"
        "pdf" -> "pdf"
        "xlsx",
        "xlsm",
        "csv",
        "tsv" -> "table"
        "docx",
        "pptx" -> "document"
        "txt",
        "md",
        "json",
        "srt",
        "vtt",
        "log" -> "text"
        "zip" -> "archive"
        "mp3",
        "m4a",
        "ogg",
        "opus",
        "wav",
        "flac" -> "audio"
        "mp4",
        "mkv",
        "webm",
        "mov" -> "video"
        else -> "other"
    }

/** The folder under the downloads folder that the archive's files are saved to. */
fun jrwiDownloadFolder(app: AppGraph, show: String? = null): File {
    val base = File(app.settings.downloadFolder, "JRWI")
    return if (show.isNullOrBlank()) base else File(base, Downloads.safeName(show))
}

/** What a download of the archive's file at [address] is known by in the list of downloads. */
fun jrwiDownloadId(app: AppGraph, address: String): String? =
    app.jrwi.api.fileUrl(address)?.toHttpUrlOrNull()?.let { "jrwi:$it" }

/**
 * Saves a file of the archive, at [address], to this computer as [name] in [folder]. The token goes
 * in a header, and only when the address is the archiver's own.
 */
fun saveJrwiFile(
    app: AppGraph,
    name: String,
    address: String,
    folder: File = jrwiDownloadFolder(app),
    title: String = name,
    route: Route? = null,
): File? {
    val api = app.jrwi.api
    val url = api.fileUrl(address)?.toHttpUrlOrNull() ?: return null
    val file = File(folder, Downloads.safeName(name))
    app.downloads.startFile(
        id = jrwiDownloadId(app, address) ?: return null,
        title = title,
        url = url.toString(),
        header = api.authHeader?.takeIf { api.isArchiverUrl(url) },
        file = file,
        route = route,
    )
    return file
}

/** Saves a file and says where it's going. */
fun saveJrwiFileAndSay(app: AppGraph, navigator: Navigator, name: String, address: String) {
    val file = saveJrwiFile(app, name, address)
    navigator.message(
        if (file != null) "Saving $name to ${file.parent}" else "The archiver isn't set up"
    )
}

/**
 * Fetches a file of the archive into a folder of the app's own and opens it with the program
 * Windows opens such files with. The other program gets the file itself, never the archiver's
 * token.
 */
suspend fun openJrwiFileElsewhere(app: AppGraph, name: String, address: String) {
    val file =
        withContext(Dispatchers.IO) {
            val folder =
                File(
                    System.getenv("LOCALAPPDATA") ?: System.getProperty("java.io.tmpdir"),
                    "Winchivist/opened",
                )
            folder.mkdirs()
            // What was opened before is cleared out after a day
            val dayAgo = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
            folder.listFiles()?.forEach { if (it.lastModified() < dayAgo) it.delete() }
            val target = File(folder, Downloads.safeName(name))
            app.jrwi.api.openFile(address).use { response ->
                if (!response.isSuccessful) {
                    throw IOException("The archiver answered ${response.code}")
                }
                target.outputStream().use { out -> response.body.byteStream().copyTo(out) }
            }
            target
        }
    withContext(Dispatchers.IO) { java.awt.Desktop.getDesktop().open(file) }
}

/** A name for a post's picture when saved: its post's ID and its own name, such as 290-01.webp. */
fun pictureSaveName(path: String): String {
    val name = path.substringBefore('?').substringAfterLast('/')
    val decoded = runCatching { URLDecoder.decode(name, "UTF-8") }.getOrDefault(name)
    val postId = POST_IN_PATH.find(path)?.groupValues?.get(1)
    return if (postId != null) "$postId-$decoded" else decoded
}

private val POST_IN_PATH = Regex("""/posts/(\d+)/""")

/** Whether the post has any files of its own to keep with a download. */
fun JrwiItem.hasFiles(): Boolean =
    images.isNotEmpty() || attachments.isNotEmpty() || documents.isNotEmpty()
