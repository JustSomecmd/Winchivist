package dev.jdtech.tubedroid.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import dev.jdtech.tubedroid.desktop.player.awaitPlaybackSaves
import dev.jdtech.tubedroid.desktop.screens.SettingsScreen
import dev.jdtech.tubedroid.desktop.ui.TubedroidTheme
import dev.jdtech.tubedroid.desktop.ui.installImageLoader
import dev.jdtech.tubedroid.desktop.ui.releaseImageMemory
import javax.swing.SwingUtilities
import org.jetbrains.skia.Image

/** The window, so the player can make it fill the screen. */
val LocalWindowState = staticCompositionLocalOf<WindowState> { error("No window") }

/** The app's window itself, for the player's controls to float over. None in the page renders. */
val LocalAppWindow = staticCompositionLocalOf<java.awt.Window?> { null }

/** Whether the window is open, and how often it was asked to come to the front. */
private class WindowSwitch(open: Boolean) {
    var open by mutableStateOf(open)
        private set

    var shown by mutableStateOf(0)
        private set

    fun show() {
        open = true
        shown++
    }

    fun hide() {
        open = false
    }
}

fun main(args: Array<String>) {
    val settings = AppSettings()
    val icon = windowIcon()
    val canUseTray = icon != null && isTraySupported
    // Started by Windows it waits in the tray, if staying there is still switched on
    val switch = WindowSwitch(open = !(START_IN_TRAY in args && settings.keepInTray && canUseTray))
    // Started a second time, the Winchivist that's running shows its window and this one leaves
    val only = SingleInstance(onShow = { SwingUtilities.invokeLater { switch.show() } })
    if (!only.claim()) return
    // The entry Windows starts it from follows the program, should it have been installed elsewhere
    if (settings.startWithWindows && StartWithWindows.available) {
        Thread({ StartWithWindows.set(true) }, "start-with-windows")
            .apply { isDaemon = true }
            .start()
    }

    val graph = AppGraph(settings)
    installImageLoader(graph.api, graph.jrwi.api, settings.imageCacheMb)
    val checks = BackgroundChecks(graph)

    application {
        val windowState = rememberWindowState(width = 1360.dp, height = 860.dp)
        val shell = remember { Shell(graph.audio) }
        val trayState = rememberTrayState()
        val quit = {
            // Where the sound got to is saved before the app goes
            graph.audio.close()
            awaitPlaybackSaves()
            exitApplication()
        }

        // The checks run for the servers that are set up, and tell what they find through the tray
        val notifies =
            (settings.notifyNewItems && graph.jellyfin.isSignedIn) ||
                (settings.notifyBots && settings.isConfigured)
        val tray = canUseTray && (settings.keepInTray || notifies)
        if (tray && icon != null) {
            Tray(
                icon = icon,
                state = trayState,
                tooltip = "Winchivist",
                onAction = switch::show,
                menu = {
                    Item("Open Winchivist", onClick = switch::show)
                    Separator()
                    Item("Quit", onClick = quit)
                },
            )
        }
        if (tray && notifies) {
            LaunchedEffect(Unit) {
                checks.run { title, text -> trayState.sendNotification(Notification(title, text)) }
            }
        }

        if (switch.open) {
            Window(
                onCloseRequest = {
                    if (tray && settings.keepInTray) {
                        // Sound keeps playing from the tray; a video has nowhere to show
                        shell.closePlayer()
                        switch.hide()
                        releaseImageMemory()
                    } else {
                        quit()
                    }
                },
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
                // Escape goes back too, once whatever has the focus, such as a menu, has let it
                // pass
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
                LaunchedEffect(switch.shown) {
                    if (switch.shown > 0) {
                        windowState.isMinimized = false
                        // Windows only flashes the taskbar button of a window that asks to come
                        // forward, but lets one that's on top for a moment through
                        window.isAlwaysOnTop = true
                        window.toFront()
                        window.isAlwaysOnTop = false
                        window.requestFocus()
                    }
                }
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
                            SettingsScreen(
                                firstRun = true,
                                onDone = {},
                                onSkip = { skipped = true },
                            )
                        }
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
