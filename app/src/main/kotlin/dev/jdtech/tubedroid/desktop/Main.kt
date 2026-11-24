package dev.jdtech.tubedroid.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.jdtech.tubedroid.desktop.screens.SettingsScreen
import dev.jdtech.tubedroid.desktop.ui.TubedroidTheme
import dev.jdtech.tubedroid.desktop.ui.installImageLoader
import org.jetbrains.skia.Image

/** The window, so the player can make it fill the screen. */
val LocalWindowState = staticCompositionLocalOf<WindowState> { error("No window") }

/** The app's window itself, for the player's controls to float over. None in the page renders. */
val LocalAppWindow = staticCompositionLocalOf<java.awt.Window?> { null }

fun main() {
    val graph = AppGraph(AppSettings())
    installImageLoader(graph.api, graph.jrwi.api)

    application {
        val windowState = rememberWindowState(width = 1360.dp, height = 860.dp)
        val shell = remember { Shell() }
        val icon = remember { windowIcon() }
        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "Winchivist",
            icon = icon,
            onPreviewKeyEvent = { event ->
                // Alt+Left goes back, as in a browser
                if (
                    event.type == KeyEventType.KeyDown &&
                        event.isAltPressed &&
                        event.key == Key.DirectionLeft
                ) {
                    shell.back()
                    true
                } else {
                    false
                }
            },
            // Escape goes back too, once whatever has the focus, such as a menu, has let it pass
            onKeyEvent = { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    shell.back()
                    true
                } else {
                    false
                }
            },
        ) {
            window.minimumSize = java.awt.Dimension(960, 600)
            CompositionLocalProvider(
                LocalApp provides graph,
                LocalWindowState provides windowState,
                LocalAppWindow provides window,
            ) {
                TubedroidTheme(accent = graph.settings.accent) {
                    // The first start asks for TubeArchivist; the other servers are in Settings
                    var skipped by remember { mutableStateOf(false) }
                    val anySetUp =
                        graph.settings.isConfigured ||
                            graph.settings.isJrwiConfigured ||
                            graph.jellyfin.isSignedIn
                    if (anySetUp || skipped) {
                        ShellContent(shell)
                    } else {
                        SettingsScreen(firstRun = true, onDone = {}, onSkip = { skipped = true })
                    }
                }
            }
        }
    }
}

/** The logo in the window's title bar and on the taskbar, as in the installer. */
private fun windowIcon(): Painter? {
    val bytes =
        Thread.currentThread().contextClassLoader?.getResourceAsStream("winchivist.png")?.use {
            it.readBytes()
        } ?: return null
    return BitmapPainter(Image.makeFromEncoded(bytes).toComposeImageBitmap())
}
