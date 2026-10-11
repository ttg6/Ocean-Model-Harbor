package dev.aigw.windows

import com.formdev.flatlaf.FlatLightLaf
import dev.aigw.core.gateway.GatewayEngine
import java.awt.Font
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import javax.swing.UIManager

fun main(args: Array<String>) {
    fun option(name: String) = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val data = File(option("--data") ?: System.getenv("AIGW_DATA")
        ?: File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), AppIdentity.DATA_DIRECTORY).path)
    data.mkdirs()
    FlatLightLaf.setup()
    UIManager.put("defaultFont", Font("Microsoft YaHei UI", Font.PLAIN, 13))
    UIManager.put("Component.arc", 8)
    UIManager.put("Button.arc", 6)
    UIManager.put("Component.focusColor", OceanUi.teal)
    UIManager.put("Component.accentColor", OceanUi.teal)
    UIManager.put("Button.default.background", OceanUi.teal)
    UIManager.put("OptionPane.okButtonText", "确定")
    UIManager.put("OptionPane.cancelButtonText", "取消")
    UIManager.put("OptionPane.yesButtonText", "是")
    UIManager.put("OptionPane.noButtonText", "否")
    UIManager.put("Table.rowHeight", 34)
    UIManager.put("TabbedPane.showTabSeparators", true)
    val channel = FileChannel.open(data.toPath().resolve("instance.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    val lock = channel.tryLock()
    if (lock == null) {
        JOptionPane.showMessageDialog(null, "该数据目录的程序已经运行。请从系统托盘打开窗口。", AppIdentity.NAME, JOptionPane.INFORMATION_MESSAGE)
        channel.close()
        return
    }
    try {
        val store = WindowsKeyValueStore(data.toPath().resolve("store"))
        val engine = GatewayEngine(store)
        if (store.read("windows/initialized") == null) {
            engine.updateSettings(engine.settings().copy(exposeLan = false))
            store.write("windows/initialized", "true")
        }
        Runtime.getRuntime().addShutdownHook(Thread {
            engine.stopCallbackServer(); engine.stop(); lock.release(); channel.close()
        })
        SwingUtilities.invokeLater {
            val window = GatewayWindow(engine, data)
            option("--window-size")?.split("x")?.takeIf { it.size == 2 }?.let { dimensions ->
                val w = dimensions[0].toIntOrNull(); val h = dimensions[1].toIntOrNull()
                if (w != null && h != null) window.setSize(w.coerceAtLeast(940), h.coerceAtLeast(650))
            }
            window.isVisible = true
            option("--screenshot")?.let { window.saveScreenshot(File(it)) }
            option("--banner-screenshot")?.let { window.saveBannerScreenshot(File(it)) }
            option("--tray-screenshot")?.let { window.saveTrayScreenshot(File(it)) }
            option("--page-screenshots")?.let { window.savePageScreenshots(File(it)) }
            if ("--start" in args || store.read("windows/autoStart") == "true") window.startGateway()
        }
    } catch (e: Exception) {
        JOptionPane.showMessageDialog(null, e.message ?: "启动失败", "${AppIdentity.NAME} 启动失败", JOptionPane.ERROR_MESSAGE)
        lock.release(); channel.close()
    }
}
