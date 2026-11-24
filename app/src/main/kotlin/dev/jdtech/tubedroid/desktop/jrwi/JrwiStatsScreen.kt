package dev.jdtech.tubedroid.desktop.jrwi

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.jrwi.JrwiAuthor
import dev.jdtech.jellyfin.jrwi.JrwiCheckRun
import dev.jdtech.jellyfin.jrwi.JrwiCommentStats
import dev.jdtech.jellyfin.jrwi.JrwiFileCount
import dev.jdtech.jellyfin.jrwi.JrwiStatus
import dev.jdtech.tubedroid.desktop.LocalApp
import dev.jdtech.tubedroid.desktop.LocalNavigator
import dev.jdtech.tubedroid.desktop.Route
import dev.jdtech.tubedroid.desktop.rememberPageModel
import dev.jdtech.tubedroid.desktop.screens.RefreshButton
import dev.jdtech.tubedroid.desktop.ui.AppIcon
import dev.jdtech.tubedroid.desktop.ui.BlockFlowScope
import dev.jdtech.tubedroid.desktop.ui.BlockPage
import dev.jdtech.tubedroid.desktop.ui.RoundIconButton
import dev.jdtech.tubedroid.desktop.ui.Spacings
import dev.jdtech.tubedroid.desktop.ui.formatDuration
import dev.jdtech.tubedroid.desktop.ui.formatNumber
import dev.jdtech.tubedroid.desktop.ui.formatPlaytime
import dev.jdtech.tubedroid.desktop.ui.fullDate
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * The archive's numbers, in the archiver's own purples, as TubeArchivist's stats: the main ones in
 * a card at the top, then watch progress, what's in the archive, the biggest shows, what was
 * archived lately, posts by year, likes and comments, records, and what the archiver counts itself.
 */
@Composable
fun JrwiStatsScreen() {
    val app = LocalApp.current
    val navigator = LocalNavigator.current
    val model = rememberPageModel("jrwi-stats") { scope -> JrwiStatsModel(app.jrwi, scope) }
    // The status is only followed while the page shows
    LaunchedEffect(model) { model.follow() }
    val stats = model.stats
    JrwiSheetTheme {
        Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            JrwiBar(
                title = "Archive stats",
                onBack = null,
                color = Color.Transparent,
                showSearch = false,
            ) {
                RefreshButton(onClick = model::refresh)
                RoundIconButton(
                    icon = "ic_external_link",
                    label = "Open the archiver's admin page",
                    onClick = { openUrl(app.jrwi.api.fileUrl(JRWI_ADMIN_PATH)) },
                )
            }
            if (model.loading && stats != null) {
                LinearProgressIndicator(
                    trackColor = Color.Transparent,
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                )
            }
            if (stats == null) {
                JrwiPageState(loading = true, error = model.error, onRetry = model::refresh)
                return@Column
            }
            // Above the cards rather than among them, so it shows wherever they're scrolled to
            model.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    modifier =
                        Modifier.padding(horizontal = Spacings.default)
                            .padding(top = Spacings.medium),
                )
            }
            BlockPage(
                scroll = model.scroll,
                minColumnWidth = CARD_MIN_WIDTH,
                modifier = Modifier.fillMaxSize(),
            ) {
                FullLine(id = "hero") { JrwiStatsHero(stats, model.status) }
                JrwiStatsCards(
                    stats = stats,
                    status = model.status,
                    onShowClick = { navigator.open(Route.JrwiShowPage(it)) },
                    onItemClick = { navigator.open(Route.JrwiPost(it)) },
                )
            }
        }
    }
}

