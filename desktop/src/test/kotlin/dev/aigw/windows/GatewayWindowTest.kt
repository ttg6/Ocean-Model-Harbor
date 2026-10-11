package dev.aigw.windows

import dev.aigw.core.gateway.GatewayEngine
import java.awt.Component
import java.awt.Container
import java.net.ServerSocket
import java.nio.file.Files
import javax.swing.JButton
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertTrue

class GatewayWindowTest {
    @Test fun `desktop start and stop buttons control the real gateway`() {
        val dir = Files.createTempDirectory("aigw-ui-")
        val engine = GatewayEngine(WindowsKeyValueStore(dir.resolve("store")))
        val port = ServerSocket(0).use { it.localPort }
        engine.updateSettings(engine.settings().copy(port = port, exposeLan = false))
        var window: GatewayWindow? = null
        fun find(component: Component, label: String): JButton? {
            if (component is JButton && component.text == label) return component
            if (component is Container) component.components.forEach { find(it, label)?.let { button -> return button } }
            return null
        }
        fun awaitState(running: Boolean) {
            val deadline = System.currentTimeMillis() + 5000
            while (engine.isRunning() != running && System.currentTimeMillis() < deadline) Thread.sleep(25)
            assertTrue(engine.isRunning() == running)
            SwingUtilities.invokeAndWait { }
        }
        try {
            SwingUtilities.invokeAndWait {
                window = GatewayWindow(engine, dir.toFile())
                find(window!!, "启动网关")!!.doClick()
            }
            awaitState(true)
            // Allow the worker's Swing completion to update the button state.
            val deadline = System.currentTimeMillis() + 5000
            var clicked = false
            while (!clicked && System.currentTimeMillis() < deadline) {
                SwingUtilities.invokeAndWait { find(window!!, "停止网关")?.takeIf { it.isEnabled }?.let { it.doClick(); clicked = true } }
                if (!clicked) Thread.sleep(25)
            }
            assertTrue(clicked)
            awaitState(false)
        } finally {
            SwingUtilities.invokeAndWait { window?.dispose() }
            engine.stop(); dir.toFile().deleteRecursively()
        }
    }
}
