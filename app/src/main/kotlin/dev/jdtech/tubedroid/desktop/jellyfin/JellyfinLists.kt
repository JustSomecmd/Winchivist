package dev.jdtech.tubedroid.desktop.jellyfin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jdtech.tubedroid.desktop.LocalApp
import dev.jdtech.tubedroid.desktop.LocalCanGoBack
import dev.jdtech.tubedroid.desktop.LocalNavigator
import dev.jdtech.tubedroid.desktop.Route
import dev.jdtech.tubedroid.desktop.rememberPageModel
import dev.jdtech.tubedroid.desktop.screens.RefreshButton
import dev.jdtech.tubedroid.desktop.ui.AppIcon
import dev.jdtech.tubedroid.desktop.ui.GridScrollbar
import dev.jdtech.tubedroid.desktop.ui.ListScrollbar
import dev.jdtech.tubedroid.desktop.ui.LoadMoreEffect
import dev.jdtech.tubedroid.desktop.ui.PageBar
import dev.jdtech.tubedroid.desktop.ui.PagePadding
import dev.jdtech.tubedroid.desktop.ui.RoundIconButton
import dev.jdtech.tubedroid.desktop.ui.SectionRow
import dev.jdtech.tubedroid.desktop.ui.Spacings
import dev.jdtech.tubedroid.desktop.ui.errorText
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.BaseItemPerson
import org.jellyfin.sdk.model.api.ImageType

/** A list of Jellyfin's items, loaded a page at a time as it's scrolled. */
class JellyfinListModel(
    private val client: JellyfinClient,
    private val scope: CoroutineScope,
    val query: JellyfinClient.ItemQuery,
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
        val count = total
        if (loading || (count != null && items.size >= count)) return
        loading = true
        error = null
        scope.launch {
            try {
                val (page, all) = client.query(query, startIndex = items.size, limit = PAGE_SIZE)
                items += page.filter { new -> items.none { it.id == new.id } }
                total = if (page.isEmpty()) items.size else all
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

// What's shown wide, as a picture of a moment, rather than as a poster
private val WIDE_KINDS =
    setOf(
        BaseItemKind.EPISODE,
        BaseItemKind.VIDEO,
        BaseItemKind.MUSIC_VIDEO,
        BaseItemKind.PLAYLIST,
        BaseItemKind.FOLDER,
        BaseItemKind.COLLECTION_FOLDER,
        BaseItemKind.USER_VIEW,
    )

/** A page with a grid of a list's items, in the size that suits what they are. */
@Composable
private fun JellyfinGridPage(
    title: String,
    model: JellyfinListModel,
    emptyText: String,
    root: Boolean,
) {
    val navigator = LocalNavigator.current
    val canGoBack = LocalCanGoBack.current
    LoadMoreEffect(model.gridState) { model.loadMore() }
    Column(modifier = Modifier.fillMaxSize()) {
        PageBar(
            title = title + (model.total?.takeIf { it > 0 }?.let { " ($it)" } ?: ""),
            onBack = if (!root && canGoBack) navigator::back else null,
        ) {
            if (model.query.sorted) JellyfinSortButtons(onChange = model::refresh)
            RefreshButton(onClick = model::refresh)
        }
        val wide = model.items.isNotEmpty() && model.items.all { it.type in WIDE_KINDS }
        Box(modifier = Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                state = model.gridState,
                columns = GridCells.Adaptive(minSize = if (wide) 260.dp else 170.dp),
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
                items(model.items, key = { it.id }) { item ->
                    if (wide) WideCard(item = item) else PosterCard(item = item)
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(Spacings.medium), Alignment.Center) {
                        val error = model.error
                        when {
                            model.loading -> CircularProgressIndicator()
                            error != null ->
                                TextButton(onClick = model::loadMore) { Text("$error Try again") }
                            model.items.isEmpty() ->
                                Text(
                                    text = emptyText,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                        }
                    }
                }
            }
            GridScrollbar(model.gridState)
        }
    }
}

/**
 * The order of Jellyfin's libraries, the same for each of them: what to order by, and a button that
 * turns it round.
 */
@Composable
internal fun JellyfinSortButtons(onChange: () -> Unit) {
    val settings = LocalApp.current.settings
    val current = JellyfinSort.fromKey(settings.jellyfinSort)
    val descending = settings.jellyfinSortDescending
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            AppIcon(
                name = "ic_arrow_down_up",
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(Spacings.small))
            Text(current.label)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            JellyfinSort.entries.forEach { sort ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = sort.label,
                            fontWeight = if (sort == current) FontWeight.SemiBold else null,
                        )
                    },
                    onClick = {
                        open = false
                        if (sort != current) {
                            settings.jellyfinSort = sort.key
                            onChange()
                        }
                    },
                )
            }
        }
    }
    RoundIconButton(
        icon = "ic_chevron_down",
        label = if (descending) "Descending" else "Ascending",
        onClick = {
            settings.jellyfinSortDescending = !descending
            onChange()
        },
        modifier = Modifier.rotate(if (descending) 0f else 180f),
    )
}

