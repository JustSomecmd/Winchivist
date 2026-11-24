package dev.jdtech.tubedroid.desktop.jellyfin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.jdtech.tubedroid.desktop.AppGraph
import dev.jdtech.tubedroid.desktop.AppSettings
import dev.jdtech.tubedroid.desktop.Downloads
import dev.jdtech.tubedroid.desktop.LocalApp
import dev.jdtech.tubedroid.desktop.LocalCanGoBack
import dev.jdtech.tubedroid.desktop.LocalNavigator
import dev.jdtech.tubedroid.desktop.Route
import dev.jdtech.tubedroid.desktop.player.PlayerRequest
import dev.jdtech.tubedroid.desktop.player.episodeTitle
import dev.jdtech.tubedroid.desktop.rememberPageModel
import dev.jdtech.tubedroid.desktop.screens.RefreshButton
import dev.jdtech.tubedroid.desktop.ui.AppIcon
import dev.jdtech.tubedroid.desktop.ui.CardShape
import dev.jdtech.tubedroid.desktop.ui.ColumnScrollbar
import dev.jdtech.tubedroid.desktop.ui.DetailButton
import dev.jdtech.tubedroid.desktop.ui.DetailHeader
import dev.jdtech.tubedroid.desktop.ui.DetailLine
import dev.jdtech.tubedroid.desktop.ui.GridScrollbar
import dev.jdtech.tubedroid.desktop.ui.HeaderTabs
import dev.jdtech.tubedroid.desktop.ui.ListScrollbar
import dev.jdtech.tubedroid.desktop.ui.LoadMoreEffect
import dev.jdtech.tubedroid.desktop.ui.MediaCard
import dev.jdtech.tubedroid.desktop.ui.PageBar
import dev.jdtech.tubedroid.desktop.ui.PagePadding
import dev.jdtech.tubedroid.desktop.ui.PageState
import dev.jdtech.tubedroid.desktop.ui.SectionRow
import dev.jdtech.tubedroid.desktop.ui.Spacings
import dev.jdtech.tubedroid.desktop.ui.errorText
import dev.jdtech.tubedroid.desktop.ui.formatDuration
import dev.jdtech.tubedroid.desktop.ui.formatSize
import java.io.File
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.LocationType
import org.jellyfin.sdk.model.api.MediaStreamType

// Home

/** A row on Jellyfin's home: its title and what's in it. */
class JellyfinRow(val title: String, val items: List<BaseItemDto>, val wide: Boolean)

