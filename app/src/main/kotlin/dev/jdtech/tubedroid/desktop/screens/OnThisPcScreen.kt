package dev.jdtech.tubedroid.desktop.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jdtech.tubedroid.desktop.Kept
import dev.jdtech.tubedroid.desktop.LocalApp
import dev.jdtech.tubedroid.desktop.LocalDownload
import dev.jdtech.tubedroid.desktop.LocalNavigator
import dev.jdtech.tubedroid.desktop.Navigator
import dev.jdtech.tubedroid.desktop.Route
import dev.jdtech.tubedroid.desktop.jrwi.JrwiBar
import dev.jdtech.tubedroid.desktop.jrwi.JrwiImage
import dev.jdtech.tubedroid.desktop.jrwi.JrwiPage
import dev.jdtech.tubedroid.desktop.jrwi.JrwiShape
import dev.jdtech.tubedroid.desktop.jrwi.JrwiTheme
import dev.jdtech.tubedroid.desktop.player.PlayerRequest
import dev.jdtech.tubedroid.desktop.rememberPageModel
import dev.jdtech.tubedroid.desktop.ui.AppIcon
import dev.jdtech.tubedroid.desktop.ui.CardShape
import dev.jdtech.tubedroid.desktop.ui.GridScrollbar
import dev.jdtech.tubedroid.desktop.ui.ItemAction
import dev.jdtech.tubedroid.desktop.ui.MediaCard
import dev.jdtech.tubedroid.desktop.ui.OverlayLabel
import dev.jdtech.tubedroid.desktop.ui.PageBar
import dev.jdtech.tubedroid.desktop.ui.PagePadding
import dev.jdtech.tubedroid.desktop.ui.Spacings
import dev.jdtech.tubedroid.desktop.ui.TaImage
import dev.jdtech.tubedroid.desktop.ui.Tooltip
import dev.jdtech.tubedroid.desktop.ui.formatDuration
import dev.jdtech.tubedroid.desktop.ui.formatSize
import java.io.File

// What Winchivist downloaded to this PC and keeps track of: a page for TubeArchivist's videos by
// channel, and one for the archive's episodes by show, each with what's still on its way at the top

/** TubeArchivist's videos on this PC, by channel. */
@Composable
fun OnThisPcScreen() {
    Column(modifier = Modifier.fillMaxSize()) {
        PageBar(title = "On this PC", onBack = null)
        OnThisPc(service = Kept.TUBEARCHIVIST)
    }
}

/** The archive's episodes on this PC, by show, in the archive's look. */
@Composable
fun JrwiOnThisPcScreen() {
    JrwiTheme {
        JrwiPage {
            Column(modifier = Modifier.fillMaxSize()) {
                JrwiBar(title = "On this PC", onBack = null)
                OnThisPc(service = Kept.JRWI)
            }
        }
    }
}

/** What's listed under one heading: a channel's videos, or a show's episodes. */
private data class KeptGroup(val name: String, val route: Route?, val items: List<Kept>)

@Composable
private fun OnThisPc(service: String) {
    val app = LocalApp.current
    val navigator = LocalNavigator.current
    val archive = service == Kept.JRWI
    val grid = rememberPageModel("on-this-pc-$service") { LazyGridState() }
    val whole = app.downloads.onThisPc(service)
    val coming = app.downloads.unfinished(service)
    val groups = remember(whole, archive) { grouped(whole, archive) }
    Box(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = grid,
            columns = GridCells.Adaptive(minSize = 240.dp),
            contentPadding = PaddingValues(PagePadding),
            horizontalArrangement = Arrangement.spacedBy(Spacings.medium),
            verticalArrangement = Arrangement.spacedBy(Spacings.medium),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "folder", span = { GridItemSpan(maxLineSpan) }) {
                FolderLine(whole, archive)
            }
            if (coming.isNotEmpty()) {
                item(key = "coming", span = { GridItemSpan(maxLineSpan) }) {
                    ComingCard(service, coming, archive)
                }
            }
            if (whole.isEmpty() && coming.isEmpty()) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text =
                            if (archive) {
                                "Nothing downloaded yet. An episode's page, and a show's, has a " +
                                    "download button."
                            } else {
                                "Nothing downloaded yet. A video's page, and a playlist's, has a " +
                                    "download button."
                            },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = Spacings.large),
                    )
                }
            }
            groups.forEach { group ->
                item(key = "group:${group.name}", span = { GridItemSpan(maxLineSpan) }) {
                    GroupHeading(group, navigator)
                }
                items(group.items, key = { it.id }) { kept -> KeptCard(kept, archive) }
            }
        }
        GridScrollbar(grid)
    }
}