/** Jellyfin's libraries, as it lists them for the user, each opening what it holds. */
@Composable
fun JellyfinLibrariesScreen() {
    val app = LocalApp.current
    val model =
        rememberPageModel("jellyfin_libraries") { scope ->
            JellyfinLibrariesModel(app.jellyfin, scope)
        }
    Column(modifier = Modifier.fillMaxSize()) {
        PageBar(title = "Libraries", onBack = null) { RefreshButton(onClick = model::load) }
        Box(modifier = Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                state = model.gridState,
                columns = GridCells.Adaptive(minSize = 260.dp),
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
                items(model.libraries, key = { it.id }) { library -> WideCard(item = library) }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(Spacings.medium), Alignment.Center) {
                        val error = model.error
                        when {
                            model.loading -> CircularProgressIndicator()
                            error != null ->
                                TextButton(onClick = model::load) { Text("$error Try again") }
                        }
                    }
                }
            }
            GridScrollbar(model.gridState)
        }
    }
}

class JellyfinLibrariesModel(
    private val client: JellyfinClient,
    private val scope: CoroutineScope,
) {
    var libraries by mutableStateOf<List<BaseItemDto>>(emptyList())
        private set

    var loading by mutableStateOf(true)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    val gridState = LazyGridState()

    init {
        load()
    }

    fun load() {
        loading = true
        error = null
        scope.launch {
            try {
                libraries = client.libraries()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = errorText(e)
            } finally {
                loading = false
            }
        }
    }
}

/** What a library holds, by its kind, or what's in a collection, a playlist or a folder. */
private fun folderQuery(route: Route.JellyfinFolder): JellyfinClient.ItemQuery {
    val parent = UUID.fromString(route.id)
    fun all(kind: BaseItemKind, sorted: Boolean = false) =
        JellyfinClient.ItemQuery(kinds = listOf(kind), parentId = parent, sorted = sorted)
    return when (route.collection) {
        // Movies and shows go by the order picked for libraries
        "movies" -> all(BaseItemKind.MOVIE, sorted = true)
        "tvshows" -> all(BaseItemKind.SERIES, sorted = true)
        "boxsets" -> all(BaseItemKind.BOX_SET)
        "playlists" -> all(BaseItemKind.PLAYLIST)
        else ->
            JellyfinClient.ItemQuery(
                parentId = parent,
                recursive = false,
                byName = !route.ordered,
            )
    }
}

/** A library, a collection, a playlist or a folder: what's in it. */
@Composable
fun JellyfinFolderScreen(route: Route.JellyfinFolder) {
    val app = LocalApp.current
    val model =
        rememberPageModel("jellyfin_folder") { scope ->
            JellyfinListModel(app.jellyfin, scope, folderQuery(route))
        }
    JellyfinGridPage(
        title = route.title,
        model = model,
        emptyText = "Nothing in here",
        root = false,
    )
}

