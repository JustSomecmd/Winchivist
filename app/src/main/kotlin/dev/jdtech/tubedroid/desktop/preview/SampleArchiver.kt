package dev.jdtech.tubedroid.desktop.preview

import com.sun.net.httpserver.HttpServer
import dev.jdtech.jellyfin.jrwi.JrwiArchiveEntry
import dev.jdtech.jellyfin.jrwi.JrwiAudio
import dev.jdtech.jellyfin.jrwi.JrwiAuthor
import dev.jdtech.jellyfin.jrwi.JrwiBackground
import dev.jdtech.jellyfin.jrwi.JrwiBadge
import dev.jdtech.jellyfin.jrwi.JrwiCheckProgress
import dev.jdtech.jellyfin.jrwi.JrwiCheckRun
import dev.jdtech.jellyfin.jrwi.JrwiComment
import dev.jdtech.jellyfin.jrwi.JrwiCommentStats
import dev.jdtech.jellyfin.jrwi.JrwiCommenter
import dev.jdtech.jellyfin.jrwi.JrwiContinue
import dev.jdtech.jellyfin.jrwi.JrwiContinueList
import dev.jdtech.jellyfin.jrwi.JrwiCounts
import dev.jdtech.jellyfin.jrwi.JrwiDisk
import dev.jdtech.jellyfin.jrwi.JrwiEvent
import dev.jdtech.jellyfin.jrwi.JrwiFile
import dev.jdtech.jellyfin.jrwi.JrwiFileCount
import dev.jdtech.jellyfin.jrwi.JrwiImage
import dev.jdtech.jellyfin.jrwi.JrwiItem
import dev.jdtech.jellyfin.jrwi.JrwiLink
import dev.jdtech.jellyfin.jrwi.JrwiPage
import dev.jdtech.jellyfin.jrwi.JrwiPlace
import dev.jdtech.jellyfin.jrwi.JrwiPodcast
import dev.jdtech.jellyfin.jrwi.JrwiPodcastEpisode
import dev.jdtech.jellyfin.jrwi.JrwiPodcastTrigger
import dev.jdtech.jellyfin.jrwi.JrwiPostFile
import dev.jdtech.jellyfin.jrwi.JrwiPostFiles
import dev.jdtech.jellyfin.jrwi.JrwiPreview
import dev.jdtech.jellyfin.jrwi.JrwiProblem
import dev.jdtech.jellyfin.jrwi.JrwiProgress
import dev.jdtech.jellyfin.jrwi.JrwiSeason
import dev.jdtech.jellyfin.jrwi.JrwiSheet
import dev.jdtech.jellyfin.jrwi.JrwiShow
import dev.jdtech.jellyfin.jrwi.JrwiStats
import dev.jdtech.jellyfin.jrwi.JrwiStatus
import dev.jdtech.jellyfin.jrwi.JrwiSync
import dev.jdtech.jellyfin.jrwi.JrwiTab
import dev.jdtech.jellyfin.jrwi.JrwiTagCount
import dev.jdtech.jellyfin.jrwi.JrwiVideo
import java.awt.Color
import java.awt.GradientPaint
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import javax.imageio.ImageIO
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/** A made-up JRWI archive: shows, episodes with both versions, posts, comments and files. */
internal object SampleArchive {
    val shows =
        listOf(
            JrwiShow(
                id = "riptide",
                name = "Riptide",
                description = "A pirate adventure at sea.",
                poster = "/files/site/riptide.webp",
                banner = "/files/site/riptide.webp",
                image = "/files/site/riptide.webp",
                itemCount = 312,
                latest = "2026-11-07T00:00:00+00:00",
            ),
            JrwiShow(
                id = "prime-defenders",
                name = "Prime Defenders",
                image = "/files/site/prime.webp",
                poster = "/files/site/prime.webp",
                itemCount = 157,
                latest = "2023-10-21T00:00:00+00:00",
            ),
            JrwiShow(
                id = "upp",
                name = "Unwitness Protection Program",
                // No picture of its own, so the logo
                image = "/files/site/logo.webp",
                itemCount = 64,
                latest = "2025-03-02T00:00:00+00:00",
            ),
            JrwiShow(
                id = "other",
                name = "Other posts",
                image = "/files/site/logo.webp",
                itemCount = 41,
                latest = "2026-10-30T00:00:00+00:00",
            ),
        )