class JellyfinHomeModel(
    private val client: JellyfinClient,
    private val settings: AppSettings,
    private val scope: CoroutineScope,
) {
    var rows by mutableStateOf<List<JellyfinRow>>(emptyList())
        private set

    var loading by mutableStateOf(true)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    /** The rows Settings asked for when Home was last loaded. */
    var shownFor: List<Boolean> = wanted()
        private set

    val listState = LazyListState()

    init {
        refresh()
    }

    /** Which rows Settings asks for, to tell when Home has to be loaded again. */
    fun wanted(): List<Boolean> =
        listOf(
            settings.homeContinue,
            settings.homeNextUp,
            settings.homeSuggestions,
            settings.homeLatest,
            settings.homeLatestEpisodes,
        )

    fun refresh() {
        scope.launch {
            loading = true
            error = null
            shownFor = wanted()
            try {
                // Each row is a request of its own, and they're all made at once
                rows = coroutineScope {
                    val resume = async { if (settings.homeContinue) client.resume() else null }
                    val nextUp = async { if (settings.homeNextUp) client.nextUp() else null }
                    val suggestions = async {
                        if (settings.homeSuggestions) orNothing { client.suggestions() } else null
                    }
                    val latest = async { if (settings.homeLatest) latest() else emptyList() }
                    buildList<JellyfinRow> {
                        resume
                            .await()
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { add(JellyfinRow("Continue watching", it, wide = true)) }
                        nextUp
                            .await()
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { add(JellyfinRow("Next up", it, wide = true)) }
                        suggestions
                            .await()
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { add(JellyfinRow("Suggestions", it, wide = false)) }
                        latest.await().forEach { (library, items) ->
                            if (items.isNotEmpty()) {
                                add(
                                    JellyfinRow(
                                        title = "Latest in ${library.name}",
                                        items = items,
                                        // New episodes by themselves show their own pictures
                                        wide = items.all { it.type == BaseItemKind.EPISODE },
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = errorText(e)
            } finally {
                loading = false
            }
        }
    }

    /** What's new in each library of videos, asked of them all at once. */
    private suspend fun latest(): List<Pair<BaseItemDto, List<BaseItemDto>>> = coroutineScope {
        val episodes = settings.homeLatestEpisodes
        client
            .libraries()
            .filter { it.isVideoLibrary() }
            .map { library ->
                async { library to orNothing { client.latest(library.id, episodes) } }
            }
            .awaitAll()
    }

    // A row that can't be read is left out rather than taking Home with it
    private suspend fun orNothing(load: suspend () -> List<BaseItemDto>): List<BaseItemDto> =
        try {
            load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
}

/** Jellyfin's home: what to carry on with, the next episodes, and what's new per library. */
@Composable
fun JellyfinHomeScreen() {
    val app = LocalApp.current
    val model =
        rememberPageModel("jellyfin_home") { scope ->
            JellyfinHomeModel(app.jellyfin, app.settings, scope)
        }
    // Rows switched on or off in Settings show the next time Home does
    val wanted = model.wanted()
    LaunchedEffect(wanted) { if (model.shownFor != wanted) model.refresh() }
    // While it's open, Home follows the library as Jellyfin says it changes, once a run of
    // changes is over
    LaunchedEffect(Unit) {
        app.jellyfin.libraryChanges().collectLatest {
            delay(LIBRARY_CHANGE_DELAY_MS)
            model.refresh()
        }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        PageBar(title = "Jellyfin", onBack = null) { RefreshButton(onClick = model::refresh) }
        if (model.rows.isEmpty()) {
            if (model.loading || model.error != null) {
                PageState(loading = model.loading, error = model.error, onRetry = model::refresh)
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Nothing here yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            return@Column
        }
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = model.listState,
                verticalArrangement = Arrangement.spacedBy(28.dp),
                contentPadding = PaddingValues(top = Spacings.small, bottom = Spacings.large),
            ) {
                items(model.rows, key = { it.title }) { row ->
                    SectionRow(row.title) {
                        items(row.items, key = { it.id }) { item ->
                            if (row.wide) {
                                WideCard(item = item, modifier = Modifier.width(300.dp))
                            } else {
                                PosterCard(item = item, modifier = Modifier.width(170.dp))
                            }
                        }
                    }
                }
            }
            ListScrollbar(model.listState)
        }
    }
}

private const val LIBRARY_CHANGE_DELAY_MS = 1_500L

// Libraries

/** Every movie or show, a page at a time, in the order picked for libraries. */
class JellyfinLibraryModel(
    private val client: JellyfinClient,
    private val scope: CoroutineScope,
    private val kind: BaseItemKind,
) {
    val items = mutableStateListOf<BaseItemDto>()
    var total by mutableStateOf<Int?>(null)
        private set

    var loading by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    val gridState = LazyGridState()

    init {
        loadMore()
    }

    fun loadMore() {
        if (loading || (total != null && items.size >= total!!)) return
        loading = true
        error = null
        scope.launch {
            try {
                val (page, count) = client.items(kind, startIndex = items.size, limit = PAGE_SIZE)
                items += page.filter { new -> items.none { it.id == new.id } }
                total = if (page.isEmpty()) items.size else count
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = errorText(e)
            } finally {
                loading = false
            }
        }
    }

    fun refresh() {
        items.clear()
        total = null
        loadMore()
    }

    private companion object {
        const val PAGE_SIZE = 60
    }
}

@Composable
fun JellyfinLibraryScreen(movies: Boolean) {
    val app = LocalApp.current
    val kind = if (movies) BaseItemKind.MOVIE else BaseItemKind.SERIES
    val model =
        rememberPageModel(if (movies) "jellyfin_movies" else "jellyfin_shows") { scope ->
            JellyfinLibraryModel(app.jellyfin, scope, kind)
        }
    LoadMoreEffect(model.gridState) { model.loadMore() }
    Column(modifier = Modifier.fillMaxSize()) {
        PageBar(
            title = (if (movies) "Movies" else "Shows") + (model.total?.let { " ($it)" } ?: ""),
            onBack = null,
        ) {
            JellyfinSortButtons(onChange = model::refresh)
            RefreshButton(onClick = model::refresh)
        }
        Box(modifier = Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                state = model.gridState,
                columns = GridCells.Adaptive(minSize = 170.dp),
                contentPadding =
                    PaddingValues(
                        start = PagePadding,
                        end = PagePadding,
                        top = 8.dp,
                        bottom = 24.dp,
                    ),
                horizontalArrangement = Arrangement.spacedBy(Spacings.medium),
                verticalArrangement = Arrangement.spacedBy(Spacings.medium),
            ) {
                items(model.items, key = { it.id }) { item -> PosterCard(item = item) }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(Spacings.medium), Alignment.Center) {
                        when {
                            model.loading -> CircularProgressIndicator()
                            model.error != null ->
                                TextButton(onClick = model::loadMore) {
                                    Text("${model.error} Try again")
                                }
                            model.items.isEmpty() ->
                                Text(
                                    if (movies) "No movies" else "No shows",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                        }
                    }
                }
            }
            GridScrollbar(model.gridState)
        }
    }
}

// A movie or an episode

class JellyfinItemModel(
    private val client: JellyfinClient,
    private val scope: CoroutineScope,
    private val itemId: String,
) {
    var item by mutableStateOf<BaseItemDto?>(null)
        private set

    var loading by mutableStateOf(true)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    val scroll = androidx.compose.foundation.ScrollState(0)

    init {
        load()
    }

    fun load() {
        scope.launch {
            loading = true
            error = null
            try {
                item = client.item(UUID.fromString(itemId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = errorText(e)
            } finally {
                loading = false
            }
        }
    }

    fun setPlayed(played: Boolean, onMessage: (String) -> Unit) {
        val current = item ?: return
        scope.launch {
            try {
                client.setPlayed(current.id, played)
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onMessage(errorText(e))
            }
        }
    }

    fun setFavorite(favorite: Boolean, onMessage: (String) -> Unit) {
        val current = item ?: return
        scope.launch {
            try {
                client.setFavorite(current.id, favorite)
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onMessage(errorText(e))
            }
        }
    }
}

/**
 * Saves a movie's or an episode's file, as it is on the server, to the downloads folder. Returns
 * the folder it goes to.
 */
private fun downloadToPc(app: AppGraph, item: BaseItemDto): File {
    val source = item.mediaSources?.firstOrNull()
    val containers = source?.container.orEmpty().split(',')
    val extension =
        (source?.path ?: item.path)?.substringAfterLast('.', "")?.takeIf {
            it.length in 2..4 && it.all(Char::isLetterOrDigit)
        }
            ?: containers.firstOrNull { it == "mp4" || it == "mkv" }
            ?: containers.firstOrNull { it.isNotBlank() }
            ?: "mkv"
    val name =
        if (item.type == BaseItemKind.EPISODE) {
            listOfNotNull(item.seriesName, episodeTitle(item, hideNumbers = false))
                .joinToString(" - ")
        } else {
            item.name.orEmpty() + (item.productionYear?.let { " ($it)" } ?: "")
        }
    val folder = File(app.settings.downloadFolder)
    app.downloads.startFile(
        id = "jellyfin:${item.id}",
        title = name,
        url = app.jellyfin.downloadUrl(item.id),
        header = app.jellyfin.authValue,
        file = File(folder, "${Downloads.safeName(name)}.$extension"),
        route = Route.JellyfinItem(item.id.toString()),
    )
    return folder
}

/**
 * Cast and crew in a row, each opening what else they're in. Settings can leave it out, for movies
 * and for shows and their episodes apart.
 */
@Composable
private fun PeopleRow(item: BaseItemDto) {
    val settings = LocalApp.current.settings
    val wanted = if (item.type == BaseItemKind.MOVIE) settings.castMovies else settings.castShows
    if (!wanted) return
    val people = item.people?.takeIf { it.isNotEmpty() } ?: return
    SectionRow("Cast and crew", modifier = Modifier.padding(top = Spacings.default)) {
        items(people.take(PEOPLE_SHOWN)) { person -> PersonCard(person) }
    }
}

private const val PEOPLE_SHOWN = 40

/** The file's size and its video, sound and subtitle tracks, as Jellyfin names them. */
@Composable
private fun FileDetails(item: BaseItemDto) {
    val source = item.mediaSources?.firstOrNull() ?: return
    val streams = source.mediaStreams.orEmpty()
    fun tracks(type: MediaStreamType): String? =
        streams
            .filter { it.type == type }
            .mapNotNull { it.displayTitle }
            .joinToString(", ")
            .takeIf { it.isNotBlank() }
    source.size?.let { DetailLine(label = "Size", value = formatSize(it, metric = true)) }
    tracks(MediaStreamType.VIDEO)?.let { DetailLine(label = "Video", value = it) }
    tracks(MediaStreamType.AUDIO)?.let { DetailLine(label = "Sound", value = it) }
    tracks(MediaStreamType.SUBTITLE)?.let { DetailLine(label = "Subtitles", value = it) }
}

/** An episode's name as Settings wants it: with its season and number in front, or without. */
@Composable
private fun episodeName(item: BaseItemDto): String =
    episodeTitle(item, LocalApp.current.settings.hideEpisodeNumbers)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JellyfinItemScreen(itemId: String) {
    val app = LocalApp.current
    val navigator = LocalNavigator.current
    val canGoBack = LocalCanGoBack.current
    val model =
        rememberPageModel("jellyfin_item") { scope ->
            JellyfinItemModel(app.jellyfin, scope, itemId)
        }
    Column(modifier = Modifier.fillMaxSize()) {
        PageBar(title = null, onBack = if (canGoBack) navigator::back else null)
        val item = model.item
        if (item == null) {
            PageState(loading = model.loading, error = model.error, onRetry = model::load)
            return@Column
        }
        val episode = item.type == BaseItemKind.EPISODE
        val position = JellyfinClient.seconds(item.userData?.playbackPositionTicks)
        val played = item.userData?.played == true
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.verticalScroll(model.scroll).padding(bottom = Spacings.large)
            ) {
                DetailHeader(
                    title = if (episode) episodeName(item) else item.name.orEmpty(),
                    info = itemInfo(item),
                    backdrop = {
                        JellyfinImage(
                            url = backdropUrl(app.jellyfin, item),
                            modifier = Modifier.fillMaxSize(),
                        )
                    },
                    poster = {
                        JellyfinImage(
                            url = posterUrl(item),
                            modifier =
                                Modifier.fillMaxWidth()
                                    .aspectRatio(if (episode) 16f / 9f else 2f / 3f),
                        )
                    },
                    posterWidth = if (episode) 400.dp else 240.dp,
                    buttons = {
                        DetailButton(
                            icon = "ic_play",
                            label =
                                if (position > 5) "Resume from ${formatDuration(position)}"
                                else "Play",
                            onClick = {
                                navigator.play(PlayerRequest.Jellyfin(item.id.toString()))
                            },
                            main = true,
                        )
                        if (position > 5) {
                            DetailButton(
                                icon = "ic_rotate_ccw",
                                label = "Play from the start",
                                onClick = {
                                    navigator.play(
                                        PlayerRequest.Jellyfin(item.id.toString(), fromStart = true)
                                    )
                                },
                            )
                        }
                        DetailButton(
                            icon = "ic_check",
                            label = if (played) "Mark unwatched" else "Mark watched",
                            onClick = { model.setPlayed(!played, navigator::message) },
                            on = played,
                        )
                        val favorite = item.userData?.isFavorite == true
                        DetailButton(
                            icon = if (favorite) "ic_heart_filled" else "ic_heart",
                            label = if (favorite) "Take out of favourites" else "Add to favourites",
                            onClick = { model.setFavorite(!favorite, navigator::message) },
                            on = favorite,
                        )
                        DetailButton(
                            icon = "ic_download",
                            label = "Download to this PC",
                            onClick = {
                                val folder = downloadToPc(app, item)
                                navigator.message("Saving to ${folder.path}")
                            },
                        )
                    },
                    details = {
                        if (episode) {
                            item.seriesName?.let { show ->
                                DetailLine(label = "Show", value = show) {
                                    item.seriesId?.let {
                                        navigator.open(Route.JellyfinShow(it.toString()))
                                    }
                                }
                            }
                        }
                        item.genres
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { DetailLine(label = "Genres", value = it.joinToString(", ")) }
                        if (app.settings.extraInfo) FileDetails(item)
                        item.overview?.let { overview ->
                            Text(
                                text = overview,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier =
                                    Modifier.widthIn(max = 900.dp).padding(top = Spacings.small),
                            )
                        }
                    },
                )
                PeopleRow(item)
            }
            ColumnScrollbar(model.scroll)
        }
    }
}

// A show

class JellyfinShowModel(
    private val client: JellyfinClient,
    private val settings: AppSettings,
    private val scope: CoroutineScope,
    private val seriesId: String,
) {
    /**
     * Whether every episode is listed by date, as Settings asks, rather than a season at a time.
     */
    var allEpisodes by mutableStateOf(settings.allEpisodes)
        private set

    var newestFirst by mutableStateOf(settings.episodesNewestFirst)
        private set

    var show by mutableStateOf<BaseItemDto?>(null)
        private set

    var seasons by mutableStateOf<List<BaseItemDto>>(emptyList())
        private set

    var season by mutableStateOf<BaseItemDto?>(null)
        private set

    var episodes by mutableStateOf<List<BaseItemDto>>(emptyList())
        private set

    var nextUp by mutableStateOf<BaseItemDto?>(null)
        private set

    var loading by mutableStateOf(true)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    val listState = LazyListState()
    private val id = UUID.fromString(seriesId)

    init {
        load()
    }

    fun load() {
        scope.launch {
            loading = true
            error = null
            try {
                val every = settings.allEpisodes
                // The show, its seasons and its episodes don't wait for each other
                coroutineScope {
                    val showLoad = async { client.item(id) }
                    val seasonsLoad = async { client.seasons(id) }
                    val nextLoad = async {
                        try {
                            client.nextUp(id).firstOrNull()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                    }
                    val everyLoad = async { if (every) client.episodes(id) else null }
                    show = showLoad.await()
                    seasons = seasonsLoad.await()
                    nextUp = nextLoad.await()
                    val all = everyLoad.await()
                    allEpisodes = all != null
                    if (all != null) {
                        season = null
                        // Episodes Jellyfin knows of but has no file for are left out
                        episodes =
                            byDate(
                                all.filter { it.locationType != LocationType.VIRTUAL },
                                newestFirst,
                            )
                    }
                }
                if (!allEpisodes) {
                    // The season of the next episode, or else the first
                    val start =
                        seasons.firstOrNull { it.id == nextUp?.seasonId }
                            ?: seasons.firstOrNull { (it.indexNumber ?: 0) > 0 }
                            ?: seasons.firstOrNull()
                    start?.let { select(it) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = errorText(e)
            } finally {
                loading = false
            }
        }
    }

    fun setFavorite(favorite: Boolean, onMessage: (String) -> Unit) {
        scope.launch {
            try {
                client.setFavorite(id, favorite)
                show = client.item(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onMessage(errorText(e))
            }
        }
    }

    /** Turns the list of every episode round, and remembers that for every show. */
    fun flipOrder() {
        newestFirst = !newestFirst
        settings.episodesNewestFirst = newestFirst
        if (allEpisodes) episodes = byDate(episodes, newestFirst)
    }

    // By release date; episodes of one day keep their season and number order, and those without
    // a date go last
    private fun byDate(all: List<BaseItemDto>, newestFirst: Boolean): List<BaseItemDto> {
        val byNumber = compareBy<BaseItemDto>({ it.parentIndexNumber }, { it.indexNumber })
        val (dated, undated) = all.partition { it.premiereDate != null }
        val oldestFirst =
            dated.sortedWith(compareBy<BaseItemDto> { it.premiereDate }.then(byNumber))
        return (if (newestFirst) oldestFirst.reversed() else oldestFirst) +
            undated.sortedWith(byNumber)
    }

    fun select(target: BaseItemDto) {
        season = target
        scope.launch {
            episodes =
                try {
                    client.episodes(id, target.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = errorText(e)
                    emptyList()
                }
        }
    }
}

@Composable
fun JellyfinShowScreen(seriesId: String) {
    val app = LocalApp.current
    val navigator = LocalNavigator.current
    val canGoBack = LocalCanGoBack.current
    val model =
        rememberPageModel("jellyfin_show") { scope ->
            JellyfinShowModel(app.jellyfin, app.settings, scope, seriesId)
        }
    // Changed in Settings while the page was open: it follows
    val every = app.settings.allEpisodes
    LaunchedEffect(every) { if (model.allEpisodes != every && model.show != null) model.load() }
    Column(modifier = Modifier.fillMaxSize()) {
        PageBar(title = null, onBack = if (canGoBack) navigator::back else null)
        val show = model.show
        if (show == null) {
            PageState(loading = model.loading, error = model.error, onRetry = model::load)
            return@Column
        }
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = model.listState,
                contentPadding = PaddingValues(bottom = Spacings.large),
                verticalArrangement = Arrangement.spacedBy(Spacings.small),
            ) {
                item(key = "header") {
                    Column {
                        DetailHeader(
                            title = show.name.orEmpty(),
                            info = itemInfo(show),
                            backdrop = {
                                JellyfinImage(
                                    url = backdropUrl(app.jellyfin, show),
                                    modifier = Modifier.fillMaxSize(),
                                )
                            },
                            poster = {
                                JellyfinImage(
                                    url = posterUrl(show),
                                    modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
                                )
                            },
                            posterWidth = 240.dp,
                            buttons = {
                                val next = model.nextUp
                                DetailButton(
                                    icon = "ic_play",
                                    label = next?.let { "Play ${episodeName(it)}" } ?: "Play",
                                    onClick = {
                                        next?.let {
                                            navigator.play(PlayerRequest.Jellyfin(it.id.toString()))
                                        }
                                    },
                                    main = true,
                                    enabled = next != null,
                                )
                                val favorite = show.userData?.isFavorite == true
                                DetailButton(
                                    icon = if (favorite) "ic_heart_filled" else "ic_heart",
                                    label =
                                        if (favorite) "Take out of favourites"
                                        else "Add to favourites",
                                    onClick = { model.setFavorite(!favorite, navigator::message) },
                                    on = favorite,
                                )
                            },
                            details = {
                                show.genres
                                    ?.takeIf { it.isNotEmpty() }
                                    ?.let {
                                        DetailLine(label = "Genres", value = it.joinToString(", "))
                                    }
                                show.overview?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 6,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 900.dp),
                                    )
                                }
                            },
                        )
                        PeopleRow(show)
                        if (model.allEpisodes) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier =
                                    Modifier.padding(
                                        start = PagePadding,
                                        end = PagePadding,
                                        top = Spacings.large,
                                        bottom = Spacings.small,
                                    ),
                            ) {
                                Text(
                                    text =
                                        if (model.episodes.size == 1) "1 episode"
                                        else "${model.episodes.size} episodes",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = model::flipOrder) {
                                    AppIcon(
                                        name = "ic_arrow_down_up",
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(Spacings.small))
                                    Text(if (model.newestFirst) "Newest first" else "Oldest first")
                                }
                            }
                        } else if (model.seasons.size > 1) {
                            HeaderTabs(
                                tabs = model.seasons.map { it.name.orEmpty() },
                                selected =
                                    model.seasons
                                        .indexOfFirst { it.id == model.season?.id }
                                        .coerceAtLeast(0),
                                onSelect = { model.select(model.seasons[it]) },
                                modifier =
                                    Modifier.padding(
                                            start = PagePadding - 14.dp,
                                            top = Spacings.large,
                                            bottom = Spacings.small,
                                        )
                                        .horizontalScroll(rememberScrollState()),
                            )
                        } else {
                            Spacer(Modifier.height(Spacings.large))
                        }
                    }
                }
                items(model.episodes, key = { it.id }) { episode ->
                    EpisodeRow(
                        episode = episode,
                        modifier = Modifier.padding(horizontal = PagePadding - 8.dp),
                    )
                }
            }
            ListScrollbar(model.listState)
        }
    }
}

/** An episode: its picture, number and name, what it's about, and whether it was watched. */
@Composable
private fun EpisodeRow(episode: BaseItemDto, modifier: Modifier = Modifier) {
    val app = LocalApp.current
    val navigator = LocalNavigator.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacings.medium),
        modifier =
            modifier
                .fillMaxWidth()
                .clip(CardShape)
                .clickable { navigator.open(Route.JellyfinItem(episode.id.toString())) }
                .padding(Spacings.small),
    ) {
        Box(modifier = Modifier.width(240.dp).aspectRatio(16f / 9f).clip(CardShape)) {
            JellyfinImage(url = wideUrl(app.jellyfin, episode), modifier = Modifier.fillMaxSize())
            WatchedMarks(episode)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = episodeName(episode),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = itemDetails(episode),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            episode.overview?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = { navigator.play(PlayerRequest.Jellyfin(episode.id.toString())) }) {
            AppIcon(name = "ic_play", contentDescription = "Play")
        }
    }
}