/** The playlists made on Jellyfin, each opening its videos in their order. */
@Composable
fun JellyfinPlaylistsScreen() {
    val app = LocalApp.current
    val model =
        rememberPageModel("jellyfin_playlists") { scope ->
            JellyfinListModel(
                app.jellyfin,
                scope,
                JellyfinClient.ItemQuery(kinds = listOf(BaseItemKind.PLAYLIST)),
            )
        }
    JellyfinGridPage(
        title = "Playlists",
        model = model,
        emptyText = "No playlists on Jellyfin yet",
        root = true,
    )
}

/** The movies and shows someone is in. */
@Composable
fun JellyfinPersonScreen(route: Route.JellyfinPerson) {
    val app = LocalApp.current
    val model =
        rememberPageModel("jellyfin_person") { scope ->
            JellyfinListModel(
                app.jellyfin,
                scope,
                JellyfinClient.ItemQuery(
                    kinds = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
                    personId = UUID.fromString(route.id),
                ),
            )
        }
    JellyfinGridPage(
        title = route.name,
        model = model,
        emptyText = "Nothing with ${route.name} in the libraries",
        root = false,
    )
}

/** A few lists side by side in time: movies, shows and episodes, each loaded once. */
class JellyfinGroupsModel(
    private val client: JellyfinClient,
    private val scope: CoroutineScope,
    private val favorites: Boolean,
) {
    var movies by mutableStateOf<List<BaseItemDto>>(emptyList())
        private set

    var shows by mutableStateOf<List<BaseItemDto>>(emptyList())
        private set

    var episodes by mutableStateOf<List<BaseItemDto>>(emptyList())
        private set

    var loading by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    /** What's searched for; empty for the favourites, which need no words. */
    var query by mutableStateOf("")
        private set

    val listState = LazyListState()
    val focus = FocusRequester()
    private var job: Job? = null

    val isEmpty: Boolean
        get() = movies.isEmpty() && shows.isEmpty() && episodes.isEmpty()

    init {
        if (favorites) load()
    }

    fun onQueryChange(text: String) {
        query = text
        job?.cancel()
        if (text.isBlank()) {
            movies = emptyList()
            shows = emptyList()
            episodes = emptyList()
            loading = false
            return
        }
        // A moment after the last key, so a word being typed is searched for once
        job = scope.launch {
            delay(SEARCH_DELAY_MS)
            read()
        }
    }

    fun load() {
        job?.cancel()
        job = scope.launch { read() }
    }

    private suspend fun read() {
        loading = true
        error = null
        try {
            val words = query.trim().takeIf { !favorites }
            suspend fun of(kind: BaseItemKind): List<BaseItemDto> =
                client
                    .query(
                        JellyfinClient.ItemQuery(
                            kinds = listOf(kind),
                            favorites = favorites,
                            search = words,
                        ),
                        startIndex = 0,
                        limit = GROUP_SIZE,
                    )
                    .first
            val found =
                kotlinx.coroutines.coroutineScope {
                    listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.EPISODE)
                        .map { kind -> async { of(kind) } }
                        .map { it.await() }
                }
            movies = found[0]
            shows = found[1]
            episodes = found[2]
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = errorText(e)
        } finally {
            loading = false
        }
    }

    private companion object {
        const val GROUP_SIZE = 60
        const val SEARCH_DELAY_MS = 300L
    }
}

