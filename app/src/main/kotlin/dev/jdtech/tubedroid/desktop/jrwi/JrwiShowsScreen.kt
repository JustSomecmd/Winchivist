package dev.jdtech.tubedroid.desktop.jrwi

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.jrwi.JrwiItem
import dev.jdtech.jellyfin.jrwi.JrwiPage
import dev.jdtech.jellyfin.jrwi.JrwiShow
import dev.jdtech.tubedroid.desktop.AppGraph
import dev.jdtech.tubedroid.desktop.LocalApp
import dev.jdtech.tubedroid.desktop.LocalCanGoBack
import dev.jdtech.tubedroid.desktop.LocalNavigator
import dev.jdtech.tubedroid.desktop.Route
import dev.jdtech.tubedroid.desktop.rememberPageModel
import dev.jdtech.tubedroid.desktop.screens.RefreshButton
import dev.jdtech.tubedroid.desktop.ui.AppIcon
import dev.jdtech.tubedroid.desktop.ui.GridScrollbar
import dev.jdtech.tubedroid.desktop.ui.HeaderTabs
import dev.jdtech.tubedroid.desktop.ui.LoadMoreEffect
import dev.jdtech.tubedroid.desktop.ui.PagePadding
import dev.jdtech.tubedroid.desktop.ui.RoundIconButton
import dev.jdtech.tubedroid.desktop.ui.Spacings
import dev.jdtech.tubedroid.desktop.ui.bleed
import dev.jdtech.tubedroid.desktop.ui.fullDate
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** A list the archiver sends a page at a time, loading the next page when asked. */
class JrwiPagedList<T>(
    private val scope: CoroutineScope,
    private val key: (T) -> Any,
    private val load: suspend (page: Int) -> JrwiPage<T>,
) {
    var items by mutableStateOf<List<T>>(emptyList())
        private set

    var loading by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var endReached by mutableStateOf(false)
        private set

    /** How many there are in all, once the first page is in. */
    var total by mutableStateOf<Int?>(null)
        private set

    private var page = 0
    private var job: Job? = null

    fun loadMore() {
        if (loading || endReached) return
        fetch(page + 1, replace = false)
    }

    /** Starts again from the first page, keeping what's shown until it arrives. */
    fun reload() {
        job?.cancel()
        endReached = false
        fetch(1, replace = true)
    }

    private fun fetch(number: Int, replace: Boolean) {
        loading = true
        error = null
        job = scope.launch {
            try {
                val next = load(number)
                page = number
                items = (if (replace) next.items else items + next.items).distinctBy(key)
                endReached = !next.hasMore || next.items.isEmpty()
                total = next.total
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = jrwiErrorText(e)
            } finally {
                loading = false
            }
        }
    }
}

