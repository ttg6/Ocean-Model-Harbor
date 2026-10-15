package dev.aigw.windows

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

class WindowsLoginStartupTest {
    @Test fun `current user registry preserves quoted executable data directory and upgrade target without enabling another profile`() {
        val dir = Files.createTempDirectory("ocean-login-")
        val key = "Software\\OceanModelHarbor-Test-${UUID.randomUUID()}"
        val name = "StartupTest"
        val data = dir.resolve("中文 数据").toFile()
        val exe = dir.resolve("${AppIdentity.NAME}.exe").toFile().apply { writeText("test fixture") }
        val startup = WindowsLoginStartup(data, exe, key, name)
        try {
            assertFalse(startup.isEnabled())
            startup.setEnabled(true)
            assertTrue(WindowsLoginStartup(data, exe, key, name).isEnabled())
            val command = Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, key, name)
            assertEquals("\"${exe.canonicalPath}\" --startup --data \"${data.canonicalPath}\"", command)
            val other = WindowsLoginStartup(dir.resolve("other").toFile(), exe, key, name)
            assertFalse(other.isEnabled()); other.setEnabled(false)
            assertTrue(startup.isEnabled())
            val upgraded = dir.resolve("new version").toFile().apply { mkdirs() }
                .resolve(exe.name).apply { writeText("new test fixture") }
            WindowsLoginStartup(data, upgraded, key, name).refreshEnabledEntry()
            assertTrue(Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, key, name).startsWith("\"${upgraded.canonicalPath}\""))
            startup.setEnabled(false)
            assertFalse(startup.isEnabled())
        } finally {
            if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, key, name)) Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, key, name)
            if (Advapi32Util.registryKeyExists(WinReg.HKEY_CURRENT_USER, key)) Advapi32Util.registryDeleteKey(WinReg.HKEY_CURRENT_USER, key)
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun `development launch cannot register a missing desktop executable`() {
        val dir = Files.createTempDirectory("ocean-no-launcher-")
        try { assertFailsWith<IllegalArgumentException> { WindowsLoginStartup(dir.toFile(), null).setEnabled(true) } }
        finally { dir.toFile().deleteRecursively() }
    }
}