/** The main numbers: posts, storage, playing time, episodes and the share played. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JrwiStatsHero(stats: JrwiArchiveStats, status: JrwiStatus?) {
    Box(
        modifier =
            Modifier.fillMaxWidth()
                .clip(JrwiShape)
                .background(
                    Brush.linearGradient(
                        listOf(JrwiPurple.Input, JrwiPurple.Card, JrwiPurple.Sheet)
                    )
                )
    ) {
        // The archiver's d20, large and faint, behind the numbers
        AppIcon(
            name = "ic_dice",
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.12f),
            modifier =
                Modifier.size(190.dp).align(Alignment.TopEnd).offset(x = 30.dp, y = (-30).dp),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(Spacings.small),
            modifier = Modifier.fillMaxWidth().padding(Spacings.default),
        ) {
            Text(
                text =
                    listOfNotNull("JRWI archive", stats.version?.let { "jrwi-archiver $it" })
                        .joinToString(" · "),
                color = JrwiPurple.Muted,
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = plural(stats.posts, "post", "posts", formatNumber(stats.posts.toLong())),
                color = Color.White,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacings.default),
                verticalArrangement = Arrangement.spacedBy(Spacings.small),
            ) {
                status?.disk?.archiveBytes?.let {
                    JrwiHeroStat(
                        icon = "ic_database",
                        label = "Storage used",
                        value = formatBytes(it),
                    )
                }
                JrwiHeroStat(
                    icon = "ic_clock",
                    label = "Playing time",
                    value = formatPlaytime(stats.playtime.toLong()),
                )
                JrwiHeroStat(
                    icon = "ic_film",
                    label = "Episodes",
                    value = formatNumber(stats.episodes.toLong()),
                )
            }
            stats.watch
                .takeIf { it.total > 0 }
                ?.let { watch ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = Spacings.small),
                    ) {
                        JrwiStatsBar(
                            fraction = watch.share.toFloat(),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(Spacings.medium))
                        Text(
                            text = "${formatPercent(watch.share)}% watched",
                            color = JrwiPurple.Muted,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
        }
    }
}

@Composable
private fun JrwiHeroStat(icon: String, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppIcon(
            name = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(text = value, color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text(
                text = label,
                color = JrwiPurple.Muted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/**
 * The cards under the main numbers, each where it has something to show. Their rough heights are
 * what shares them out over the columns: a list's goes by how long it is now, as these lists only
 * change over months.
 */
@Composable
private fun BlockFlowScope.JrwiStatsCards(
    stats: JrwiArchiveStats,
    status: JrwiStatus?,
    onShowClick: (String) -> Unit,
    onItemClick: (Long) -> Unit,
) {
    if (stats.watch.total > 0) {
        Block(id = "watch", about = 332.dp) { JrwiWatchCard(stats.watch) }
    }
    Block(id = "contents", about = 365.dp) { JrwiContentsCard(stats, status) }
    if (stats.shows.isNotEmpty()) {
        Block(id = "shows", about = 100.dp + RANK_ABOUT * minOf(stats.shows.size, 10)) {
            JrwiShowsCard(stats.shows, onShowClick)
        }
    }
    stats.archivedDays?.let { days ->
        Block(id = "archived", about = 244.dp) { JrwiArchivedCard(days, stats.failedChecks) }
    }
    if (stats.byYear.isNotEmpty()) {
        Block(id = "years", about = 208.dp) { JrwiYearsCard(stats) }
    }
    if (stats.likes > 0 || stats.comments > 0) {
        Block(id = "community", about = 404.dp) { JrwiCommunityCard(stats, onItemClick) }
    }
    Block(id = "records", about = 256.dp) { JrwiRecordsCard(stats, onItemClick) }
    if (stats.bothVersions > 0 || stats.heights.isNotEmpty()) {
        Block(id = "versions", about = 157.dp + RANK_ABOUT * stats.heights.size) {
            JrwiVersionsCard(stats)
        }
    }
    // What the archiver counts itself
    stats.extra?.let { extra ->
        if (extra.checks.isNotEmpty()) {
            Block(id = "checks", about = 136.dp + LINE_ABOUT * extra.checks.size) {
                JrwiChecksCard(extra.checks)
            }
        }
        if (extra.files.isNotEmpty()) {
            Block(id = "files", about = 64.dp + LINE_ABOUT * extra.files.size) {
                JrwiFilesCard(extra.files)
            }
        }
        extra.comments
            ?.takeIf { it.total > 0 }
            ?.let { comments ->
                Block(id = "commenters", about = 148.dp + LINE_ABOUT * comments.top.size) {
                    JrwiCommentersCard(comments)
                }
            }
    }
}

