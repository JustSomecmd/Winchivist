package dev.jdtech.tubedroid.desktop.player

import dev.jdtech.jellyfin.jrwi.JrwiItem
import dev.jdtech.jellyfin.tubearchivist.TaApi
import dev.jdtech.jellyfin.tubearchivist.TaChannel
import dev.jdtech.jellyfin.tubearchivist.TaVideo
import dev.jdtech.jellyfin.tubearchivist.loadPlaylist
import dev.jdtech.tubedroid.desktop.AppSettings
import dev.jdtech.tubedroid.desktop.Route
import dev.jdtech.tubedroid.desktop.WatchHistory
import dev.jdtech.tubedroid.desktop.jellyfin.JellyfinClient
import dev.jdtech.tubedroid.desktop.jellyfin.Languages
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.MediaSegmentType
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType

/**
 * What to play: a TubeArchivist video, a movie or episode on Jellyfin, or a JRWI episode straight
 * from its archive.
 */
sealed interface PlayerRequest {
    /** Starts at the beginning even when it was watched part of the way. */
    val fromStart: Boolean

    /**
     * Starts at this moment whatever was saved, such as when the sound is handed over to the video
     * or the other way round.
     */
    val startSeconds: Double?

    /** A TubeArchivist video, and the playlist it's played from, if any. */
    data class Ta(
        val videoId: String,
        val playlistId: String? = null,
        override val fromStart: Boolean = false,
        override val startSeconds: Double? = null,
    ) : PlayerRequest

    /** A movie or episode on Jellyfin. Episodes go on to the next one. */
    data class Jellyfin(
        val itemId: String,
        override val fromStart: Boolean = false,
        override val startSeconds: Double? = null,
    ) : PlayerRequest

    /**
     * A JRWI episode straight from the archive, by any of its posts' IDs: its audio, or with
     * [audio] false its archived video, for when Jellyfin doesn't have it.
     */
    data class Jrwi(
        val postId: Long,
        val audio: Boolean,
        override val fromStart: Boolean = false,
        override val startSeconds: Double? = null,
    ) : PlayerRequest

    /** The same, from [seconds] in. */
    fun startingAt(seconds: Double): PlayerRequest =
        when (this) {
            is Ta -> copy(fromStart = false, startSeconds = seconds)
            is Jellyfin -> copy(fromStart = false, startSeconds = seconds)
            is Jrwi -> copy(fromStart = false, startSeconds = seconds)
        }
}

/** A subtitle file next to the video, which mpv adds as a track. */
internal data class ExternalSubtitle(val url: String, val title: String, val language: String?)

/** One thing to play, from either server, as the player needs it. */
internal data class PlayItem(
    val id: String,
    val title: String,
    /** The channel, or the show and episode. */
    val subtitle: String?,
    val url: String,
    /** The header that signs mpv's requests in. */
    val header: String?,
    val resumeSeconds: Double?,
    val subtitles: List<ExternalSubtitle> = emptyList(),
    val segments: List<SkipSegment> = emptyList(),
    /** The audio track to start with, as mpv numbers them; null leaves it to mpv. */
    val audioTrack: Int? = null,
    /** The subtitle track to start with, as mpv numbers them; 0 is none, null leaves it to mpv. */
    val subtitleTrack: Int? = null,
    val trickplay: Trickplay? = null,
    /** A picture of it, for the audio player. */
    val picture: String? = null,
    /** The page of what plays. */
    val route: Route? = null,
    /** The JRWI archive's episode this is, when it's one, and whether it's that one's audio. */
    val jrwi: JrwiItem? = null,
    val jrwiAudio: Boolean = false,
    /** The post whose transcript follows what plays, timed in its timeline, or null. */
    val transcriptPost: Long? = null,
)

/**
 * Jellyfin's preview pictures for the seek bar: [count] pictures of [width] by [height], one every
 * [intervalMs], laid out [columns] by [rows] in each tile.
 */
internal data class Trickplay(
    val width: Int,
    val height: Int,
    val columns: Int,
    val rows: Int,
    val count: Int,
    val intervalMs: Int,
    val tileUrl: (index: Int) -> String,
)

