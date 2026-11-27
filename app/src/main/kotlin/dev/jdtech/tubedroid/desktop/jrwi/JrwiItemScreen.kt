package dev.jdtech.tubedroid.desktop.jrwi

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import dev.jdtech.jellyfin.jrwi.JrwiComment
import dev.jdtech.jellyfin.jrwi.JrwiItem
import dev.jdtech.jellyfin.jrwi.JrwiPostFile
import dev.jdtech.jellyfin.jrwi.JrwiShow
import dev.jdtech.tubedroid.desktop.AppGraph
import dev.jdtech.tubedroid.desktop.LocalApp
import dev.jdtech.tubedroid.desktop.LocalCanGoBack
import dev.jdtech.tubedroid.desktop.LocalDownload
import dev.jdtech.tubedroid.desktop.LocalNavigator
import dev.jdtech.tubedroid.desktop.Navigator
import dev.jdtech.tubedroid.desktop.Route
import dev.jdtech.tubedroid.desktop.player.PlayerRequest
import dev.jdtech.tubedroid.desktop.rememberPageModel
import dev.jdtech.tubedroid.desktop.screens.DownloadProgress
import dev.jdtech.tubedroid.desktop.ui.AppIcon
import dev.jdtech.tubedroid.desktop.ui.ListScrollbar
import dev.jdtech.tubedroid.desktop.ui.PagePadding
import dev.jdtech.tubedroid.desktop.ui.RoundIconButton
import dev.jdtech.tubedroid.desktop.ui.Spacings
import dev.jdtech.tubedroid.desktop.ui.Tooltip
import dev.jdtech.tubedroid.desktop.ui.fullDate
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ceil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What a version of a post shows under its buttons: the post itself (the picked version's own, or
 * the whole item when it has one version), its files and its comments.
 */
