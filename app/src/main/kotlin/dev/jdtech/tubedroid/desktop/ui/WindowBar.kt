package dev.jdtech.tubedroid.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.platform.win32.Shell32
import com.sun.jna.platform.win32.ShellAPI
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.IntByReference
import java.awt.Dimension
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import kotlin.math.abs
import kotlin.math.roundToInt

/** Whether the window has Winchivist's own bar, and so no frame of Windows' own. */
val LocalOwnWindowBar = staticCompositionLocalOf { false }

/** How tall the window's own bar is: as tall as Windows' own. */
val WINDOW_BAR_HEIGHT = 32.dp

/**
 * How far in from the window's edges the mouse resizes it. A video is kept this far from the edges
 * too, as the mouse can't reach through its picture to them.
 */
val WINDOW_EDGE = 6.dp

/**
 * The window's bar, in place of Windows' own white one: the logo and the name at the left, and
 * minimize, maximize and close at the right, in the app's colours and with its icons.
 *
 * It does what Windows' bar does with the mouse. Pulling it moves the window, and a double click
 * maximizes it or makes it a window again. Pulled while maximized, it comes off the top as a window
 * under the mouse, and let go at the very top of a screen it's maximized. Without a [window], as in
 * the page renders, it's only drawn.
 */
@Composable
fun WindowBar(
    state: WindowState,
    window: ComposeWindow?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val maximized = state.placement == WindowPlacement.Maximized
    val pull = remember(window, state) { window?.let { WindowPull(it, state) } }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .height(WINDOW_BAR_HEIGHT)
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier.weight(1f)
                    .fillMaxHeight()
                    .pointerInput(window, state) {
                        detectTapGestures(onDoubleTap = { OwnFrame.toggleMaximized(window, state) })
                    }
                    .pointerInput(pull) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            if (currentEvent.buttons.isPrimaryPressed) pull?.press()
                        }
                    }
                    .padding(start = 14.dp),
        ) {
            WinchivistLogo(modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Winchivist",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        WindowButton(icon = "ic_window_minimize", label = "Minimize") { state.isMinimized = true }
        WindowButton(
            icon = if (maximized) "ic_window_restore" else "ic_window_maximize",
            label = if (maximized) "Restore" else "Maximize",
        ) {
            OwnFrame.toggleMaximized(window, state)
        }
        WindowButton(icon = "ic_x", label = "Close", closes = true, onClick = onClose)
    }
}

/** One of the bar's three buttons, as wide as Windows' own. Close turns red under the mouse. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun WindowButton(
    icon: String,
    label: String,
    closes: Boolean = false,
    onClick: () -> Unit,
) {
    // Kept by hand: the window goes from under the mouse at a click, and nothing says it left
    var under by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Tooltip(label) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.width(46.dp)
                    .fillMaxHeight()
                    .background(
                        when {
                            !under -> Color.Transparent
                            closes -> CLOSE_RED
                            else -> colors.onSurface.copy(alpha = 0.1f)
                        }
                    )
                    .onPointerEvent(PointerEventType.Enter) { under = true }
                    .onPointerEvent(PointerEventType.Exit) { under = false }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        under = false
                        onClick()
                    },
        ) {
            AppIcon(
                name = icon,
                contentDescription = label,
                tint = if (under && closes) Color.White else colors.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** The red of Windows' own close button under the mouse. */
private val CLOSE_RED = Color(0xFFC42B1C)

/**
 * Moves the window with the mouse from its bar. It listens to the window's own mouse events, as
 * Compose's draggable area does: the place Compose gives is counted from the window, which is the
 * thing being moved.
 */
private class WindowPull(private val window: ComposeWindow, private val state: WindowState) {
    private var mouseFrom: Point? = null
    private var windowFrom = Point()
    private var pulling = false

    // Set for a window pulled off the top: its size as a window, which Java learns a moment later
    private var size: Dimension? = null

    private val onMove =
        object : MouseMotionAdapter() {
            override fun mouseDragged(event: MouseEvent) = move()
        }
    private val onRelease =
        object : MouseAdapter() {
            override fun mouseReleased(event: MouseEvent) = release()
        }

    /** The mouse's button went down on the bar. */
    fun press() {
        stopListening()
        mouseFrom = MouseInfo.getPointerInfo()?.location ?: return
        windowFrom = window.location
        pulling = false
        size = null
        window.addMouseMotionListener(onMove)
        window.addMouseListener(onRelease)
    }

    private fun move() {
        val from = mouseFrom ?: return
        val mouse = MouseInfo.getPointerInfo()?.location ?: return
        if (!pulling) {
            // The little a mouse moves during a click isn't a pull
            if (abs(mouse.x - from.x) < PULL_FROM && abs(mouse.y - from.y) < PULL_FROM) return
            pulling = true
            if (window.placement == WindowPlacement.Maximized) comeOffTheTop(from)
        }
        val x = windowFrom.x + mouse.x - from.x
        val y = windowFrom.y + mouse.y - from.y
        size?.let { window.setBounds(x, y, it.width, it.height) } ?: window.setLocation(x, y)
    }

    /**
     * Makes a maximized window a window again, with its top where it was and the mouse as far along
     * its bar as it was along the maximized one.
     */
    private fun comeOffTheTop(mouse: Point) {
        val along = (mouse.x - window.x).toFloat() / window.width.coerceAtLeast(1)
        // The size it had as a window, which Compose keeps while it's maximized
        val kept = state.size.takeIf { it.isSpecified }
        val width = kept?.width?.value?.roundToInt() ?: (window.width / 2)
        val height = kept?.height?.value?.roundToInt() ?: (window.height / 2)
        val top = window.y
        OwnFrame.restore(window, state)
        windowFrom = Point(mouse.x - (width * along).roundToInt(), top)
        size = Dimension(width, height)
    }

