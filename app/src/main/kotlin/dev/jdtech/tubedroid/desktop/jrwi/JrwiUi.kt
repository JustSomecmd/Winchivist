package dev.jdtech.tubedroid.desktop.jrwi

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.jrwi.JrwiApiException
import dev.jdtech.jellyfin.jrwi.JrwiAuthor
import dev.jdtech.jellyfin.jrwi.JrwiBadge
import dev.jdtech.jellyfin.jrwi.JrwiComment
import dev.jdtech.jellyfin.jrwi.JrwiItem
import dev.jdtech.jellyfin.jrwi.JrwiPostFile
import dev.jdtech.tubedroid.desktop.LocalApp
import dev.jdtech.tubedroid.desktop.LocalNavigator
import dev.jdtech.tubedroid.desktop.Route
import dev.jdtech.tubedroid.desktop.ui.AppIcon
import dev.jdtech.tubedroid.desktop.ui.HEADER_HEIGHT
import dev.jdtech.tubedroid.desktop.ui.RoundIconButton
import dev.jdtech.tubedroid.desktop.ui.Spacings
import dev.jdtech.tubedroid.desktop.ui.Tooltip
import dev.jdtech.tubedroid.desktop.ui.formatDuration
import dev.jdtech.tubedroid.desktop.ui.formatSize
import dev.jdtech.tubedroid.desktop.ui.fullDate
import dev.jdtech.tubedroid.desktop.ui.pictureFrom
import java.io.IOException
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

// The pieces the JRWI pages are made of, in the archiver's look: its pictures, the labels on them,
// cards, tags and pills, comments as the site shows them, and the pages' header

/**
 * A picture from the archive, by the path the API gives, such as /files/posts/300/thumbnail.webp.
 */
@Composable
fun JrwiImage(
    path: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    /** Behind the picture, before it loads and around it when it's fitted. */
    background: Color = JrwiColors.Picture,
) {
    val api = LocalApp.current.jrwi.api
    AsyncImage(
        model = pictureFrom(api.fileUrl(path)),
        contentDescription = null,
        contentScale = contentScale,
        modifier = modifier.background(background),
    )
}

/**
 * Opens an address in the browser, or the mail program. Only web and mail addresses: a link in a
 * post's text can be anything, and no other kind is handed on.
 */
fun openUrl(url: String?) {
    val scheme = url?.substringBefore(':', "")?.lowercase() ?: return
    if (scheme !in OPENABLE_SCHEMES) return
    runCatching {
        val desktop = java.awt.Desktop.getDesktop()
        if (scheme == "mailto") desktop.mail(java.net.URI(url))
        else desktop.browse(java.net.URI(url))
    }
}

private val OPENABLE_SCHEMES = setOf("http", "https", "mailto")

/** What went wrong with the archiver, in words for the page. */
fun jrwiErrorText(e: Throwable): String =
    when (e) {
        is JrwiApiException ->
            when (e.code) {
                401,
                403 -> "The archiver didn't accept the API token. Check it in Settings."
                else -> e.message ?: "The archiver answered ${e.code}."
            }
        is IOException -> "Can't reach the JRWI archiver. Check the address in Settings."
        else -> e.message ?: e.toString()
    }

/** A size as the archiver shows it: 1.2 GB, 340 MB. */
fun formatBytes(bytes: Long): String = formatSize(bytes, metric = true)

/**
 * The header of a JRWI page, on the archiver's deep purple (or the theme's own, on the archiver's
 * side): back, the title or [start], the page's [actions], and the search of the archive.
 */
@Composable
fun JrwiBar(
    title: String?,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    color: Color = JrwiColors.Header,
    showSearch: Boolean = true,
    start: (@Composable RowScope.() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val navigator = LocalNavigator.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .height(HEADER_HEIGHT)
                .background(color)
                .padding(horizontal = Spacings.medium),
    ) {
        if (onBack != null) {
            RoundIconButton(icon = "ic_arrow_left", label = "Back", onClick = onBack)
            Spacer(Modifier.width(Spacings.small))
        }
        // Whatever room the buttons leave, so they sit at the end however long the title is
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            if (start != null) {
                start()
            } else {
                Text(
                    text = title.orEmpty(),
                    style = MaterialTheme.typography.titleLarge,
                    color = JrwiColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacings.small),
        ) {
            actions()
            if (showSearch) {
                RoundIconButton(
                    icon = "ic_search",
                    label = "Search the archive",
                    onClick = { navigator.open(Route.JrwiSearch()) },
                )
            }
        }
    }
}

