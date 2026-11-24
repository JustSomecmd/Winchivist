package dev.jdtech.tubedroid.desktop.jrwi

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.jrwi.JrwiApiException
import dev.jdtech.jellyfin.jrwi.JrwiComment
import dev.jdtech.jellyfin.jrwi.JrwiContinue
import dev.jdtech.jellyfin.jrwi.JrwiFeature
import dev.jdtech.jellyfin.jrwi.JrwiFeedEntry
import dev.jdtech.jellyfin.jrwi.JrwiItem
import dev.jdtech.jellyfin.jrwi.JrwiStatus
import dev.jdtech.tubedroid.desktop.LocalApp
import dev.jdtech.tubedroid.desktop.LocalNavigator
import dev.jdtech.tubedroid.desktop.Route
import dev.jdtech.tubedroid.desktop.Section
import dev.jdtech.tubedroid.desktop.rememberPageModel
import dev.jdtech.tubedroid.desktop.screens.RefreshButton
import dev.jdtech.tubedroid.desktop.ui.AppIcon
import dev.jdtech.tubedroid.desktop.ui.ListScrollbar
import dev.jdtech.tubedroid.desktop.ui.PagePadding
import dev.jdtech.tubedroid.desktop.ui.SectionRow
import dev.jdtech.tubedroid.desktop.ui.Spacings
import dev.jdtech.tubedroid.desktop.ui.fullDate
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ceil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What JRWI's Home shows: the episodes started and not finished, and the archive's feed, a page at
 * a time, each post whole with its first comments.
 */
class JrwiHomeModel(private val repository: JrwiRepository, private val scope: CoroutineScope) {
    private val api = repository.api

    var continueWatching by mutableStateOf<List<JrwiContinue>>(emptyList())
        private set

    var feed by mutableStateOf<List<JrwiFeedEntry>>(emptyList())
        private set

    var loading by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var endReached by mutableStateOf(false)
        private set

    /** The archiver's version and what it's doing, for the header's pill. */
    var version by mutableStateOf<String?>(null)
        private set

    var status by mutableStateOf<JrwiStatus?>(null)
        private set

    val listState = LazyListState()
    private var page = 0
    private var job: Job? = null
    private var shown = false

    init {
        refresh()
    }

    fun refresh() {
        job?.cancel()
        page = 0
        endReached = false
        loading = false
        loadMore(replace = true)
        loadContinue()
        scope.launch {
            try {
                repository.loadShows()
                version = api.getPing().version
                status = api.getStatus()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The feed's own error says the archiver can't be reached
            }
        }
    }

    /** Reads Continue watching again when Home shows again, a moment after a player closed. */
    fun onShown() {
        if (!shown) {
            shown = true
            return
        }
        scope.launch {
            delay(RETURN_DELAY_MS)
            loadContinue()
        }
    }

    private fun loadContinue() {
        scope.launch {
            try {
                continueWatching = api.getContinue(CONTINUE_LIMIT)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Left as it was; an archiver without the list has none
            }
        }
    }

    fun loadMore(replace: Boolean = false) {
        if (loading || endReached) return
        loading = true
        error = null
        job = scope.launch {
            try {
                if (api.has(JrwiFeature.FEED) == false) {
                    error =
                        "This archiver has no feed for apps yet. Updating it to 0.9.7 or " +
                            "newer brings the feed here."
                    endReached = true
                    return@launch
                }
                val next = api.getFeed(page = page + 1, pageSize = FEED_PAGE)
                page = next.page
                feed = (if (replace) next.items else feed + next.items).distinctBy { it.post.id }
                endReached = !next.hasMore || next.items.isEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: JrwiApiException) {
                error =
                    if (e.code == 404) {
                        "This archiver has no feed for apps yet. Updating it brings the " +
                            "feed here."
                    } else {
                        jrwiErrorText(e)
                    }
                if (e.code == 404) endReached = true
            } catch (e: Exception) {
                error = jrwiErrorText(e)
            } finally {
                loading = false
            }
        }
    }

    private companion object {
        const val CONTINUE_LIMIT = 12
        const val FEED_PAGE = 20
        const val RETURN_DELAY_MS = 1_000L
    }
}

/**
 * JRWI's Home: what was started and not finished, then the archive's feed, as the web app's Home
 * has it. The pill in its header says which archiver this is and what it's doing.
 */