    private fun release() {
        stopListening()
        val pulled = pulling
        pulling = false
        mouseFrom = null
        size = null
        if (!pulled) return
        // Let go at the very top of a screen, it's maximized, as with Windows' own bars
        val mouse = MouseInfo.getPointerInfo()?.location ?: return
        if (mouse.y <= window.graphicsConfiguration.bounds.y) OwnFrame.maximize(window, state)
    }

    private fun stopListening() {
        window.removeMouseMotionListener(onMove)
        window.removeMouseListener(onRelease)
    }

    private companion object {
        const val PULL_FROM = 4
    }
}

/**
 * What Winchivist does for its window by hand, as a window with its own bar has no frame of
 * Windows' own to do it. The player goes through it for full screen whichever bar the window has.
 */
object OwnFrame {
    /** Maximizes [window], or makes it a window again. */
    fun toggleMaximized(window: ComposeWindow?, state: WindowState) {
        val now = window?.placement ?: state.placement
        if (now == WindowPlacement.Maximized) restore(window, state) else maximize(window, state)
    }

    fun maximize(window: ComposeWindow?, state: WindowState) {
        if (window != null) {
            keepOffTheTaskbar(window)
            window.placement = WindowPlacement.Maximized
        }
        state.placement = WindowPlacement.Maximized
    }

    fun restore(window: ComposeWindow?, state: WindowState) {
        window?.placement = WindowPlacement.Floating
        state.placement = WindowPlacement.Floating
    }

    /**
     * Tells a window without a frame where to go when it's maximized: Windows would lay it over the
     * taskbar. A window with Windows' own frame is left to Windows.
     */
    fun keepOffTheTaskbar(window: ComposeWindow) {
        if (window.isUndecorated) window.maximizedBounds = workArea(window)
    }

    /** Puts [window] in full screen. */
    fun enterFullscreen(window: ComposeWindow?, state: WindowState) {
        if (window != null) {
            // Java holds a maximized window to the bounds it was given for that, full screen or
            // not, so they're taken away for as long as it lasts
            if (window.isUndecorated) window.maximizedBounds = null
            window.placement = WindowPlacement.Fullscreen
        }
        state.placement = WindowPlacement.Fullscreen
    }

    /**
     * Takes [window] out of full screen and places it as [back] says.
     *
     * Only Floating takes a Compose window out of full screen. Asked to be maximized, a window in
     * full screen is marked as maximized and stays in full screen, so it's taken out first and
     * maximized after. Both are done on the window itself, where they happen at once.
     */
    fun leaveFullscreen(window: ComposeWindow?, state: WindowState, back: WindowPlacement) {
        if (window != null) {
            window.placement = WindowPlacement.Floating
            keepOffTheTaskbar(window)
            if (back == WindowPlacement.Maximized) window.placement = WindowPlacement.Maximized
        }
        state.placement = if (back == WindowPlacement.Maximized) back else WindowPlacement.Floating
    }

    /** The part of [window]'s screen that the taskbar leaves free. */
    private fun workArea(window: Window): Rectangle {
        val screen = window.graphicsConfiguration
        val whole = screen.bounds
        val taken = Toolkit.getDefaultToolkit().getScreenInsets(screen)
        val area =
            Rectangle(
                whole.x + taken.left,
                whole.y + taken.top,
                whole.width - taken.left - taken.right,
                whole.height - taken.top - taken.bottom,
            )
        // A taskbar that hides itself takes no room, and doesn't come back up over a window that
        // covers the whole screen. One row of the screen is left for it to notice the mouse in
        if (area == whole && taskbarHides()) area.height -= 1
        return area
    }

    private fun taskbarHides(): Boolean =
        try {
            if (!Platform.isWindows()) {
                false
            } else {
                val data = ShellAPI.APPBARDATA()
                data.cbSize = WinDef.DWORD(data.size().toLong())
                val asked = WinDef.DWORD(ShellAPI.ABM_GETSTATE.toLong())
                (Shell32.INSTANCE.SHAppBarMessage(asked, data).toInt() and TASKBAR_HIDES) != 0
            }
        } catch (e: Throwable) {
            false
        }

    /**
     * Asks Windows 11 for its rounded corners, which it gives a window without a frame only when
     * asked, or for square ones while the window fills the screen. Older Windows says no to the
     * question, and that's left at that.
     */
    fun roundCorners(window: Window, round: Boolean) {
        try {
            val handle = WinDef.HWND(Native.getWindowPointer(window))
            val wanted = IntByReference(if (round) CORNERS_ROUND else CORNERS_SQUARE)
            dwm?.DwmSetWindowAttribute(handle, CORNER_PREFERENCE, wanted, Int.SIZE_BYTES)
        } catch (e: Throwable) {
            // Not a window Windows knows yet, or not Windows at all
        }
    }

    private val dwm: Dwm? by lazy {
        try {
            if (Platform.isWindows()) Native.load("dwmapi", Dwm::class.java) else null
        } catch (e: Throwable) {
            null
        }
    }

    // ABS_AUTOHIDE, DWMWA_WINDOW_CORNER_PREFERENCE, DWMWCP_DONOTROUND and DWMWCP_ROUND
    private const val TASKBAR_HIDES = 0x1
    private const val CORNER_PREFERENCE = 33
    private const val CORNERS_SQUARE = 1
    private const val CORNERS_ROUND = 2
}

/** The one call into Windows' window manager that the corners need. */
@Suppress("FunctionName")
internal interface Dwm : Library {
    fun DwmSetWindowAttribute(
        window: WinDef.HWND,
        attribute: Int,
        value: IntByReference,
        size: Int,
    ): Int
}