/** A centred spinner, or what went wrong with a button to try again. */
@Composable
fun JrwiPageState(loading: Boolean, error: String?, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (error != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = error, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetry) { Text("Retry") }
            }
        } else if (loading) {
            CircularProgressIndicator()
        }
    }
}

/**
 * The label in the corner of an audio or video post's picture, as every list of the archive shows
 * it: a film when it has a video, headphones when it has audio, then its length. A version that's
 * on this PC has its mark in the accent, as on the phone.
 */
@Composable
fun JrwiVersionLabel(item: JrwiItem, modifier: Modifier = Modifier) {
    val length = item.duration?.takeIf { it > 0 }
    val video = item.video != null
    val audio = item.audio != null
    if (!video && !audio && length == null) return
    val downloads = LocalApp.current.jrwiDownloads
    val here = MaterialTheme.colorScheme.primary
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .padding(6.dp)
                .clip(JrwiShape)
                .background(Color.Black.copy(alpha = 0.7f))
                .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        if (video) {
            AppIcon(
                name = "ic_film",
                contentDescription = "Video",
                tint = if (downloads.isKept(item, audio = false)) here else Color.White,
                modifier = Modifier.size(14.dp),
            )
        }
        if (audio) {
            AppIcon(
                name = "ic_headphones",
                contentDescription = "Audio",
                tint = if (downloads.isKept(item, audio = true)) here else Color.White,
                modifier = Modifier.size(14.dp),
            )
        }
        length?.let {
            Text(
                text = formatDuration(it),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
            )
        }
    }
}

/** A small square badge on a picture, in the accent: watched, or the version played last. */
@Composable
fun JrwiPictureBadge(icon: String, description: String, modifier: Modifier = Modifier) {
    AppIcon(
        name = icon,
        contentDescription = description,
        tint = MaterialTheme.colorScheme.onPrimary,
        modifier =
            modifier
                .padding(6.dp)
                .clip(JrwiShape)
                .background(MaterialTheme.colorScheme.primary)
                .padding(4.dp)
                .size(14.dp),
    )
}

