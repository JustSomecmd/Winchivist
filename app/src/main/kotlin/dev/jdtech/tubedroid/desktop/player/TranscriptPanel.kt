package dev.jdtech.tubedroid.desktop.player

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.jrwi.JrwiTranscript
import dev.jdtech.jellyfin.jrwi.JrwiTranscriptLine
import dev.jdtech.jellyfin.jrwi.JrwiTranscriptTimes
import dev.jdtech.tubedroid.desktop.LocalApp
import dev.jdtech.tubedroid.desktop.jrwi.jrwiErrorText
import dev.jdtech.tubedroid.desktop.ui.RoundIconButton
import dev.jdtech.tubedroid.desktop.ui.Spacings
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first

/**
 * A JRWI episode's transcript, as the archive's web app shows it under its player: each turn with
 * its speaker, and the line being spoken lit and kept a third of the way down. Scrolling by hand
 * stops it following until it's asked back, and clicking a timed line plays from there. The lines
 * are those of the post [postId], timed in that post's timeline, which is what [position] is in.
 */
@Composable
internal fun TranscriptPanel(
    postId: Long,
    position: () -> Double,
    onSeek: (Double) -> Unit,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
) {
    val repository = LocalApp.current.jrwi
    var transcript by remember(postId) { mutableStateOf<JrwiTranscript?>(null) }
    var error by remember(postId) { mutableStateOf<String?>(null) }
    var attempt by remember(postId) { mutableIntStateOf(0) }
    LaunchedEffect(postId, attempt) {
        error = null
        try {
            transcript = repository.transcript(postId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = jrwiErrorText(e)
        }
    }
    Surface(color = MaterialTheme.colorScheme.background, modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (onClose != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .padding(start = Spacings.medium, end = Spacings.small)
                            .padding(vertical = 4.dp),
                ) {
                    Text(
                        text = "Transcript",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    RoundIconButton(
                        icon = "ic_x",
                        label = "Close the transcript",
                        onClick = onClose,
                    )
                }
            }
            val lines = transcript
            val failed = error
            when {
                lines != null -> TranscriptLines(lines, position, onSeek)
                failed != null ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth().padding(Spacings.default),
                    ) {
                        Text(text = failed, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { attempt += 1 }) { Text("Retry") }
                    }
                else ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TranscriptLines(
    transcript: JrwiTranscript,
    position: () -> Double,
    onSeek: (Double) -> Unit,
) {
    val times = remember(transcript) { JrwiTranscriptTimes(transcript) }
    val listState = rememberLazyListState()
    var following by remember(transcript) { mutableStateOf(true) }
    var placed by remember(transcript) { mutableStateOf(false) }
    // Only a new line redraws anything, not each reading of where the sound is
    val current by
        remember(times) { derivedStateOf { if (times.any) times.lineAt(position()) else -1 } }

    LaunchedEffect(current, following) {
        if (!following || current < 0) return@LaunchedEffect
        // The line being spoken sits a third of the way down, once the list knows its height
        val height = snapshotFlow { listState.layoutInfo.viewportSize.height }.first { it > 0 }
        if (placed) {
            listState.animateScrollToItem(current, -(height / 3))
        } else {
            listState.scrollToItem(current, -(height / 3))
            placed = true
        }
    }

    Box(
        modifier =
            Modifier.fillMaxSize().onPointerEvent(PointerEventType.Scroll) {
                // Scrolled by hand, the lines stay where they're put
                if (times.any) following = false
            }
    ) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = Spacings.small, bottom = 96.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(transcript.lines, contentType = { _, _ -> "line" }) { index, line ->
                val start = line.start.takeIf { times.any }
                val play: (() -> Unit)? =
                    if (start != null) {
                        {
                            onSeek(start)
                            following = true
                        }
                    } else {
                        null
                    }
                TranscriptLine(
                    line = line,
                    lit = index == current,
                    // A line the archive couldn't place among timed ones
                    dimmed = times.any && start == null,
                    onClick = play,
                )
            }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier =
                Modifier.align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(vertical = 2.dp)
                    .onPointerEvent(PointerEventType.Press) { if (times.any) following = false },
        )
        if (!times.any) {
            Text(
                text = "Not timed yet: the lines don't follow the sound.",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier.align(Alignment.BottomCenter)
                        .padding(Spacings.medium)
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerHighest,
                            MaterialTheme.shapes.small,
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        } else if (!following) {
            Button(
                onClick = { following = true },
                modifier = Modifier.align(Alignment.BottomCenter).padding(Spacings.medium),
            ) {
                Text("Back to the line being spoken")
            }
        }
    }
}

/**
 * A speaker's turn: the name in bold in the accent, then what was said. A note on a line of its
 * own, such as "[laughter]", is muted and slanted. The line being spoken has the accent behind it
 * and a bar of it at its start.
 */
@Composable
private fun TranscriptLine(
    line: JrwiTranscriptLine,
    lit: Boolean,
    dimmed: Boolean,
    onClick: (() -> Unit)?,
) {
    val accent = MaterialTheme.colorScheme.primary
    val speaker = line.speaker
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .background(if (lit) accent.copy(alpha = LIT_ALPHA) else Color.Transparent)
                .drawBehind {
                    if (lit) drawRect(color = accent, size = Size(3.dp.toPx(), size.height))
                }
                .padding(horizontal = Spacings.medium, vertical = 6.dp)
                .alpha(if (dimmed) DIMMED_ALPHA else 1f)
    ) {
        if (speaker != null) {
            Text(
                text = speaker,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = accent,
            )
        }
        Text(
            text = line.text,
            style = MaterialTheme.typography.bodyMedium,
            fontStyle = if (speaker == null) FontStyle.Italic else null,
            color =
                if (speaker == null) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onBackground,
        )
    }
}

private const val LIT_ALPHA = 0.14f
private const val DIMMED_ALPHA = 0.55f
