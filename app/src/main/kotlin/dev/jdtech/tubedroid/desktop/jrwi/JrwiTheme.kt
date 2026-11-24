package dev.jdtech.tubedroid.desktop.jrwi

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp

/**
 * The JRWI archiver's own colours, from its web app's stylesheet, so the JRWI pages look like the
 * archive they show: a deep purple header over dark navy, with jrwishow.com's yellow. The same
 * values as the phone app's.
 */
object JrwiColors {
    val Header = Color(0xFF150824)
    val Background = Color(0xFF111320)
    val Card = Color(0xFF131522)
    val Card2 = Color(0xFF1B1D2B)
    val Line = Color(0xFF3B3D46)
    val LineSoft = Color(0xFF2A2C37)
    val Input = Color(0xFF282A36)
    val Pill = Color(0xFF272934)
    val PillText = Color(0xFFAEAFB3)
    val Text = Color(0xFFFFFFFF)
    val BodyText = Color(0xFFE9E9EE)
    val Muted = Color(0xFF8E9096)

    // A post's tags, as the web app's .chip
    val Chip = Color(0xFF262835)
    val ChipText = Color(0xFFC8C9D0)
    val Accent = Color(0xFFF4E434)
    val AccentInk = Color(0xFF1B0833)
    val Ok = Color(0xFF4CD18F)
    val Bad = Color(0xFFFF5C6C)

    /** Behind pictures, before and around them. */
    val Picture = Color(0xFF0B0C14)

    /** The purple glow at the top of the page. */
    val Glow = Color(0x2E50288C)

    /** Behind the JRWI logo where a show has no picture of its own: the glow, brighter. */
    val LogoGlow = Color(0x737846C8)
}

private val JrwiColorScheme =
    darkColorScheme(
        primary = JrwiColors.Accent,
        onPrimary = JrwiColors.AccentInk,
        primaryContainer = JrwiColors.Accent,
        onPrimaryContainer = JrwiColors.AccentInk,
        inversePrimary = JrwiColors.Accent,
        secondary = JrwiColors.Accent,
        onSecondary = JrwiColors.AccentInk,
        secondaryContainer = JrwiColors.Pill,
        onSecondaryContainer = JrwiColors.Text,
        tertiary = JrwiColors.Ok,
        onTertiary = JrwiColors.AccentInk,
        background = JrwiColors.Background,
        onBackground = JrwiColors.BodyText,
        surface = JrwiColors.Background,
        onSurface = JrwiColors.Text,
        surfaceVariant = JrwiColors.Card2,
        onSurfaceVariant = JrwiColors.Muted,
        // Flat colours, as on the site, with no tint from the accent
        surfaceTint = Color.Transparent,
        inverseSurface = JrwiColors.Text,
        inverseOnSurface = JrwiColors.Background,
        error = JrwiColors.Bad,
        onError = Color.White,
        errorContainer = JrwiColors.Bad,
        onErrorContainer = Color.White,
        outline = JrwiColors.Line,
        outlineVariant = JrwiColors.LineSoft,
        scrim = Color.Black,
        surfaceBright = JrwiColors.Card2,
        surfaceDim = JrwiColors.Background,
        surfaceContainerLowest = JrwiColors.Background,
        surfaceContainerLow = JrwiColors.Card,
        // Menus, which the site draws in the header's purple
        surfaceContainer = JrwiColors.Header,
        surfaceContainerHigh = JrwiColors.Card2,
        surfaceContainerHighest = JrwiColors.Input,
    )

/**
 * The purples of the archiver's own side (its status, controls and numbers), which take the place
 * of the yellow there. The sidebar's JRWI group is in them too.
 */
object JrwiPurple {
    val Accent = Color(0xFFB794F6)
    val Ink = Color(0xFF1B0833)
    val Sheet = Color(0xFF1E1433)
    val Card = Color(0xFF2A1D45)
    val Input = Color(0xFF3A2A5C)
    val Line = Color(0xFF4A3A6B)
    val Muted = Color(0xFFA99BC4)
}

