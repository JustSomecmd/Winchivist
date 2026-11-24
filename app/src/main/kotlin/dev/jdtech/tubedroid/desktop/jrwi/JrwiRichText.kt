package dev.jdtech.tubedroid.desktop.jrwi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jdtech.tubedroid.desktop.LocalApp
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * A post's or a comment's text, laid out as the web app's: paragraphs apart, headings in capitals
 * and sized, lists with their bullets or numbers, and links in the accent, which open only as web
 * and mail addresses. From [html] when there is any, else from [text]. The text can be selected.
 */
@Composable
fun JrwiHtmlText(
    html: String?,
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    gap: Dp = JRWI_PARAGRAPH_GAP,
    color: Color = MaterialTheme.colorScheme.onBackground,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val base = LocalApp.current.jrwi.api.baseUrl
    val paragraphs =
        remember(html, text, linkColor, base) {
            if (html != null) {
                jrwiHtmlParagraphs(
                    html,
                    TextLinkStyles(style = SpanStyle(color = linkColor)),
                    base,
                )
            } else {
                text
                    .split(BLANK_LINE)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map { JrwiParagraph(AnnotatedString(it)) }
            }
        }
    SelectionContainer(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            paragraphs.forEach { paragraph ->
                val scale = paragraph.heading
                Text(
                    text = paragraph.text,
                    color = color,
                    style =
                        if (scale == null) style
                        else
                            style.copy(
                                fontSize = style.fontSize * scale,
                                lineHeight = style.fontSize * (scale * HEADING_LINE_HEIGHT),
                            ),
                )
            }
        }
    }
}

/** One paragraph of a post, or a heading with its size against the text's. */
internal data class JrwiParagraph(val text: AnnotatedString, val heading: Float? = null)

/**
 * Splits the HTML into its paragraphs, with bold, italics, underlines and links kept. Pictures in
 * the text are left out, as the post's pictures show under it. Addresses that start with a slash
 * are the archiver's own, under [base].
 */
internal fun jrwiHtmlParagraphs(
    html: String,
    links: TextLinkStyles,
    base: String?,
): List<JrwiParagraph> {
    val reader = HtmlReader(links, base)
    reader.read(Jsoup.parseBodyFragment(html).body())
    reader.end()
    return reader.paragraphs
}

/** Goes through the parsed HTML, gathering text into paragraphs as blocks open and close. */
private class HtmlReader(private val links: TextLinkStyles, private val base: String?) {
    val paragraphs = mutableListOf<JrwiParagraph>()
    private var builder = AnnotatedString.Builder()
    private var heading: Float? = null
    private var capitals = false

    // For each list open around this point: its next number, or 0 for bullets
    private val lists = ArrayDeque<Int>()

    /** Ends the paragraph being gathered, if it has any text. */
    fun end() {
        val text = builder.toAnnotatedString()
        var from = 0
        var to = text.length
        while (from < to && text[from].isWhitespace()) from++
        while (to > from && text[to - 1].isWhitespace()) to--
        if (from < to) paragraphs += JrwiParagraph(text.subSequence(from, to), heading)
        builder = AnnotatedString.Builder()
        heading = null
    }

    fun read(node: Node) {
        when (node) {
            is TextNode -> {
                val text = node.text()
                builder.append(if (capitals) text.uppercase() else text)
            }
            is Element -> element(node)
        }
    }

    private fun children(element: Element) {
        element.childNodes().forEach(::read)
    }

    private fun styled(style: SpanStyle, element: Element) {
        builder.pushStyle(style)
        children(element)
        builder.pop()
    }

    private fun element(element: Element) {
        when (val tag = element.normalName()) {
            "br" -> builder.append('\n')
            "img",
            "script",
            "style",
            "iframe" -> {}
            "h1",
            "h2",
            "h3",
            "h4",
            "h5",
            "h6" -> {
                end()
                val level = tag[1] - '1'
                heading = HEADING_SIZES[level]
                // The web app shows h1 to h4 in capitals
                capitals = level < UPPERCASE_LEVELS
                styled(SpanStyle(fontWeight = FontWeight.Bold), element)
                capitals = false
                end()
            }
            "ul",
            "ol" -> {
                if (lists.isEmpty()) end()
                lists.addLast(if (tag == "ol") 1 else 0)
                children(element)
                lists.removeLast()
                if (lists.isEmpty()) end()
            }
            "li" -> {
                if (builder.length > 0) builder.append('\n')
                builder.append("    ".repeat((lists.size - 1).coerceAtLeast(0)))
                val number = lists.lastOrNull() ?: 0
                if (number > 0) {
                    builder.append("$number. ")
                    lists[lists.lastIndex] = number + 1
                } else {
                    builder.append("•  ")
                }
                children(element)
            }
            "a" -> {
                val address = address(element.attr("href"))
                if (address == null) {
                    children(element)
                } else {
                    builder.pushLink(LinkAnnotation.Url(address, links) { openUrl(address) })
                    children(element)
                    builder.pop()
                }
            }
            "b",
            "strong" -> styled(SpanStyle(fontWeight = FontWeight.Bold), element)
            "i",
            "em" -> styled(SpanStyle(fontStyle = FontStyle.Italic), element)
            "u" -> styled(SpanStyle(textDecoration = TextDecoration.Underline), element)
            "s",
            "strike",
            "del" -> styled(SpanStyle(textDecoration = TextDecoration.LineThrough), element)
            "code",
            "tt" -> styled(SpanStyle(fontFamily = FontFamily.Monospace), element)
            else ->
                if (tag in BLOCKS && lists.isEmpty()) {
                    end()
                    children(element)
                    end()
                } else {
                    children(element)
                }
        }
    }

    /** A link's address in full, when it's a web or mail address; null for any other kind. */
    private fun address(href: String): String? {
        val trimmed = href.trim()
        return when {
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) ||
                trimmed.startsWith("mailto:", ignoreCase = true) -> trimmed
            trimmed.startsWith("/") && !trimmed.startsWith("//") && base != null -> base + trimmed
            else -> null
        }
    }

    private companion object {
        val BLOCKS =
            setOf(
                "p",
                "div",
                "blockquote",
                "pre",
                "section",
                "article",
                "figure",
                "figcaption",
                "table",
                "tr",
                "hr",
            )

        // The web app's: 2, 1.5, 1.1 and 1 rem for h1 to h4, against text of 1 rem
        val HEADING_SIZES = listOf(2f, 1.5f, 1.1f, 1f, 1f, 1f)
        const val UPPERCASE_LEVELS = 4
    }
}

/** The space between paragraphs, as the web app's 0.8 rem under each. */
internal val JRWI_PARAGRAPH_GAP = 12.dp

/** Between a comment's paragraphs, as the web app's 0.4 rem. */
internal val JRWI_COMMENT_PARAGRAPH_GAP = 6.dp

// Two line breaks or more, with nothing but spaces between them: where a paragraph ends
private val BLANK_LINE = Regex("""\n[ \t ]*\n\s*""")

// The web app's line height for headings
private const val HEADING_LINE_HEIGHT = 1.15f
