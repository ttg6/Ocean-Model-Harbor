package dev.aigw.windows

import com.formdev.flatlaf.FlatLightLaf
import dev.aigw.core.gateway.GatewayEngine
import java.awt.Component
import java.awt.Container
import java.net.ServerSocket
import java.nio.file.Files
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JTable
import javax.swing.JViewport
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

class OceanNavigationTest {
    @Test fun `navigation and home shortcuts preserve gateway and work at minimum size`() {
        val dir = Files.createTempDirectory("ocean-navigation-")
        val engine = GatewayEngine(WindowsKeyValueStore(dir.resolve("store")))
        engine.updateSettings(engine.settings().copy(port = ServerSocket(0).use { it.localPort }, exposeLan = false))
        fun descendants(c: Component): List<Component> = listOf(c) + if (c is Container) c.components.flatMap { descendants(it) } else emptyList()
        var window: GatewayWindow? = null
        try {
            engine.start()
            SwingUtilities.invokeAndWait {
                FlatLightLaf.setup()
                window = GatewayWindow(engine, dir.toFile()).apply { setSize(940, 650); isVisible = true }
                val controls = descendants(window!!)
                fun click(label: String) = controls.filterIsInstance<JButton>().first { it.text == label }.doClick()
                fun visibleLabel(label: String) = controls.filterIsInstance<JLabel>().any { it.text == label && it.isShowing }
                click("管理账号"); assertTrue(visibleLabel("供应商与账号"))
                val creditsButton = controls.filterIsInstance<JButton>().first { it.text == "刷新额度" }
                val buttonBounds = SwingUtilities.convertRectangle(creditsButton.parent, creditsButton.bounds, window!!.contentPane)
                assertTrue(window!!.contentPane.bounds.contains(buttonBounds), "Account actions must fit the minimum window")
                val accountTable = controls.filterIsInstance<JTable>().first { it.columnModel.getColumn(2).headerValue == "额度" }
                assertTrue((accountTable.parent as JViewport).height >= 42, "At least one account row must remain visible: viewport=${accountTable.parent.height}")
                click("模型"); assertTrue(visibleLabel("模型目录"))
                click("调用记录"); assertTrue(visibleLabel("调用记录"))
                click("设置"); assertTrue(visibleLabel("系统与网关设置"))
                assertTrue(controls.filterIsInstance<JButton>().first { it.text == "保存设置" }.isShowing)
                click("网关"); assertTrue(visibleLabel("模型网关"))
                click("查看模型"); assertTrue(visibleLabel("模型目录"))
                assertNotNull(controls.filterIsInstance<JButton>().firstOrNull { it.text == "浏览器登录" })
                assertTrue(engine.isRunning())
            }
        } finally {
            SwingUtilities.invokeAndWait { window?.dispose() }
            engine.stop(); dir.toFile().deleteRecursively()
        }
    }
}
