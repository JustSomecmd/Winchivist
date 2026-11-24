package dev.jdtech.tubedroid.desktop.jellyfin

import dev.jdtech.jellyfin.tubearchivist.TaPlaylistSessionStore
import dev.jdtech.jellyfin.tubearchivist.TaPlaylistSessionSync
import dev.jdtech.tubedroid.desktop.AppSettings
import dev.jdtech.tubedroid.desktop.player.StreamQuality
import java.net.InetAddress
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jellyfin.sdk.api.client.extensions.authenticationApi
import org.jellyfin.sdk.api.client.extensions.displayPreferenceApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import org.jellyfin.sdk.api.client.extensions.mediaSegmentApi
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.client.extensions.showApi
import org.jellyfin.sdk.api.client.extensions.suggestionApi
import org.jellyfin.sdk.api.client.extensions.userDataApi
import org.jellyfin.sdk.api.client.extensions.userViewApi
import org.jellyfin.sdk.api.sockets.subscribe
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.api.AuthenticateUserByName
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CodecProfile
import org.jellyfin.sdk.model.api.CodecType
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.EncodingContext
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.LibraryChangedMessage
import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import org.jellyfin.sdk.model.api.PlaybackInfoResponse
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.ProfileCondition
import org.jellyfin.sdk.model.api.ProfileConditionType
import org.jellyfin.sdk.model.api.ProfileConditionValue
import org.jellyfin.sdk.model.api.RepeatMode
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.api.SubtitleProfile
import org.jellyfin.sdk.model.api.TranscodingProfile
import org.jellyfin.sdk.model.api.UpdateUserItemDataDto

/**
 * Jellyfin, through its own Kotlin library, as the phone app uses it. Signing in keeps the server,
 * the user and an access token in the settings; the password isn't kept.
 */
class JellyfinClient(private val settings: AppSettings) {
    private val jellyfin = createJellyfin {
        clientInfo = ClientInfo(name = "Winchivist", version = appVersion())
        deviceInfo = DeviceInfo(id = settings.deviceId, name = computerName())
    }

    private val api =
        jellyfin.createApi(baseUrl = settings.jellyfinUrl, accessToken = settings.jellyfinToken)

    val isSignedIn: Boolean
        get() =
            !settings.jellyfinUrl.isNullOrBlank() &&
                !settings.jellyfinToken.isNullOrBlank() &&
                !settings.jellyfinUserId.isNullOrBlank()

    val userName: String?
        get() = settings.jellyfinUserName

    val serverUrl: String?
        get() = settings.jellyfinUrl

    private val userId: UUID
        get() = UUID.fromString(settings.jellyfinUserId ?: error("Not signed in to Jellyfin"))

    /**
     * Where the playlists being watched are kept for the phone to see too: the signed-in user's
     * display settings. Null when not signed in.
     */
    val playlistSessionStore: TaPlaylistSessionStore?
        get() = if (isSignedIn) PlaylistSessionStore() else null

    private inner class PlaylistSessionStore : TaPlaylistSessionStore {
        override suspend fun read(): String? =
            withContext(Dispatchers.IO) { preferences().customPrefs[TaPlaylistSessionSync.KEY] }

        override suspend fun write(text: String) =
            withContext(Dispatchers.IO) {
                val current = preferences()
                api.displayPreferenceApi.updateDisplayPreferences(
                    displayPreferencesId = TaPlaylistSessionSync.PREFERENCES_ID,
                    userId = userId,
                    client = TaPlaylistSessionSync.CLIENT,
                    data =
                        current.copy(
                            customPrefs = current.customPrefs + (TaPlaylistSessionSync.KEY to text)
                        ),
                )
                Unit
            }

        private suspend fun preferences() =
            api.displayPreferenceApi
                .getDisplayPreferences(
                    displayPreferencesId = TaPlaylistSessionSync.PREFERENCES_ID,
                    userId = userId,
                    client = TaPlaylistSessionSync.CLIENT,
                )
                .content
    }