@Composable
fun JrwiHomeScreen() {
    val app = LocalApp.current
    val navigator = LocalNavigator.current
    val model = rememberPageModel("jrwi-home") { scope -> JrwiHomeModel(app.jrwi, scope) }
    LaunchedEffect(Unit) { model.onShown() }
    JrwiTheme {
        JrwiPage {
            Column(modifier = Modifier.fillMaxSize()) {
                JrwiBar(
                    title = null,
                    onBack = null,
                    start = { JrwiHeaderPill(model.version, model.status) },
                ) {
                    RefreshButton(onClick = model::refresh)
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    JrwiHomeList(model, onOpen = { navigator.open(Route.JrwiPost(it)) })
                    ListScrollbar(model.listState)
                }
            }
        }
    }
}

/**
 * The header's "JRWI" pill, which opens the archiver's address, version and what it's doing, with
 * its page here, its web app and the app's settings.
 */
@Composable
private fun JrwiHeaderPill(version: String?, status: JrwiStatus?) {
    val app = LocalApp.current
    val navigator = LocalNavigator.current
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacings.small),
            modifier =
                Modifier.clip(JrwiPillShape)
                    .background(JrwiColors.Pill)
                    .clickable { open = true }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            AppIcon(
                name = "ic_dice",
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = "JRWI",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = JrwiColors.Text,
            )
            if (status?.running == true) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(JrwiColors.Ok))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.width(320.dp).padding(horizontal = Spacings.medium),
            ) {
                Text(
                    text = "JRWI archiver" + (version?.let { " $it" } ?: ""),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = JrwiColors.Text,
                )
                Text(
                    text = app.jrwi.api.baseUrl.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                status?.let {
                    Text(
                        text =
                            if (it.running) "Checking: ${it.phase.orEmpty()}"
                            else "Waiting for the next check",
                        style = MaterialTheme.typography.bodyMedium,
                        color = JrwiColors.BodyText,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                HorizontalDivider(
                    color = JrwiColors.Line,
                    modifier = Modifier.padding(vertical = Spacings.small),
                )
            }
            JrwiMenuLine("ic_server", "What the archiver is doing") {
                open = false
                navigator.select(Section.JRWI_ARCHIVER)
            }
            JrwiMenuLine("ic_external_link", "Open the archiver's web app") {
                open = false
                openUrl(app.jrwi.api.baseUrl)
            }
            JrwiMenuLine("ic_settings", "Settings") {
                open = false
                navigator.select(Section.SETTINGS)
            }
        }
    }
}

@Composable
private fun JrwiMenuLine(icon: String, text: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacings.medium),
        modifier =
            Modifier.fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = Spacings.medium, vertical = 10.dp),
    ) {
        AppIcon(
            name = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Text(text = text, style = MaterialTheme.typography.bodyLarge, color = JrwiColors.Text)
    }
}

/**
 * Continue watching, then the archive's feed: every post on its own, newest first, loaded a page at
 * a time as it's scrolled, in a column down the middle as on the web app.
 */
@Composable
private fun JrwiHomeList(model: JrwiHomeModel, onOpen: (Long) -> Unit) {
    val app = LocalApp.current
    val showNames = remember(app.jrwi.shows) { app.jrwi.shows.associate { it.id to it.name } }
    LazyColumn(
        state = model.listState,
        contentPadding = PaddingValues(top = Spacings.medium, bottom = Spacings.large),
        verticalArrangement = Arrangement.spacedBy(Spacings.medium),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (model.continueWatching.isNotEmpty()) {
            item(key = "continue") {
                SectionRow("Continue watching") {
                    items(model.continueWatching, key = { "continue:" + it.item.id }) { entry ->
                        JrwiContinueCard(
                            entry = entry,
                            // On the version it was played in
                            onClick = { onOpen(entry.progress.postId ?: entry.item.id) },
                            modifier = Modifier.width(CONTINUE_CARD_WIDTH),
                        )
                    }
                }
            }
        }
        if (model.feed.isNotEmpty()) {
            item(key = "feed-title") {
                FeedColumn {
                    Text(
                        text = "Latest",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Medium,
                        color = JrwiColors.Text,
                    )
                }
            }
        }
        // Cards of one kind, so scrolling reuses them rather than building each anew
        itemsIndexed(
            model.feed,
            key = { _, entry -> "feed:" + entry.post.id },
            contentType = { _, _ -> "feed-post" },
        ) { index, entry ->
            // The next page, a few posts before the end
            if (index >= model.feed.size - FEED_PREFETCH) {
                LaunchedEffect(model.feed.size) { model.loadMore() }
            }
            FeedColumn {
                JrwiFeedCard(
                    entry = entry,
                    showName = showNames[entry.post.show],
                    onClick = { onOpen(entry.post.id) },
                )
            }
        }
        item(key = "feed-foot") {
            FeedColumn {
                val error = model.error
                when {
                    error != null ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = error, color = MaterialTheme.colorScheme.error)
                            if (!model.endReached) {
                                TextButton(onClick = { model.loadMore() }) { Text("Retry") }
                            }
                        }
                    model.loading ->
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(32.dp))
                        }
                    model.feed.isEmpty() ->
                        Text(
                            text = "Nothing here yet",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                }
            }
        }
    }
}