    private val titles =
        listOf(
            "Through the Fire",
            "Paid In Blood",
            "Blue Jay",
            "The Long Night",
            "Starting From Zero",
            "Hole in the Sea",
        )

    /** Episodes of Riptide, the newest first, each with a video and an audio version. */
    val episodes =
        (0 until 14).map { index ->
            val number = 136 - index
            val videoId = 300L - index * 4
            val audioId = videoId - 2
            JrwiItem(
                id = videoId,
                title = "${titles[index % titles.size]} | Riptide #$number",
                date =
                    "2026-11-${(24 - index).coerceAtLeast(1).toString().padStart(2, '0')}T00:00:00+00:00",
                show = "riptide",
                tab = "episodes",
                tabs = listOf("episodes"),
                places = listOf(JrwiPlace("riptide", "episodes")),
                kind = "episode",
                season = 1,
                episode = number,
                pinned = index == 0,
                thumbnail = "/files/posts/$videoId/thumbnail.webp",
                duration = 11_695.0 - index * 300,
                video =
                    JrwiVideo(
                        postId = videoId,
                        jellyfinPath =
                            "/media/jrwi/Riptide/Season 01/Riptide - S01E$number [jrwi-$videoId].mp4",
                        url = "/api/video/$videoId",
                        height = 1080,
                        size = 4_123_456_789L - index * 100_000_000L,
                        duration = 11_712.0 - index * 300,
                    ),
                audio =
                    JrwiAudio(
                        postId = audioId,
                        url = "/api/audio/$audioId",
                        mime = "audio/mp4",
                        size = 187_654_321L,
                        duration = 11_695.0 - index * 300,
                    ),
                sync = JrwiSync(offset = -17.2, checked = true),
                progress =
                    when (index % 4) {
                        1 ->
                            JrwiProgress(
                                videoId,
                                4_210.0,
                                11_412.0,
                                false,
                                "2026-11-21T00:12:00+00:00",
                            )
                        3 -> JrwiProgress(videoId, 0.0, 10_812.0, true, "2026-11-18T00:12:00+00:00")
                        else -> null
                    },
                commentCount = 12 + index,
                likeCount = 469 - index * 11,
                archived = true,
                bodyHtml =
                    "<p>The crew sails into the storm, and <strong>nothing</strong> goes to plan.</p>" +
                        "<h3>In this episode</h3><ul><li>A deal with the harbour master</li>" +
                        "<li>Chip finds the map</li></ul>" +
                        "<p>Transcript: <a href=\"https://docs.example.com/transcript\">read it here</a>.</p>",
                bodyText =
                    "The crew sails into the storm, and nothing goes to plan.\n\nIn this episode\n" +
                        "A deal with the harbour master\nChip finds the map\n\nTranscript: read it here.",
                originalUrl = "https://jrwishow.com/supporters/videos/$videoId",
                tags = listOf("RIPTIDE", "Riptide Early Access"),
                images = listOf(JrwiImage("/files/posts/$videoId/images/01.webp")),
                attachments =
                    listOf(
                        JrwiFile(
                            name = "Zombie Character Sheets.pdf",
                            url =
                                "/files/posts/$videoId/attachments/Zombie%20Character%20Sheets.pdf",
                            size = 1_234_567,
                        ),
                        JrwiFile(
                            name = "Loot table.xlsx",
                            url = "/files/posts/$videoId/attachments/Loot%20table.xlsx",
                            size = 48_000,
                        ),
                    ),
                links =
                    listOf(
                        JrwiLink(
                            "Episode transcription here.",
                            "https://docs.example.com/transcript",
                        )
                    ),
                version = "video",
                paired = true,
                itemId = videoId,
                transcript = true,
            )
        }