// About how tall a line of a card's list is, for sharing the cards out: one with a bar under
// it, and one with a second line of small print
private val RANK_ABOUT = 40.dp
private val LINE_ABOUT = 48.dp

/** How much of the archive was watched or listened to, by playing time. */
@Composable
private fun JrwiWatchCard(watch: JrwiWatchTotals) {
    JrwiCard(title = "Watch progress") {
        Text(
            text = "${formatPercent(watch.share)}% watched",
            style = MaterialTheme.typography.headlineSmall,
            color = JrwiColors.Text,
        )
        Text(
            text =
                "By playing time, out of every episode in the archive. An episode's video and " +
                    "audio count once.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        JrwiStatsBar(
            fraction = watch.share.toFloat(),
            modifier = Modifier.padding(vertical = Spacings.small),
        )
        JrwiStatLine(label = "Time played", value = formatPlaytime(watch.played.toLong()))
        JrwiStatLine(
            label = "Watched",
            detail = episodeCount(watch.watched.count),
            value = formatPlaytime(watch.watched.seconds.toLong()),
        )
        if (watch.started.count > 0) {
            val playedOfStarted = watch.played - watch.watched.seconds
            val left = (watch.started.seconds - playedOfStarted).toLong().coerceAtLeast(0)
            JrwiStatLine(
                label = "Started",
                detail = episodeCount(watch.started.count) + " · ${formatPlaytime(left)} left",
                value = formatPlaytime(watch.started.seconds.toLong()),
            )
        }
        JrwiStatLine(
            label = "Unwatched",
            detail = episodeCount(watch.unwatched.count),
            value = formatPlaytime(watch.unwatched.seconds.toLong()),
        )
    }
}

/** What kinds of posts and files the archive holds, and what it couldn't keep. */
@Composable
private fun JrwiContentsCard(stats: JrwiArchiveStats, status: JrwiStatus?) {
    JrwiCard(title = "In the archive") {
        JrwiTotalsLine("Members-only videos", stats.videos)
        JrwiTotalsLine("Audio", stats.audio)
        if (stats.youtube.count > 0) {
            val length = formatPlaytime(stats.youtube.seconds.toLong())
            JrwiStatLine(
                label = "Videos on YouTube",
                // Their sizes are TubeArchivist's copies', from archivers since 0.14.9
                detail =
                    if (stats.youtube.bytes > 0) {
                        "${formatBytes(stats.youtube.bytes)} · $length · played from TubeArchivist"
                    } else {
                        "$length · played from TubeArchivist"
                    },
                value = formatNumber(stats.youtube.count.toLong()),
            )
        }
        if (stats.handedOver > 0) {
            JrwiStatLine(
                label = "Handed over to TubeArchivist",
                value = formatNumber(stats.handedOver.toLong()),
            )
        }
        JrwiStatLine(label = "Text posts", value = formatNumber(stats.textPosts.toLong()))
        JrwiStatLine(label = "Art", value = formatNumber(stats.artPosts.toLong()))
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        status?.counts?.let { counts ->
            if (counts.locked > 0) {
                JrwiStatLine(
                    label = "Above your tier",
                    value = formatNumber(counts.locked.toLong()),
                )
            }
            if (counts.problems > 0) {
                JrwiStatLine(
                    label = "Not fully saved",
                    value = formatNumber(counts.problems.toLong()),
                )
            }
        }
        if (stats.removedFromSite > 0) {
            JrwiStatLine(
                label = "Taken down from the site",
                detail = "Still kept in the archive",
                value = formatNumber(stats.removedFromSite.toLong()),
            )
        }
        val disk = status?.disk
        disk?.archiveBytes?.let { archived ->
            // Thumbnails, pictures, documents, saved pages and the archiver's database
            val other = archived - stats.videos.bytes - stats.audio.bytes
            JrwiStatLine(
                label = "Storage used",
                detail =
                    listOfNotNull(
                            other
                                .takeIf { it > 0 }
                                ?.let { "${formatBytes(it)} of pictures, documents and pages" },
                            disk?.freeBytes?.let { "${formatBytes(it)} free" },
                        )
                        .joinToString(" · ")
                        .takeIf { it.isNotEmpty() },
                value = formatBytes(archived),
            )
        }
    }
}