    /** Signs in, keeping the token for later. Throws when the server or the details are wrong. */
    suspend fun signIn(url: String, username: String, password: String) =
        withContext(Dispatchers.IO) {
            val address = url.trim().trimEnd('/')
            api.update(baseUrl = address, accessToken = null)
            val result by
                api.authenticationApi.authenticateUserByName(
                    data = AuthenticateUserByName(username = username, pw = password)
                )
            val token = result.accessToken ?: error("Jellyfin didn't give a token")
            val user = result.user ?: error("Jellyfin didn't say who signed in")
            api.update(baseUrl = address, accessToken = token)
            settings.jellyfinUrl = address
            settings.jellyfinToken = token
            settings.jellyfinUserId = user.id.toString()
            settings.jellyfinUserName = user.name
        }

    fun signOut() {
        settings.jellyfinToken = null
        settings.jellyfinUserId = null
        settings.jellyfinUserName = null
        api.update(accessToken = null)
    }

    /**
     * Tells each time the server says something was added to or taken out of its library, over the
     * connection Jellyfin keeps open to its clients for that. The connection is held for as long as
     * this is listened to, and made again by itself when it drops.
     */
    fun libraryChanges(): Flow<LibraryChange> =
        api.webSocket
            .subscribe<LibraryChangedMessage>()
            .mapNotNull { message ->
                val change = message.data ?: return@mapNotNull null
                LibraryChange(
                        added = change.itemsAdded.isNotEmpty(),
                        removed = change.itemsRemoved.isNotEmpty(),
                    )
                    .takeIf { it.added || it.removed }
            }
            // Without it, what's new is still found by looking now and then
            .catch { e -> System.err.println("Jellyfin's live updates: $e") }

    class LibraryChange(val added: Boolean, val removed: Boolean)

    /** The user's libraries, such as Movies and Shows. */
    suspend fun libraries(): List<BaseItemDto> =
        withContext(Dispatchers.IO) { api.userViewApi.getUserViews(userId).content.items }