/** The end of a list of the archive: a spinner, what went wrong, or a note when it's empty. */
@Composable
fun <T> JrwiListFooter(list: JrwiPagedList<T>, emptyText: String = "Nothing here yet") {
    Box(
        modifier = Modifier.fillMaxWidth().padding(Spacings.medium),
        contentAlignment = Alignment.Center,
    ) {
        val error = list.error
        when {
            list.loading -> CircularProgressIndicator(modifier = Modifier.size(32.dp))
            error != null ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = error, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = list::loadMore) { Text("Retry") }
                }
            list.items.isEmpty() && list.endReached ->
                Text(text = emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// The shows

class JrwiShowsModel(private val repository: JrwiRepository, private val scope: CoroutineScope) {
    var loading by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    val gridState = LazyGridState()

    init {
        refresh(fresh = false)
    }

    fun refresh(fresh: Boolean = true) {
        if (loading) return
        loading = true
        error = null
        scope.launch {
            try {
                repository.loadShows(fresh)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = jrwiErrorText(e)
            } finally {
                loading = false
            }
        }
    }
}

/** The archive's shows as tiles, each with its picture, or the JRWI logo shown whole. */
@Composable
fun JrwiShowsScreen() {
    val app = LocalApp.current
    val navigator = LocalNavigator.current
    val model = rememberPageModel("jrwi-shows") { scope -> JrwiShowsModel(app.jrwi, scope) }
    val shows = app.jrwi.shows
    JrwiTheme {
        JrwiPage {
            Column(modifier = Modifier.fillMaxSize()) {
                JrwiBar(title = "Shows", onBack = null) {
                    RefreshButton(onClick = { model.refresh() })
                }
                if (shows.isEmpty()) {
                    JrwiPageState(
                        loading = model.loading,
                        error = model.error,
                        onRetry = { model.refresh() },
                    )
                    return@Column
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyVerticalGrid(
                        state = model.gridState,
                        columns = GridCells.Adaptive(minSize = 280.dp),
                        contentPadding = PaddingValues(PagePadding),
                        horizontalArrangement = Arrangement.spacedBy(Spacings.medium),
                        verticalArrangement = Arrangement.spacedBy(Spacings.medium),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(shows, key = { it.id }) { show ->
                            JrwiShowCard(
                                show = show,
                                onClick = { navigator.open(Route.JrwiShowPage(show.id)) },
                            )
                        }
                    }
                    GridScrollbar(model.gridState)
                }
            }
        }
    }
}

/** Whether the show's picture is the JRWI logo, which is shown whole rather than cropped. */
private val JrwiShow.hasLogo: Boolean
    get() = poster == null && banner == null

/** A show's tile: its picture, or the JRWI logo whole on the purple glow, its name and posts. */
@Composable
private fun JrwiShowCard(show: JrwiShow, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Column(
        modifier =
            Modifier.hoverable(hover)
                .pointerHoverIcon(PointerIcon.Hand)
                .clip(JrwiShape)
                .clickable(onClick = onClick)
    ) {
        Box(
            modifier =
                Modifier.fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(JrwiShape)
                    .background(JrwiColors.Picture)
                    .border(
                        width = 2.dp,
                        color =
                            if (hovered) MaterialTheme.colorScheme.primary else Color.Transparent,
                        shape = JrwiShape,
                    )
        ) {
            JrwiShowPicture(show = show, modifier = Modifier.fillMaxSize(), logoPadding = 20.dp)
        }
        Text(
            text = show.name,
            style = MaterialTheme.typography.titleMedium,
            color = JrwiColors.Text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = Spacings.small, start = 2.dp, end = 2.dp),
        )
        Text(
            text = postCount(show.itemCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        Spacer(Modifier.height(Spacings.small))
    }
}

/** The show's picture cropped to fill, or the JRWI logo whole on its glow. */
@Composable
fun JrwiShowPicture(
    show: JrwiShow,
    modifier: Modifier = Modifier,
    logoPadding: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val picture = show.image ?: show.poster ?: show.banner
    if (show.hasLogo) {
        Box(
            modifier =
                modifier.drawBehind {
                    drawRect(
                        Brush.radialGradient(
                            0f to JrwiColors.LogoGlow,
                            1f to Color.Transparent,
                            center = Offset(size.width / 2, size.height * 0.45f),
                            radius = size.width * 0.6f,
                        )
                    )
                }
        ) {
            JrwiImage(
                path = picture,
                contentScale = ContentScale.Fit,
                background = Color.Transparent,
                modifier = Modifier.fillMaxSize().padding(logoPadding),
            )
        }
    } else {
        JrwiImage(path = picture, modifier = modifier)
    }
}

fun postCount(count: Int): String = if (count == 1) "1 post" else "$count posts"

// One show

/**
 * One page of a show: a tab, or one season of a tab that has more than one, named as on the
 * archiver's web app.
 */
data class JrwiShowTab(
    val tab: String,
    val tabName: String,
    val season: Int? = null,
    val seasonName: String? = null,
) {
    /** "Season 2: Empty Graves", "Season 2", "Just Rolled With It: Season 2", or the tab's name. */
    val title: String
        get() {
            val number = season ?: return tabName
            val name = seasonName?.takeIf { it.isNotBlank() }
            return when {
                tab != "episodes" -> "$tabName: Season $number"
                name != null -> "Season $number: $name"
                else -> "Season $number"
            }
        }

    /** What its download button says, or null for pages without one, such as the extras. */
    val downloadLabel: String?
        get() =
            when {
                tab != "episodes" && tab != "aftershow" -> null
                season != null -> "Download season"
                tab == "episodes" -> "Download show"
                else -> "Download all"
            }
}

class JrwiShowModel(
    private val app: AppGraph,
    val showId: String,
    private val scope: CoroutineScope,
) {
    private val api = app.jrwi.api

    var show by mutableStateOf<JrwiShow?>(null)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var tabs by mutableStateOf<List<JrwiShowTab>>(emptyList())
        private set

    var selected by mutableStateOf(0)

    private val lists = mutableMapOf<JrwiShowTab, JrwiPagedList<JrwiItem>>()
    private val gridStates = mutableMapOf<JrwiShowTab, LazyGridState>()

    /** A page's episodes read for its download, while its pop-up asks what to save. */
    var downloadOffer by mutableStateOf<JrwiPageContents?>(null)

    /** A page's episodes are being read for its download. */
    var reading by mutableStateOf(false)
        private set

    init {
        refresh()
    }

    fun refresh() {
        error = null
        scope.launch {
            try {
                val loaded = api.getShow(showId)
                show = loaded
                tabs = pagesOf(loaded)
                selected = selected.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
                lists.values.forEach { it.reload() }
                app.jrwi.loadShows()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (show == null) error = jrwiErrorText(e)
            }
        }
    }

    /** A page per tab, or per season where a tab lists more than one. */
    private fun pagesOf(show: JrwiShow): List<JrwiShowTab> =
        show.tabs.flatMap { tab ->
            // Archivers on contract v0.13 list the episodes' seasons on the show itself
            val seasons =
                tab.seasons.ifEmpty { if (tab.id == "episodes") show.seasons else emptyList() }
            if (seasons.size > 1) {
                seasons.map { JrwiShowTab(tab.id, tabName(tab.id, tab.name), it.season, it.name) }
            } else {
                listOf(JrwiShowTab(tab.id, tabName(tab.id, tab.name)))
            }
        }

    private fun tabName(id: String, name: String): String = name.ifBlank {
        when (id) {
            "episodes" -> "Episodes"
            "aftershow" -> "Just Rolled With It"
            "extras" -> "Extras"
            "art" -> "Art"
            else -> "Posts"
        }
    }

    fun list(tab: JrwiShowTab): JrwiPagedList<JrwiItem> =
        lists.getOrPut(tab) {
            JrwiPagedList<JrwiItem>(scope, key = { it.id }) { page ->
                    api.getShowItems(showId, tab.tab, season = tab.season, page = page)
                }
                .also { it.loadMore() }
        }

    fun gridState(tab: JrwiShowTab): LazyGridState = gridStates.getOrPut(tab) { LazyGridState() }

    /** Reads the page's episodes, for the pop-up that asks what to download of them. */
    fun offerDownload(tab: JrwiShowTab, onMessage: (String) -> Unit) {
        if (reading) return
        reading = true
        scope.launch {
            try {
                val contents = app.jrwiDownloads.pageContents(showId, tab.tab, tab.season)
                if (contents.audio == 0 && contents.video == 0) {
                    onMessage("Nothing here has audio or a video to download.")
                } else {
                    downloadOffer = contents
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onMessage(jrwiErrorText(e))
            } finally {
                reading = false
            }
        }
    }
}

/**
 * A show: its picture across the top with its name, as the web app's show page has it, then a tab
 * for each kind of post it has, such as episodes and aftershows, or each season of them, and their
 * posts as cards. Scrolling folds the picture away, and the bar then has the show's name with the
 * tabs kept under it.
 */
@Composable
fun JrwiShowScreen(showId: String) {
    val app = LocalApp.current
    val navigator = LocalNavigator.current
    val canGoBack = LocalCanGoBack.current
    val model = rememberPageModel("jrwi-show") { scope -> JrwiShowModel(app, showId, scope) }
    val show = model.show
    JrwiTheme {
        JrwiPage {
            if (show == null) {
                Column(modifier = Modifier.fillMaxSize()) {
                    JrwiBar(title = null, onBack = if (canGoBack) navigator::back else null)
                    JrwiPageState(loading = true, error = model.error, onRetry = model::refresh)
                }
                return@JrwiPage
            }
            val tab = model.tabs.getOrNull(model.selected)
            val gridState = tab?.let(model::gridState) ?: remember { LazyGridState() }
            val folded by
                remember(gridState) { derivedStateOf { gridState.firstVisibleItemIndex > 0 } }
            Column(modifier = Modifier.fillMaxSize()) {
                JrwiBar(
                    title = if (folded) show.name else null,
                    onBack = if (canGoBack) navigator::back else null,
                    color = if (folded) JrwiColors.Header else Color.Transparent,
                ) {
                    RefreshButton(onClick = model::refresh)
                    // The page shown, on the archiver's web app
                    RoundIconButton(
                        icon = "ic_external_link",
                        label = "Open in the archiver",
                        onClick = {
                            val query =
                                listOfNotNull(
                                        tab?.let { "tab=${it.tab}" },
                                        tab?.season?.let { "season=$it" },
                                    )
                                    .joinToString("&")
                            openUrl(
                                app.jrwi.api.fileUrl(
                                    "/shows/$showId" + if (query.isEmpty()) "" else "?$query"
                                )
                            )
                        },
                    )
                }
                AnimatedVisibility(visible = folded && model.tabs.size > 1) {
                    JrwiShowTabs(
                        model = model,
                        modifier = Modifier.fillMaxWidth().background(JrwiColors.Header),
                    )
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    val list = tab?.let(model::list)
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(minSize = 260.dp),
                        contentPadding =
                            PaddingValues(
                                start = PagePadding,
                                end = PagePadding,
                                bottom = Spacings.large,
                            ),
                        horizontalArrangement = Arrangement.spacedBy(Spacings.medium),
                        verticalArrangement = Arrangement.spacedBy(Spacings.medium),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        item(key = "hero", span = { GridItemSpan(maxLineSpan) }) {
                            // From edge to edge, past the grid's own padding
                            JrwiShowHero(show, Modifier.bleed(PagePadding))
                        }
                        item(key = "tabs", span = { GridItemSpan(maxLineSpan) }) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (model.tabs.size > 1) {
                                    JrwiShowTabs(model = model, modifier = Modifier.weight(1f))
                                } else {
                                    Spacer(Modifier.weight(1f))
                                }
                                val label = tab?.downloadLabel
                                if (tab != null && label != null) {
                                    JrwiPageDownloadButton(
                                        label = label,
                                        busy = model.reading,
                                        onClick = { model.offerDownload(tab, navigator::message) },
                                    )
                                }
                            }
                        }
                        if (list != null) {
                            items(list.items, key = { it.id }, contentType = { "item" }) { item ->
                                JrwiItemCard(
                                    item = item,
                                    // Posts the site's tags place here from another show say which
                                    otherShow =
                                        item.show
                                            .takeIf { it != showId }
                                            ?.let { other ->
                                                app.jrwi.shows.firstOrNull { it.id == other }?.name
                                            },
                                    onClick = { navigator.open(Route.JrwiPost(item.id)) },
                                )
                            }
                            item(key = "footer", span = { GridItemSpan(maxLineSpan) }) {
                                JrwiListFooter(list)
                            }
                        }
                    }
                    if (list != null) LoadMoreEffect(gridState, list::loadMore)
                    GridScrollbar(gridState)
                }
            }
            val offer = model.downloadOffer
            if (offer != null && tab != null) {
                JrwiPageDownloadDialog(
                    title = tab.downloadLabel.orEmpty(),
                    contents = offer,
                    onDismiss = { model.downloadOffer = null },
                    onDownload = { audio, withFiles ->
                        model.downloadOffer = null
                        val added =
                            app.jrwiDownloads.downloadAll(offer, audio, withFiles, show.name)
                        navigator.message(
                            if (added == 0) "Nothing here could be downloaded."
                            else
                                "Saving $added ${if (added == 1) "episode" else "episodes"} to " +
                                    jrwiDownloadFolder(app, show.name).path
                        )
                    },
                )
            }
        }
    }
}

/** The show's pages as tabs: plain words, the shown one underlined in the archiver's yellow. */
@Composable
private fun JrwiShowTabs(model: JrwiShowModel, modifier: Modifier = Modifier) {
    Box(modifier = modifier.horizontalScroll(rememberScrollState())) {
        HeaderTabs(
            tabs = model.tabs.map { it.title },
            selected = model.selected,
            onSelect = { model.selected = it },
            modifier = Modifier.padding(horizontal = Spacings.small, vertical = 4.dp),
        )
    }
}

/**
 * The show's picture across the top, fading into the page, with its name in capitals and how many
 * posts it has over it. A show without a picture of its own has the JRWI logo whole on its glow.
 */
@Composable
private fun JrwiShowHero(show: JrwiShow, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.BottomStart,
        modifier = modifier.fillMaxWidth().height(HERO_HEIGHT).background(JrwiColors.Header),
    ) {
        JrwiShowPicture(
            show = show,
            modifier = Modifier.fillMaxSize(),
            logoPadding = 48.dp,
        )
        // Into the page at the bottom, as the web app's .hero
        Box(
            Modifier.fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.42f to Color.Transparent,
                        1f to JrwiColors.Background.copy(alpha = 0.96f),
                    )
                )
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier =
                Modifier.padding(start = PagePadding, end = PagePadding, bottom = Spacings.medium),
        ) {
            Text(
                text = show.name.uppercase(),
                color = JrwiColors.Text,
                style =
                    MaterialTheme.typography.displaySmall.copy(
                        fontWeight = FontWeight.Black,
                        shadow =
                            Shadow(
                                color = Color.Black.copy(alpha = 0.6f),
                                offset = Offset(0f, 4f),
                                blurRadius = 24f,
                            ),
                    ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val newest = fullDate(show.latest)?.let { "newest $it" }
            Text(
                text = listOfNotNull(postCount(show.itemCount), newest).joinToString(" · "),
                style = MaterialTheme.typography.bodyLarge,
                color = JrwiColors.ChipText,
            )
        }
    }
}