/** How much is on this PC and where, with a button that opens the folder. */
@Composable
private fun FolderLine(whole: List<Kept>, archive: Boolean) {
    val app = LocalApp.current
    val folder = app.settings.downloadFolder
    val size = whole.sumOf { it.size ?: 0L }
    val count =
        when {
            whole.isEmpty() -> "Downloads go to $folder"
            archive -> "${counted(whole.size, "episode")} · ${formatSize(size, true)} in $folder"
            else -> "${counted(whole.size, "video")} · ${formatSize(size, true)} in $folder"
        }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = count,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { runCatching { java.awt.Desktop.getDesktop().open(File(folder)) } }) {
            Text("Open folder")
        }
    }
}

/**
 * The downloads still to finish, in the order they're fetched, each with what can be done to it.
 */
@Composable
private fun ComingCard(service: String, coming: List<LocalDownload>, archive: Boolean) {
    val app = LocalApp.current
    val navigator = LocalNavigator.current
    val shape = if (archive) JrwiShape else RoundedCornerShape(12.dp)
    Column(
        verticalArrangement = Arrangement.spacedBy(Spacings.small),
        modifier =
            Modifier.fillMaxWidth()
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(Spacings.medium),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Downloads in progress · ${coming.size}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (coming.any { it.active }) {
                TextButton(onClick = { app.downloads.pauseAll(service) }) { Text("Pause all") }
            } else {
                TextButton(onClick = { app.downloads.resumeAll(service) }) {
                    Text("Go on with all")
                }
            }
        }
        coming.forEach { download ->
            ComingRow(
                download = download,
                onOpen = { download.route?.let(navigator::open) },
                onPause = { app.downloads.pause(download.id) },
                onResume = { app.downloads.resume(download.id) },
                onRemove = { app.downloads.remove(download.id) },
            )
        }
    }
}

@Composable
private fun ComingRow(
    download: LocalDownload,
    onOpen: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val failed = download.state == LocalDownload.State.FAILED
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f).clickable(onClick = onOpen)) {
            Text(
                text = download.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = comingText(download),
                style = MaterialTheme.typography.bodySmall,
                color = if (failed) colors.error else colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (download.state == LocalDownload.State.RUNNING) {
                val progress = download.progress
                val bar = Modifier.fillMaxWidth().padding(top = 6.dp)
                if (progress != null) {
                    LinearProgressIndicator(progress = { progress }, modifier = bar)
                } else {
                    LinearProgressIndicator(modifier = bar)
                }
            }
        }
        Spacer(Modifier.width(Spacings.small))
        if (download.active) {
            RowButton(icon = "ic_pause", label = "Pause", onClick = onPause)
        } else {
            RowButton(
                icon = if (failed) "ic_refresh" else "ic_play",
                label = if (failed) "Try again" else "Go on",
                onClick = onResume,
            )
        }
        RowButton(icon = "ic_x", label = "Take it off this PC", onClick = onRemove)
    }
}

@Composable
private fun RowButton(icon: String, label: String, onClick: () -> Unit) {
    Tooltip(label) {
        IconButton(onClick = onClick) {
            AppIcon(name = icon, contentDescription = label, modifier = Modifier.size(20.dp))
        }
    }
}

