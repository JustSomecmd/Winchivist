package dev.jdtech.tubedroid.desktop.compute

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.win32.StdCallLibrary

/** What Windows says about the PC being in use, for work that waits until it isn't. */
internal object PcState {
    val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")

    /** How long ago the keyboard or mouse was last used, in seconds; null when it can't be told. */
    fun idleSeconds(): Long? {
        if (!isWindows) return null
        return try {
            val last = WinUser.LASTINPUTINFO()
            if (!User32.INSTANCE.GetLastInputInfo(last)) return null
            // Both count milliseconds since Windows started, in 32 bits that go round together
            val gone = (Kernel32.INSTANCE.GetTickCount() - last.dwTime).toLong() and 0xFFFFFFFFL
            gone / 1000
        } catch (e: Throwable) {
            null
        }
    }

    /** Whether the PC is on mains power. A PC that can't tell, such as a desktop, counts as on. */
    fun onMains(): Boolean {
        if (!isWindows) return true
        return try {
            val kernel = power ?: return true
            // SYSTEM_POWER_STATUS is 12 bytes, of which the first says where the power comes from
            val status = Memory(POWER_STATUS_BYTES)
            if (!kernel.GetSystemPowerStatus(status)) return true
            // 0 is on the battery, 1 on mains, 255 unknown
            status.getByte(0).toInt() != 0
        } catch (e: Throwable) {
            true
        }
    }

    private val power: PowerLib? by lazy {
        runCatching { Native.load("kernel32", PowerLib::class.java) }.getOrNull()
    }

    private const val POWER_STATUS_BYTES = 12L
}

/** The one call for the PC's power that JNA's own Kernel32 leaves out. */
internal interface PowerLib : StdCallLibrary {
    @Suppress("FunctionName") fun GetSystemPowerStatus(status: Pointer): Boolean
}