/** Movies, shows and episodes in a row each, as Jellyfin's own search and favourites lay them. */
@Composable
private fun JellyfinGroups(model: JellyfinGroupsModel, emptyText: String?) {
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = model.listState,
            verticalArrangement = Arrangement.spacedBy(28.dp),
            contentPadding = PaddingValues(top = Spacings.small, bottom = Spacings.large),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (model.loading) {
                item { CircularProgressIndicator(modifier = Modifier.padding(start = PagePadding)) }
            }
            model.error?.let { error ->
                item {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = PagePadding),
                    )
                }
            }
            if (model.movies.isNotEmpty()) {
                item(key = "movies") {
                    SectionRow("Movies") {
                        items(model.movies, key = { it.id }) { item ->
                            PosterCard(item = item, modifier = Modifier.width(170.dp))
                        }
                    }
                }
            }
            if (model.shows.isNotEmpty()) {
                item(key = "shows") {
                    SectionRow("Shows") {
                        items(model.shows, key = { it.id }) { item ->
                            PosterCard(item = item, modifier = Modifier.width(170.dp))
                        }
                    }
                }
            }
            if (model.episodes.isNotEmpty()) {
                item(key = "episodes") {
                    SectionRow("Episodes") {
                        items(model.episodes, key = { it.id }) { item ->
                            WideCard(item = item, modifier = Modifier.width(300.dp))
                        }
                    }
                }
            }
            if (emptyText != null && model.isEmpty && !model.loading && model.error == null) {
                item {
                    Text(
                        text = emptyText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = PagePadding),
                    )
                }
            }
        }
        ListScrollbar(model.listState)
    }
}

/** What's marked as a favourite on Jellyfin, with the heart on its page. */
@Composable
fun JellyfinFavoritesScreen() {
    val app = LocalApp.current
    val model =
        rememberPageModel("jellyfin_favorites") { scope ->
            JellyfinGroupsModel(app.jellyfin, scope, favorites = true)
        }
    Column(modifier = Modifier.fillMaxSize()) {
        PageBar(title = "Favourites", onBack = null) { RefreshButton(onClick = model::load) }
        JellyfinGroups(
            model = model,
            emptyText =
                "Nothing marked as a favourite yet. The heart on a movie's, a show's or an " +
                    "episode's page adds it here.",
        )
    }
}

/** Search of Jellyfin's movies, shows and episodes, under the search page's [bar]. */
@Composable
fun JellyfinSearch(bar: @Composable () -> Unit) {
    val app = LocalApp.current
    val model =
        rememberPageModel("jellyfin_search") { scope ->
            JellyfinGroupsModel(app.jellyfin, scope, favorites = false)
        }
    LaunchedEffect(Unit) { runCatching { model.focus.requestFocus() } }
    Column(modifier = Modifier.fillMaxSize()) {
        bar()
        OutlinedTextField(
            value = model.query,
            onValueChange = model::onQueryChange,
            placeholder = { Text("Movies, shows and episodes") },
            leadingIcon = { AppIcon(name = "ic_search", contentDescription = null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { model.load() }),
            shape = RoundedCornerShape(24.dp),
            modifier =
                Modifier.padding(horizontal = PagePadding, vertical = Spacings.medium)
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .focusRequester(model.focus),
        )
        JellyfinGroups(
            model = model,
            emptyText = if (model.query.isBlank()) null else "Nothing found",
        )
    }
}

/** Someone in a movie or a show: their picture, name and part, opening what else they're in. */
@Composable
fun PersonCard(person: BaseItemPerson, modifier: Modifier = Modifier) {
    val client = LocalApp.current.jellyfin
    val navigator = LocalNavigator.current
    val name = person.name.orEmpty()
    Column(
        modifier =
            modifier
                .width(132.dp)
                .clip(MaterialTheme.shapes.small)
                .clickable { navigator.open(Route.JellyfinPerson(person.id.toString(), name)) }
                .padding(4.dp)
    ) {
        JellyfinImage(
            url = client.imageUrl(person.id, ImageType.PRIMARY, person.primaryImageTag, 300),
            modifier =
                Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(MaterialTheme.shapes.small),
        )
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        person.role
            ?.takeIf { it.isNotBlank() }
            ?.let { role ->
                Text(
                    text = role,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
    }
}