/** A channel's or a show's name over its downloads. A click opens its page. */
@Composable
private fun GroupHeading(group: KeptGroup, navigator: Navigator) {
    val size = group.items.sumOf { it.size ?: 0L }
    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.padding(top = Spacings.medium),
    ) {
        Text(
            text = group.name,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier.weight(1f, fill = false).clickable(enabled = group.route != null) {
                    group.route?.let(navigator::open)
                },
        )
        Spacer(Modifier.width(Spacings.medium))
        Text(
            text = "${group.items.size} · ${formatSize(size, true)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One download as a card: its picture and length, its name and its size. */
@Composable
private fun KeptCard(kept: Kept, archive: Boolean) {
    val app = LocalApp.current
    val navigator = LocalNavigator.current
    val folder = app.settings.downloadFolder
    MediaCard(
        title = kept.title,
        subtitle = subtitleOf(kept, archive),
        onClick = { navigator.open(kept.route) },
        onPlay = { play(kept, navigator) },
        actions =
            listOf(
                ItemAction("Show in folder") {
                    runCatching {
                        val file = File(folder, kept.file.replace('/', File.separatorChar))
                        java.awt.Desktop.getDesktop().open(file.parentFile)
                    }
                },
                ItemAction("Remove from this PC") {
                    app.downloads.remove(kept.id)
                    navigator.message("Took ${kept.title} off this PC")
                },
            ),
        imageShape = if (archive) JrwiShape else CardShape,
    ) {
        KeptPicture(kept, archive)
    }
}

@Composable
private fun BoxScope.KeptPicture(kept: Kept, archive: Boolean) {
    // The picture comes from the server that holds the file
    if (kept.service == Kept.JRWI) {
        JrwiImage(path = kept.picture, modifier = Modifier.fillMaxSize())
    } else {
        TaImage(path = kept.picture, modifier = Modifier.fillMaxSize())
    }
    val length = kept.seconds?.takeIf { it > 0 }?.let { formatDuration(it) }
    if (archive) {
        // The archive's own label: what kind of version it is, in the mark for "on this PC"
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier.align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(JrwiShape)
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            AppIcon(
                name = if (kept.audio) "ic_headphones" else "ic_film",
                contentDescription = if (kept.audio) "Audio" else "Video",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp),
            )
            length?.let {
                Text(text = it, style = MaterialTheme.typography.labelSmall, color = Color.White)
            }
        }
    } else if (length != null) {
        OverlayLabel(
            text = length,
            modifier = Modifier.align(Alignment.BottomEnd).padding(Spacings.small),
        )
    }
}

private fun play(kept: Kept, navigator: Navigator) {
    val episode = kept.episode
    when {
        kept.service == Kept.TUBEARCHIVIST -> navigator.play(PlayerRequest.Ta(videoId = kept.id))
        episode == null -> navigator.open(kept.route)
        kept.audio -> navigator.listen(PlayerRequest.Jrwi(episode.item, audio = true))
        else -> navigator.play(PlayerRequest.Jrwi(episode.item, audio = false))
    }
}

private fun subtitleOf(kept: Kept, archive: Boolean): String {
    val size = kept.size?.takeIf { it > 0 }?.let { formatSize(it, true) }
    val episode = kept.episode
    val number =
        when {
            !archive || episode?.episode == null -> null
            episode.season != null -> "Season ${episode.season}, episode ${episode.episode}"
            else -> "Episode ${episode.episode}"
        }
    return listOfNotNull(number, size).joinToString(" · ")
}

private fun comingText(download: LocalDownload): String {
    val size = download.kept?.size?.takeIf { it > 0 }
    val got =
        when {
            size != null && download.have > 0 ->
                "${formatSize(download.have, true)} of ${formatSize(size, true)}"
            size != null -> formatSize(size, true)
            download.have > 0 -> formatSize(download.have, true)
            else -> null
        }
    val group = download.kept?.group?.takeIf { it.isNotBlank() }
    val state =
        when (download.state) {
            LocalDownload.State.RUNNING -> download.progress?.let { "${(it * 100).toInt()}%" }
            LocalDownload.State.QUEUED -> "In queue"
            LocalDownload.State.PAUSED -> "Paused"
            LocalDownload.State.FAILED -> "Failed: ${download.error.orEmpty()}"
            else -> null
        }
    return listOfNotNull(group, state, got).joinToString(" · ")
}

private fun counted(count: Int, what: String): String =
    if (count == 1) "1 $what" else "$count ${what}s"

/**
 * The downloads under their headings. TubeArchivist's go by channel, newest first. The archive's go
 * by show: the numbered episodes in season and episode order, an episode's video before its audio,
 * then the rest by name.
 */
private fun grouped(whole: List<Kept>, archive: Boolean): List<KeptGroup> =
    whole
        .groupBy { kept ->
            val name = if (archive) kept.episode?.show ?: kept.group else kept.group
            name.ifBlank { if (archive) "Other posts" else "Other videos" }
        }
        .map { (name, items) ->
            val first = items.first()
            val route =
                if (archive) first.episode?.showId?.let { Route.JrwiShowPage(it) }
                else first.groupId?.let { Route.Channel(it) }
            val sorted =
                if (archive) {
                    items.sortedWith(
                        compareBy<Kept>(
                            { it.episode?.episode == null },
                            { it.episode?.season ?: 0 },
                            { it.episode?.episode ?: 0 },
                            { it.title.lowercase() },
                            { it.audio },
                        )
                    )
                } else {
                    items.sortedWith(
                        compareByDescending<Kept> { it.date.orEmpty() }
                            .thenByDescending { it.added }
                    )
                }
            KeptGroup(name, route, sorted)
        }
        .sortedBy { it.name.lowercase() }