private val JrwiSheetColorScheme =
    JrwiColorScheme.copy(
        primary = JrwiPurple.Accent,
        onPrimary = JrwiPurple.Ink,
        primaryContainer = JrwiPurple.Accent,
        onPrimaryContainer = JrwiPurple.Ink,
        inversePrimary = JrwiPurple.Accent,
        secondary = JrwiPurple.Accent,
        onSecondary = JrwiPurple.Ink,
        secondaryContainer = JrwiPurple.Card,
        background = JrwiPurple.Sheet,
        surface = JrwiPurple.Sheet,
        surfaceVariant = JrwiPurple.Card,
        onSurfaceVariant = JrwiPurple.Muted,
        outline = JrwiPurple.Line,
        outlineVariant = JrwiPurple.Card,
        surfaceBright = JrwiPurple.Card,
        surfaceDim = JrwiPurple.Sheet,
        surfaceContainerLowest = JrwiPurple.Sheet,
        surfaceContainerLow = JrwiPurple.Sheet,
        surfaceContainer = JrwiPurple.Card,
        surfaceContainerHigh = JrwiPurple.Card,
        surfaceContainerHighest = JrwiPurple.Input,
    )

/** The site's corners: 4 dp on cards, buttons and fields. */
val JrwiShape = RoundedCornerShape(4.dp)

/**
 * What's a pill or a round button in the app's own look, such as badges and the header's buttons:
 * small corners in proportion to its size, so the JRWI pages stay square.
 */
val JrwiPillShape = RoundedCornerShape(percent = 18)

/** Labels next to names, as the web app's badges. */
val JrwiLabelShape = RoundedCornerShape(6.dp)

private val JrwiShapes =
    Shapes(
        extraSmall = JrwiShape,
        small = JrwiShape,
        medium = JrwiShape,
        large = JrwiShape,
        extraLarge = RoundedCornerShape(8.dp),
    )

/** The JRWI pages in the archiver's colours and square corners, whatever the app's own theme. */
@Composable
fun JrwiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = JrwiColorScheme,
        typography = MaterialTheme.typography,
        shapes = JrwiShapes,
        content = content,
    )
}

/** The archiver's own side: the JRWI pages' look, in purple where they're yellow. */
@Composable
fun JrwiSheetTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = JrwiSheetColorScheme,
        typography = MaterialTheme.typography,
        shapes = JrwiShapes,
        content = content,
    )
}

/** A page on the theme's background, with the site's purple glow at the top. */
@Composable
fun JrwiPage(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier =
            modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).drawBehind {
                drawGlow()
            }
    ) {
        content()
    }
}

/**
 * The site's glow: an ellipse twice as wide as it's tall, centred above the top of the page, from
 * purple to nothing at 70% of the way out, kept to the page.
 */
private fun DrawScope.drawGlow() {
    val radius = 1200.dp.toPx()
    val center = Offset(size.width / 2, -200.dp.toPx())
    clipRect {
        scale(scaleX = 1f, scaleY = 0.5f, pivot = center) {
            drawCircle(
                brush =
                    Brush.radialGradient(
                        0f to JrwiColors.Glow,
                        0.7f to Color.Transparent,
                        center = center,
                        radius = radius,
                    ),
                radius = radius,
                center = center,
            )
        }
    }
}

/** The site's fields: filled in the input grey, outlined in the accent while typing. */
@Composable
fun jrwiFieldColors() =
    OutlinedTextFieldDefaults.colors(
        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = Color.Transparent,
        focusedLabelColor = MaterialTheme.colorScheme.primary,
        unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        cursorColor = MaterialTheme.colorScheme.primary,
        focusedTextColor = JrwiColors.Text,
        unfocusedTextColor = JrwiColors.Text,
    )