/** Where the player is and what it plays, as told to the server. */
internal data class PlaybackReport(
    val seconds: Double,
    val paused: Boolean,
    val muted: Boolean = false,
    /** From 0 to 100. */
    val volume: Int = 100,
    /** mpv's audio and subtitle tracks; null when there's none or it's off. */
    val audioId: Int? = null,
    val subtitleId: Int? = null,
    /** How long what plays is, as the player has it, or null while it isn't known. */
    val duration: Double? = null,
)

/** What plays next: its title, and its picture where there is one. */
internal data class UpNext(val title: String, val picture: String?)

/** Where the player's videos come from, and where it reports back to: TubeArchivist or Jellyfin. */
internal interface PlaybackSource {
    suspend fun first(): PlayItem

    /** What plays after [current], if anything, by its title; the whole item loads when it's up. */
    suspend fun nextTitle(current: PlayItem): String?

    /** What plays after [current], with its picture where there is one, for the Up next card. */
    suspend fun upNext(current: PlayItem): UpNext? = nextTitle(current)?.let { UpNext(it, null) }

    /** Whether the stream's quality can be chosen, which Jellyfin's can. */
    val hasQualities: Boolean
        get() = false

    /**
     * [current] again from [seconds] in, as the settings now say to stream it, such as after the
     * quality was changed. Null when there's nothing to load again.
     */
    suspend fun reload(current: PlayItem, seconds: Double): PlayItem? = null

    suspend fun next(current: PlayItem): PlayItem?

    suspend fun started(item: PlayItem, report: PlaybackReport) {}

    suspend fun progress(item: PlayItem, report: PlaybackReport)

    suspend fun stopped(item: PlayItem, report: PlaybackReport) =
        progress(item, report.copy(paused = true))

    /** Played to the end: marked as watched. */
    suspend fun ended(item: PlayItem)

    /** Whether this plays a playlist, which can start over after its last video. */
    val isPlaylist: Boolean
        get() = false

    /** The playlist's name and its videos in order, for the playlist beside the video. */
    val playlistName: String
        get() = ""

    val playlistVideos: List<TaVideo>
        get() = emptyList()

    /** Loads the playlist again, with how far each video was watched. */
    suspend fun refreshPlaylist() {}

    /** One of the playlist's videos, picked from the playlist, or null if it isn't in it. */
    suspend fun playlistItem(videoId: String): PlayItem? = null

    /** Whether the playlist starts over after its last video. */
    var loop: Boolean
        get() = false
        set(_) {}
}