@Composable
private fun JrwiTotalsLine(label: String, totals: JrwiTotals) {
    JrwiStatLine(
        label = label,
        detail =
            if (totals.count > 0) {
                formatBytes(totals.bytes) + " · " + formatPlaytime(totals.seconds.toLong())
            } else {
                null
            },
        value = formatNumber(totals.count.toLong()),
    )
}

private enum class JrwiShowOrder(val label: String) {
    POSTS("Posts"),
    PLAYTIME("Playing time"),
    STORAGE("Storage"),
    WATCHED("Watched"),
}

/** The ten biggest shows by posts, playing time or storage, or the most watched. */
@Composable
private fun JrwiShowsCard(shows: List<JrwiShowTotals>, onShowClick: (String) -> Unit) {
    var order by rememberSaveable { mutableStateOf(JrwiShowOrder.POSTS) }
    JrwiCard(title = "Biggest shows") {
        JrwiStatsChoices(
            choices = JrwiShowOrder.entries.map { it to it.label },
            chosen = order,
            onChoose = { order = it },
        )
        val ranked =
            when (order) {
                JrwiShowOrder.POSTS -> shows.sortedByDescending { it.posts }
                JrwiShowOrder.PLAYTIME -> shows.sortedByDescending { it.seconds }
                JrwiShowOrder.STORAGE -> shows.sortedByDescending { it.bytes }
                JrwiShowOrder.WATCHED ->
                    shows.filter { it.seconds > 0 }.sortedByDescending { it.watchedShare }
            }.take(TOP_SHOWS)
        if (ranked.isEmpty()) JrwiStatsNone()
        val max =
            when (order) {
                JrwiShowOrder.WATCHED -> 1.0
                else -> ranked.maxOfOrNull { it.value(order) }?.coerceAtLeast(1.0) ?: 1.0
            }
        ranked.forEachIndexed { index, show ->
            JrwiRankRow(
                rank = index + 1,
                name = show.name,
                value =
                    when (order) {
                        JrwiShowOrder.POSTS -> formatNumber(show.posts.toLong())
                        JrwiShowOrder.PLAYTIME -> formatPlaytime(show.seconds.toLong())
                        JrwiShowOrder.STORAGE -> formatBytes(show.bytes)
                        JrwiShowOrder.WATCHED -> formatPercent(show.watchedShare) + "%"
                    },
                fraction = (show.value(order) / max).toFloat(),
                onClick = { onShowClick(show.id) },
            )
        }
    }
}

private fun JrwiShowTotals.value(order: JrwiShowOrder): Double =
    when (order) {
        JrwiShowOrder.POSTS -> posts.toDouble()
        JrwiShowOrder.PLAYTIME -> seconds
        JrwiShowOrder.STORAGE -> bytes.toDouble()
        JrwiShowOrder.WATCHED -> watchedShare
    }

private const val TOP_SHOWS = 10

/** Posts archived on each of the last eight days, from the archiver's events. */
@Composable
private fun JrwiArchivedCard(days: List<Pair<LocalDate, Int>>, failedChecks: Int) {
    JrwiCard(title = "Archived lately") {
        JrwiStatLine(
            label = "Archived in the last 8 days",
            value = formatNumber(days.sumOf { it.second }.toLong()),
        )
        if (failedChecks > 0) {
            JrwiStatLine(
                label = "Failed checks in the last 8 days",
                value = formatNumber(failedChecks.toLong()),
            )
        }
        val today = LocalDate.now()
        JrwiBarChart(
            bars =
                days.map { (day, count) ->
                    val label =
                        if (day == today) "Today"
                        else day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                    label to count
                }
        )
    }
}

