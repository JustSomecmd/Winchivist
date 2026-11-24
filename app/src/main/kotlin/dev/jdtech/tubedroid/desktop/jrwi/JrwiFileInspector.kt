package dev.jdtech.tubedroid.desktop.jrwi

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jdtech.jellyfin.jrwi.JrwiBlock
import dev.jdtech.jellyfin.jrwi.JrwiPostFile
import dev.jdtech.jellyfin.jrwi.JrwiPreview
import dev.jdtech.jellyfin.jrwi.JrwiSheet
import dev.jdtech.tubedroid.desktop.LocalApp
import dev.jdtech.tubedroid.desktop.LocalNavigator
import dev.jdtech.tubedroid.desktop.Navigator
import dev.jdtech.tubedroid.desktop.ui.ListScrollbar
import dev.jdtech.tubedroid.desktop.ui.RoundIconButton
import dev.jdtech.tubedroid.desktop.ui.Spacings
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

/** Opens the file inspector over the window, on [files]' file at [start]. */
fun Navigator.inspectFiles(files: List<JrwiPostFile>, start: Int) {
    if (files.isEmpty()) return
    overlay { JrwiFileInspector(files = files, start = start, onClose = ::closeOverlay) }
}

/**
 * A file of a post over the whole window, showing what's inside it as the archiver's file inspector
 * does: spreadsheets a sheet at a time, Word and PowerPoint files as their text, text files, and
 * what's in a zip. The arrows and the arrow keys go from file to file. Nothing is saved on this
 * computer unless the save button is clicked.
 */
@Composable
fun JrwiFileInspector(files: List<JrwiPostFile>, start: Int, onClose: () -> Unit) {
    val app = LocalApp.current
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    if (files.isEmpty()) return
    var index by remember { mutableIntStateOf(start.coerceIn(0, files.lastIndex)) }
    val file = files[index]
    var preview by remember(file) { mutableStateOf<JrwiPreview?>(null) }
    var error by remember(file) { mutableStateOf<String?>(null) }
    LaunchedEffect(file) {
        val address = file.preview ?: return@LaunchedEffect
        try {
            preview = app.jrwi.api.getPreview(address)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    JrwiTheme {
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .focusRequester(focus)
                    .focusable()
                    .onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when (event.key) {
                            Key.DirectionRight ->
                                if (index < files.lastIndex) index += 1 else return@onKeyEvent false
                            Key.DirectionLeft ->
                                if (index > 0) index -= 1 else return@onKeyEvent false
                            else -> return@onKeyEvent false
                        }
                        true
                    }
                    // Nothing under the inspector is clicked through it
                    .pointerInput(Unit) { detectTapGestures {} }
        ) {
            JrwiBar(
                title = null,
                onBack = onClose,
                showSearch = false,
                start = {
                    Column(modifier = Modifier.weight(1f, fill = false)) {
                        Text(
                            text = file.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = JrwiColors.Text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = jrwiFileDetail(file),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            ) {
                if (files.size > 1) {
                    Text(
                        text = "${index + 1} / ${files.size}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    RoundIconButton(
                        icon = "ic_chevron_left",
                        label = "Previous file",
                        onClick = { index -= 1 },
                        enabled = index > 0,
                    )
                    RoundIconButton(
                        icon = "ic_chevron_right",
                        label = "Next file",
                        onClick = { index += 1 },
                        enabled = index < files.lastIndex,
                    )
                }
                file.source?.let { source ->
                    RoundIconButton(
                        icon = "ic_globe",
                        label = "Original link",
                        onClick = { openUrl(source) },
                    )
                }
                file.url?.let { address ->
                    RoundIconButton(
                        icon = "ic_external_link",
                        label = "Open in another app",
                        onClick = {
                            scope.launch {
                                try {
                                    navigator.message("Getting ${file.name} from the archive…")
                                    openJrwiFileElsewhere(app, file.name, address)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    navigator.message("Couldn't open ${file.name}: ${e.message}")
                                }
                            }
                        },
                    )
                    RoundIconButton(
                        icon = "ic_download",
                        label = "Save to this PC",
                        onClick = { saveJrwiFileAndSay(app, navigator, file.name, address) },
                    )
                }
            }
            Box(modifier = Modifier.fillMaxSize()) {
                val shown = preview
                val failed = error
                when {
                    file.preview == null ->
                        InspectorNote(
                            "There's no preview for this type of file. Save it or open it in " +
                                "another app."
                        )
                    failed != null ->
                        InspectorNote(
                            "The preview couldn't be loaded ($failed). Save the file or open it " +
                                "in another app."
                        )
                    shown == null ->
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    else -> InspectorContent(shown)
                }
            }
        }
    }
}

@Composable
private fun InspectorNote(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(Spacings.large)) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

/** What's inside the file, by its type. */
@Composable
private fun InspectorContent(preview: JrwiPreview) {
    Column(modifier = Modifier.fillMaxSize()) {
        preview.note
            ?.takeIf { it.isNotBlank() }
            ?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Spacings.medium),
                )
            }
        when (preview.type) {
            "table" -> InspectorTable(preview.sheets)
            "document" -> InspectorDocument(preview.blocks)
            "text" -> InspectorText(preview.text.orEmpty(), preview.truncated)
            "archive" -> InspectorArchive(preview)
            else ->
                if (preview.note.isNullOrBlank()) {
                    InspectorNote(
                        "There's no preview for this type of file. Save it or open it in " +
                            "another app."
                    )
                }
        }
    }
}