/** A bar along the bottom of a picture for how far it was played, from 0 to 1. */
@Composable
fun BoxScope.JrwiPlayedBar(fraction: Float) {
    Box(
        modifier =
            Modifier.align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(4.dp)
                .background(Color.Black.copy(alpha = 0.4f))
    ) {
        Box(
            Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(4.dp)
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}

/**
 * An item's picture with its length and whether it has a video, audio or both. With [marks], a
 * check when it was watched and a bar when it was partly played. [contentScale] crops the picture
 * to fill its 16:9 box, or fits it whole inside. [overlay] draws more on the picture.
 */
@Composable
fun JrwiThumbnail(
    item: JrwiItem,
    modifier: Modifier = Modifier,
    marks: Boolean = true,
    contentScale: ContentScale = ContentScale.Crop,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val repository = LocalApp.current.jrwi
    Box(modifier = modifier.aspectRatio(16f / 9f).clip(JrwiShape)) {
        if (item.thumbnail != null) {
            JrwiImage(
                path = item.thumbnail,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // A post with no picture of its own: the die, faintly, rather than an empty box
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize().background(JrwiColors.Picture),
            ) {
                AppIcon(
                    name = "ic_dice",
                    contentDescription = null,
                    tint = JrwiColors.Text.copy(alpha = 0.16f),
                    modifier = Modifier.fillMaxHeight(0.36f).aspectRatio(1f),
                )
            }
        }
        if (marks) {
            if (repository.finished(item)) {
                JrwiPictureBadge(
                    icon = "ic_check",
                    description = "Watched",
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
            repository.playedFraction(item)?.let { JrwiPlayedBar(it) }
        }
        overlay()
        JrwiVersionLabel(item = item, modifier = Modifier.align(Alignment.BottomEnd))
    }
}

/** Such as "Episode 136 · 7 Nov 2026", or "Season 2, episode 1" after the first season. */
fun itemDetails(item: JrwiItem, otherShow: String? = null): String? {
    val episode = item.episode?.takeIf { "episodes" in item.ownTabs }
    val season = item.season ?: 1
    val number =
        when {
            episode == null -> null
            season > 1 -> "Season $season, episode $episode"
            else -> "Episode $episode"
        }
    val parts = listOfNotNull(otherShow, number, fullDate(item.date))
    return parts.joinToString(" · ").takeIf { it.isNotEmpty() }
}

/** Which version of its episode a single post is, when the episode has more than one. */
fun versionLabel(post: JrwiItem): String? {
    if (!post.paired) return null
    return when (post.version) {
        "video" -> "Video"
        "audio" -> "Audio"
        "youtube" -> "YouTube"
        else -> null
    }
}

/**
 * An item as a card in a grid or a row: its picture, then its title and details. The picture is
 * outlined in the accent while the mouse is on it.
 */
@Composable
fun JrwiItemCard(
    item: JrwiItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    otherShow: String? = null,
    label: String? = null,
    details: String? = itemDetails(item, otherShow),
    marks: Boolean = true,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Column(
        modifier =
            modifier
                .hoverable(hover)
                .pointerHoverIcon(PointerIcon.Hand)
                .clip(JrwiShape)
                .clickable(onClick = onClick)
    ) {
        JrwiThumbnail(
            item = item,
            marks = marks,
            overlay = overlay,
            modifier =
                Modifier.fillMaxWidth()
                    .border(
                        width = 2.dp,
                        color =
                            if (hovered) MaterialTheme.colorScheme.primary else Color.Transparent,
                        shape = JrwiShape,
                    ),
        )
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleSmall,
            color = JrwiColors.Text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = Spacings.small, start = 2.dp, end = 2.dp),
        )
        details?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
        label?.let { Box(Modifier.padding(top = 4.dp, start = 2.dp)) { JrwiTag(it) } }
        if (item.locked) {
            Text(
                text = "Your tier can't see this post",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
        Spacer(Modifier.height(Spacings.small))
    }
}

/** A small label, as the web app's tags next to a post's date. */
@Composable
fun JrwiTag(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = JrwiColors.PillText,
        modifier =
            modifier
                .background(JrwiColors.Pill, JrwiPillShape)
                .padding(horizontal = 10.dp, vertical = 2.dp),
    )
}

/** A post's tags as chips, as the web app's; a click searches the archive for the tag. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JrwiTags(tags: List<String>, modifier: Modifier = Modifier) {
    val navigator = LocalNavigator.current
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier,
    ) {
        tags.forEach { tag ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier =
                    Modifier.clip(JrwiLabelShape)
                        .background(JrwiColors.Chip)
                        .clickable { navigator.open(Route.JrwiSearch(tag = tag)) }
                        .pointerHoverIcon(PointerIcon.Hand)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                AppIcon(
                    name = "ic_tag",
                    contentDescription = null,
                    tint = JrwiColors.ChipText,
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    text = tag,
                    style = MaterialTheme.typography.labelMedium,
                    color = JrwiColors.ChipText,
                )
            }
        }
    }
}

/** An outlined pill under a post, as the web app's: its likes, comments or a link. */
@Composable
fun JrwiPill(icon: String, text: String, onClick: (() -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier =
            Modifier.clip(JrwiPillShape)
                .border(1.dp, JrwiColors.Line, JrwiPillShape)
                .then(
                    if (onClick != null) {
                        Modifier.clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        AppIcon(
            name = icon,
            contentDescription = null,
            tint = JrwiColors.PillText,
            modifier = Modifier.size(16.dp),
        )
        Text(text = text, style = MaterialTheme.typography.labelLarge, color = JrwiColors.PillText)
    }
}

/** A heading within a post's page, such as Files and Comments. */
@Composable
fun JrwiSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = JrwiColors.Text,
        modifier = modifier.padding(top = Spacings.medium, bottom = Spacings.small),
    )
}

/** A file or a link as a row: an icon, its name, a detail under it. */
@Composable
fun JrwiLinkRow(
    icon: String,
    text: String,
    detail: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacings.medium),
        modifier =
            modifier
                .fillMaxWidth()
                .clip(JrwiShape)
                .clickable(onClick = onClick)
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(horizontal = Spacings.small, vertical = Spacings.small),
    ) {
        AppIcon(
            name = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = JrwiColors.Text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The icon for a file of a post, by how the archiver says to show it. */
fun jrwiFileIcon(type: String): String =
    when (type) {
        "pdf",
        "document",
        "text" -> "ic_logs"
        "table" -> "ic_database"
        "archive" -> "ic_library"
        "image" -> "ic_eye"
        "audio" -> "ic_headphones"
        "video" -> "ic_film"
        "youtube" -> "ic_youtube"
        "link" -> "ic_external_link"
        else -> "ic_download"
    }

fun jrwiFileTypeName(type: String): String =
    when (type) {
        "pdf" -> "PDF"
        "table" -> "Spreadsheet"
        "document" -> "Document"
        "text" -> "Text"
        "archive" -> "Zip archive"
        "image" -> "Picture"
        "audio" -> "Audio"
        "video" -> "Video"
        "youtube" -> "YouTube"
        "link" -> "Link"
        else -> "File"
    }

/** A file's details under its name: its type, the Drive folder it's from and its size. */
fun jrwiFileDetail(file: JrwiPostFile): String {
    val parts = mutableListOf(jrwiFileTypeName(file.type))
    if (!file.saved && file.type == "link") parts += "not saved in the archive"
    file.size?.let { parts += formatBytes(it) }
    return parts.joinToString(" · ")
}

// Comments, as the site shows them

/** A comment with the depth it's shown at: 0, or more for a reply. */
data class FlatComment(val comment: JrwiComment, val depth: Int)

/** Comments and their replies in the order they're shown, each with its depth. */
fun List<JrwiComment>.flattenReplies(depth: Int = 0): List<FlatComment> = flatMap {
    listOf(FlatComment(it, depth)) + it.replies.flattenReplies(depth + 1)
}

/**
 * A comment as the site shows it: the name in its tier's colour, its badges, Pinned, how long ago,
 * a link to it on the site, its text and its likes. Replies are indented by [depth].
 */
@Composable
fun JrwiCommentRow(comment: JrwiComment, depth: Int, modifier: Modifier = Modifier) {
    val avatarSize = if (depth == 0) 36.dp else 28.dp
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacings.small),
        modifier =
            modifier
                .fillMaxWidth()
                .padding(
                    start = (depth.coerceAtMost(4) * 28).dp,
                    top = Spacings.small,
                    bottom = Spacings.small,
                ),
    ) {
        JrwiCommentAvatar(comment.author, avatarSize)
        Column(modifier = Modifier.weight(1f)) {
            JrwiCommentHeader(comment)
            JrwiHtmlText(
                html = comment.bodyHtml?.takeIf { it.isNotBlank() },
                text = comment.bodyText,
                style = MaterialTheme.typography.bodyMedium,
                gap = JRWI_COMMENT_PARAGRAPH_GAP,
            )
            comment.likeCount
                ?.takeIf { it > 0 }
                ?.let { likes ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        AppIcon(
                            name = "ic_heart",
                            contentDescription = "Likes",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = likes.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
        }
    }
}

/** A commenter's picture, or the name's first letter, as the site shows members without one. */
@Composable
fun JrwiCommentAvatar(author: JrwiAuthor, size: Dp) {
    if (author.avatar != null) {
        JrwiImage(path = author.avatar, modifier = Modifier.size(size).clip(CircleShape))
        return
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(size).clip(CircleShape).background(JrwiColors.LineSoft),
    ) {
        Text(
            text = author.name.take(1).uppercase().ifEmpty { "?" },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = JrwiColors.Text,
        )
    }
}

/** Who wrote a comment and when, with a link to it on the site. */
@Composable
fun JrwiCommentHeader(comment: JrwiComment) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        JrwiAuthorName(
            author = comment.author,
            pinned = comment.pinned,
            modifier = Modifier.weight(1f),
        )
        JrwiCommentAge(comment, Modifier.padding(start = Spacings.small))
        comment.link?.let { link ->
            Tooltip("Open this comment on jrwishow.com") {
                AppIcon(
                    name = "ic_external_link",
                    contentDescription = "Open this comment on jrwishow.com",
                    tint = JrwiColors.Muted,
                    modifier =
                        Modifier.padding(start = Spacings.small)
                            .clip(JrwiShape)
                            .clickable { openUrl(link) }
                            .pointerHoverIcon(PointerIcon.Hand)
                            .padding(2.dp)
                            .size(16.dp),
                )
            }
        }
    }
}

/**
 * How long ago a comment was posted, as the web app shows it: worked out from its date ("today",
 * "4d", "2w", "5mo", "1y"), or else the site's own text. Resting the mouse on it shows the date,
 * and what the site showed.
 */
@Composable
fun JrwiCommentAge(comment: JrwiComment, modifier: Modifier = Modifier) {
    val siteText = comment.dateText?.takeIf { it.isNotBlank() }?.let(::shownOnce)
    val date = fullDate(comment.date)
    val age = commentAge(comment.date) ?: siteText ?: date ?: return
    val details =
        when {
            date != null && siteText != null -> "$date (shown on the site as $siteText)"
            else -> date
        }
    val text =
        @Composable {
            Text(
                text = age,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = modifier,
            )
        }
    if (details == null) text() else Tooltip(details) { text() }
}

/**
 * A comment's date as the site shows it, once: older archivers read replies' dates with the time in
 * twice ("2d 2d"), so a text that's the same thing twice over is shown halved.
 */
private fun shownOnce(text: String): String {
    val words = text.trim().split(WHITESPACE)
    if (words.size % 2 != 0) return text
    val half = words.size / 2
    return if (words.subList(0, half) == words.subList(half, words.size)) {
        words.subList(0, half).joinToString(" ")
    } else {
        text
    }
}

private val WHITESPACE = Regex("\\s+")

/**
 * How long ago [iso] was, in whole days as the web app counts them (by the day in UTC, as the
 * archive gives its dates): "today", then days, weeks, months of 30 days and years.
 */
private fun commentAge(iso: String?): String? {
    val day =
        try {
            iso?.let {
                OffsetDateTime.parse(it).withOffsetSameInstant(ZoneOffset.UTC).toLocalDate()
            }
        } catch (_: Exception) {
            null
        } ?: return null
    val days = ChronoUnit.DAYS.between(day, LocalDate.now(ZoneOffset.UTC))
    return when {
        days < 1 -> "today"
        days < 7 -> "${days}d"
        days < 30 -> "${days / 7}w"
        days < 365 -> "${days / 30}mo"
        else -> "${days / 365}y"
    }
}

/** A member's name in their tier's colour, with their badges and, for a comment, Pinned. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JrwiAuthorName(author: JrwiAuthor, modifier: Modifier = Modifier, pinned: Boolean = false) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier,
    ) {
        Text(
            text = author.name,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = cssColor(author.color) ?: JrwiColors.Text,
            modifier = Modifier.align(Alignment.CenterVertically),
        )
        author.badges.forEach { badge ->
            JrwiBadgeLabel(badge, Modifier.align(Alignment.CenterVertically))
        }
        if (pinned) {
            Text(
                text = "Pinned",
                style = MaterialTheme.typography.labelSmall,
                color = JrwiColors.PillText,
                modifier =
                    Modifier.align(Alignment.CenterVertically)
                        .background(JrwiColors.Pill, JrwiLabelShape)
                        .padding(horizontal = 8.dp, vertical = 1.dp),
            )
        }
    }
}

/**
 * A badge next to a commenter's name, as the site shows it: the tier as its picture alone, others
 * outlined with their picture or emoji and their text.
 */
@Composable
private fun JrwiBadgeLabel(badge: JrwiBadge, modifier: Modifier = Modifier) {
    val image = badge.image
    val icon = badge.icon
    if (image != null && icon == null) {
        val picture = @Composable { JrwiBadgeImage(image, badge.name, modifier.height(16.dp)) }
        val name = badge.name
        if (name != null) Tooltip(name) { picture() } else picture()
        return
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier =
            modifier
                .border(1.dp, JrwiColors.Line, JrwiLabelShape)
                .padding(horizontal = 8.dp, vertical = 1.dp),
    ) {
        when {
            image != null -> JrwiBadgeImage(image, null, Modifier.height(14.dp))
            icon != null -> Text(icon, style = MaterialTheme.typography.labelSmall)
        }
        badge.name?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = JrwiColors.BodyText,
            )
        }
    }
}