// Pieces

/** A movie's or show's poster, with its name and year, as Jellyfin's cards. */
@Composable
fun PosterCard(item: BaseItemDto, modifier: Modifier = Modifier) {
    val navigator = LocalNavigator.current
    MediaCard(
        title = item.name.orEmpty(),
        subtitle = item.productionYear?.toString(),
        onClick = { navigator.open(item.route()) },
        onPlay =
            if (item.plays()) {
                { navigator.play(PlayerRequest.Jellyfin(item.id.toString())) }
            } else {
                null
            },
        aspectRatio = 2f / 3f,
        modifier = modifier,
    ) {
        JellyfinImage(url = posterUrl(item), modifier = Modifier.fillMaxSize())
        WatchedMarks(item)
    }
}

/** An episode or a movie to carry on with, wide, with how far it got. */
@Composable
fun WideCard(item: BaseItemDto, modifier: Modifier = Modifier) {
    val app = LocalApp.current
    val navigator = LocalNavigator.current
    val episode = item.type == BaseItemKind.EPISODE
    // An episode shows its show's name on top, or its own when Settings says so
    val titleFirst = app.settings.episodeTitleFirst
    MediaCard(
        title =
            when {
                !episode -> item.name.orEmpty()
                titleFirst -> episodeName(item)
                else -> item.seriesName.orEmpty()
            },
        subtitle =
            when {
                !episode -> item.productionYear?.toString()
                titleFirst -> item.seriesName
                else -> episodeName(item)
            },
        onClick = { navigator.open(item.route()) },
        onPlay =
            if (item.plays()) {
                { navigator.play(PlayerRequest.Jellyfin(item.id.toString())) }
            } else {
                null
            },
        modifier = modifier,
    ) {
        JellyfinImage(url = wideUrl(app.jellyfin, item), modifier = Modifier.fillMaxSize())
        WatchedMarks(item)
    }
}