/** Keeps the feed to a column down the middle of a wide window, as the web app's. */
@Composable
private fun FeedColumn(content: @Composable () -> Unit) {
    Box(
        contentAlignment = Alignment.TopCenter,
        modifier = Modifier.fillMaxWidth().padding(horizontal = PagePadding),
    ) {
        Box(modifier = Modifier.widthIn(max = FEED_WIDTH).fillMaxWidth()) { content() }
    }
}

private val FEED_WIDTH = 760.dp
private val CONTINUE_CARD_WIDTH = 280.dp

// How many posts before the end of the feed the next page is asked for
private const val FEED_PREFETCH = 5

/**
 * An episode in Continue watching: its picture with how far it got and the version it was played
 * in, as the web app shows it, then its title and the minutes left.
 */
@Composable
private fun JrwiContinueCard(entry: JrwiContinue, onClick: () -> Unit, modifier: Modifier) {
    val item = entry.item
    val progress = entry.progress
    val audio = progress.postId != null && progress.postId == item.audio?.postId
    val length =
        progress.duration
            ?: (if (audio) item.audio?.duration else item.video?.duration)
            ?: item.duration
    val left =
        length
            ?.takeIf { it > progress.position }
            ?.let { ceil((it - progress.position) / 60).toInt() }
    JrwiItemCard(
        item = item,
        onClick = onClick,
        marks = false,
        details =
            listOfNotNull(left?.let { "$it min left" }, itemDetails(item)).joinToString(" · "),
        modifier = modifier,
    ) {
        JrwiPictureBadge(
            icon = if (audio) "ic_headphones" else "ic_film",
            description = if (audio) "Audio" else "Video",
            modifier = Modifier.align(Alignment.TopStart),
        )
        if (length != null && length > 0) {
            JrwiPlayedBar((progress.position / length).toFloat())
        }
    }
}

/**
 * A post in the feed, laid out as the web app's feed has it: its media, then the show and date, its
 * title, a short preview of its text that opens in place, its files and tags, likes, comments, the
 * post in the archive's web app, and its first comments. A click opens the post's page, and
 * pictures open in the viewer.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JrwiFeedCard(entry: JrwiFeedEntry, showName: String?, onClick: () -> Unit) {
    val app = LocalApp.current
    val navigator = LocalNavigator.current
    val post = entry.post
    // Its pictures, then its picture files and PDFs, in one viewer, as on the web app's feed
    val shown = remember(post) { jrwiViewerItems(post) }
    fun show(at: Int) {
        if (at in shown.indices) navigator.showPictures(JrwiPictures(shown, at))
    }
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .clip(JrwiShape)
                .background(JrwiColors.Card)
                .border(1.dp, JrwiColors.Line, JrwiShape)
                .clickable(onClick = onClick)
    ) {
        JrwiFeedMedia(post = post, onPicture = ::show)
        Column(
            verticalArrangement = Arrangement.spacedBy(Spacings.small),
            modifier = Modifier.padding(Spacings.medium),
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacings.small),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = listOfNotNull(showName, fullDate(post.date)).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = JrwiColors.Muted,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
                if (post.pinned) JrwiTag("Pinned")
                versionLabel(post)?.let { JrwiTag(it) }
            }
            Text(
                text = post.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = JrwiColors.Text,
            )
            JrwiFeedText(post)
            JrwiFeedFiles(
                post = post,
                onMore = onClick,
                onShow = { address ->
                    val at = shown.indexOfFirst { it.address == address }
                    if (at >= 0) show(at) else onClick()
                },
            )
            if (post.tags.isNotEmpty()) JrwiTags(post.tags)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacings.small),
                verticalArrangement = Arrangement.spacedBy(Spacings.small),
                modifier = Modifier.padding(top = Spacings.small),
            ) {
                post.likeCount?.let { JrwiPill("ic_heart", it.toString()) }
                JrwiPill(
                    icon = "ic_message_square",
                    text = (post.commentCount ?: 0).toString(),
                    onClick = onClick,
                )
                // The post in the archiver's web app
                app.jrwi.api.fileUrl("/items/${post.id}")?.let { url ->
                    JrwiPill(
                        icon = "ic_external_link",
                        text = "Original post",
                        onClick = { openUrl(url) },
                    )
                }
            }
            if (entry.topComments.isNotEmpty()) {
                Column(modifier = Modifier.padding(top = Spacings.small)) {
                    entry.topComments.forEach { comment -> JrwiFeedComment(comment) }
                    if (entry.moreComments) {
                        Text(
                            text = "More comments",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier =
                                Modifier.clip(JrwiShape)
                                    .clickable(onClick = onClick)
                                    .padding(vertical = Spacings.small),
                        )
                    }
                }
            }
        }
    }
}

/**
 * A feed post's media, as the web app's feed shows it: an audio or video post's picture with its
 * length and watched mark, or the post's pictures, up to four with how many more.
 */