/** A spreadsheet, one sheet at a time, picked from a row of the sheets' names. */
@Composable
private fun InspectorTable(sheets: List<JrwiSheet>) {
    if (sheets.isEmpty()) {
        InspectorNote("The spreadsheet is empty.")
        return
    }
    var selected by remember(sheets) { mutableIntStateOf(0) }
    val sheet = sheets[selected.coerceIn(0, sheets.lastIndex)]
    Column(modifier = Modifier.fillMaxSize()) {
        if (sheets.size > 1) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacings.small),
                modifier =
                    Modifier.fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = Spacings.medium, vertical = Spacings.small),
            ) {
                sheets.forEachIndexed { index, each ->
                    val chosen = index == selected
                    Text(
                        text = each.name.ifBlank { "Sheet ${index + 1}" },
                        style = MaterialTheme.typography.labelLarge,
                        color =
                            if (chosen) MaterialTheme.colorScheme.onPrimary
                            else JrwiColors.PillText,
                        modifier =
                            Modifier.clip(JrwiPillShape)
                                .background(
                                    if (chosen) MaterialTheme.colorScheme.primary
                                    else JrwiColors.Pill
                                )
                                .clickable { selected = index }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
        if (sheet.rows.isEmpty()) {
            InspectorNote("This sheet is empty.")
            return@Column
        }
        if (sheet.truncated) {
            Text(
                text = "Only the first rows and columns are shown. Save the file for all of it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacings.medium),
            )
        }
        val columns = remember(sheet) { sheet.rows.maxOf { it.size } }
        val state = rememberLazyListState()
        val sideways = rememberScrollState()
        Box(modifier = Modifier.fillMaxSize().padding(Spacings.medium)) {
            // The rows scroll sideways together, each with its number in front
            LazyColumn(
                state = state,
                modifier =
                    Modifier.fillMaxSize()
                        .horizontalScroll(sideways)
                        .width(ROW_NUMBER_WIDTH + CELL_WIDTH * columns),
            ) {
                itemsIndexed(sheet.rows) { number, row ->
                    Row {
                        TableCell(
                            text = (number + 1).toString(),
                            width = ROW_NUMBER_WIDTH,
                            header = true,
                        )
                        for (column in 0 until columns) {
                            TableCell(
                                text = row.getOrNull(column).orEmpty(),
                                width = CELL_WIDTH,
                                header = number == 0,
                            )
                        }
                    }
                }
            }
            ListScrollbar(state)
        }
    }
}

private val CELL_WIDTH = 180.dp
private val ROW_NUMBER_WIDTH = 52.dp

@Composable
private fun TableCell(text: String, width: androidx.compose.ui.unit.Dp, header: Boolean) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
        color = if (header) JrwiColors.Text else JrwiColors.BodyText,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier =
            Modifier.width(width)
                .border(0.5.dp, JrwiColors.LineSoft)
                .background(if (header) JrwiColors.Card2 else JrwiColors.Card)
                .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

/** A Word or PowerPoint file as its text: headings, paragraphs, list items and table rows. */
@Composable
private fun InspectorDocument(blocks: List<JrwiBlock>) {
    if (blocks.isEmpty()) {
        InspectorNote("The document has no text.")
        return
    }
    val state = rememberLazyListState()
    Box(modifier = Modifier.fillMaxSize()) {
        SelectionContainer {
            LazyColumn(
                state = state,
                contentPadding = PaddingValues(Spacings.large),
                verticalArrangement = Arrangement.spacedBy(Spacings.small),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxSize(),
            ) {
                items(blocks) { block ->
                    Box(modifier = Modifier.widthIn(max = READING_WIDTH).fillMaxWidth()) {
                        when (block.kind) {
                            "heading" ->
                                Text(
                                    text = block.text.orEmpty(),
                                    style =
                                        when (block.level ?: 1) {
                                            1 -> MaterialTheme.typography.headlineSmall
                                            2 -> MaterialTheme.typography.titleLarge
                                            else -> MaterialTheme.typography.titleMedium
                                        },
                                    fontWeight = FontWeight.Bold,
                                    color = JrwiColors.Text,
                                    modifier = Modifier.padding(top = Spacings.small),
                                )
                            "item" ->
                                Text(
                                    text = "•  " + block.text.orEmpty(),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = JrwiColors.BodyText,
                                    modifier = Modifier.padding(start = Spacings.medium),
                                )
                            "row" ->
                                Row {
                                    block.cells.forEach { cell ->
                                        Text(
                                            text = cell,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = JrwiColors.BodyText,
                                            modifier =
                                                Modifier.weight(1f)
                                                    .border(0.5.dp, JrwiColors.LineSoft)
                                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                        )
                                    }
                                }
                            else ->
                                Text(
                                    text = block.text.orEmpty(),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = JrwiColors.BodyText,
                                )
                        }
                    }
                }
            }
        }
        ListScrollbar(state)
    }
}

private val READING_WIDTH = 820.dp

/** A text file as it is, in a fixed-width font, never as a page. */
@Composable
private fun InspectorText(text: String, truncated: Boolean) {
    // In pieces of a few lines each, so a long file is laid out as it's scrolled to
    val pieces = remember(text) { text.lines().chunked(TEXT_LINES).map { it.joinToString("\n") } }
    val state = rememberLazyListState()
    Box(modifier = Modifier.fillMaxSize()) {
        SelectionContainer {
            LazyColumn(
                state = state,
                contentPadding = PaddingValues(Spacings.large),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(pieces) { piece ->
                    Text(
                        text = piece,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 20.sp,
                        color = JrwiColors.BodyText,
                    )
                }
                if (truncated) {
                    item {
                        Text(
                            text = "Only the start is shown. Save the file for all of it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = Spacings.medium),
                        )
                    }
                }
            }
        }
        ListScrollbar(state)
    }
}

private const val TEXT_LINES = 40

/** What's in a zip, with sizes. */
@Composable
private fun InspectorArchive(preview: JrwiPreview) {
    val state = rememberLazyListState()
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = state,
            contentPadding = PaddingValues(Spacings.large),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize(),
        ) {
            items(preview.entries) { entry ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier.widthIn(max = READING_WIDTH)
                            .fillMaxWidth()
                            .height(36.dp)
                            .padding(horizontal = Spacings.small),
                ) {
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = if (entry.folder) JrwiColors.Text else JrwiColors.BodyText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = if (entry.folder) "folder" else entry.size?.let(::formatBytes) ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (preview.truncated) {
                item {
                    Text(
                        text = "Only the start is shown. Save the file for all of it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacings.medium),
                    )
                }
            }
        }
        ListScrollbar(state)
    }
}
