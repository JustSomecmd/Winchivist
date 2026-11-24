package dev.jdtech.tubedroid.desktop.jrwi

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import dev.jdtech.jellyfin.jrwi.JrwiItem
import dev.jdtech.jellyfin.jrwi.JrwiPostFile
import dev.jdtech.tubedroid.desktop.LocalApp
import dev.jdtech.tubedroid.desktop.LocalNavigator
import dev.jdtech.tubedroid.desktop.Navigator
import dev.jdtech.tubedroid.desktop.ui.AppIcon
import dev.jdtech.tubedroid.desktop.ui.Spacings
import dev.jdtech.tubedroid.desktop.ui.Tooltip
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

/**
 * What the picture viewer goes through, from the one at [start]: a post's pictures, and the files
 * it carries that are pictures or PDFs, a PDF page by page.
 */
data class JrwiPictures(val items: List<JrwiShown>, val start: Int)

/** One thing the picture viewer shows. */
sealed interface JrwiShown {
    /** Where it is in the archive, which also tells it apart from the others. */
    val address: String

    /** The name it's saved under. */
    val name: String

    /** A picture at [address]. [file] is set for one the post carries as a file. */
    data class Picture(override val address: String, val file: JrwiPostFile? = null) : JrwiShown {
        override val name: String
            get() = file?.name?.takeIf { it.isNotBlank() } ?: pictureSaveName(address)
    }

    /** A PDF, shown page by page once the viewer gets to it. */
    data class Pdf(val file: JrwiPostFile) : JrwiShown {
        override val address: String
            get() = file.url.orEmpty()

        override val name: String
            get() = file.name
    }
}

/**
 * A post's pictures, then its attachments, then its documents, as the post lists them, those that
 * are pictures or PDFs: what the picture viewer goes through for a post, as the web app's does.
 * [files] gives each file's type as the archiver lists them; without them, as from the feed, the
 * type goes by the file's name.
 */
fun jrwiViewerItems(post: JrwiItem, files: List<JrwiPostFile>? = null): List<JrwiShown> {
    val listed = files.orEmpty().filter { it.url != null }.associateBy { it.url }
    val carried =
        (post.attachments.map { listed[it.url] ?: it.asPostFile("attachment") } +
                post.documents.map { listed[it.url] ?: it.asPostFile("document") })
            .distinctBy { it.url }
    return post.images.map { JrwiShown.Picture(it.url) } +
        carried.mapNotNull { file ->
            val url = file.url
            when {
                url == null -> null
                file.type == "image" -> JrwiShown.Picture(url, file)
                file.type == "pdf" -> JrwiShown.Pdf(file)
                else -> null
            }
        }
}

/** Opens the picture viewer over the window. */
fun Navigator.showPictures(pictures: JrwiPictures) {
    if (pictures.items.isEmpty()) return
    overlay { JrwiPictureViewer(pictures = pictures, onClose = ::closeOverlay) }
}

/** What's known of a PDF the viewer has got to: its pages, once the archiver has said. */
private sealed interface PdfState {
    data object Loading : PdfState

    data class Ready(val pages: Int, val pageUrl: String) : PdfState

    data object Failed : PdfState
}

