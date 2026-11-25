package dev.jdtech.tubedroid.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import java.awt.Window
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener

/**
 * Runs [then] when [window] is come back to after a while somewhere else: another window had the
 * focus, or this one was minimized or in the tray. A moment in another window and back doesn't
 * count, so nothing is asked of a server for every switch.
 */
@Composable
fun OnComingBack(window: Window?, then: () -> Unit) {
    val latest by rememberUpdatedState(then)
    DisposableEffect(window) {
        if (window == null) return@DisposableEffect onDispose {}
        var leftAt: Long? = null
        val listener =
            object : WindowFocusListener {
                override fun windowLostFocus(event: WindowEvent) {
                    leftAt = System.currentTimeMillis()
                }

                override fun windowGainedFocus(event: WindowEvent) {
                    val since = leftAt ?: return
                    leftAt = null
                    if (System.currentTimeMillis() - since >= AWAY_MS) latest()
                }
            }
        window.addWindowFocusListener(listener)
        onDispose { window.removeWindowFocusListener(listener) }
    }
}

// How long the window has to be left for coming back to it to count
private const val AWAY_MS = 10_000L