    /** Posts without a video or audio: art and announcements. */
    val posts =
        (0 until 6).map { index ->
            val id = 180L - index
            JrwiItem(
                id = id,
                title =
                    if (index % 2 == 0) "Fan art round-up, week ${12 - index}"
                    else "Merch drop and schedule update",
                date = "2026-10-${(28 - index * 3).toString().padStart(2, '0')}T00:00:00+00:00",
                show = if (index % 2 == 0) "riptide" else "other",
                tab = if (index % 2 == 0) "art" else "posts",
                tabs = listOf(if (index % 2 == 0) "art" else "posts"),
                kind = if (index % 2 == 0) "art" else "post",
                commentCount = 4 + index,
                likeCount = 120 - index * 9,
                archived = true,
                bodyHtml =
                    "<p>Thanks to everyone who sent something in this week.</p><p>More soon.</p>",
                bodyText = "Thanks to everyone who sent something in this week.\n\nMore soon.",
                tags = listOf(if (index % 2 == 0) "Art" else "announcement"),
                images =
                    if (index % 2 == 0)
                        (1..(3 + index)).map { JrwiImage("/files/posts/$id/images/0$it.webp") }
                    else emptyList(),
                itemId = id,
            )
        }

    val comments =
        listOf(
            JrwiComment(
                id = "300:1",
                postId = 300,
                author =
                    JrwiAuthor(
                        id = "1234567",
                        name = "jazzyferin",
                        badges =
                            listOf(
                                JrwiBadge(name = "4-month member", icon = "🔥"),
                                JrwiBadge(name = "Verified"),
                            ),
                        color = "rgb(247, 221, 210)",
                    ),
                date = "2026-11-11T00:00:00+00:00",
                dateText = "13d",
                bodyHtml =
                    "<p>For anyone not reading the description: the transcript is linked there.</p>",
                bodyText =
                    "For anyone not reading the description: the transcript is linked there.",
                likeCount = 327,
                pinned = true,
                link = "https://jrwishow.com/supporters/videos/300?cid=1",
                replies =
                    listOf(
                        JrwiComment(
                            id = "300:2",
                            postId = 300,
                            author = JrwiAuthor(name = "chipfan", color = "#9ad1ff"),
                            date = "2026-11-22T00:00:00+00:00",
                            dateText = "2d",
                            bodyText = "Thank you, I'd missed it.",
                            likeCount = 12,
                        )
                    ),
            ),
            JrwiComment(
                id = "300:3",
                postId = 300,
                author = JrwiAuthor(name = "Gillion", color = "rgb(160, 230, 190)"),
                date = "2026-11-20T00:00:00+00:00",
                dateText = "4d",
                bodyText = "That ending. I was not ready for it, and neither was the table.",
                likeCount = 88,
            ),
        )

    val files =
        listOf(
            JrwiPostFile(
                name = "Zombie Character Sheets.pdf",
                role = "attachment",
                type = "pdf",
                mime = "application/pdf",
                size = 1_234_567,
                url = "/files/posts/300/attachments/Zombie%20Character%20Sheets.pdf",
                path = "posts/300/attachments/Zombie Character Sheets.pdf",
                postId = 300,
                preview = "/api/preview/posts/300/attachments/Zombie%20Character%20Sheets.pdf",
            ),
            JrwiPostFile(
                name = "Loot table.xlsx",
                role = "attachment",
                type = "table",
                size = 48_000,
                url = "/files/posts/300/attachments/Loot%20table.xlsx",
                path = "posts/300/attachments/Loot table.xlsx",
                postId = 300,
                preview = "/api/preview/posts/300/attachments/Loot%20table.xlsx",
            ),
            JrwiPostFile(
                name = "Through the Fire on YouTube",
                role = "link",
                type = "youtube",
                saved = false,
                source = "https://www.youtube.com/watch?v=CzZ13VPQOcw",
                youtubeId = "CzZ13VPQOcw",
                postId = 300,
            ),
        )

