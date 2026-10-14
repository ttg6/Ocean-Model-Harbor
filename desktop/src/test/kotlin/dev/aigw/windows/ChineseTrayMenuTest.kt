package dev.aigw.windows

import dev.aigw.core.gateway.GatewayEngine
import java.awt.Component
import java.awt.Container
import java.awt.Point
import java.awt.Robot
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.net.ServerSocket
import java.nio.file.Files
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChineseTrayMenuTest {
    @Test fun `tray control window accepts Escape and closes on an actual outside click with hidden owner`() {
        val robot = Robot().apply { autoDelay = 50 }
        lateinit var owner: JFrame
        lateinit var other: JFrame
        lateinit var menu: ChineseTrayMenu
        try {
            SwingUtilities.invokeAndWait {
                owner = JFrame().apply { pack() }
                other = JFrame("Tray dismissal test").apply { setBounds(80, 80, 240, 180); isVisible = true }
                menu = ChineseTrayMenu(owner, {}, {}, {})
            }
            fun show() {
                for (attempt in 0 until 3) {
                    SwingUtilities.invokeAndWait {
                        val screen = owner.graphicsConfiguration.bounds
                        menu.showAt(Point(screen.x + screen.width - 25, screen.y + screen.height - 25), false)
                    }
                    robot.waitForIdle()
                    // A real click must acquire Windows foreground before keyboard delivery.
                    val header = menu.locationOnScreen
                    robot.mouseMove(header.x + 100, header.y + 20)
                    robot.mousePress(InputEvent.BUTTON1_DOWN_MASK); robot.delay(80)
                    robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                    robot.waitForIdle(); robot.delay(150)
                    var focused = false
                    SwingUtilities.invokeAndWait { focused = menu.isVisible && menu.isFocused }
                    if (focused) break
                }
                SwingUtilities.invokeAndWait { assertTrue(menu.isVisible); assertTrue(menu.isFocused) }
            }
            show()
            // Other desktop windows can steal foreground while UI tests run.
            // Reacquire with a genuine click and allow bounded delivery of the real Esc key.
            for (attempt in 0 until 3) {
                val header = menu.locationOnScreen
                robot.mouseMove(header.x + 100, header.y + 20)
                robot.mousePress(InputEvent.BUTTON1_DOWN_MASK); robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                robot.waitForIdle(); robot.delay(100)
                robot.keyPress(KeyEvent.VK_ESCAPE); robot.delay(100); robot.keyRelease(KeyEvent.VK_ESCAPE)
                robot.waitForIdle(); robot.delay(200)
                if (!menu.isVisible) break
            }
            SwingUtilities.invokeAndWait { assertFalse(menu.isVisible) }
            show()
            robot.mouseMove(150, 160)
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK); robot.delay(200); robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
            robot.waitForIdle(); robot.delay(150)
            SwingUtilities.invokeAndWait { assertFalse(menu.isVisible); assertFalse(owner.isVisible) }
            show() // Reopening must work after both dismissal paths.
        } finally {
            SwingUtilities.invokeAndWait { menu.dispose(); other.dispose(); owner.dispose() }
        }
    }

    @Test fun `tray window stays near its anchor on negative and top taskbar monitors`() {
        val size = Dimension(286, 260)
        val leftMonitor = Rectangle(-1920, 0, 1920, 1040)
        val anchor = Point(-100, 1060)
        val below = Rectangle(TrayPopupPlacement.position(anchor, size, leftMonitor), size)
        assertTrue(leftMonitor.contains(below))
        assertTrue(kotlin.math.abs(below.x + below.width - anchor.x) <= 12)
        assertTrue(anchor.y - (below.y + below.height) <= 30)
        val topMonitor = Rectangle(0, 40, 1920, 1040)
        val top = Rectangle(TrayPopupPlacement.position(Point(1700, 20), size, topMonitor), size)
        assertTrue(topMonitor.contains(top)); assertEquals(40, top.y)
        val edge = Rectangle(TrayPopupPlacement.position(Point(-1910, 1050), size, leftMonitor), size)
        assertTrue(leftMonitor.contains(edge)); assertEquals(-1920, edge.x)
    }

    @Test fun `Chinese tray menu renders and controls gateway with the main window hidden`() {
        val dir = Files.createTempDirectory("ocean-tray-test-")
        val engine = GatewayEngine(WindowsKeyValueStore(dir.resolve("store")))
        engine.updateSettings(engine.settings().copy(port = ServerSocket(0).use { it.localPort }, exposeLan = false))
        var owner: JFrame? = null
        var menu: ChineseTrayMenu? = null
        var opened = 0
        var quit = 0
        try {
            SwingUtilities.invokeAndWait {
                owner = JFrame("Ocean tray test").apply { pack() }
                menu = ChineseTrayMenu(owner!!, onOpen = { opened++ },
                    onToggle = { if (engine.isRunning()) engine.stop() else engine.start() }, onQuit = { quit++ })
                val screen = owner!!.graphicsConfiguration.bounds
                val point = Point(screen.x + screen.width - 5, screen.y + screen.height - 5)
                fun show() = menu!!.showAt(point, engine.isRunning())
                fun buttons() = descendants(menu!!).filterIsInstance<JButton>().toList()
                fun click(label: String) = buttons().first { it.text == label }.doClick()
                show()
                assertFalse(owner!!.isVisible)
                assertTrue(menu!!.isVisible)
                assertTrue(menu!!.isFocusableWindow, "A hidden owner must not prevent outside-click dismissal or keyboard focus")
                assertTrue(screen.contains(menu!!.bounds), "Menu must remain inside the monitor")
                assertEquals(listOf("打开 Ocean 模型港", "启动网关", "退出程序"), buttons().map { it.text })
                buttons().forEach { assertEquals(-1, it.font.canDisplayUpTo(it.text), "Missing Chinese glyphs: ${it.text}") }
                click("启动网关")
                assertTrue(engine.isRunning())
                assertFalse(menu!!.isVisible)
                show()
                click("停止网关")
                assertFalse(engine.isRunning())
                show()
                click("打开 Ocean 模型港")
                assertEquals(1, opened)
                show()
                click("退出程序")
                assertEquals(1, quit)
                assertFalse(menu!!.isVisible)
            }
        } finally {
            SwingUtilities.invokeAndWait { menu?.dispose(); owner?.dispose() }
            engine.stop()
            dir.toFile().deleteRecursively()
        }
    }

    private fun descendants(component: Component): Sequence<Component> = sequence {
        yield(component)
        if (component is Container) component.components.forEach { yieldAll(descendants(it)) }
    }
}
