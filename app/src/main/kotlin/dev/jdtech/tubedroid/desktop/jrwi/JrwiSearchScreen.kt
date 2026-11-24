package dev.jdtech.tubedroid.desktop.jrwi

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.jrwi.JrwiFeature
import dev.jdtech.jellyfin.jrwi.JrwiItem
import dev.jdtech.jellyfin.jrwi.JrwiTagCount
import dev.jdtech.tubedroid.desktop.LocalApp
import dev.jdtech.tubedroid.desktop.LocalNavigator
import dev.jdtech.tubedroid.desktop.Route
import dev.jdtech.tubedroid.desktop.rememberPageModel
import dev.jdtech.tubedroid.desktop.ui.AppIcon
import dev.jdtech.tubedroid.desktop.ui.GridScrollbar
import dev.jdtech.tubedroid.desktop.ui.LoadMoreEffect
import dev.jdtech.tubedroid.desktop.ui.PagePadding
import dev.jdtech.tubedroid.desktop.ui.Spacings
import dev.jdtech.tubedroid.desktop.ui.fullDate
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A search of the archive's posts, as the web feed's: by words in their title, text and tags, kept
 * to a show, a tab or a tag, newest or oldest first.
 */
class JrwiSearchModel(
    private val repository: JrwiRepository,
    private val scope: CoroutineScope,
    show: String?,
    tag: String?,
) {
    private val api = repository.api

    var query by mutableStateOf("")
        private set

    var show by mutableStateOf(show)
        private set

    var tab by mutableStateOf<String?>(null)
        private set

    var tag by mutableStateOf(tag)
        private set

    var newestFirst by mutableStateOf(true)
        private set

    /** The archive's tags for the tag menu, or null when the archiver doesn't list them. */
    var tags by mutableStateOf<List<JrwiTagCount>?>(null)
        private set

    val gridState = LazyGridState()
    val focus = FocusRequester()
    private var typing: Job? = null

    /** Whether anything is asked for: words, or something to keep the posts to. */
    val active: Boolean
        get() = query.isNotBlank() || show != null || tab != null || tag != null

    val results =
        JrwiPagedList<JrwiItem>(scope, key = { it.id }) { page ->
            api.getPosts(
                page = page,
                pageSize = PAGE_SIZE,
                order = if (newestFirst) "desc" else "asc",
                query = query.trim().takeIf { it.isNotEmpty() },
                show = this.show,
                tab = tab,
                // Only to an archiver that lists its tags; an older one would ignore it
                tag = this.tag?.takeIf { tags != null },
            )
        }

    init {
        scope.launch {
            try {
                repository.loadShows()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
        }
        scope.launch {
            tags =
                try {
                    if (api.has(JrwiFeature.TAGS) == false) null else api.getTags()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            if (active) results.reload()
        }
    }

    /** Searches a moment after typing stops. */
    fun onQueryChange(text: String) {
        query = text
        typing?.cancel()
        typing = scope.launch {
            delay(TYPING_DELAY_MS)
            search()
        }
    }

    fun search() {
        typing?.cancel()
        if (active) results.reload()
    }

    fun pickShow(id: String?) {
        show = id
        search()
    }

    fun pickTab(id: String?) {
        tab = id
        search()
    }

    fun pickTag(name: String?) {
        tag = name
        search()
    }

    fun pickOrder(newest: Boolean) {
        newestFirst = newest
        search()
    }

    fun clearFilters() {
        show = null
        tab = null
        tag = null
        search()
    }

    private companion object {
        const val PAGE_SIZE = 30
        const val TYPING_DELAY_MS = 350L
    }
}

/** The search of the JRWI archive, under the search page's [bar], in the archiver's colours. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JrwiSearch(bar: @Composable () -> Unit, show: String?, tag: String?) {
    val app = LocalApp.current
    val navigator = LocalNavigator.current
    val model =
        rememberPageModel("jrwi-search") { scope -> JrwiSearchModel(app.jrwi, scope, show, tag) }
    LaunchedEffect(Unit) { runCatching { model.focus.requestFocus() } }
    val shows = app.jrwi.shows
    val showNames = remember(shows) { shows.associate { it.id to it.name } }
    val tags = model.tags
    JrwiTheme {
        JrwiPage {
            Column(modifier = Modifier.fillMaxSize()) {
                bar()
                OutlinedTextField(
                    value = model.query,
                    onValueChange = model::onQueryChange,
                    placeholder = {
                        Text(
                            if (tags != null) "Search titles, text and tags"
                            else "Search titles and text"
                        )
                    },
                    leadingIcon = { AppIcon(name = "ic_search", contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { model.search() }),
                    shape = JrwiShape,
                    colors = jrwiFieldColors(),
                    modifier =
                        Modifier.padding(
                                start = PagePadding,
                                end = PagePadding,
                                top = Spacings.medium,
                            )
                            .widthIn(max = 720.dp)
                            .fillMaxWidth()
                            .focusRequester(model.focus),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacings.small),
                    verticalArrangement = Arrangement.spacedBy(Spacings.small),
                    modifier =
                        Modifier.padding(horizontal = PagePadding, vertical = Spacings.medium),
                ) {
                    JrwiMenuButton(
                        label = model.show?.let { showNames[it] ?: it } ?: "All shows",
                        on = model.show != null,
                        options =
                            listOf<Pair<String?, String>>(null to "All shows") +
                                shows.map { it.id to it.name },
                        onPick = model::pickShow,
                    )
                    JrwiMenuButton(
                        label = model.tab?.let { tabName(it) } ?: "Every tab",
                        on = model.tab != null,
                        options =
                            listOf<Pair<String?, String>>(null to "Every tab") +
                                TABS.map { it to tabName(it) },
                        onPick = model::pickTab,
                    )
                    if (tags != null) {
                        JrwiMenuButton(
                            label = model.tag ?: "All tags",
                            on = model.tag != null,
                            options =
                                listOf<Pair<String?, String>>(null to "All tags") +
                                    tags.map { it.name to "${it.name} (${it.count})" },
                            onPick = model::pickTag,
                        )
                    }
                    JrwiMenuButton(
                        label = if (model.newestFirst) "Newest first" else "Oldest first",
                        on = false,
                        options = listOf("newest" to "Newest first", "oldest" to "Oldest first"),
                        onPick = { model.pickOrder(it == "newest") },
                    )
                    if (model.show != null || model.tab != null || model.tag != null) {
                        TextButton(onClick = model::clearFilters) { Text("Clear filters") }
                    }
                }
                if (!model.active) {
                    Text(
                        text =
                            if (tags != null) {
                                "Search the archive's posts by their title, text and tags, or " +
                                    "keep them to a show, a tab or a tag."
                            } else {
                                "Search the archive's posts by their title and text, or keep " +
                                    "them to a show or a tab. Picking a tag needs an archiver " +
                                    "that lists its tags."
                            },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = PagePadding),
                    )
                    return@Column
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    val results = model.results
                    LazyVerticalGrid(
                        state = model.gridState,
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
                        results.total?.let { total ->
                            item(key = "count", span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    text = postCount(total),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        items(results.items, key = { it.id }, contentType = { "post" }) { post ->
                            JrwiItemCard(
                                item = post,
                                // Each post on its own, with its show and which version it is
                                details = searchDetails(post, showNames[post.show]),
                                label = versionLabel(post),
                                onClick = { navigator.open(Route.JrwiPost(post.id)) },
                            )
                        }
                        item(key = "footer", span = { GridItemSpan(maxLineSpan) }) {
                            JrwiListFooter(results, emptyText = "Nothing found")
                        }
                    }
                    LoadMoreEffect(model.gridState, results::loadMore)
                    GridScrollbar(model.gridState)
                }
            }
        }
    }
}

private val TABS = listOf("episodes", "aftershow", "extras", "art", "posts")

private fun tabName(id: String): String =
    when (id) {
        "episodes" -> "Episodes"
        "aftershow" -> "Just Rolled With It"
        "extras" -> "Extras"
        "art" -> "Art"
        else -> "Posts"
    }

/** A found post's show, number and date. */
private fun searchDetails(post: JrwiItem, showName: String?): String {
    val episode = post.episode?.takeIf { "episodes" in post.ownTabs }
    val season = post.season ?: 1
    val number =
        when {
            episode == null -> null
            season > 1 -> "S$season #$episode"
            else -> "#$episode"
        }
    return listOfNotNull(showName, number, fullDate(post.date)).joinToString(" · ")
}

/** A button that opens a menu of [options] and shows the one picked; lit when it narrows. */
@Composable
private fun <T> JrwiMenuButton(
    label: String,
    on: Boolean,
    options: List<Pair<T, String>>,
    onPick: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier =
                Modifier.clip(JrwiPillShape)
                    .background(if (on) MaterialTheme.colorScheme.primary else JrwiColors.Pill)
                    .clickable { open = true }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            val ink = if (on) MaterialTheme.colorScheme.onPrimary else JrwiColors.Text
            Text(text = label, style = MaterialTheme.typography.labelLarge, color = ink)
            AppIcon(
                name = "ic_chevron_down",
                contentDescription = null,
                tint = ink,
                modifier = Modifier.size(16.dp).rotate(if (open) 180f else 0f),
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.heightIn(max = 480.dp),
        ) {
            options.forEach { (value, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        open = false
                        onPick(value)
                    },
                )
            }
        }
    }
}