class JrwiPostContent(
    private val app: AppGraph,
    private val scope: CoroutineScope,
    /** The post whose own text and comments these are, or null for the whole item's. */
    private val postId: Long?,
    private val item: JrwiItem,
) {
    private val api = app.jrwi.api

    var post by mutableStateOf(if (postId == null) item else null)
        private set

    var files by mutableStateOf<List<JrwiPostFile>?>(null)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    val comments =
        JrwiPagedList<JrwiComment>(scope, key = { it.id }) { page ->
            if (postId == null) api.getComments(item.id, page)
            else api.getPostComments(postId, page)
        }

    init {
        comments.loadMore()
        scope.launch {
            try {
                if (postId != null) post = api.getPost(postId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = jrwiErrorText(e)
            }
        }
        scope.launch {
            try {
                files = if (postId == null) api.getItemFiles(item.id) else api.getPostFiles(postId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Its attachments and documents are listed from the post itself then
                files = null
            }
        }
    }
}

/** One post of the archive: its versions, where each was left, and what the picked one shows. */
class JrwiItemModel(
    private val app: AppGraph,
    private val postId: Long,
    private val scope: CoroutineScope,
) {
    private val repository = app.jrwi
    private val api = repository.api

    var item by mutableStateOf<JrwiItem?>(null)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    /** The show it's from, for the link under its title. */
    var show by mutableStateOf<JrwiShow?>(null)
        private set

    /** Whether the audio is the picked version, rather than the video. */
    var playsAudio by mutableStateOf(false)
        private set

    /** Where each version was left, in its own timeline. */
    var videoPosition by mutableStateOf(0.0)
        private set

    var audioPosition by mutableStateOf(0.0)
        private set

    var finished by mutableStateOf(false)
        private set

    /** Jellyfin is being asked for the video. */
    var lookingUp by mutableStateOf(false)
        private set

    /** The download button was clicked for a post with files, and asks whether they come too. */
    var askAboutFiles by mutableStateOf(false)

    val listState = LazyListState()
    private val contents = mutableMapOf<Long?, JrwiPostContent>()
    private var shown = false

    init {
        load()
    }

    fun load() {
        error = null
        scope.launch {
            try {
                val loaded = api.getItem(postId)
                if (item == null) {
                    // Opened on the version whose post this is; an item with only audio plays that
                    playsAudio =
                        loaded.audio != null &&
                            (loaded.video == null || loaded.audio?.postId == postId)
                }
                item = loaded
                finished = repository.finished(loaded)
                loadProgress(loaded)
                show =
                    runCatching { repository.loadShows().firstOrNull { it.id == loaded.show } }
                        .getOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (item == null) error = jrwiErrorText(e)
            }
        }
    }

    /** Reads where it was left again when the page shows again, a moment after a player closed. */
    fun onShown() {
        if (!shown) {
            shown = true
            return
        }
        val current = item ?: return
        scope.launch {
            delay(RETURN_DELAY_MS)
            loadProgress(current)
        }
    }

    private suspend fun loadProgress(item: JrwiItem) {
        val video = repository.progress(item, audio = false)
        val audio = repository.progress(item, audio = true)
        video?.let { videoPosition = if (it.finished) 0.0 else it.position }
        audio?.let { audioPosition = if (it.finished) 0.0 else it.position }
        (video ?: audio)?.let { finished = it.finished }
    }

    fun choose(audio: Boolean) {
        playsAudio = audio
    }

    /** The picked version's position, in its own timeline. */
    val position: Double
        get() = if (playsAudio) audioPosition else videoPosition

    /** What the picked version shows: its own post when the item has two, else the whole item. */
    fun content(): JrwiPostContent? {
        val current = item ?: return null
        val own =
            if (current.hasBoth) {
                if (playsAudio) current.audio?.postId else current.video?.postId
            } else {
                null
            }
        return contents.getOrPut(own) { JrwiPostContent(app, scope, own, current) }
    }

    /**
     * Plays the picked version: the audio from the archive; the video from Jellyfin when it has it,
     * from TubeArchivist when it's only on YouTube, or else straight from the archive.
     */
    fun play(fromStart: Boolean, navigator: Navigator) {
        val current = item ?: return
        if (playsAudio) {
            // In the audio player, which leaves the page free to read on
            navigator.listen(PlayerRequest.Jrwi(current.id, audio = true, fromStart = fromStart))
            return
        }
        val video = current.video ?: return
        if (video.isYoutube && !app.api.isConfigured) {
            navigator.message(
                "This video is only on YouTube, so it plays from TubeArchivist, which isn't set up."
            )
            return
        }
        if (lookingUp) return
        lookingUp = true
        scope.launch {
            try {
                repository.videoRequest(current, fromStart)?.let(navigator::play)
            } finally {
                lookingUp = false
            }
        }
    }

    /** Reads where it was left again, a moment after the audio player let go of it. */
    fun reloadProgress() {
        val current = item ?: return
        scope.launch {
            delay(RETURN_DELAY_MS)
            loadProgress(current)
        }
    }

    /** Marks both versions watched, or both not watched. */
    fun toggleWatched(navigator: Navigator) {
        val current = item ?: return
        val watched = !finished
        scope.launch {
            try {
                repository.setWatched(current, watched)
                finished = watched
                if (watched) {
                    videoPosition = 0.0
                    audioPosition = 0.0
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                navigator.message(jrwiErrorText(e))
            }
        }
    }

    /** Saves the picked version to this computer, after asking about the post's files. */
    fun download(navigator: Navigator, withFiles: Boolean? = null) {
        val current = item ?: return
        if (withFiles == null && current.hasFiles()) {
            askAboutFiles = true
            return
        }
        askAboutFiles = false
        val folder =
            app.jrwiDownloads.download(
                item = current,
                audio = playsAudio,
                withFiles = withFiles == true,
                showName = show?.name ?: current.show,
            )
        navigator.message(
            if (folder != null) "Saving to ${folder.path}"
            else "There's nothing of this version to download."
        )
    }

    private companion object {
        const val RETURN_DELAY_MS = 1_000L
    }
}

/**
 * One post: its video and audio behind one switch and one Play button, then its text, pictures,
 * files, links and comments, laid out as the web app's post page.
 */
@Composable
fun JrwiItemScreen(postId: Long) {
    val app = LocalApp.current
    val navigator = LocalNavigator.current
    val canGoBack = LocalCanGoBack.current
    val model = rememberPageModel("jrwi-item") { scope -> JrwiItemModel(app, postId, scope) }
    LaunchedEffect(Unit) { model.onShown() }
    val item = model.item
    JrwiTheme {
        JrwiPage {
            Column(modifier = Modifier.fillMaxSize()) {
                val content = model.content()
                JrwiBar(title = null, onBack = if (canGoBack) navigator::back else null) {
                    if (item != null) {
                        // The shown version's post in the archiver's web app
                        RoundIconButton(
                            icon = "ic_external_link",
                            label = "Open in the archiver",
                            onClick = {
                                openUrl(
                                    app.jrwi.api.fileUrl("/items/${content?.post?.id ?: item.id}")
                                )
                            },
                        )
                    }
                }
                if (item == null || content == null) {
                    JrwiPageState(loading = true, error = model.error, onRetry = model::load)
                    return@Column
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    JrwiItemList(model = model, item = item, content = content)
                    ListScrollbar(model.listState)
                }
            }
            if (model.askAboutFiles) {
                AlertDialog(
                    onDismissRequest = { model.askAboutFiles = false },
                    title = { Text("Download the post's files too?") },
                    text = {
                        Text(
                            "Its attachments, documents and pictures, in a folder next to the " +
                                "episode. They can take up a lot of space."
                        )
                    },
                    confirmButton = {
                        Row {
                            TextButton(onClick = { model.download(navigator, withFiles = false) }) {
                                Text("Without files")
                            }
                            TextButton(onClick = { model.download(navigator, withFiles = true) }) {
                                Text("With files")
                            }
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { model.askAboutFiles = false }) { Text("Cancel") }
                    },
                )
            }
        }
    }
}

/** Keeps a part of the post's page to a column down the middle of a wide window. */
@Composable
private fun PostColumn(content: @Composable () -> Unit) {
    Box(
        contentAlignment = Alignment.TopCenter,
        modifier = Modifier.fillMaxWidth().padding(horizontal = PagePadding),
    ) {
        Box(modifier = Modifier.widthIn(max = POST_WIDTH).fillMaxWidth()) { content() }
    }
}

private val POST_WIDTH = 980.dp
private val TEXT_WIDTH = 760.dp

@Composable
private fun JrwiItemList(model: JrwiItemModel, item: JrwiItem, content: JrwiPostContent) {
    val app = LocalApp.current
    val navigator = LocalNavigator.current
    val post = content.post
    val files = content.files
    // Its pictures, then its picture files and PDFs, in one viewer
    val viewerItems = remember(post, files) { post?.let { jrwiViewerItems(it, files) }.orEmpty() }
    val comments = content.comments
    val flatComments = remember(comments.items) { comments.items.flattenReplies() }
    LazyColumn(
        state = model.listState,
        contentPadding = PaddingValues(top = Spacings.medium, bottom = Spacings.large),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "header") { PostColumn { JrwiItemHeader(model = model, item = item) } }
        val failed = content.error
        when {
            post == null && failed != null ->
                item(key = "post-error") {
                    PostColumn { Text(text = failed, color = MaterialTheme.colorScheme.error) }
                }
            post == null ->
                item(key = "post-loading") {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            else ->
                jrwiPostContent(
                    post = post,
                    files = files,
                    onPicture = { at -> navigator.showPictures(JrwiPictures(viewerItems, at)) },
                    onFile = { file, listed ->
                        openPostFile(app, navigator, file, listed, viewerItems)
                    },
                )
        }
        item(key = "comments-title") {
            PostColumn { JrwiSectionTitle("Comments" + (comments.total?.let { " ($it)" } ?: "")) }
        }
        items(flatComments, key = { "comment:" + it.comment.id }, contentType = { "comment" }) {
            flat ->
            PostColumn {
                JrwiCommentRow(
                    comment = flat.comment,
                    depth = flat.depth,
                    modifier = Modifier.widthIn(max = TEXT_WIDTH),
                )
            }
        }
        item(key = "comments-foot") {
            PostColumn {
                val error = comments.error
                when {
                    error != null -> Text(text = error, color = MaterialTheme.colorScheme.error)
                    comments.loading -> CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    !comments.endReached ->
                        TextButton(onClick = comments::loadMore) { Text("More comments") }
                    comments.items.isEmpty() ->
                        Text(
                            text = "No comments",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                }
            }
        }
    }
}

/**
 * Opens one of the post's files: pictures and PDFs in the viewer with the post's others, a YouTube
 * entry on TubeArchivist's page for the video, a link in the browser, other sound and video in
 * another program, and everything else in the file inspector.
 */
private fun openPostFile(
    app: AppGraph,
    navigator: Navigator,
    file: JrwiPostFile,
    listed: List<JrwiPostFile>,
    viewerItems: List<JrwiShown>,
) {
    val shownAt = viewerItems.indexOfFirst { it.address == file.url && file.url != null }
    val address = file.url
    when {
        shownAt >= 0 && (file.type == "image" || file.type == "pdf") ->
            navigator.showPictures(JrwiPictures(viewerItems, shownAt))
        file.type == "youtube" -> {
            val youtubeId = file.youtubeId
            if (youtubeId != null && app.api.isConfigured) {
                navigator.open(Route.Video(youtubeId))
            } else {
                openUrl(file.source)
            }
        }
        file.type == "link" || address == null -> openUrl(file.source)
        file.type == "image" ->
            navigator.showPictures(JrwiPictures(listOf(JrwiShown.Picture(address, file)), 0))
        file.type == "audio" || file.type == "video" -> {
            // Another program gets the file itself, never the archiver's token
            navigator.message("Getting ${file.name} from the archive…")
            app.jrwiDownloads.openElsewhere(file.name, address, navigator::message)
        }
        else -> {
            val inspected = listed.filter { it.url != null && it.type !in NOT_INSPECTED }
            navigator.inspectFiles(inspected, inspected.indexOf(file).coerceAtLeast(0))
        }
    }
}

private val NOT_INSPECTED = setOf("image", "pdf", "youtube", "link", "audio", "video")

/**
 * A post's own text, tags, pictures, files and links. [onPicture] opens the viewer at one of the
 * post's pictures; [onFile] opens a file, given with the files listed along with it.
 */
private fun LazyListScope.jrwiPostContent(
    post: JrwiItem,
    files: List<JrwiPostFile>?,
    onPicture: (Int) -> Unit,
    onFile: (JrwiPostFile, List<JrwiPostFile>) -> Unit,
) {
    // Keyed by post, so switching versions never mixes the two
    val id = post.id
    // The site took it down, such as a members-only episode once it's on YouTube
    val removed =
        post.removedFromSite ?: post.versions.firstOrNull { it.postId == id }?.removedFromSite
    if (removed != null) {
        item(key = "removed:$id") {
            PostColumn {
                Text(
                    text =
                        "Taken down from the site on ${fullDate(removed) ?: removed}. The " +
                            "archive keeps it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = Spacings.small),
                )
            }
        }
    }
    val body = post.bodyHtml?.takeIf { it.isNotBlank() }
    if (body != null || !post.bodyText.isNullOrBlank()) {
        item(key = "body:$id") {
            PostColumn {
                JrwiHtmlText(
                    html = body,
                    text = post.bodyText.orEmpty(),
                    modifier = Modifier.widthIn(max = TEXT_WIDTH).padding(top = Spacings.small),
                )
            }
        }
    }
    if (post.tags.isNotEmpty()) {
        item(key = "tags:$id") {
            PostColumn { JrwiTags(post.tags, Modifier.padding(top = Spacings.medium)) }
        }
    }
    if (post.images.isNotEmpty()) {
        item(key = "images:$id") {
            PostColumn {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacings.small),
                    modifier = Modifier.padding(top = Spacings.medium),
                ) {
                    itemsIndexed(post.images) { index, image ->
                        JrwiImage(
                            path = image.url,
                            contentScale = ContentScale.Fit,
                            modifier =
                                Modifier.height(PICTURE_HEIGHT)
                                    .aspectRatio(1f, matchHeightConstraintsFirst = true)
                                    .clip(JrwiShape)
                                    .clickable { onPicture(index) }
                                    .pointerHoverIcon(PointerIcon.Hand),
                        )
                    }
                }
            }
        }
    }
    // What the post has in it: not the episode's own video and audio, whose size shows under the
    // title, nor the pictures, which are in their row above
    val listed =
        files?.filter {
            it.role != "image" && it.role != "thumbnail" && it.role != "video" && it.role != "audio"
        }
            // An archiver that lists no files still names the post's attachments and documents
            ?: (post.attachments.map { it.asPostFile("attachment") } +
                    post.documents.map { it.asPostFile("document") })
                .distinctBy { it.url }
    if (listed.isNotEmpty()) {
        item(key = "files-title:$id") { PostColumn { JrwiSectionTitle("Files") } }
        itemsIndexed(listed, key = { index, _ -> "file:$id:$index" }) { _, file ->
            PostColumn {
                JrwiLinkRow(
                    icon = jrwiFileIcon(file.type),
                    text = file.name,
                    detail = jrwiFileDetail(file),
                    onClick = { onFile(file, listed) },
                    modifier = Modifier.widthIn(max = TEXT_WIDTH),
                )
            }
        }
    }
    val links = post.links.distinctBy { it.url }
    if (links.isNotEmpty()) {
        item(key = "links-title:$id") { PostColumn { JrwiSectionTitle("Links") } }
        items(links, key = { "link:$id:" + it.url }) { link ->
            PostColumn {
                JrwiLinkRow(
                    icon = "ic_external_link",
                    text = link.text.ifBlank { link.url },
                    detail = link.url.takeIf { link.text.isNotBlank() },
                    onClick = { openUrl(link.url) },
                    modifier = Modifier.widthIn(max = TEXT_WIDTH),
                )
            }
        }
    }
}

private val PICTURE_HEIGHT = 240.dp

/**
 * The picture, title and details, the show it's from, the switch between the video and the audio,
 * and the buttons: beside the picture in a wide window, under it in a narrow one. A post with
 * neither a video nor audio starts with its title, as on the web app.
 */
@Composable
private fun JrwiItemHeader(model: JrwiItemModel, item: JrwiItem) {
    val playable = item.video != null || item.audio != null
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val wide = maxWidth >= 760.dp
        val pictureWidth = maxWidth * 0.44f
        val picture: @Composable (Modifier) -> Unit = { modifier ->
            Box(modifier = modifier) {
                // With its own bar, for the version that's picked
                JrwiThumbnail(item = item, modifier = Modifier.fillMaxWidth(), marks = false)
                val length =
                    (if (model.playsAudio) item.audio?.duration else item.video?.duration)
                        ?: item.duration
                if (!model.finished && model.position > 0 && length != null && length > 0) {
                    JrwiPlayedBar((model.position / length).toFloat())
                }
            }
        }
        if (wide && playable) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacings.default)) {
                picture(Modifier.width(pictureWidth))
                Column(modifier = Modifier.weight(1f)) { JrwiItemDetails(model, item) }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(Spacings.medium)) {
                if (playable) picture(Modifier.fillMaxWidth())
                JrwiItemDetails(model, item)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JrwiItemDetails(model: JrwiItemModel, item: JrwiItem) {
    val app = LocalApp.current
    val navigator = LocalNavigator.current
    val playsAudio = model.playsAudio
    Column(verticalArrangement = Arrangement.spacedBy(Spacings.small)) {
        // Bold, as in the feed
        Text(
            text = item.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = JrwiColors.Text,
        )
        // With the size of the version that's picked, which the files don't list
        val size = if (playsAudio) item.audio?.size else item.video?.size
        listOfNotNull(itemDetails(item), size?.let { formatBytes(it) })
            .joinToString(" · ")
            .takeIf { it.isNotEmpty() }
            ?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        if (item.locked) {
            Text(text = "Your tier can't see this post", color = MaterialTheme.colorScheme.error)
        }
        // The archived video was deleted once TubeArchivist had it from YouTube
        item.video?.handedOver?.let { handedOver ->
            Text(
                text =
                    "The archived video was deleted on " +
                        "${fullDate(handedOver.at) ?: handedOver.at.orEmpty()}, because " +
                        "TubeArchivist has it from YouTube. It plays from there.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        model.show?.let { show ->
            JrwiShowLink(show = show, onClick = { navigator.open(Route.JrwiShowPage(show.id)) })
        }
        if (item.video == null && item.audio == null) return@Column
        if (item.video != null && item.audio != null) {
            JrwiVersionSwitch(playsAudio = playsAudio, onChoose = model::choose)
        }
        if (!playsAudio && item.video?.isYoutube == true) {
            Text(
                text = "This video is only on YouTube, so it plays from TubeArchivist.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // While the audio player has this episode's audio, Play and the time left follow it
        val live by
            remember(item.id, playsAudio) {
                derivedStateOf { if (playsAudio) app.audio.stateOf(item) else null }
            }
        var wasLive by remember { mutableStateOf(false) }
        LaunchedEffect(live != null) {
            if (live != null) {
                wasLive = true
            } else if (wasLive) {
                wasLive = false
                model.reloadProgress()
            }
        }
        val length =
            (if (playsAudio) item.audio?.duration else item.video?.duration) ?: item.duration
        val position = model.position.takeIf { !model.finished && it > 0 } ?: 0.0
        val left =
            live?.minutesLeft
                ?: length
                    ?.takeIf { position > 0 && it > position }
                    ?.let { ceil((it - position) / 60).toInt() }
        val download = app.jrwiDownloads.downloadOf(item, playsAudio)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacings.small),
            verticalArrangement = Arrangement.spacedBy(Spacings.small),
            modifier = Modifier.padding(top = Spacings.small),
        ) {
            JrwiPlayButton(
                text =
                    when {
                        live?.playing == true && left != null -> "Pause · $left min left"
                        live?.playing == true -> "Pause"
                        left != null -> "Resume · $left min left"
                        position > 0 || live != null -> "Resume"
                        else -> "Play"
                    },
                busy = model.lookingUp,
                onClick = {
                    if (live != null) app.audio.togglePause()
                    else model.play(fromStart = false, navigator = navigator)
                },
            )
            if (position >= FROM_START_AFTER) {
                JrwiSquareButton(
                    icon = "ic_rotate_ccw",
                    label = "Play from the start",
                    onClick = { model.play(fromStart = true, navigator = navigator) },
                )
            }
            JrwiSquareButton(
                icon = "ic_check",
                label = if (model.finished) "Mark not watched" else "Mark watched",
                on = model.finished,
                onClick = { model.toggleWatched(navigator) },
            )
            when {
                download?.active == true -> {
                    JrwiSquareButton(
                        icon = "ic_pause",
                        label = "Pause the download",
                        on = true,
                        onClick = { app.downloads.pause(download.id) },
                    )
                    JrwiSquareButton(
                        icon = "ic_x",
                        label = "Cancel the download",
                        onClick = { app.jrwiDownloads.cancel(item, playsAudio) },
                    )
                }
                download?.kept != null && download.unfinished -> {
                    JrwiSquareButton(
                        icon = "ic_download",
                        label = "Go on with the download",
                        onClick = { app.downloads.resume(download.id) },
                    )
                    JrwiSquareButton(
                        icon = "ic_x",
                        label = "Cancel the download",
                        onClick = { app.jrwiDownloads.cancel(item, playsAudio) },
                    )
                }
                download?.state == LocalDownload.State.DONE && download.kept != null -> {
                    JrwiSquareButton(
                        icon = "ic_download",
                        label = "On this PC: show in folder",
                        on = true,
                        onClick = {
                            runCatching {
                                download.file.parentFile?.let {
                                    java.awt.Desktop.getDesktop().open(it)
                                }
                            }
                        },
                    )
                    JrwiSquareButton(
                        icon = "ic_trash",
                        label = "Remove from this PC",
                        onClick = { app.jrwiDownloads.cancel(item, playsAudio) },
                    )
                }
                download?.state == LocalDownload.State.DONE ->
                    JrwiSquareButton(
                        icon = "ic_download",
                        label = "On this PC: show in folder",
                        on = true,
                        onClick = {
                            runCatching {
                                download?.file?.parentFile?.let {
                                    java.awt.Desktop.getDesktop().open(it)
                                }
                            }
                        },
                    )
                else ->
                    JrwiSquareButton(
                        icon = "ic_download",
                        label =
                            if (playsAudio) "Download the audio to this PC"
                            else "Download the video to this PC",
                        onClick = { model.download(navigator) },
                    )
            }
        }
        download?.let { DownloadProgress(it) }
    }
}

// From a minute in, the page offers to play from the start as well
private const val FROM_START_AFTER = 60.0

/** The show an episode is from: its picture, name and number of posts. A click opens the show. */
@Composable
private fun JrwiShowLink(show: JrwiShow, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacings.small),
        modifier =
            Modifier.clip(JrwiShape)
                .clickable(onClick = onClick)
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(vertical = 4.dp, horizontal = 2.dp),
    ) {
        Box(
            modifier =
                Modifier.width(64.dp)
                    .aspectRatio(16f / 9f)
                    .clip(JrwiShape)
                    .background(JrwiColors.Picture)
        ) {
            JrwiShowPicture(show = show, modifier = Modifier.fillMaxSize(), logoPadding = 4.dp)
        }
        Column {
            Text(
                text = show.name,
                style = MaterialTheme.typography.titleSmall,
                color = JrwiColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = postCount(show.itemCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The site's video/audio switch: yellow when on, a thin outline when off. */
@Composable
private fun JrwiVersionSwitch(playsAudio: Boolean, onChoose: (audio: Boolean) -> Unit) {
    Row(modifier = Modifier.clip(JrwiShape).border(1.dp, JrwiColors.Line, JrwiShape)) {
        listOf(false to "Video", true to "Audio").forEach { (audio, label) ->
            val chosen = audio == playsAudio
            val ink = if (chosen) MaterialTheme.colorScheme.onPrimary else JrwiColors.PillText
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier =
                    Modifier.background(
                            if (chosen) MaterialTheme.colorScheme.primary else Color.Transparent
                        )
                        .clickable { onChoose(audio) }
                        .pointerHoverIcon(PointerIcon.Hand)
                        .padding(horizontal = 18.dp, vertical = 8.dp),
            ) {
                AppIcon(
                    name = if (audio) "ic_headphones" else "ic_film",
                    contentDescription = null,
                    tint = ink,
                    modifier = Modifier.size(18.dp),
                )
                Text(text = label, style = MaterialTheme.typography.labelLarge, color = ink)
            }
        }
    }
}

/** The page's main button: the archiver's yellow with dark ink and square corners. */
@Composable
private fun JrwiPlayButton(text: String, busy: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacings.small),
        modifier =
            Modifier.height(BUTTON_SIZE)
                .clip(JrwiShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable(enabled = !busy, onClick = onClick)
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(horizontal = 20.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp),
            )
        } else {
            AppIcon(
                name = "ic_play",
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

/** A square button with only an icon, which says what it does when the mouse rests on it. */
@Composable
fun JrwiSquareButton(
    icon: String,
    label: String,
    onClick: () -> Unit,
    on: Boolean = false,
    enabled: Boolean = true,
) {
    Tooltip(label) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.size(BUTTON_SIZE)
                    .clip(JrwiShape)
                    .background(
                        if (on) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                    .clickable(enabled = enabled, onClick = onClick)
                    .pointerHoverIcon(PointerIcon.Hand),
        ) {
            AppIcon(
                name = icon,
                contentDescription = label,
                tint = if (on) MaterialTheme.colorScheme.onPrimary else JrwiColors.Text,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private val BUTTON_SIZE = 44.dp
