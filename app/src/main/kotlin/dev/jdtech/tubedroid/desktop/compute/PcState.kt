package dev.jdtech.tubedroid.desktop.compute

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinUser

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
            val power = WinBase.SYSTEM_POWER_STATUS()
            if (!Kernel32.INSTANCE.GetSystemPowerStatus(power)) return true
            // 0 is on the battery, 1 on mains, 255 unknown
            power.ACLineStatus.toInt() != 0
        } catch (e: Throwable) {
            true
        }
    }
}