    val status =
        JrwiStatus(
            running = true,
            step = "posts",
            phase = "Saving new and changed posts",
            detail = "Through the Fire | Riptide #136 · downloading the video, 42% of ~4.1 GB",
            progress = JrwiCheckProgress(done = 2, total = 12, current = 0.42, unit = "post"),
            lastCheckStarted = "2026-11-24T08:00:00+00:00",
            lastCheckFinished = "2026-11-24T04:04:12+00:00",
            nextCheck = "2026-11-24T12:00:00+00:00",
            lastResult = "ok",
            loginOk = true,
            counts = JrwiCounts(items = 2104, archived = 2001, locked = 87, problems = 3),
            disk = JrwiDisk(archiveBytes = 912_345_678_901L, freeBytes = 2_345_678_901_234L),
            background =
                JrwiBackground(
                    step = "transcripts",
                    phase = "Timing transcripts",
                    detail = "Paid In Blood | Riptide #134 · hearing, 42%",
                    progress =
                        JrwiCheckProgress(done = 2, total = 40, current = 0.42, unit = "episode"),
                ),
        )

    val stats =
        JrwiStats(
            checks =
                (0 until 5).map {
                    JrwiCheckRun(
                        started = "2026-11-${24 - it}T04:00:00+00:00",
                        finished = "2026-11-${24 - it}T04:04:12+00:00",
                        result = "ok",
                        newItems = 3 - it % 3,
                        archived = 5,
                    )
                },
            files =
                listOf(
                    JrwiFileCount("image", ".webp", 4200, 3_100_000_000L),
                    JrwiFileCount("pdf", ".pdf", 312, 1_234_567_890L),
                    JrwiFileCount("audio", ".m4a", 412, 77_000_000_000L),
                    JrwiFileCount("video", ".mp4", 190, 780_000_000_000L),
                ),
            comments =
                JrwiCommentStats(
                    total = 18_234,
                    replies = 4_012,
                    commenters = 812,
                    top =
                        listOf(
                            JrwiCommenter(
                                name = "jazzyferin",
                                color = "rgb(247, 221, 210)",
                                count = 120,
                                likes = 3400,
                            ),
                            JrwiCommenter(
                                name = "Gillion",
                                color = "rgb(160, 230, 190)",
                                count = 96,
                                likes = 2100,
                            ),
                        ),
                ),
        )

    val podcast =
        JrwiPodcast(
            setUp = true,
            lastRead = "2026-11-24T08:45:00+00:00",
            ok = true,
            title = "JRWI Members",
            count = 412,
            matched = 398,
            withTime = 398,
            everyMinutes = 15,
            lastTrigger =
                JrwiPodcastTrigger(
                    at = "2026-11-22T11:02:00+00:00",
                    titles = listOf("Through the Fire | Riptide #136 (AUDIO ONLY)"),
                ),
            latest =
                listOf(
                    JrwiPodcastEpisode(
                        "Through the Fire | Riptide #136 (AUDIO ONLY)",
                        "2026-11-22T11:00:00+00:00",
                        298,
                        11_695.0,
                    ),
                    JrwiPodcastEpisode(
                        "Paid In Blood | Riptide #135 (AUDIO ONLY)",
                        "2026-11-15T11:00:00+00:00",
                        294,
                        11_395.0,
                    ),
                ),
        )
}

/**
 * Answers like the JRWI archiver's API, with [SampleArchive], and draws a picture for every file.
 */
internal class SampleArchiver {
    private val json = Json { encodeDefaults = true }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val url = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val query = exchange.requestURI.rawQuery.orEmpty()
            val (type, body) =
                if (
                    path.startsWith("/files/") ||
                        (path.startsWith("/api/preview/") && query.contains("page="))
                ) {
                    "image/png" to picture(path)
                } else {
                    "application/json" to (answer(path) ?: "{}").toByteArray()
                }
            exchange.responseHeaders.add("Content-Type", type)
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    fun stop() = server.stop(0)

