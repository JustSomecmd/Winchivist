package dev.jdtech.tubedroid.desktop

import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileLock

/** The argument Winchivist is started with when Windows starts it: in the tray, with no window. */
const val START_IN_TRAY = "--tray"

/**
 * Makes sure only one Winchivist runs, which matters once it can stay in the tray: starting it
 * again brings the one that's running to the front rather than adding a second.
 *
 * The first one holds a lock on a file in the app's folder and listens on a port of this computer
 * alone, which it writes beside the lock. A second one finds the lock taken, knocks on the port and
 * leaves. The port is in a file of its own because Windows lets no other program read a locked one.
 */
class SingleInstance(private val onShow: () -> Unit) {
    // Held for as long as the app runs, through the thread that listens. Windows lets go of it when
    // the app ends, however it ends
    private var lock: FileLock? = null
    private var server: ServerSocket? = null

    /** Returns whether this is the only Winchivist; if not, the other one was asked to show. */
    fun claim(): Boolean {
        val folder = appDataFolder()
        val portFile = File(folder, "instance.port")
        return try {
            folder.mkdirs()
            val access = RandomAccessFile(File(folder, "instance.lock"), "rw")
            val held = access.channel.tryLock()
            if (held == null) {
                access.close()
                // Another Winchivist has it: ask that one to show its window
                runCatching { portFile.readText().trim().toIntOrNull() }.getOrNull()?.let(::knock)
                false
            } else {
                lock = held
                val listening = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
                server = listening
                portFile.writeText(listening.localPort.toString())
                Thread({ listen(listening) }, "single-instance").apply { isDaemon = true }.start()
                true
            }
        } catch (e: Exception) {
            // It starts all the same, as a second Winchivist is better than none
            System.err.println("Single instance: $e")
            true
        }
    }

    private fun knock(port: Int) {
        runCatching {
            Socket(InetAddress.getLoopbackAddress(), port).use { it.getOutputStream().write(1) }
        }
    }

    // Waits without using the processor until another Winchivist knocks
    private fun listen(listening: ServerSocket) {
        while (!listening.isClosed) {
            try {
                listening.accept().close()
                onShow()
            } catch (_: Exception) {
                if (listening.isClosed) return
            }
        }
    }
}

/**
 * Starting with Windows: an entry under the user's own Run key, which starts Winchivist in the tray
 * when they sign in. Only an installed Winchivist knows where its program is.
 */
object StartWithWindows {
    private const val RUN_KEY = """HKCU\Software\Microsoft\Windows\CurrentVersion\Run"""
    private const val NAME = "Winchivist"

    /** Where the installed program is, or null when run some other way, such as from Gradle. */
    private val program: String?
        get() = System.getProperty("jpackage.app-path")?.takeIf { File(it).isFile }

    val available: Boolean
        get() = program != null && System.getProperty("os.name").orEmpty().startsWith("Windows")

    /** Adds or takes away the entry. Returns whether that worked. */
    fun set(on: Boolean): Boolean {
        val path = program ?: return false
        val command =
            if (on) {
                listOf(
                    "reg",
                    "add",
                    RUN_KEY,
                    "/v",
                    NAME,
                    "/t",
                    "REG_SZ",
                    "/d",
                    "\"$path\" $START_IN_TRAY",
                    "/f",
                )
            } else {
                listOf("reg", "delete", RUN_KEY, "/v", NAME, "/f")
            }
        return try {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            process.inputStream.readAllBytes()
            // Taking away an entry that isn't there is as good as done
            process.waitFor() == 0 || !on
        } catch (e: Exception) {
            System.err.println("Start with Windows: $e")
            false
        }
    }
}