internal class TaPlaybackSource(
    private val api: TaApi,
    private val settings: AppSettings,
    private val history: WatchHistory,
    private val request: PlayerRequest.Ta,
    /** Told when a video plays here, so every device knows what was played last. */
    private val jellyfin: JellyfinClient? = null,
) : PlaybackSource {
    private var playlist: List<TaVideo> = emptyList()
    private var name = ""
    // The channel of each video played, for finding the video in Jellyfin
    private val channels = ConcurrentHashMap<String, TaChannel>()

    override val playlistName: String
        get() = name

    override val isPlaylist: Boolean
        get() = playlist.isNotEmpty()

    override val playlistVideos: List<TaVideo>
        get() = playlist

    override suspend fun refreshPlaylist() {
        val playlistId = request.playlistId ?: return
        val (loaded, videos) = api.loadPlaylist(playlistId)
        name = loaded.name
        if (videos.isNotEmpty()) playlist = videos
    }

    override suspend fun playlistItem(videoId: String): PlayItem? {
        if (playlist.none { it.youtubeId == videoId }) return null
        return api.getVideo(videoId).toPlayItem(fromStart = false)
    }

    override var loop = false

    override suspend fun first(): PlayItem {
        val video = api.getVideo(request.videoId)
        request.playlistId?.let { id ->
            runCatching { api.loadPlaylist(id) }
                .getOrNull()
                ?.let { (loaded, videos) ->
                    name = loaded.name
                    playlist = videos
                }
        }
        val item = video.toPlayItem(fromStart = request.fromStart)
        val startAt = request.startSeconds ?: return item
        return item.copy(resumeSeconds = startAt.takeIf { it > 1 })
    }

    /**
     * Notes the video as played, and the playlist as being at it, for Continue watching. Jellyfin
     * is told too: TubeArchivist doesn't keep when a video was played, so the apps go by Jellyfin's
     * dates to put what was played last first, on whichever device that was.
     */
    override suspend fun started(item: PlayItem, report: PlaybackReport) {
        history.played(item.id)
        request.playlistId?.let { history.updatePlaylist(it, playlistName, item.id) }
        val episode = jellyfinEpisode(item) ?: return
        jellyfin?.notePlayedNow(episode)
    }

    /** Jellyfin's copy of the video, or null when not signed in to Jellyfin or it has none. */
    private suspend fun jellyfinEpisode(item: PlayItem): UUID? {
        val jellyfin = jellyfin?.takeIf { it.isSignedIn } ?: return null
        val channel = channels[item.id]
        return try {
            jellyfin.taEpisodeId(item.id, item.title, channel?.id, channel?.name)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** The playlist's video after [current], or its first after its last when it loops. */
    private fun nextVideo(current: PlayItem): TaVideo? {
        val index = playlist.indexOfFirst { it.youtubeId == current.id }
        if (index < 0) return null
        return playlist.getOrNull(index + 1) ?: playlist.firstOrNull()?.takeIf { loop }
    }

    override suspend fun nextTitle(current: PlayItem): String? = nextVideo(current)?.title

    override suspend fun upNext(current: PlayItem): UpNext? =
        nextVideo(current)?.let { UpNext(it.title, api.mediaUrl(it.thumbUrl)) }

    override suspend fun next(current: PlayItem): PlayItem? {
        val next = nextVideo(current) ?: return null
        // Starting over plays each video from its start again
        val startingOver = playlist.lastOrNull()?.youtubeId == current.id
        // The playlist's entries are short; the player needs the whole video
        return api.getVideo(next.youtubeId).toPlayItem(fromStart = startingOver)
    }

    override suspend fun progress(item: PlayItem, report: PlaybackReport) {
        api.postProgress(item.id, report.seconds)
    }

    /** Where it was left goes to Jellyfin's copy too, as it does when the phone app plays it. */
    override suspend fun stopped(item: PlayItem, report: PlaybackReport) {
        progress(item, report.copy(paused = true))
        val episode = jellyfinEpisode(item) ?: return
        jellyfin?.setPosition(episode, report.seconds)
    }

    override suspend fun ended(item: PlayItem) {
        api.setWatched(item.id, true)
        // Jellyfin's copy is watched too; a playlist carries on whether or not Jellyfin answers
        try {
            jellyfinEpisode(item)?.let { jellyfin?.setPlayed(it, true) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {}
        // The playlist carries on from the next video next time, and is done after its last
        val playlistId = request.playlistId ?: return
        if (playlist.isEmpty()) return
        val next = nextVideo(item)
        if (next != null) {
            history.updatePlaylist(playlistId, playlistName, next.youtubeId)
        } else {
            history.removePlaylist(playlistId)
        }
    }

    private fun TaVideo.toPlayItem(fromStart: Boolean): PlayItem {
        val url = api.mediaUrl(mediaUrl) ?: error("TubeArchivist has no file for this video")
        channel?.let { channels[youtubeId] = it }
        val resume =
            player?.position?.takeIf { position ->
                val length = player?.duration ?: 0.0
                !fromStart &&
                    player?.watched != true &&
                    position > 5 &&
                    (length <= 0 || position < length - 10)
            }
        return PlayItem(
            id = youtubeId,
            title = title,
            subtitle = channel?.name,
            url = url,
            header = api.authHeader?.let { "Authorization: $it" },
            resumeSeconds = resume,
            subtitles =
                subtitles.orEmpty().mapNotNull { subtitle ->
                    val subtitleUrl = api.mediaUrl(subtitle.mediaUrl) ?: return@mapNotNull null
                    ExternalSubtitle(
                        url = subtitleUrl,
                        title =
                            (subtitle.name ?: subtitle.lang) +
                                if (subtitle.source == "auto") " (automatic)" else "",
                        language = subtitle.lang,
                    )
                },
            segments =
                if (settings.sponsorBlock == AppSettings.SPONSORBLOCK_OFF) emptyList()
                else skipSegments(),
            picture = api.mediaUrl(thumbUrl),
            route = Route.Video(youtubeId, request.playlistId),
        )
    }

    private fun TaVideo.skipSegments(): List<SkipSegment> =
        sponsorblock?.segments.orEmpty().mapNotNull { segment ->
            val start = segment.segment.getOrNull(0) ?: return@mapNotNull null
            val end = segment.segment.getOrNull(1) ?: return@mapNotNull null
            // Segments that only mark a point, such as highlights, can't be skipped
            if (end - start < 1.0 || segment.actionType == "poi") return@mapNotNull null
            SkipSegment(
                start,
                end,
                segment.category,
                auto = settings.sponsorBlock == AppSettings.SPONSORBLOCK_AUTO,
            )
        }
}

/**
 * Jellyfin's movies and episodes, played straight from the file on the server, as Jellyfin's own
 * desktop app does: Jellyfin is asked how to play each one first, which gives the file, the tracks
 * the user's settings pick and a play session, and the tracks picked are told back to it.
 */
internal class JellyfinPlaybackSource(
    private val client: JellyfinClient,
    private val settings: AppSettings,
    private val request: PlayerRequest.Jellyfin,
) : PlaybackSource {
    private val items = mutableMapOf<String, BaseItemDto>()

    override val hasQualities: Boolean
        get() = true

    override suspend fun reload(current: PlayItem, seconds: Double): PlayItem? =
        items[current.id]?.toPlayItem(fromStart = false, startAt = seconds)

    /** How each item plays, from what Jellyfin said: its file, play session and tracks. */
    private class Playing(
        val mediaSourceId: String,
        val playSessionId: String?,
        /** Jellyfin's number for each audio track, in mpv's order. */
        val audioIndexes: List<Int>,
        /** Jellyfin's number for each subtitle track, in mpv's order: the file's, then the rest. */
        val subtitleIndexes: List<Int>,
        /** Jellyfin converts it to a lower quality, with one sound track and subtitles as files. */
        val converted: Boolean = false,
    )

    private val playing = mutableMapOf<String, Playing>()

    override suspend fun first(): PlayItem {
        val item = client.item(UUID.fromString(request.itemId))
        return item.toPlayItem(fromStart = request.fromStart, startAt = request.startSeconds)
    }

    /** The episode after this one in the show, across seasons. */
    private suspend fun nextEpisode(current: PlayItem): BaseItemDto? {
        val item = items[current.id] ?: return null
        if (item.type != BaseItemKind.EPISODE) return null
        val seriesId = item.seriesId ?: return null
        val episodes = client.episodes(seriesId)
        val index = episodes.indexOfFirst { it.id == item.id }
        return if (index >= 0) episodes.getOrNull(index + 1) else null
    }

    override suspend fun nextTitle(current: PlayItem): String? =
        nextEpisode(current)?.let { episodeTitle(it, settings.hideEpisodeNumbers) }

    override suspend fun upNext(current: PlayItem): UpNext? =
        nextEpisode(current)?.let {
            UpNext(episodeTitle(it, settings.hideEpisodeNumbers), it.picture())
        }

    override suspend fun next(current: PlayItem): PlayItem? {
        val next = nextEpisode(current) ?: return null
        // The list of episodes leaves out the files; the whole item has them
        return client.item(next.id).toPlayItem(fromStart = true)
    }

    override suspend fun started(item: PlayItem, report: PlaybackReport) {
        client.reportStart(state(item, report))
    }

    override suspend fun progress(item: PlayItem, report: PlaybackReport) {
        client.reportProgress(state(item, report))
    }

    override suspend fun stopped(item: PlayItem, report: PlaybackReport) {
        client.reportStop(state(item, report))
    }

    override suspend fun ended(item: PlayItem) {
        client.setPlayed(UUID.fromString(item.id), true)
    }

    /** What's playing, with mpv's tracks put back into Jellyfin's numbers. */
    private fun state(item: PlayItem, report: PlaybackReport): JellyfinClient.PlayState {
        val info = playing[item.id]
        return JellyfinClient.PlayState(
            itemId = UUID.fromString(item.id),
            seconds = report.seconds,
            paused = report.paused,
            muted = report.muted,
            volume = report.volume,
            mediaSourceId = info?.mediaSourceId,
            playSessionId = info?.playSessionId,
            audioStreamIndex = report.audioId?.let { info?.audioIndexes?.getOrNull(it - 1) },
            subtitleStreamIndex =
                if (report.subtitleId == null) -1
                else info?.subtitleIndexes?.getOrNull(report.subtitleId - 1),
            converted = info?.converted == true,
        )
    }

    private suspend fun BaseItemDto.toPlayItem(
        fromStart: Boolean,
        startAt: Double? = null,
    ): PlayItem {
        items[id.toString()] = this
        val position = JellyfinClient.seconds(userData?.playbackPositionTicks)
        val resume =
            if (startAt != null) startAt.takeIf { it > 1 }
            else position.takeIf { !fromStart && it > 5 }
        val info = client.playbackInfo(id, resume)
        val source = info.mediaSources.firstOrNull() ?: error("Jellyfin has no file for this")
        val sourceId = source.id ?: id.toString().replace("-", "")

        // mpv numbers each kind of track from 1 in the file's order, and the separate subtitle
        // files after the file's own, in the order they're added
        val streams = source.mediaStreams.orEmpty().sortedBy { it.index }
        val audio = streams.filter { it.type == MediaStreamType.AUDIO && !it.isExternal }
        val inFile = streams.filter { it.type == MediaStreamType.SUBTITLE && !it.isExternal }
        val separate = streams.filter {
            it.type == MediaStreamType.SUBTITLE && it.isExternal && !it.deliveryUrl.isNullOrBlank()
        }
        val audioIndexes = audio.map { it.index }
        val subtitleIndexes = (inFile + separate).map { it.index }
        val title =
            if (type == BaseItemKind.EPISODE) episodeTitle(this, settings.hideEpisodeNumbers)
            else name.orEmpty()

        // A language picked in Settings comes before Jellyfin's own choice, when the file has it
        val audioLanguage = Languages.code(settings.audioLanguage)
        val audioIndex =
            audio
                .firstOrNull {
                    audioLanguage != null && Languages.code(it.language) == audioLanguage
                }
                ?.index ?: source.defaultAudioStreamIndex
        val subtitleLanguage = Languages.code(settings.subtitleLanguage)
        fun wanted(tracks: List<MediaStream>): MediaStream? {
            if (subtitleLanguage == null) return null
            val inLanguage = tracks.filter { Languages.code(it.language) == subtitleLanguage }
            // The whole of what's said, rather than only the signs and foreign lines
            return inLanguage.firstOrNull { !it.isForced } ?: inLanguage.firstOrNull()
        }

        // A lower quality is Jellyfin's to make: one stream with the sound track its settings
        // pick, and the subtitles as files. When the server offers none, the file plays as it is
        val quality = StreamQuality.fromKey(settings.streamQuality)
        val converted =
            if (quality == StreamQuality.ORIGINAL) {
                null
            } else {
                try {
                    client.convertedStream(id, sourceId, quality, audioIndex)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    System.err.println("Jellyfin gave no converted stream: $e")
                    null
                }
            }
        if (converted != null) {
            playing[id.toString()] =
                Playing(
                    mediaSourceId = sourceId,
                    playSessionId = info.playSessionId,
                    audioIndexes = listOfNotNull(audioIndex),
                    subtitleIndexes = converted.subtitles.map { it.index },
                    converted = true,
                )
            return PlayItem(
                id = id.toString(),
                title = title,
                subtitle = seriesName ?: productionYear?.toString(),
                url = converted.url,
                header = client.authHeader,
                resumeSeconds = resume,
                subtitles =
                    converted.subtitles.map { stream ->
                        ExternalSubtitle(
                            url = client.serverUrl(stream.deliveryUrl.orEmpty()),
                            title =
                                stream.displayTitle
                                    ?: stream.title
                                    ?: stream.language
                                    ?: "Subtitles",
                            language = stream.language,
                        )
                    },
                segments = skipSegments(),
                subtitleTrack =
                    wanted(converted.subtitles)
                        ?.let { converted.subtitles.indexOf(it) }
                        ?.takeIf { it >= 0 }
                        ?.plus(1),
                trickplay = trickplay(sourceId),
                picture = picture(),
                route = Route.JellyfinItem(id.toString()),
            )
        }
        playing[id.toString()] =
            Playing(sourceId, info.playSessionId, audioIndexes, subtitleIndexes)

        return PlayItem(
            id = id.toString(),
            title = title,
            subtitle = seriesName ?: productionYear?.toString(),
            url = client.streamUrl(id, sourceId, info.playSessionId),
            header = client.authHeader,
            resumeSeconds = resume,
            subtitles =
                separate.map { stream ->
                    ExternalSubtitle(
                        url = client.serverUrl(stream.deliveryUrl.orEmpty()),
                        title =
                            stream.displayTitle ?: stream.title ?: stream.language ?: "Subtitles",
                        language = stream.language,
                    )
                },
            segments = skipSegments(),
            // Jellyfin picks the tracks by the user's language and subtitle settings there, unless
            // a language is picked here
            audioTrack = audioIndex?.let { audioIndexes.indexOf(it) }?.takeIf { it >= 0 }?.plus(1),
            subtitleTrack =
                when (
                    val index =
                        wanted(inFile + separate)?.index ?: source.defaultSubtitleStreamIndex
                ) {
                    null -> null
                    -1 -> 0
                    else -> subtitleIndexes.indexOf(index).takeIf { it >= 0 }?.plus(1)
                },
            trickplay = trickplay(sourceId),
            picture = picture(),
            route = Route.JellyfinItem(id.toString()),
        )
    }

    /** The item's own picture, or its show's for an episode without one. */
    private fun BaseItemDto.picture(): String? =
        client.imageUrl(id, ImageType.PRIMARY, imageTags?.get(ImageType.PRIMARY), PICTURE_WIDTH)
            ?: seriesId?.let { client.imageUrl(it, ImageType.PRIMARY, seriesPrimaryImageTag, 600) }

    /**
     * Intros, credits and the like that Jellyfin knows of, to skip with a button or without being
     * asked, as the settings say.
     */
    private suspend fun BaseItemDto.skipSegments(): List<SkipSegment> {
        if (settings.mediaSegments == AppSettings.SPONSORBLOCK_OFF) return emptyList()
        val auto = settings.mediaSegments == AppSettings.SPONSORBLOCK_AUTO
        return try {
            client.segments(id).mapNotNull { segment ->
                val category =
                    when (segment.type) {
                        MediaSegmentType.INTRO -> "intro"
                        MediaSegmentType.OUTRO -> "outro"
                        MediaSegmentType.RECAP -> "recap"
                        MediaSegmentType.PREVIEW -> "preview"
                        MediaSegmentType.COMMERCIAL -> "commercial"
                        else -> return@mapNotNull null
                    }
                val start = JellyfinClient.seconds(segment.startTicks)
                val end = JellyfinClient.seconds(segment.endTicks)
                if (end - start < 1.0) null else SkipSegment(start, end, category, auto)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Older servers have none
            emptyList()
        }
    }

    /** The smallest of the preview picture sizes, which is plenty for the seek bar. */
    private fun BaseItemDto.trickplay(sourceId: String): Trickplay? {
        if (!settings.trickplay) return null
        val sizes = trickplay?.get(sourceId) ?: trickplay?.values?.firstOrNull() ?: return null
        val info = sizes.values.minByOrNull { it.width } ?: return null
        if (info.thumbnailCount <= 0 || info.interval <= 0) return null
        val itemId = id
        return Trickplay(
            width = info.width,
            height = info.height,
            columns = info.tileWidth,
            rows = info.tileHeight,
            count = info.thumbnailCount,
            intervalMs = info.interval,
        ) { index ->
            client.trickplayTileUrl(itemId, sourceId, info.width, index)
        }
    }
}

private const val PICTURE_WIDTH = 800

/** Such as "S2:E5 · The Long Night", or the name alone with [hideNumbers]. */
internal fun episodeTitle(item: BaseItemDto, hideNumbers: Boolean): String {
    if (hideNumbers) return item.name.orEmpty()
    val number =
        listOfNotNull(
                item.parentIndexNumber?.let { "S$it" },
                item.indexNumber?.let { "E$it" },
            )
            .joinToString(":")
    return listOf(number, item.name.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
}