    private fun <T> page(serializer: KSerializer<T>, items: List<T>): String =
        json.encodeToString(JrwiPage.serializer(serializer), JrwiPage(items, 1, 50, items.size))

    private fun <T> list(serializer: KSerializer<T>, items: List<T>): String =
        json.encodeToString(ListSerializer(serializer), items)

    private val everything = SampleArchive.episodes + SampleArchive.posts

    /** A post as the feed gives it: whole, with its first comments. */
    private fun feedEntry(post: JrwiItem): JsonObject {
        val whole = json.encodeToJsonElement(JrwiItem.serializer(), post).jsonObject
        return buildJsonObject {
            whole.forEach { (key, value) -> put(key, value) }
            put(
                "top_comments",
                json.encodeToJsonElement(
                    ListSerializer(JrwiComment.serializer()),
                    if (post.id == 300L) SampleArchive.comments else emptyList(),
                ),
            )
            put("more_comments", JsonPrimitive(post.id == 300L))
        }
    }

    private fun answer(path: String): String? {
        val parts = path.trim('/').split('/')
        if (parts.firstOrNull() != "api") return null
        val id = parts.getOrNull(2)?.toLongOrNull()
        return when (parts.getOrNull(1)) {
            "ping" ->
                """{"name":"jrwi-archiver","version":"0.14.9","contract":"0.27","features":""" +
                    """["post_progress","tags","continue","stop","feed","stats","reparse",""" +
                    """"podcast","watched","transcript","transcript_jobs"],"watched_left":120.0}"""
            "status" -> json.encodeToString(JrwiStatus.serializer(), SampleArchive.status)
            "stats" -> json.encodeToString(JrwiStats.serializer(), SampleArchive.stats)
            "podcast" -> json.encodeToString(JrwiPodcast.serializer(), SampleArchive.podcast)
            "tags" ->
                list(
                    JrwiTagCount.serializer(),
                    listOf(
                        JrwiTagCount("Art", 96),
                        JrwiTagCount("RIPTIDE", 312),
                        JrwiTagCount("Riptide Early Access", 14),
                        JrwiTagCount("announcement", 41),
                    ),
                )
            "events" ->
                list(
                    JrwiEvent.serializer(),
                    listOf(
                        JrwiEvent(
                            "new_item",
                            "2026-11-24T04:03:30+00:00",
                            300,
                            "Through the Fire | Riptide #136",
                        ),
                        JrwiEvent(
                            "video_handed_over",
                            "2026-11-23T04:02:11+00:00",
                            288,
                            "Blue Jay | Riptide #133",
                        ),
                        JrwiEvent(
                            "check_failed",
                            "2026-11-21T16:00:40+00:00",
                            null,
                            null,
                            "The site didn't answer",
                        ),
                    ),
                )
            "problems" ->
                list(
                    JrwiProblem.serializer(),
                    listOf(
                        JrwiProblem(
                            212,
                            "Campaign maps",
                            "partial",
                            listOf("A Drive folder isn't shared with everyone who has the link"),
                        )
                    ),
                )
            "shows" ->
                when {
                    parts.size == 2 -> list(JrwiShow.serializer(), SampleArchive.shows)
                    parts.getOrNull(3) == "items" ->
                        page(JrwiItem.serializer(), SampleArchive.episodes)
                    else ->
                        SampleArchive.shows
                            .firstOrNull { it.id == parts[2] }
                            ?.let { show ->
                                val seasons =
                                    listOf(
                                        JrwiSeason(1, "Diseased Residents", 24),
                                        JrwiSeason(2, "Empty Graves", 40),
                                    )
                                json.encodeToString(
                                    JrwiShow.serializer(),
                                    show.copy(
                                        tabs =
                                            listOf(
                                                JrwiTab("episodes", "Episodes", 64, seasons),
                                                JrwiTab("aftershow", "Just Rolled With It", 56),
                                                JrwiTab("extras", "Extras", 52),
                                                JrwiTab("art", "Art", 12),
                                            ),
                                        seasons = seasons,
                                    ),
                                )
                            }
                }
            "feed" -> buildJsonObject {
                    put(
                        "items",
                        kotlinx.serialization.json.JsonArray(
                            (SampleArchive.episodes.take(2) +
                                    SampleArchive.posts.take(2) +
                                    SampleArchive.episodes.drop(2).take(4))
                                .map(::feedEntry)
                        ),
                    )
                    put("page", JsonPrimitive(1))
                    put("page_size", JsonPrimitive(20))
                    put("total", JsonPrimitive(8))
                }
                    .toString()
            "continue" ->
                json.encodeToString(
                    JrwiContinueList.serializer(),
                    JrwiContinueList(
                        SampleArchive.episodes
                            .filter { it.progress?.finished == false }
                            .mapIndexed { index, item ->
                                val audio = index % 2 == 1
                                JrwiContinue(
                                    item,
                                    item.progress!!.copy(
                                        postId = if (audio) item.audio?.postId else item.id
                                    ),
                                )
                            }
                    ),
                )
            "items",
            "posts" ->
                when {
                    id == null -> page(JrwiItem.serializer(), everything)
                    parts.getOrNull(3) == "comments" ->
                        page(JrwiComment.serializer(), SampleArchive.comments)
                    parts.getOrNull(3) == "files" ->
                        json.encodeToString(
                            JrwiPostFiles.serializer(),
                            JrwiPostFiles(SampleArchive.files),
                        )
                    else ->
                        // By either version's post
                        (everything.firstOrNull { it.id == id || it.audio?.postId == id }
                                ?: everything.first())
                            .let { json.encodeToString(JrwiItem.serializer(), it) }
                }
            "progress" ->
                json.encodeToString(
                    JrwiProgress.serializer(),
                    everything.firstOrNull { it.id == id || it.audio?.postId == id }?.progress
                        ?: JrwiProgress(postId = id),
                )
            "preview" ->
                json.encodeToString(
                    JrwiPreview.serializer(),
                    if (path.endsWith(".pdf")) {
                        JrwiPreview(
                            name = "Zombie Character Sheets.pdf",
                            type = "pdf",
                            pages = 5,
                            pageUrl = "$path?page={n}",
                        )
                    } else {
                        JrwiPreview(
                            name = "Loot table.xlsx",
                            type = "table",
                            sheets =
                                listOf(
                                    JrwiSheet(
                                        name = "Party",
                                        rows =
                                            listOf(listOf("Name", "Class", "Gold")) +
                                                (1..14).map {
                                                    listOf(
                                                        "Sailor $it",
                                                        if (it % 2 == 0) "Rogue" else "Cleric",
                                                        "${it * 37}",
                                                    )
                                                },
                                    ),
                                    JrwiSheet(
                                        name = "Loot",
                                        rows = listOf(listOf("Item", "Worth")),
                                    ),
                                ),
                            entries = listOf(JrwiArchiveEntry("maps/harbour.png", 1234)),
                        )
                    },
                )
            else -> null
        }
    }

    /** A gradient in a colour picked from the path, so each picture looks different. */
    private fun picture(path: String): ByteArray {
        val avatar = path.contains("avatar")
        val width = if (avatar) 96 else 640
        val height = if (avatar) 96 else 360
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val hue = (path.hashCode() and 0xFFFF) / 65535f
        val graphics = image.createGraphics()
        graphics.paint =
            GradientPaint(
                0f,
                0f,
                Color.getHSBColor(hue, 0.5f, 0.7f),
                width.toFloat(),
                height.toFloat(),
                Color.getHSBColor((hue + 0.12f) % 1f, 0.6f, 0.3f),
            )
        graphics.fillRect(0, 0, width, height)
        graphics.dispose()
        return ByteArrayOutputStream().use {
            ImageIO.write(image, "png", it)
            it.toByteArray()
        }
    }
}