/** Posts by the year they came out on the site. */
@Composable
private fun JrwiYearsCard(stats: JrwiArchiveStats) {
    JrwiCard(title = "Posts by year") {
        stats.byYear
            .maxByOrNull { it.second }
            ?.let { (year, count) ->
                JrwiStatLine(label = "Busiest year: $year", value = formatNumber(count.toLong()))
            }
        // The last years, so the bars stay wide enough to read
        JrwiBarChart(
            bars = stats.byYear.takeLast(MAX_YEARS).map { (year, count) -> "$year" to count }
        )
    }
}

private const val MAX_YEARS = 10

/** Likes and comments across the archive, and the posts with the most. */
@Composable
private fun JrwiCommunityCard(stats: JrwiArchiveStats, onItemClick: (Long) -> Unit) {
    var byComments by rememberSaveable { mutableStateOf(false) }
    JrwiCard(title = "Likes and comments") {
        JrwiStatLine(
            label = "Likes",
            detail =
                stats.posts.takeIf { it > 0 }?.let { "${formatNumber(stats.likes / it)} per post" },
            value = formatNumber(stats.likes),
        )
        JrwiStatLine(
            label = "Comments",
            detail =
                stats.posts
                    .takeIf { it > 0 }
                    ?.let { "${formatNumber(stats.comments / it)} per post" },
            value = formatNumber(stats.comments),
        )
        JrwiStatsChoices(
            choices = listOf(false to "Most liked", true to "Most comments"),
            chosen = byComments,
            onChoose = { byComments = it },
            modifier = Modifier.padding(top = Spacings.small),
        )
        val ranked = if (byComments) stats.mostCommented else stats.mostLiked
        if (ranked.isEmpty()) JrwiStatsNone()
        val max = (ranked.maxOfOrNull { it.value } ?: 0L).coerceAtLeast(1L)
        ranked.forEachIndexed { index, entry ->
            JrwiRankRow(
                rank = index + 1,
                name = entry.post.title,
                value = formatNumber(entry.value),
                fraction = entry.value.toFloat() / max,
                onClick = { onItemClick(entry.post.id) },
            )
        }
    }
}

/** The longest episode, the biggest video, and the first and newest posts. */
@Composable
private fun JrwiRecordsCard(stats: JrwiArchiveStats, onItemClick: (Long) -> Unit) {
    JrwiCard(title = "Records") {
        stats.longest?.let {
            JrwiStatLine(
                label = "Longest episode",
                detail = it.post.title,
                value = formatDuration(it.value.toDouble()),
                onClick = { onItemClick(it.post.id) },
            )
        }
        stats.biggestVideo?.let {
            JrwiStatLine(
                label = "Biggest video",
                detail = it.post.title,
                value = formatBytes(it.value),
                onClick = { onItemClick(it.post.id) },
            )
        }
        stats.first?.let {
            JrwiStatLine(
                label = "First post",
                detail = it.title,
                value = fullDate(it.date).orEmpty(),
                onClick = { onItemClick(it.id) },
            )
        }
        stats.newest?.let {
            JrwiStatLine(
                label = "Newest post",
                detail = it.title,
                value = fullDate(it.date).orEmpty(),
                onClick = { onItemClick(it.id) },
            )
        }
    }
}