/**
 * A post's pictures and PDFs over the whole window, as the archiver's picture viewer: the arrows at
 * the sides and the arrow keys go through them, the wheel zooms in and out, a double click zooms in
 * or back out, and a zoomed picture is dragged to look around it. A PDF is looked up when the
 * viewer gets next to it and goes in page by page; its label gives its name and the page. The save
 * button saves the picture, or the PDF itself, to this computer.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun JrwiPictureViewer(pictures: JrwiPictures, onClose: () -> Unit) {
    val app = LocalApp.current
    val navigator = LocalNavigator.current
    val api = app.jrwi.api
    val items = pictures.items
    if (items.isEmpty()) return
    val scope = rememberCoroutineScope()
    var index by remember { mutableIntStateOf(pictures.start.coerceIn(0, items.lastIndex)) }
    // The page within a PDF; LAST_PAGE is its last one, for coming to it from what's after it
    var page by remember { mutableIntStateOf(0) }
    val pdfs = remember { mutableStateMapOf<Int, PdfState>() }

    fun lookUp(at: Int, again: Boolean = false) {
        val pdf = items.getOrNull(at) as? JrwiShown.Pdf ?: return
        if (at in pdfs && !again) return
        pdfs[at] = PdfState.Loading
        scope.launch {
            pdfs[at] =
                try {
                    val preview =
                        api.getPreview(pdf.file.preview ?: "/api/preview/${pdf.file.path}")
                    val count = preview.pages
                    val pageUrl = preview.pageUrl
                    if (count != null && count > 0 && pageUrl != null) {
                        PdfState.Ready(count, pageUrl)
                    } else {
                        PdfState.Failed
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    PdfState.Failed
                }
        }
    }
    // A PDF is looked up once the viewer is next to it
    LaunchedEffect(index) {
        lookUp(index)
        lookUp(index + 1)
        lookUp(index - 1)
    }

    val shown = items[index]
    val pdf = pdfs[index] as? PdfState.Ready
    val pageCount = pdf?.pages ?: 1
    val currentPage = if (page == LAST_PAGE) pageCount - 1 else page.coerceIn(0, pageCount - 1)

    fun next() {
        if (currentPage + 1 < pageCount) {
            page = currentPage + 1
        } else if (index + 1 < items.size) {
            index += 1
            page = 0
        }
    }
    fun previous() {
        if (currentPage > 0) {
            page = currentPage - 1
        } else if (index > 0) {
            index -= 1
            page = LAST_PAGE
        }
    }
    val hasNext = currentPage + 1 < pageCount || index + 1 < items.size
    val hasPrevious = currentPage > 0 || index > 0

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Box(
        modifier =
            Modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = 0.96f))
                .focusRequester(focus)
                .focusable()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionRight,
                        Key.PageDown -> next()
                        Key.DirectionLeft,
                        Key.PageUp -> previous()
                        else -> return@onKeyEvent false
                    }
                    true
                }
                // Nothing under the viewer is clicked through it
                .pointerInput(Unit) { detectTapGestures {} }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // The bar: close, where it is among the post's pictures and files, and what to do
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacings.small),
                modifier =
                    Modifier.fillMaxWidth()
                        .height(VIEWER_BAR)
                        .padding(horizontal = Spacings.medium),
            ) {
                ViewerButton("ic_x", "Close", onClose)
                Text(
                    text = "${index + 1} / ${items.size}",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                )
                Text(
                    text =
                        if (shown is JrwiShown.Pdf && pdf != null) {
                            "${shown.name} · page ${currentPage + 1} of ${pdf.pages}"
                        } else {
                            shown.name
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = Spacings.small),
                )
                ViewerButton("ic_external_link", "Open in another app") {
                    scope.launch {
                        try {
                            navigator.message("Getting ${shown.name} from the archive…")
                            openJrwiFileElsewhere(app, shown.name, shown.address)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            navigator.message("Couldn't open ${shown.name}: ${e.message}")
                        }
                    }
                }
                ViewerButton("ic_download", "Save to this PC") {
                    saveJrwiFileAndSay(app, navigator, shown.name, shown.address)
                }
            }
            Box(modifier = Modifier.fillMaxSize()) {
                val state = pdfs[index]
                when {
                    shown is JrwiShown.Pdf && state is PdfState.Failed ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.align(Alignment.Center),
                        ) {
                            Text(text = "${shown.name} couldn't be shown.", color = Color.White)
                            TextButton(onClick = { lookUp(index, again = true) }) { Text("Retry") }
                        }
                    shown is JrwiShown.Pdf && pdf == null ->
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    else -> {
                        val address =
                            if (shown is JrwiShown.Pdf && pdf != null) {
                                pdf.pageUrl.replace("{n}", (currentPage + 1).toString())
                            } else {
                                shown.address
                            }
                        ZoomablePicture(url = api.fileUrl(address), key = "$index:$currentPage")
                    }
                }
                if (hasPrevious) {
                    ViewerArrow(
                        icon = "ic_chevron_left",
                        label = "Previous",
                        onClick = ::previous,
                        modifier = Modifier.align(Alignment.CenterStart),
                    )
                }
                if (hasNext) {
                    ViewerArrow(
                        icon = "ic_chevron_right",
                        label = "Next",
                        onClick = ::next,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
            }
        }
    }
}

private const val LAST_PAGE = -1
private val VIEWER_BAR = 56.dp

/**
 * A picture fitted to the space, which the wheel zooms, a double click zooms in or back out, and a
 * drag moves around while it's zoomed in. It loads at up to twice the space's size, so zooming
 * stays sharp without decoding huge files whole.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ZoomablePicture(url: String?, key: String) {
    var scale by remember(key) { mutableStateOf(1f) }
    var offset by remember(key) { mutableStateOf(Offset.Zero) }
    var loading by remember(key) { mutableStateOf(true) }
    var failed by remember(key) { mutableStateOf(false) }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        fun held(to: Offset, at: Float): Offset {
            val x = width * (at - 1f) / 2f
            val y = height * (at - 1f) / 2f
            return Offset(to.x.coerceIn(-x, x), to.y.coerceIn(-y, y))
        }
        fun zoomTo(target: Float) {
            scale = target.coerceIn(1f, MAX_ZOOM)
            offset = if (scale == 1f) Offset.Zero else held(offset, scale)
        }
        val context = LocalPlatformContext.current
        val request =
            remember(url, width, height) {
                ImageRequest.Builder(context)
                    .data(url)
                    .size((width * 2).coerceAtLeast(1), (height * 2).coerceAtLeast(1))
                    .build()
            }
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            onLoading = {
                loading = true
                failed = false
            },
            onSuccess = { loading = false },
            onError = {
                loading = false
                failed = true
            },
            modifier =
                Modifier.fillMaxSize()
                    .onPointerEvent(PointerEventType.Scroll) { event ->
                        val turned = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                        if (turned != 0f) {
                            zoomTo(if (turned < 0f) scale * ZOOM_STEP else scale / ZOOM_STEP)
                        }
                    }
                    .pointerInput(key) {
                        detectTapGestures(onDoubleTap = { zoomTo(if (scale > 1f) 1f else 2.5f) })
                    }
                    .pointerInput(key) {
                        detectDragGestures { change, dragged ->
                            if (scale > 1f) {
                                change.consume()
                                offset = held(offset + dragged, scale)
                            }
                        }
                    }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
        )
        if (loading) CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        if (failed) {
            Text(
                text = "The picture couldn't be loaded.",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

private const val MAX_ZOOM = 8f
private const val ZOOM_STEP = 1.25f

/** A button of the viewer's bar, white on the dark. */
@Composable
private fun ViewerButton(icon: String, label: String, onClick: () -> Unit) {
    Tooltip(label) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.size(40.dp)
                    .clip(JrwiPillShape)
                    .clickable(onClick = onClick)
                    .pointerHoverIcon(PointerIcon.Hand),
        ) {
            AppIcon(
                name = icon,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** An arrow at the side of the viewer, to the picture before or after. */
@Composable
private fun ViewerArrow(icon: String, label: String, onClick: () -> Unit, modifier: Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .padding(Spacings.medium)
                .size(48.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(onClick = onClick)
                .pointerHoverIcon(PointerIcon.Hand),
    ) {
        AppIcon(
            name = icon,
            contentDescription = label,
            tint = Color.White,
            modifier = Modifier.size(28.dp),
        )
    }
}