@Composable
private fun JrwiFeedMedia(post: JrwiItem, onPicture: (Int) -> Unit) {
    val thumbnail = post.thumbnail
    val pictures = post.images.map { it.url }
    when {
        post.audio != null || (post.video != null && thumbnail != null) ->
            JrwiThumbnail(
                item = post,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth(),
            )
        pictures.isNotEmpty() -> JrwiImageGrid(paths = pictures, onPicture = onPicture)
        thumbnail != null ->
            JrwiImage(
                path = thumbnail,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            )
    }
}

/**
 * Up to four of a post's pictures, as the web app's feed lays them out: one whole, two or four side
 * by side, or three with the first across the top. The fourth says how many more there are. A click
 * opens them all in the viewer.
 */
@Composable
private fun JrwiImageGrid(paths: List<String>, onPicture: (Int) -> Unit) {
    if (paths.size == 1) {
        JrwiImage(
            path = paths[0],
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clickable { onPicture(0) },
        )
        return
    }
    val rows =
        if (paths.size == 3) listOf(listOf(0), listOf(1, 2))
        else listOf(listOf(0, 1), listOf(2, 3).filter { it < paths.size })
    Column(verticalArrangement = Arrangement.spacedBy(GRID_GAP)) {
        rows
            .filter { it.isNotEmpty() }
            .forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(GRID_GAP)) {
                    row.forEach { index ->
                        // One across the top is twice as wide as it's tall; pairs a little wider
                        val ratio = if (row.size == 1) 2.4f else 1.5f
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier =
                                Modifier.weight(1f).aspectRatio(ratio).clickable {
                                    onPicture(index)
                                },
                        ) {
                            JrwiImage(
                                path = paths[index],
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                            val more = paths.size - MAX_GRID
                            if (index == MAX_GRID - 1 && more > 0) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier =
                                        Modifier.fillMaxSize()
                                            .background(Color.Black.copy(alpha = 0.55f)),
                                ) {
                                    Text(
                                        text = "+$more",
                                        style = MaterialTheme.typography.headlineMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                    )
                                }
                            }
                        }
                    }
                }
            }
    }
}

private const val MAX_GRID = 4
private val GRID_GAP = 2.dp

/**
 * A short preview of a post's text, as the web app's feed has it, and "…Continue reading" to open
 * the rest in place, with its links.
 */
@Composable
private fun JrwiFeedText(post: JrwiItem) {
    val text = post.bodyText?.trim().orEmpty()
    if (text.isEmpty()) return
    var open by rememberSaveable(post.id) { mutableStateOf(false) }
    var cut by remember(post.id) { mutableStateOf(false) }
    if (open) {
        JrwiHtmlText(html = post.bodyHtml?.takeIf { it.isNotBlank() }, text = text)
        return
    }
    Column {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = JrwiColors.BodyText,
            maxLines = PREVIEW_LINES,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { cut = it.hasVisualOverflow },
        )
        if (cut) {
            Text(
                text = "…Continue reading",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier =
                    Modifier.clip(JrwiShape)
                        .clickable { open = true }
                        .padding(vertical = Spacings.small),
            )
        }
    }
}