    /** Movies and episodes started but not finished. */
    suspend fun resume(): List<BaseItemDto> =
        withContext(Dispatchers.IO) {
            api.libraryApi
                .getResumeItems(
                    userId,
                    limit = 16,
                    includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE),
                )
                .content
                .items
        }

    /** The next episode of each show being watched, or of just the one show. */
    suspend fun nextUp(seriesId: UUID? = null): List<BaseItemDto> =
        withContext(Dispatchers.IO) {
            api.showApi
                .getNextUp(userId, limit = 16, seriesId = seriesId, enableResumable = false)
                .content
                .items
        }

    /**
     * What was added to a library last. With [episodes], every new episode by itself, newest first,
     * rather than grouped under its show.
     */
    suspend fun latest(libraryId: UUID, episodes: Boolean): List<BaseItemDto> =
        withContext(Dispatchers.IO) {
            api.libraryApi
                .getLatestMedia(userId, parentId = libraryId, limit = 16, groupItems = !episodes)
                .content
        }

    // The order the user picked for libraries, with the name deciding between equals so that
    // pages don't repeat or skip any
    private val librarySort: List<ItemSortBy>
        get() =
            listOf(JellyfinSort.fromKey(settings.jellyfinSort).by, ItemSortBy.SORT_NAME).distinct()

    private val libraryOrder: List<SortOrder>
        get() =
            listOf(
                    if (settings.jellyfinSortDescending) SortOrder.DESCENDING
                    else SortOrder.ASCENDING,
                    SortOrder.ASCENDING,
                )
                .take(librarySort.size)

    /** One page of every movie or show, in the order picked. Also gives how many there are. */
    suspend fun items(
        kind: BaseItemKind,
        startIndex: Int,
        limit: Int,
    ): Pair<List<BaseItemDto>, Int> =
        withContext(Dispatchers.IO) {
            val result =
                api.libraryApi
                    .getItems(
                        userId,
                        includeItemTypes = listOf(kind),
                        recursive = true,
                        sortBy = librarySort,
                        sortOrder = libraryOrder,
                        startIndex = startIndex,
                        limit = limit,
                    )
                    .content
            result.items to result.totalRecordCount
        }

    /**
     * Which of Jellyfin's items a list holds: of some kinds, inside a library, collection or
     * playlist, the favourites, what a search finds, or what someone is in. A playlist keeps its
     * own order, a library ([sorted]) goes by the order picked for libraries, the rest by name.
     */
    data class ItemQuery(
        val kinds: List<BaseItemKind>? = null,
        val parentId: UUID? = null,
        val favorites: Boolean = false,
        val search: String? = null,
        val personId: UUID? = null,
        val recursive: Boolean = true,
        val byName: Boolean = true,
        val sorted: Boolean = false,
    )

    /** One page of what [query] asks for, with how many there are in all. */
    suspend fun query(
        query: ItemQuery,
        startIndex: Int,
        limit: Int,
    ): Pair<List<BaseItemDto>, Int> =
        withContext(Dispatchers.IO) {
            val result =
                api.libraryApi
                    .getItems(
                        userId,
                        parentId = query.parentId,
                        includeItemTypes = query.kinds,
                        recursive = query.recursive,
                        filters = if (query.favorites) listOf(ItemFilter.IS_FAVORITE) else null,
                        searchTerm = query.search,
                        personIds = query.personId?.let { listOf(it) },
                        sortBy =
                            when {
                                query.sorted -> librarySort
                                query.byName -> listOf(ItemSortBy.SORT_NAME)
                                else -> null
                            },
                        sortOrder =
                            when {
                                query.sorted -> libraryOrder
                                query.byName -> listOf(SortOrder.ASCENDING)
                                else -> null
                            },
                        startIndex = startIndex,
                        limit = limit,
                    )
                    .content
            result.items to result.totalRecordCount
        }

    /** The movies and episodes the server added last, newest first, without pictures. */
    suspend fun newest(limit: Int): List<BaseItemDto> =
        withContext(Dispatchers.IO) {
            api.libraryApi
                .getItems(
                    userId,
                    includeItemTypes = PLAYED_KINDS,
                    recursive = true,
                    sortBy = listOf(ItemSortBy.DATE_CREATED),
                    sortOrder = listOf(SortOrder.DESCENDING),
                    limit = limit,
                    enableImages = false,
                    enableUserData = false,
                )
                .content
                .items
        }

    /** The ID of every movie and episode on the server, read a page at a time. */
    suspend fun allIds(): Set<String> =
        withContext(Dispatchers.IO) {
            val ids = HashSet<String>()
            var start = 0
            while (start < MAX_IDS) {
                val page =
                    api.libraryApi
                        .getItems(
                            userId,
                            includeItemTypes = PLAYED_KINDS,
                            recursive = true,
                            // Oldest first, so what's added while it reads goes at the end
                            sortBy = listOf(ItemSortBy.DATE_CREATED),
                            sortOrder = listOf(SortOrder.ASCENDING),
                            startIndex = start,
                            limit = IDS_PAGE,
                            enableImages = false,
                            enableUserData = false,
                            enableTotalRecordCount = false,
                        )
                        .content
                        .items
                page.forEach { ids += it.id.toString() }
                if (page.size < IDS_PAGE) break
                start += IDS_PAGE
            }
            ids
        }

    /** A few movies and shows Jellyfin suggests. */
    suspend fun suggestions(): List<BaseItemDto> =
        withContext(Dispatchers.IO) {
            api.suggestionApi
                .getSuggestions(
                    userId,
                    limit = 12,
                    type = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
                )
                .content
                .items
        }

    suspend fun setFavorite(id: UUID, favorite: Boolean) {
        withContext(Dispatchers.IO) {
            if (favorite) api.userDataApi.markFavoriteItem(id)
            else api.userDataApi.unmarkFavoriteItem(id)
        }
    }

    suspend fun item(id: UUID): BaseItemDto =
        withContext(Dispatchers.IO) { api.libraryApi.getItem(itemId = id, userId = userId).content }

    suspend fun seasons(seriesId: UUID): List<BaseItemDto> =
        withContext(Dispatchers.IO) { api.showApi.getSeasons(seriesId, userId).content.items }

    suspend fun episodes(seriesId: UUID, seasonId: UUID? = null): List<BaseItemDto> =
        withContext(Dispatchers.IO) {
            api.showApi
                .getEpisodes(
                    seriesId,
                    userId,
                    seasonId = seasonId,
                    fields = listOf(ItemFields.OVERVIEW),
                )
                .content
                .items
        }

    /**
     * Shows named like [name], with where their files are, for finding the JRWI archiver's videos,
     * which it files under a folder named after their show.
     */
    suspend fun seriesNamed(name: String): List<BaseItemDto> =
        withContext(Dispatchers.IO) {
            api.libraryApi
                .getItems(
                    userId = userId,
                    searchTerm = name,
                    includeItemTypes = listOf(BaseItemKind.SERIES),
                    recursive = true,
                    fields = FILE_FIELDS,
                    limit = 25,
                    enableUserData = false,
                )
                .content
                .items
        }

    /** Every episode of a show, each with its file's path and the IDs its .nfo gives it. */
    suspend fun episodeFiles(seriesId: UUID): List<BaseItemDto> =
        withContext(Dispatchers.IO) {
            api.libraryApi
                .getItems(
                    userId = userId,
                    parentId = seriesId,
                    includeItemTypes = listOf(BaseItemKind.EPISODE),
                    recursive = true,
                    fields = FILE_FIELDS,
                    enableUserData = false,
                )
                .content
                .items
        }

    /**
     * Sets where an item was left without playing it, such as after listening to its audio
     * elsewhere: Jellyfin takes it as a play that stopped there.
     */
    suspend fun setPosition(id: UUID, seconds: Double) {
        withContext(Dispatchers.IO) {
            api.sessionApi.reportPlaybackStopped(
                PlaybackStopInfo(itemId = id, positionTicks = ticks(seconds), failed = false)
            )
        }
    }

    suspend fun setPlayed(id: UUID, played: Boolean) {
        withContext(Dispatchers.IO) {
            if (played) api.userDataApi.markPlayedItem(id) else api.userDataApi.markUnplayedItem(id)
        }
    }

    // TubeArchivist's videos as Jellyfin has them. TubeArchivist doesn't keep when a video was
    // played and Jellyfin does, so Jellyfin's dates put Continue watching in the same order on
    // every device: the phone app plays these videos through Jellyfin, and this app, which plays
    // them from TubeArchivist, tells Jellyfin when it does.

    private val taEpisodes = ConcurrentHashMap<String, UUID>()
    private val taShows = ConcurrentHashMap<String, UUID>()

    /**
     * When TubeArchivist's videos were last played on any device, by video ID, in milliseconds, for
     * the [limit] items played last.
     */
    suspend fun taLastPlayed(limit: Int = 100): Map<String, Long> =
        withContext(Dispatchers.IO) {
            val items =
                api.libraryApi
                    .getItems(
                        userId = userId,
                        includeItemTypes = listOf(BaseItemKind.EPISODE),
                        recursive = true,
                        sortBy = listOf(ItemSortBy.DATE_PLAYED),
                        sortOrder = listOf(SortOrder.DESCENDING),
                        fields = FILE_FIELDS,
                        limit = limit,
                        enableUserData = true,
                        enableImages = false,
                    )
                    .content
                    .items
            val played = mutableMapOf<String, Long>()
            for (item in items) {
                val videoId = item.tubeArchivistId() ?: continue
                taEpisodes[videoId] = item.id
                // Never played comes as year 1, or not at all
                val date = item.userData?.lastPlayedDate?.takeIf { it.year > 1970 } ?: continue
                played[videoId] = date.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }
            played
        }

    /**
     * The Jellyfin episode of a TubeArchivist video, found as the phone app finds it: by its title,
     * or else among its channel's episodes. Null when Jellyfin doesn't have it (yet).
     */
    suspend fun taEpisodeId(
        videoId: String,
        title: String,
        channelId: String?,
        channelName: String?,
    ): UUID? =
        withContext(Dispatchers.IO) {
            taEpisodes[videoId]?.let {
                return@withContext it
            }
            val term = title.trim().take(TA_SEARCH_LENGTH)
            if (term.isNotEmpty()) {
                val found =
                    api.libraryApi
                        .getItems(
                            userId = userId,
                            searchTerm = term,
                            includeItemTypes = listOf(BaseItemKind.EPISODE),
                            recursive = true,
                            fields = FILE_FIELDS,
                            limit = 25,
                            enableUserData = false,
                        )
                        .content
                        .items
                rememberTaEpisodes(found)
                taEpisodes[videoId]?.let {
                    return@withContext it
                }
            }
            val showId = channelId?.let { taShowId(it, channelName) } ?: return@withContext null
            rememberTaEpisodes(episodeFiles(showId))
            taEpisodes[videoId]
        }

    /** The Jellyfin show of a TubeArchivist channel. */
    private suspend fun taShowId(channelId: String, channelName: String?): UUID? {
        taShows[channelId]?.let {
            return it
        }
        val named = channelName?.trim()?.take(TA_SEARCH_LENGTH).orEmpty()
        if (named.isNotEmpty()) {
            seriesNamed(named).forEach { show ->
                show.tubeArchivistId()?.let { taShows[it] = show.id }
            }
            taShows[channelId]?.let {
                return it
            }
        }
        val shows =
            api.libraryApi
                .getItems(
                    userId = userId,
                    includeItemTypes = listOf(BaseItemKind.SERIES),
                    recursive = true,
                    fields = FILE_FIELDS,
                    enableUserData = false,
                )
                .content
                .items
        shows.forEach { show -> show.tubeArchivistId()?.let { taShows[it] = show.id } }
        return taShows[channelId]
    }

    private fun rememberTaEpisodes(items: List<BaseItemDto>) {
        items.forEach { item -> item.tubeArchivistId()?.let { taEpisodes[it] = item.id } }
    }

    /**
     * Notes in Jellyfin that an item was played just now, without playing it there. Only the date
     * is sent, which leaves where it was left and whether it's watched as they are.
     */
    suspend fun notePlayedNow(id: UUID) {
        withContext(Dispatchers.IO) {
            api.userDataApi.updateItemUserData(
                itemId = id,
                userId = userId,
                data = UpdateUserItemDataDto(lastPlayedDate = LocalDateTime.now()),
            )
        }
    }

    /**
     * Asks Jellyfin how to play an item, as its own apps do first: the file and its tracks, the
     * tracks the user's settings pick, and a play session to report on.
     */
    suspend fun playbackInfo(id: UUID, startSeconds: Double?): PlaybackInfoResponse =
        withContext(Dispatchers.IO) {
            api.mediaInfoApi
                .getPostedPlaybackInfo(
                    id,
                    PlaybackInfoDto(
                        userId = userId,
                        startTimeTicks = startSeconds?.let(::ticks),
                        maxStreamingBitrate = MAX_BITRATE,
                        deviceProfile = DIRECT_PLAY_PROFILE,
                        enableDirectPlay = true,
                        enableDirectStream = true,
                        enableTranscoding = false,
                        autoOpenLiveStream = true,
                    ),
                )
                .content
        }

    /** A stream Jellyfin converts, and the subtitle files that come beside it. */
    class Converted(val url: String, val subtitles: List<MediaStream>)

    /**
     * Asks Jellyfin to convert an item to at most [quality], as the phone app does: H.264 and AAC
     * over HLS, with the subtitles as files beside it. Null when the server offers no such stream.
     */
    suspend fun convertedStream(
        id: UUID,
        mediaSourceId: String,
        quality: StreamQuality,
        audioStreamIndex: Int?,
    ): Converted? =
        withContext(Dispatchers.IO) {
            val size =
                listOf(
                    ProfileCondition(
                        condition = ProfileConditionType.LESS_THAN_EQUAL,
                        property = ProfileConditionValue.WIDTH,
                        value = quality.maxWidth.toString(),
                        isRequired = false,
                    ),
                    ProfileCondition(
                        condition = ProfileConditionType.LESS_THAN_EQUAL,
                        property = ProfileConditionValue.HEIGHT,
                        value = quality.maxHeight.toString(),
                        isRequired = false,
                    ),
                )
            val response =
                api.mediaInfoApi
                    .getPostedPlaybackInfo(
                        id,
                        PlaybackInfoDto(
                            userId = userId,
                            mediaSourceId = mediaSourceId,
                            audioStreamIndex = audioStreamIndex,
                            maxStreamingBitrate = quality.maxBitrate,
                            enableDirectPlay = false,
                            enableDirectStream = false,
                            enableTranscoding = true,
                            allowVideoStreamCopy = false,
                            allowAudioStreamCopy = true,
                            autoOpenLiveStream = true,
                            deviceProfile =
                                DeviceProfile(
                                    name = "Winchivist converted",
                                    maxStreamingBitrate = quality.maxBitrate,
                                    maxStaticBitrate = quality.maxBitrate,
                                    directPlayProfiles = emptyList(),
                                    transcodingProfiles =
                                        listOf(
                                            TranscodingProfile(
                                                container = "ts",
                                                type = DlnaProfileType.VIDEO,
                                                videoCodec = "h264",
                                                audioCodec = "aac",
                                                protocol = MediaStreamProtocol.HLS,
                                                context = EncodingContext.STREAMING,
                                                maxAudioChannels = "2",
                                                minSegments = 1,
                                                breakOnNonKeyFrames = true,
                                                conditions = emptyList(),
                                            )
                                        ),
                                    containerProfiles = emptyList(),
                                    codecProfiles =
                                        listOf(
                                            CodecProfile(
                                                type = CodecType.VIDEO,
                                                codec = "h264",
                                                conditions = size,
                                                applyConditions = emptyList(),
                                            )
                                        ),
                                    subtitleProfiles =
                                        listOf("srt", "subrip", "ass", "ssa", "vtt", "webvtt").map {
                                            SubtitleProfile(it, SubtitleDeliveryMethod.EXTERNAL)
                                        },
                                ),
                        ),
                    )
                    .content
            val source =
                response.mediaSources.firstOrNull { it.id == mediaSourceId }
                    ?: response.mediaSources.firstOrNull()
                    ?: return@withContext null
            val path = source.transcodingUrl?.takeIf { it.isNotBlank() } ?: return@withContext null
            val subtitles =
                source.mediaStreams.orEmpty().filter {
                    it.type == MediaStreamType.SUBTITLE &&
                        it.deliveryMethod == SubtitleDeliveryMethod.EXTERNAL &&
                        !it.deliveryUrl.isNullOrBlank()
                }
            Converted(limitSize(serverUrl(path), quality), subtitles)
        }

    /**
     * Makes sure the stream asks for at most the quality's size, whatever the server made of it.
     */
    private fun limitSize(url: String, quality: StreamQuality): String {
        val parsed = url.toHttpUrlOrNull() ?: return url
        val builder = parsed.newBuilder()
        parsed.queryParameterNames
            .filter { it.equals("MaxWidth", true) || it.equals("MaxHeight", true) }
            .forEach { builder.removeAllQueryParameters(it) }
        return builder
            .addQueryParameter("MaxWidth", quality.maxWidth.toString())
            .addQueryParameter("MaxHeight", quality.maxHeight.toString())
            .build()
            .toString()
    }

    /** The parts of an item Jellyfin knows of, such as its intro and credits, to skip. */
    suspend fun segments(id: UUID): List<MediaSegmentDto> =
        withContext(Dispatchers.IO) { api.mediaSegmentApi.getItemSegments(id).content.items }

    suspend fun reportStart(state: PlayState) {
        withContext(Dispatchers.IO) {
            api.sessionApi.reportPlaybackStart(
                PlaybackStartInfo(
                    itemId = state.itemId,
                    mediaSourceId = state.mediaSourceId,
                    playSessionId = state.playSessionId,
                    audioStreamIndex = state.audioStreamIndex,
                    subtitleStreamIndex = state.subtitleStreamIndex,
                    canSeek = true,
                    isPaused = state.paused,
                    isMuted = state.muted,
                    volumeLevel = state.volume,
                    playMethod =
                        if (state.converted) PlayMethod.TRANSCODE else PlayMethod.DIRECT_PLAY,
                    repeatMode = RepeatMode.REPEAT_NONE,
                    playbackOrder = PlaybackOrder.DEFAULT,
                    positionTicks = ticks(state.seconds),
                )
            )
        }
    }

    suspend fun reportProgress(state: PlayState) {
        withContext(Dispatchers.IO) {
            api.sessionApi.reportPlaybackProgress(
                PlaybackProgressInfo(
                    itemId = state.itemId,
                    mediaSourceId = state.mediaSourceId,
                    playSessionId = state.playSessionId,
                    audioStreamIndex = state.audioStreamIndex,
                    subtitleStreamIndex = state.subtitleStreamIndex,
                    canSeek = true,
                    isPaused = state.paused,
                    isMuted = state.muted,
                    volumeLevel = state.volume,
                    playMethod =
                        if (state.converted) PlayMethod.TRANSCODE else PlayMethod.DIRECT_PLAY,
                    repeatMode = RepeatMode.REPEAT_NONE,
                    playbackOrder = PlaybackOrder.DEFAULT,
                    positionTicks = ticks(state.seconds),
                )
            )
        }
    }

    suspend fun reportStop(state: PlayState) {
        withContext(Dispatchers.IO) {
            api.sessionApi.reportPlaybackStopped(
                PlaybackStopInfo(
                    itemId = state.itemId,
                    mediaSourceId = state.mediaSourceId,
                    playSessionId = state.playSessionId,
                    positionTicks = ticks(state.seconds),
                    failed = false,
                )
            )
        }
    }

    /**
     * What plays and how, for Jellyfin's list of what's playing and for the tracks it picks next
     * time. A subtitle index of -1 means subtitles are off.
     */
    data class PlayState(
        val itemId: UUID,
        val seconds: Double,
        val paused: Boolean = false,
        val muted: Boolean = false,
        /** From 0 to 100. */
        val volume: Int = 100,
        val mediaSourceId: String? = null,
        val playSessionId: String? = null,
        val audioStreamIndex: Int? = null,
        val subtitleStreamIndex: Int? = null,
        /** Jellyfin converts the stream, rather than sending the file as it is. */
        val converted: Boolean = false,
    )

    /** The file itself, as stored on the server, which mpv plays as it is. */
    fun streamUrl(itemId: UUID, mediaSourceId: String, playSessionId: String?): String =
        "${settings.jellyfinUrl}/Videos/$itemId/stream?static=true&mediaSourceId=$mediaSourceId" +
            "&deviceId=${settings.deviceId}" +
            (playSessionId?.let { "&playSessionId=$it" } ?: "")

    /** A file of the server's own, such as a subtitle file, from the path Jellyfin gave for it. */
    fun serverUrl(path: String): String = settings.jellyfinUrl.orEmpty() + path

    /**
     * One tile of an item's preview pictures, which Jellyfin makes for the seek bar. The token goes
     * in the address, as the pictures load without the sign-in header.
     */
    fun trickplayTileUrl(itemId: UUID, mediaSourceId: String, width: Int, index: Int): String =
        "${settings.jellyfinUrl}/Videos/$itemId/Trickplay/$width/$index.jpg" +
            "?mediaSourceId=$mediaSourceId&api_key=${settings.jellyfinToken}"

    /** The header that signs mpv's requests in, as Jellyfin's own apps send it. */
    val authHeader: String
        get() =
            "Authorization: MediaBrowser Client=\"Winchivist\", Device=\"${computerName()}\", " +
                "DeviceId=\"${settings.deviceId}\", Version=\"${appVersion()}\", " +
                "Token=\"${settings.jellyfinToken}\""

    /** What signs a request in, as the Authorization header's value. */
    val authValue: String
        get() = authHeader.removePrefix("Authorization: ")

    /** The file of a movie or an episode as it is on the server, to save to this computer. */
    fun downloadUrl(id: UUID): String = "${settings.jellyfinUrl}/Items/$id/Download"

    /** A picture of an item, such as its poster, sized for the page. */
    fun imageUrl(itemId: UUID, type: ImageType, tag: String?, maxWidth: Int): String? {
        tag ?: return null
        return "${settings.jellyfinUrl}/Items/$itemId/Images/${type.serialName}" +
            "?tag=$tag&maxWidth=$maxWidth&quality=90"
    }

    companion object {
        private val FILE_FIELDS = listOf(ItemFields.PROVIDER_IDS, ItemFields.PATH)
        private const val TA_SEARCH_LENGTH = 60
        private val PLAYED_KINDS = listOf(BaseItemKind.EPISODE, BaseItemKind.MOVIE)
        private const val IDS_PAGE = 1000
        private const val MAX_IDS = 200_000

        // Higher than any file, so Jellyfin never asks for a smaller copy
        private const val MAX_BITRATE = 1_000_000_000

        // mpv plays the files as they are, so the profile only says how subtitle files come:
        // separate files as files, and the rest inside the video
        private val DIRECT_PLAY_PROFILE =
            DeviceProfile(
                name = "Winchivist",
                maxStaticBitrate = MAX_BITRATE,
                maxStreamingBitrate = MAX_BITRATE,
                codecProfiles = emptyList(),
                containerProfiles = emptyList(),
                directPlayProfiles = emptyList(),
                transcodingProfiles = emptyList(),
                subtitleProfiles =
                    listOf("srt", "subrip", "ass", "ssa", "vtt", "webvtt", "sub", "smi").map {
                        SubtitleProfile(it, SubtitleDeliveryMethod.EXTERNAL)
                    } +
                        listOf(
                                "srt",
                                "subrip",
                                "ass",
                                "ssa",
                                "vtt",
                                "webvtt",
                                "sub",
                                "smi",
                                "pgssub",
                                "dvdsub",
                                "dvbsub",
                                "mov_text",
                            )
                            .map { SubtitleProfile(it, SubtitleDeliveryMethod.EMBED) },
            )

        /** Jellyfin counts time in ticks of 100 nanoseconds. */
        fun ticks(seconds: Double): Long = (seconds * 10_000_000).toLong()

        fun seconds(ticks: Long?): Double = (ticks ?: 0L) / 10_000_000.0
    }
}

private fun appVersion(): String = System.getProperty("jpackage.app-version") ?: "dev"

private fun computerName(): String =
    System.getenv("COMPUTERNAME")
        ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull()
        ?: "Windows"

/** The orders Jellyfin's libraries can be listed in. */
enum class JellyfinSort(val key: String, val label: String, internal val by: ItemSortBy) {
    NAME("name", "Title", ItemSortBy.SORT_NAME),
    RATING("rating", "Rating", ItemSortBy.COMMUNITY_RATING),
    PARENTAL("parental", "Age rating", ItemSortBy.OFFICIAL_RATING),
    ADDED("added", "Date added", ItemSortBy.DATE_CREATED),
    PLAYED("played", "Date played", ItemSortBy.DATE_PLAYED),
    RELEASED("released", "Release date", ItemSortBy.PREMIERE_DATE);

    companion object {
        fun fromKey(key: String?): JellyfinSort = entries.firstOrNull { it.key == key } ?: NAME
    }
}