/** A tick when watched, the number left for a show, and a bar for how far it got. */
@Composable
private fun WatchedMarks(item: BaseItemDto) {
    Box(modifier = Modifier.fillMaxSize()) {
        val data = item.userData
        val left = data?.unplayedItemCount ?: 0
        if (data?.played == true) {
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier.align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(24.dp)
                        .clip(MaterialTheme.shapes.extraLarge)
                        .background(MaterialTheme.colorScheme.primary),
            ) {
                AppIcon(
                    name = "ic_check",
                    contentDescription = "Watched",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp),
                )
            }
        } else if (item.type == BaseItemKind.SERIES && left > 0) {
            Text(
                text = left.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier =
                    Modifier.align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        val percent = data?.playedPercentage
        if (percent != null && percent > 0 && data?.played != true) {
            LinearProgressIndicator(
                progress = { (percent / 100).toFloat().coerceIn(0f, 1f) },
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Black.copy(alpha = 0.5f),
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp),
            )
        }
    }
}

/** A picture from Jellyfin, which doesn't need signing in for. */
@Composable
fun JellyfinImage(url: String?, modifier: Modifier = Modifier) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest),
    )
}

/**
 * The facts under an item's name, as on Jellyfin: year, length, rating, age rating, seasons, and
 * when it would end if started now.
 */