/** A badge's picture, as wide as it is for its height. */
@Composable
private fun JrwiBadgeImage(path: String, description: String?, modifier: Modifier) {
    val api = LocalApp.current.jrwi.api
    AsyncImage(
        model = pictureFrom(api.fileUrl(path)),
        contentDescription = description,
        contentScale = ContentScale.Fit,
        modifier = modifier.widthIn(max = 64.dp),
    )
}

/** A CSS colour as the site writes it, such as "rgb(247, 221, 210)" or "#f7ddd2"; else null. */
fun cssColor(value: String?): Color? {
    val text = value?.trim()?.lowercase() ?: return null
    RGB_REGEX.matchEntire(text)?.let { match ->
        val channels = (1..3).map { match.groupValues[it].toFloatOrNull() ?: return null }
        val alpha = match.groupValues[4].toFloatOrNull() ?: 1f
        return Color(
            red = (channels[0] / 255f).coerceIn(0f, 1f),
            green = (channels[1] / 255f).coerceIn(0f, 1f),
            blue = (channels[2] / 255f).coerceIn(0f, 1f),
            alpha = alpha.coerceIn(0f, 1f),
        )
    }
    if (!text.startsWith("#")) return null
    val hex = text.removePrefix("#")
    val full = if (hex.length == 3) hex.map { "$it$it" }.joinToString("") else hex
    val rgb = full.takeIf { it.length == 6 }?.toLongOrNull(16) ?: return null
    return Color(0xFF000000 or rgb)
}

private val RGB_REGEX =
    Regex("""rgba?\(\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)\s*(?:,\s*([\d.]+)\s*)?\)""")
