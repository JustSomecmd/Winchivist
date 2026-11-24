package dev.jdtech.tubedroid.desktop.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

private enum class BlockKind {
    /** One column wide, as tall as it needs. */
    COLUMN,

    /** As wide as it makes itself, over the columns that covers. */
    OWN_WIDTH,

    /** Across every column. */
    FULL_LINE,
}

/** How a block sits among the others: its kind, and roughly how tall it is. */
private data class BlockData(val kind: BlockKind, val about: Dp) : ParentDataModifier {
    override fun Density.modifyParentData(parentData: Any?): Any = this@BlockData
}

/** What a [BlockFlow] holds. */
object BlockFlowScope {
    /**
     * A block one column wide. [about] is roughly how tall it gets with a usual amount in it: the
     * blocks are shared out over the columns by these heights, not by what's in them at the moment,
     * so a block stays in its column from one start of the app to the next, and while a list in it
     * grows.
     */
    @Composable
    fun Block(id: Any, about: Dp, content: @Composable () -> Unit) {
        key(id) {
            Box(
                modifier = BlockData(BlockKind.COLUMN, about),
                propagateMinConstraints = true,
            ) {
                content()
            }
        }
    }

    /** A block of its own width, over as many columns as it covers, with others beside it. */
    @Composable
    fun OwnWidthBlock(id: Any, about: Dp, content: @Composable () -> Unit) {
        key(id) { Box(modifier = BlockData(BlockKind.OWN_WIDTH, about)) { content() } }
    }

    /** A block across the whole width, with everything after it under it. */
    @Composable
    fun FullLine(id: Any, content: @Composable () -> Unit) {
        key(id) {
            Box(
                modifier = BlockData(BlockKind.FULL_LINE, 0.dp),
                propagateMinConstraints = true,
            ) {
                content()
            }
        }
    }
}

/**
 * Blocks in as many columns as fit, each as wide as a grid of [minColumnWidth] cells would make it
 * and as tall as it needs, every block under the column that's shortest so far. Unlike a grid's
 * rows, a short block leaves no hole beside a tall one.
 *
 * Which column a block goes in comes from the blocks' rough heights alone (see
 * [BlockFlowScope.Block]), so the same blocks in a window of the same width always sit the same
 * way.
 */
@Composable
fun BlockFlow(
    minColumnWidth: Dp,
    spacing: Dp,
    modifier: Modifier = Modifier,
    content: @Composable BlockFlowScope.() -> Unit,
) {
    Layout(content = { BlockFlowScope.content() }, modifier = modifier) { measurables, constraints
        ->
        val gap = spacing.roundToPx()
        val minWidth = minColumnWidth.roundToPx()
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else minWidth
        // As a grid's adaptive cells: as many as fit, sharing the width
        val count = max(1, (width + gap) / (minWidth + gap))
        val room = width - gap * (count - 1)
        val widths = IntArray(count) { room / count + if (it < room % count) 1 else 0 }
        val lefts = IntArray(count)
        for (column in 1 until count) {
            lefts[column] = lefts[column - 1] + widths[column - 1] + gap
        }
        // Where each column ends so far, and where it would by the blocks' rough heights
        val bottoms = IntArray(count)
        val rough = IntArray(count)
        val blocks = ArrayList<Placeable>(measurables.size)
        val xs = ArrayList<Int>(measurables.size)
        val ys = ArrayList<Int>(measurables.size)

        for (measurable in measurables) {
            val data = measurable.parentData as? BlockData ?: BlockData(BlockKind.COLUMN, 0.dp)
            val about = data.about.roundToPx() + gap
            when (data.kind) {
                BlockKind.FULL_LINE -> {
                    val block = measurable.measure(Constraints.fixedWidth(width))
                    val y = bottoms.max()
                    val top = rough.max()
                    // A block with nothing in it takes no room, and no gap
                    if (block.height > 0) bottoms.fill(y + block.height + gap)
                    rough.fill(top + about)
                    blocks += block
                    xs += 0
                    ys += y
                }
                BlockKind.OWN_WIDTH -> {
                    val block = measurable.measure(Constraints(maxWidth = width))
                    var span = 1
                    while (span < count && lefts[span - 1] + widths[span - 1] < block.width) span++
                    // The columns that are free the soonest, the first of them when it's a tie
                    var start = 0
                    var top = Int.MAX_VALUE
                    for (first in 0..count - span) {
                        var end = 0
                        for (column in first until first + span) end = max(end, rough[column])
                        if (end < top) {
                            top = end
                            start = first
                        }
                    }
                    var y = 0
                    for (column in start until start + span) y = max(y, bottoms[column])
                    for (column in start until start + span) {
                        if (block.height > 0) bottoms[column] = y + block.height + gap
                        rough[column] = top + about
                    }
                    blocks += block
                    xs += lefts[start]
                    ys += y
                }
                BlockKind.COLUMN -> {
                    var column = 0
                    for (other in 1 until count) {
                        if (rough[other] < rough[column]) column = other
                    }
                    val block = measurable.measure(Constraints.fixedWidth(widths[column]))
                    blocks += block
                    xs += lefts[column]
                    ys += bottoms[column]
                    if (block.height > 0) bottoms[column] += block.height + gap
                    rough[column] += about
                }
            }
        }

        val height = max(0, bottoms.max() - gap)
        layout(width, max(height, constraints.minHeight)) {
            for (index in blocks.indices) blocks[index].place(xs[index], ys[index])
        }
    }
}

/** A page of blocks in a [BlockFlow] that scrolls, with its scroll bar. */
@Composable
fun BlockPage(
    scroll: ScrollState,
    minColumnWidth: Dp,
    modifier: Modifier = Modifier,
    content: @Composable BlockFlowScope.() -> Unit,
) {
    Box(modifier = modifier) {
        BlockFlow(
            minColumnWidth = minColumnWidth,
            spacing = Spacings.medium,
            modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(Spacings.default),
            content = content,
        )
        ColumnScrollbar(scroll)
    }
}