private fun itemInfo(item: BaseItemDto): List<String> =
    listOfNotNull(
        item.productionYear?.toString(),
        item.runTimeTicks?.let { runtime(JellyfinClient.seconds(it)) },
        item.communityRating?.let { "★ %.1f".format(it) },
        item.officialRating,
        item.childCount
            ?.takeIf { item.type == BaseItemKind.SERIES }
            ?.let { if (it == 1) "1 season" else "$it seasons" },
        item.runTimeTicks
            ?.takeIf { item.type != BaseItemKind.SERIES }
            ?.let { ticks ->
                val left =
                    JellyfinClient.seconds(ticks) -
                        JellyfinClient.seconds(item.userData?.playbackPositionTicks)
                "Ends at " +
                    java.time.LocalTime.now()
                        .plusSeconds(left.toLong().coerceAtLeast(0))
                        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
            },
    )

/** Year, length, rating and age rating, such as "2019 · 1 h 52 min · ★ 7.8 · 12". */
private fun itemDetails(item: BaseItemDto): String =
    listOfNotNull(
            item.productionYear?.toString(),
            item.runTimeTicks?.let { runtime(JellyfinClient.seconds(it)) },
            item.communityRating?.let { "★ %.1f".format(it) },
            item.officialRating,
            item.childCount
                ?.takeIf { item.type == BaseItemKind.SERIES }
                ?.let { if (it == 1) "1 season" else "$it seasons" },
        )
        .joinToString(" · ")

