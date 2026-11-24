package dev.jdtech.tubedroid.desktop.jrwi

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jdtech.tubedroid.desktop.LocalApp
import dev.jdtech.tubedroid.desktop.LocalNavigator
import dev.jdtech.tubedroid.desktop.Section
import dev.jdtech.tubedroid.desktop.compute.HearJob
import dev.jdtech.tubedroid.desktop.ui.AppIcon
import dev.jdtech.tubedroid.desktop.ui.RoundIconButton
import dev.jdtech.tubedroid.desktop.ui.Spacings

/**
 * The compute box's question, as a small card in a corner of the window: whether now is a good time
 * to hear the episodes waiting. It shows on whatever page is open and stays until it's answered.
 * Nothing is heard before the answer is yes.
 */
@Composable
fun ComputeAskCard(modifier: Modifier = Modifier) {
    val app = LocalApp.current
    val navigator = LocalNavigator.current
    val box = app.compute
    val asking = box.asking
    // What it asked about last, for the moment the card takes to go
    val last = remember { AskedAbout() }
    if (asking != null) last.jobs = asking
    AnimatedVisibility(
        visible = asking != null,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut(),
        modifier = modifier,
    ) {
        JrwiSheetTheme {
            AskCard(
                waiting = last.jobs,
                model = app.settings.hearModel,
                onHear = {
                    box.start(asked = true)
                    // It doesn't start while the archiver's list is being read; the card then stays
                    if (box.asking == null) {
                        navigator.message(
                            "The compute box is starting. Its page shows how far it is."
                        )
                    }
                },
                onLater = { box.askLater(tomorrow = false) },
                onTomorrow = { box.askLater(tomorrow = true) },
                onOpen = { navigator.select(Section.JRWI_COMPUTE) },
            )
        }
    }
}

private class AskedAbout {
    var jobs: List<HearJob> = emptyList()
}

@Composable
private fun AskCard(
    waiting: List<HearJob>,
    model: String,
    onHear: () -> Unit,
    onLater: () -> Unit,
    onTomorrow: () -> Unit,
    onOpen: () -> Unit,
) {
    val first = waiting.firstOrNull() ?: return
    val one = waiting.size == 1
    val seconds = waiting.sumOf { it.seconds ?: 0.0 }
    val sound =
        if (seconds > 0) hours(seconds) + " of sound" + (if (one) "" else " in all") + ", heard"
        else "Heard"
    Column(
        verticalArrangement = Arrangement.spacedBy(Spacings.small),
        modifier =
            Modifier.width(ASK_WIDTH)
                .shadow(16.dp, JrwiShape)
                .clip(JrwiShape)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .border(1.dp, MaterialTheme.colorScheme.outline, JrwiShape)
                // A click beside the buttons stays with the card, and doesn't reach the page under
                // it
                .pointerInput(Unit) { detectTapGestures {} }
                .padding(start = Spacings.medium, end = Spacings.small, bottom = Spacings.medium),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacings.small),
            modifier = Modifier.padding(top = Spacings.small),
        ) {
            AppIcon(
                name = "ic_monitor",
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = "Compute box",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            RoundIconButton(
                icon = "ic_settings",
                label = "When it asks, and what it hears with",
                onClick = onOpen,
                size = 32.dp,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(Spacings.small),
            modifier = Modifier.padding(end = Spacings.small),
        ) {
            Text(
                text =
                    if (one) "Is now a good time to hear this episode?"
                    else "Is now a good time to hear these ${waiting.size} episodes?",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = JrwiColors.Text,
            )
            Text(
                text = if (one) first.title else "${first.title}, and ${waiting.size - 1} more",
                style = MaterialTheme.typography.bodyMedium,
                color = JrwiColors.BodyText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text =
                    "$sound on this PC with $model to time " +
                        (if (one) "its transcript." else "their transcripts, newest first."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacings.small),
                modifier = Modifier.padding(top = Spacings.extraSmall),
            ) {
                Button(shape = JrwiShape, onClick = onHear) {
                    Text(if (one) "Hear it now" else "Hear them now")
                }
                // The theme's own outline is the card's colour, so these two say theirs
                OutlinedButton(
                    shape = JrwiShape,
                    border =
                        BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
                    colors =
                        ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary
                        ),
                    onClick = onLater,
                ) {
                    Text("In an hour")
                }
                TextButton(
                    shape = JrwiShape,
                    colors =
                        ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                    onClick = onTomorrow,
                ) {
                    Text("Not today")
                }
            }
        }
    }
}

private val ASK_WIDTH = 440.dp