private const val PREVIEW_LINES = 4

/**
 * A feed post's attachments and documents, the first few listed: pictures and PDFs open in the
 * viewer ([onShow], with the file's address), the rest on the post's page.
 */
@Composable
private fun JrwiFeedFiles(post: JrwiItem, onMore: () -> Unit, onShow: (String) -> Unit) {
    val files =
        remember(post) {
            (post.attachments.map { it.asPostFile("attachment") } +
                    post.documents.map { it.asPostFile("document") })
                .distinctBy { it.url }
        }
    if (files.isEmpty()) return
    Column {
        files.take(FEED_FILES).forEach { file ->
            JrwiLinkRow(
                icon =
                    jrwiFileIcon(
                        if (file.type == "other" && file.role == "document") "document"
                        else file.type
                    ),
                text = file.name,
                detail = file.size?.let { formatBytes(it) },
                onClick = { onShow(file.url.orEmpty()) },
            )
        }
        if (files.size > FEED_FILES) {
            Text(
                text = "${files.size - FEED_FILES} more files",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier =
                    Modifier.clip(JrwiShape)
                        .clickable(onClick = onMore)
                        .padding(horizontal = Spacings.small, vertical = Spacings.small),
            )
        }
    }
}

private const val FEED_FILES = 3

/**
 * One of a feed post's first comments, as the web app's feed shows them: the name in its tier's
 * colour with its badges, Pinned, how long ago and a link to it on the site, the start of the text
 * with "Read more" to open the rest, its likes, and its replies, which open under it.
 */
@Composable
private fun JrwiFeedComment(comment: JrwiComment) {
    var open by rememberSaveable(comment.id) { mutableStateOf(false) }
    var cut by remember(comment.id) { mutableStateOf(false) }
    var replies by rememberSaveable(comment.id) { mutableStateOf(false) }
    Column {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacings.small),
            modifier = Modifier.fillMaxWidth().padding(vertical = Spacings.small),
        ) {
            JrwiCommentAvatar(comment.author, 36.dp)
            Column(modifier = Modifier.weight(1f)) {
                JrwiCommentHeader(comment)
                if (open) {
                    JrwiHtmlText(
                        html = comment.bodyHtml?.takeIf { it.isNotBlank() },
                        text = comment.bodyText,
                        style = MaterialTheme.typography.bodyMedium,
                        gap = JRWI_COMMENT_PARAGRAPH_GAP,
                    )
                } else {
                    Text(
                        text = comment.bodyText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = JrwiColors.BodyText,
                        maxLines = COMMENT_LINES,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { cut = it.hasVisualOverflow },
                    )
                    if (cut) {
                        Text(
                            text = "Read more",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier =
                                Modifier.clip(JrwiShape)
                                    .clickable { open = true }
                                    .padding(vertical = 4.dp),
                        )
                    }
                }
                // The web feed's foot: likes, and the replies, which open under the comment
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacings.medium),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    JrwiCommentCount(
                        icon = "ic_heart",
                        text = comment.likeCount?.takeIf { it > 0 }?.toString(),
                    )
                    val count = comment.replies.size
                    if (count > 0) {
                        JrwiCommentCount(
                            icon = "ic_message_square",
                            text = count.toString(),
                            action = if (replies) "Hide replies" else "Load replies",
                            onClick = { replies = !replies },
                        )
                    }
                }
            }
        }
        if (replies) {
            comment.replies.flattenReplies(depth = 1).forEach { (reply, depth) ->
                JrwiCommentRow(comment = reply, depth = depth)
            }
        }
    }
}

private const val COMMENT_LINES = 3

/** A count under a comment in the feed, with what a click does, as the web feed's foot. */
@Composable
private fun JrwiCommentCount(
    icon: String,
    text: String?,
    action: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier =
            Modifier.clip(JrwiShape)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(vertical = 4.dp),
    ) {
        AppIcon(
            name = icon,
            contentDescription = null,
            tint = JrwiColors.Muted,
            modifier = Modifier.size(14.dp),
        )
        text?.let {
            Text(text = it, style = MaterialTheme.typography.labelMedium, color = JrwiColors.Muted)
        }
        action?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