private fun runtime(seconds: Double): String {
    val minutes = (seconds / 60).toInt()
    return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
}

private fun BaseItemDto.isVideoLibrary(): Boolean =
    collectionType?.serialName in setOf("movies", "tvshows", "homevideos", "musicvideos")

/** The page of an item: a show's, what a library, collection or playlist holds, or its own. */
internal fun BaseItemDto.route(): Route =
    when (type) {
        BaseItemKind.SERIES -> Route.JellyfinShow(id.toString())
        BaseItemKind.SEASON ->
            seriesId?.let { Route.JellyfinShow(it.toString()) }
                ?: Route.JellyfinFolder(id.toString(), name.orEmpty())
        BaseItemKind.BOX_SET,
        BaseItemKind.FOLDER,
        BaseItemKind.COLLECTION_FOLDER,
        BaseItemKind.USER_VIEW ->
            Route.JellyfinFolder(
                id = id.toString(),
                title = name.orEmpty(),
                collection = collectionType?.serialName,
            )
        BaseItemKind.PLAYLIST -> Route.JellyfinFolder(id.toString(), name.orEmpty(), ordered = true)
        BaseItemKind.PERSON -> Route.JellyfinPerson(id.toString(), name.orEmpty())
        else -> Route.JellyfinItem(id.toString())
    }

