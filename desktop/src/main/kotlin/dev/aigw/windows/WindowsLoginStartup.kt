package dev.aigw.windows

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.io.File

interface LoginStartup {
    fun isEnabled(): Boolean
    fun setEnabled(enabled: Boolean)
}

/** Current user's login only; each entry retains the application's existing data directory. */
internal class WindowsLoginStartup(
    private val dataDir: File,
    private val launcher: File? = detectLauncher(),
    private val registryPath: String = "Software\\Microsoft\\Windows\\CurrentVersion\\Run",
    private val valueName: String = "OceanModelHarbor",
) : LoginStartup {
    private val suffix get() = " --startup --data ${quote(dataDir.canonicalPath)}"

    private fun read(): String? = if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, registryPath, valueName))
        Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, registryPath, valueName) else null

    override fun isEnabled(): Boolean = read()?.endsWith(suffix, ignoreCase = true) == true

    override fun setEnabled(enabled: Boolean) {
        if (!enabled) {
            if (isEnabled()) Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, registryPath, valueName)
            return
        }
        val exe = launcher?.canonicalFile
        require(exe != null && exe.isFile && exe.name.equals("${AppIdentity.NAME}.exe", ignoreCase = true)) {
            "请使用解压后的 ${AppIdentity.NAME}.exe 设置开机启动"
        }
        val command = quote(exe.path) + suffix
        require(command.length <= 260) { "程序或数据目录路径过长，请移到较短路径后重新开启开机启动" }
        Advapi32Util.registryCreateKey(WinReg.HKEY_CURRENT_USER, registryPath)
        Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, registryPath, valueName, command)
        check(read() == command) { "开机启动设置未保存，请检查 Windows 启动应用设置" }
    }

    /** Manually launching an upgraded or relocated copy repairs an already-enabled entry. */
    fun refreshEnabledEntry() { if (isEnabled()) setEnabled(true) }

    companion object {
        private fun detectLauncher(): File? = sequenceOf(
            System.getProperty("jpackage.app-path"),
            ProcessHandle.current().info().command().orElse(null),
        ).filterNotNull().map(::File).firstOrNull { it.name.equals("${AppIdentity.NAME}.exe", ignoreCase = true) }

        private fun quote(value: String): String {
            require(value.none { it == '"' || it == '\r' || it == '\n' || it == '\u0000' }) { "启动路径无效" }
            // A Windows drive-root data path ends with a slash, which must be doubled before a quote.
            return "\"$value${if (value.endsWith('\\')) "\\" else ""}\""
        }
    }
}