/** How the episodes' versions line up, and the archived videos' quality. */
@Composable
private fun JrwiVersionsCard(stats: JrwiArchiveStats) {
    JrwiCard(title = "Video and audio") {
        if (stats.bothVersions > 0) {
            JrwiStatLine(
                label = "Episodes with both versions",
                value = formatNumber(stats.bothVersions.toLong()),
            )
            JrwiStatLine(
                label = "Lined up by the archiver",
                detail =
                    "${formatNumber((stats.bothVersions - stats.linedUp).toLong())} assumed to " +
                        "start together",
                value = formatNumber(stats.linedUp.toLong()),
            )
        }
        if (stats.heights.isNotEmpty()) {
            if (stats.bothVersions > 0) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            val max = (stats.heights.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
            stats.heights.forEach { (height, count) ->
                JrwiRankRow(
                    rank = null,
                    name = "${height}p",
                    value = formatNumber(count.toLong()),
                    fraction = count.toFloat() / max,
                )
            }
        }
    }
}

/** The archiver's last checks: when, how long, what came of them. From its own stats. */
@Composable
private fun JrwiChecksCard(checks: List<JrwiCheckRun>) {
    JrwiCard(title = "Last checks") {
        val lengths = checks.mapNotNull { checkLength(it) }
        if (lengths.isNotEmpty()) {
            JrwiStatLine(
                label = "Average length",
                value = formatPlaytime(lengths.average().toLong()),
            )
        }
        JrwiStatLine(
            label = "New posts found",
            value = formatNumber(checks.sumOf { it.newItems }.toLong()),
        )
        checks.take(MAX_CHECKS).forEach { check ->
            JrwiStatLine(
                label = dateAndTime(check.started) ?: "?",
                detail =
                    listOfNotNull(
                            check.result?.let(::checkResultText),
                            checkLength(check)?.let { formatPlaytime(it) },
                            check.message?.takeIf { it.isNotBlank() },
                        )
                        .joinToString(" · ")
                        .takeIf { it.isNotEmpty() },
                value = "+" + formatNumber(check.newItems.toLong()),
            )
        }
    }
}

private const val MAX_CHECKS = 5

/** How long a check took, in seconds, or null when it's still running or the times are off. */
private fun checkLength(check: JrwiCheckRun): Long? =
    try {
        val started = OffsetDateTime.parse(check.started ?: return null)
        val finished = OffsetDateTime.parse(check.finished ?: return null)
        Duration.between(started, finished).seconds.takeIf { it >= 0 }
    } catch (_: Exception) {
        null
    }

/** The archive's files by type, as its Admin > Files page counts them. From its own stats. */
@Composable
private fun JrwiFilesCard(files: List<JrwiFileCount>) {
    JrwiCard(title = "Files") {
        val byType =
            files
                .groupBy { it.type }
                .map { (type, list) ->
                    Triple(type, list.sumOf { it.count }, list.sumOf { it.bytes })
                }
                .sortedByDescending { it.second }
        byType.forEach { (type, count, bytes) ->
            JrwiStatLine(
                label = jrwiFileTypeName(type),
                detail =
                    files
                        .filter { it.type == type && it.ext.isNotBlank() }
                        .sortedByDescending { it.count }
                        .take(MAX_EXTENSIONS)
                        .joinToString(", ") { it.ext } +
                        (if (bytes > 0) " · " + formatBytes(bytes) else ""),
                value = formatNumber(count.toLong()),
            )
        }
    }
}

private const val MAX_EXTENSIONS = 4

/** Who comments most. From the archiver's own stats. */
@Composable
private fun JrwiCommentersCard(comments: JrwiCommentStats) {
    JrwiCard(title = "Commenters") {
        JrwiStatLine(
            label = "Comments",
            detail = "${formatNumber(comments.replies.toLong())} of them replies",
            value = formatNumber(comments.total.toLong()),
        )
        if (comments.commenters > 0) {
            JrwiStatLine(
                label = "Members who commented",
                value = formatNumber(comments.commenters.toLong()),
            )
        }
        comments.top.forEachIndexed { index, member ->
            val author =
                JrwiAuthor(
                    id = member.id,
                    name = member.name,
                    avatar = member.avatar,
                    badges = member.badges,
                    color = member.color,
                )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacings.small),
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            ) {
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(20.dp),
                )
                JrwiCommentAvatar(author, 28.dp)
                Column(modifier = Modifier.weight(1f)) {
                    JrwiAuthorName(author = author)
                    if (member.likes > 0) {
                        Text(
                            text = "${formatNumber(member.likes.toLong())} likes on their comments",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text = formatNumber(member.count.toLong()),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = JrwiColors.Text,
                )
            }
        }
    }
}