private val HERO_HEIGHT = 320.dp

/** The button that saves every episode of the page to this computer, after its pop-up. */
@Composable
private fun JrwiPageDownloadButton(label: String, busy: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = !busy, shape = JrwiShape) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        } else {
            AppIcon(
                name = "ic_download",
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(Spacings.small))
        Text(label)
    }
}

/**
 * Asks what to save of a page's episodes: the audio or the video, with how many there are of each,
 * only when the page has both; then with or without the posts' own files.
 */
@Composable
fun JrwiPageDownloadDialog(
    title: String,
    contents: JrwiPageContents,
    onDismiss: () -> Unit,
    onDownload: (audio: Boolean, withFiles: Boolean) -> Unit,
) {
    val both = contents.audio > 0 && contents.video > 0
    var audio by remember { mutableStateOf(contents.audio > 0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacings.medium)) {
                if (both) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacings.small)) {
                        FilterChip(
                            selected = audio,
                            onClick = { audio = true },
                            label = { Text("Audio · ${contents.audio}") },
                        )
                        FilterChip(
                            selected = !audio,
                            onClick = { audio = false },
                            label = { Text("Video · ${contents.video}") },
                        )
                    }
                } else {
                    val count = if (audio) contents.audio else contents.video
                    Text(
                        "$count ${if (count == 1) "episode" else "episodes"}, as " +
                            if (audio) "audio." else "video."
                    )
                }
                Text(
                    "Each episode's attachments, documents and pictures can come too, in a " +
                        "folder next to it. They can take up a lot of space."
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { onDownload(audio, false) }) { Text("Without files") }
                TextButton(onClick = { onDownload(audio, true) }) { Text("With files") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