/** Whether the item is something to play, rather than something that holds others. */
internal fun BaseItemDto.plays(): Boolean =
    type in
        setOf(
            BaseItemKind.MOVIE,
            BaseItemKind.EPISODE,
            BaseItemKind.VIDEO,
            BaseItemKind.MUSIC_VIDEO,
            BaseItemKind.TRAILER,
        )

@Composable
private fun posterUrl(item: BaseItemDto): String? {
    val client = LocalApp.current.jellyfin
    return client.imageUrl(item.id, ImageType.PRIMARY, item.imageTags?.get(ImageType.PRIMARY), 400)
}

/** For an episode its own picture, for a movie its backdrop, with the show's as the last resort. */
private fun wideUrl(client: JellyfinClient, item: BaseItemDto): String? =
    if (item.type == BaseItemKind.EPISODE) {
        client.imageUrl(item.id, ImageType.PRIMARY, item.imageTags?.get(ImageType.PRIMARY), 600)
            ?: backdropUrl(client, item)
    } else {
        backdropUrl(client, item)
            ?: client.imageUrl(
                item.id,
                ImageType.PRIMARY,
                item.imageTags?.get(ImageType.PRIMARY),
                600,
            )
    }

private fun backdropUrl(client: JellyfinClient, item: BaseItemDto): String? {
    item.backdropImageTags?.firstOrNull()?.let { tag ->
        return client.imageUrl(item.id, ImageType.BACKDROP, tag, 1600)
    }
    val parent = item.parentBackdropItemId ?: return null
    val tag = item.parentBackdropImageTags?.firstOrNull() ?: return null
    return client.imageUrl(parent, ImageType.BACKDROP, tag, 1600)
}