/** A number's name, with a detail under it, and the number on the right. A click opens it. */
@Composable
private fun JrwiStatLine(
    label: String,
    value: String,
    detail: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.fillMaxWidth()
                .clip(JrwiShape)
                .then(
                    if (onClick != null) {
                        Modifier.clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
                    } else {
                        Modifier
                    }
                )
                .padding(vertical = 6.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = JrwiColors.BodyText,
            )
            detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(Spacings.small))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = JrwiColors.Text,
        )
    }
}

/** A place in a ranking: its number, its name and value, and a bar for how it compares. */
@Composable
private fun JrwiRankRow(
    rank: Int?,
    name: String,
    value: String,
    fraction: Float,
    onClick: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.fillMaxWidth()
                .clip(JrwiShape)
                .then(
                    if (onClick != null) {
                        Modifier.clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
                    } else {
                        Modifier
                    }
                )
                .padding(vertical = 6.dp, horizontal = 4.dp),
    ) {
        rank?.let {
            Text(
                text = "$it",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(28.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = JrwiColors.BodyText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(Spacings.small))
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = JrwiColors.Text,
                )
            }
            JrwiStatsBar(
                fraction = fraction,
                trackColor = Color.Transparent,
                height = 4.dp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** A row of choices, the chosen one filled in the archiver's purple. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> JrwiStatsChoices(
    choices: List<Pair<T, String>>,
    chosen: T,
    onChoose: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacings.small),
        verticalArrangement = Arrangement.spacedBy(Spacings.small),
        modifier = modifier.padding(bottom = 4.dp),
    ) {
        choices.forEach { (value, name) ->
            val selected = value == chosen
            Text(
                text = name,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) JrwiPurple.Ink else JrwiColors.Text,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                modifier =
                    Modifier.clip(JrwiShape)
                        .background(if (selected) JrwiPurple.Accent else JrwiPurple.Input)
                        .clickable { onChoose(value) }
                        .pointerHoverIcon(PointerIcon.Hand)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/** A bar for each value, its count above and its name under it. */
@Composable
private fun JrwiBarChart(bars: List<Pair<String, Int>>) {
    val max = (bars.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
    val barHeight = 64.dp
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.fillMaxWidth().padding(top = Spacings.small),
    ) {
        bars.forEach { (label, value) ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = if (value > 0) formatNumber(value.toLong()) else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = JrwiColors.BodyText,
                    maxLines = 1,
                )
                Box(
                    modifier =
                        Modifier.fillMaxWidth()
                            .height(if (value > 0) barHeight * (value.toFloat() / max) else 2.dp)
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                            .background(if (value > 0) JrwiPurple.Accent else JrwiPurple.Input)
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** A bar filled to [fraction], in the archiver's purple. */
@Composable
private fun JrwiStatsBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    trackColor: Color = JrwiPurple.Input,
    height: Dp = 8.dp,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(50))
                .background(trackColor)
    ) {
        Box(
            modifier =
                Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(50))
                    .background(JrwiPurple.Accent)
        )
    }
}

@Composable
private fun JrwiStatsNone() {
    Text(
        text = "Nothing to show yet",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = Spacings.small),
    )
}

private fun episodeCount(count: Int): String =
    plural(count, "episode", "episodes", formatNumber(count.toLong()))

/** "1 post", "1,171 posts": [count] as [shown] with the right word after it. */
private fun plural(count: Int, one: String, many: String, shown: String): String =
    "$shown ${if (count == 1) one else many}"

/** 0.2612 as 26.1. */
private fun formatPercent(fraction: Double): String =
    String.format(Locale.getDefault(), "%.1f", fraction * 100)
