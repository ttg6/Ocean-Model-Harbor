package dev.aigw.windows

import com.formdev.flatlaf.FlatLightLaf
import dev.aigw.core.gateway.GatewayEngine
import dev.aigw.core.gateway.ProxyType
import java.awt.Component
import java.awt.Container
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.*
import kotlin.test.*

class StitchSettingsPanelTest {
    private class Fixture : AutoCloseable {
        val dir = Files.createTempDirectory("ocean-stitch-")
        val engine = GatewayEngine(WindowsKeyValueStore(dir.resolve("store")))
        val worker = Executors.newSingleThreadExecutor()
        val errors = CopyOnWriteArrayList<String>()
        var loginEnabled = false
        var loginFailure = false
        val loginStartup = object : LoginStartup {
            override fun isEnabled() = loginEnabled
            override fun setEnabled(enabled: Boolean) {
                if (loginFailure) error("模拟启动项保存失败")
                loginEnabled = enabled
            }
        }
        lateinit var panel: StitchSettingsPanel
        init {
            engine.updateSettings(engine.settings().copy(exposeLan = false))
            SwingUtilities.invokeAndWait {
                FlatLightLaf.setup()
                panel = StitchSettingsPanel(engine, dir.toFile(), { _, task -> worker.submit { try { task() } catch (e: Exception) { errors += e.message.orEmpty() } } }, { errors += it }, { false }, {}, loginStartup)
            }
        }
        fun all(c: Component = panel): List<Component> = listOf(c) + if (c is Container) c.components.flatMap { all(it) } else emptyList()
        fun button(text: String) = all().filterIsInstance<JButton>().first { it.text == text }
        fun input(name: String) = all().filterIsInstance<JTextField>().first { it.name == name }
        fun toggle(name: String) = all().filterIsInstance<JCheckBox>().first { it.name == name }
        fun await(condition: () -> Boolean) {
            val deadline = System.currentTimeMillis() + 5000
            while (!condition() && System.currentTimeMillis() < deadline) { Thread.sleep(20); SwingUtilities.invokeAndWait {} }
            assertTrue(condition()); assertTrue(errors.isEmpty(), errors.joinToString())
        }
        override fun close() { worker.shutdownNow(); engine.stop(); SwingUtilities.invokeAndWait {}; dir.toFile().deleteRecursively() }
    }

    @Test fun `login startup and gateway startup save independently with defaults and discard remaining drafts`() {
        Fixture().use { f ->
            SwingUtilities.invokeAndWait {
                f.toggle("开机启动 Ocean").doClick()
                assertTrue(f.toggle("打开程序时自动启动网关").isSelected)
                assertFalse(f.loginEnabled)
                f.button("保存设置").doClick()
            }
            f.await { f.loginEnabled && f.engine.store().read("windows/autoStart") == "true" }
            SwingUtilities.invokeAndWait {
                f.button("恢复默认值").doClick()
                assertFalse(f.toggle("开机启动 Ocean").isSelected)
                assertTrue(f.loginEnabled)
                f.button("放弃修改").doClick()
                assertTrue(f.toggle("开机启动 Ocean").isSelected)
                f.toggle("打开程序时自动启动网关").doClick()
                f.button("保存设置").doClick()
            }
            f.await { f.loginEnabled && f.engine.store().read("windows/autoStart") == "false" }
            SwingUtilities.invokeAndWait {
                f.toggle("开机启动 Ocean").doClick()
                f.toggle("打开程序时自动启动网关").doClick()
                f.button("保存设置").doClick()
            }
            f.await { !f.loginEnabled && f.engine.store().read("windows/autoStart") == "true" }
        }
    }

    @Test fun `failed Windows startup write does not silently save gateway startup or other settings`() {
        Fixture().use { f ->
            val original = f.engine.settings().port
            f.loginFailure = true
            SwingUtilities.invokeAndWait {
                f.toggle("开机启动 Ocean").doClick()
                f.input("监听端口").text = "18791"
                f.button("保存设置").doClick()
            }
            val deadline = System.currentTimeMillis() + 5000
            while (f.errors.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertTrue(f.errors.any { it.contains("启动项保存失败") })
            assertFalse(f.loginEnabled)
            assertNotEquals("true", f.engine.store().read("windows/autoStart"))
            assertEquals(original, f.engine.settings().port)
        }
    }

    @Test fun `save applies real authentication and changed port and persists preferences`() {
        Fixture().use { f ->
            val originalPort = ServerSocket(0).use { it.localPort }
            f.engine.updateSettings(f.engine.settings().copy(port = originalPort)); f.engine.start()
            val newPort = ServerSocket(0).use { it.localPort }
            SwingUtilities.invokeAndWait {
                f.input("监听端口").text = newPort.toString(); f.input("网关 API Key").text = "fake-stitch-test-key"
                f.toggle("允许无 Key 调用").isSelected = false
                f.toggle("打开程序时自动启动网关").isSelected = true
                f.input("代理地址").text = "127.0.0.1"; f.input("代理端口").text = "7890"
                f.panel.let { f.all().filterIsInstance<JComboBox<*>>().first { it.name == "代理协议" }.selectedItem = ProxyType.SOCKS }
                f.button("保存设置").doClick()
            }
            f.await { f.engine.settings().port == newPort && f.engine.isRunning() && f.engine.store().read("windows/autoStart") == "true" }
            fun status(key: Boolean): Int {
                val connection = URL("http://127.0.0.1:$newPort/v1/models").openConnection() as HttpURLConnection
                if (key) connection.setRequestProperty("Authorization", "Bearer fake-stitch-test-key")
                connection.connectTimeout = 2000; connection.readTimeout = 2000
                return try { connection.responseCode } finally { connection.disconnect() }
            }
            assertEquals(401, status(false)); assertEquals(200, status(true))
            val restored = GatewayEngine(WindowsKeyValueStore(f.dir.resolve("store")))
            try { assertEquals(newPort, restored.settings().port); assertFalse(restored.settings().allowNoKey); assertEquals(ProxyType.SOCKS, restored.proxySettings().type) } finally { restored.stop() }
        }
    }

    @Test fun `key generation and defaults are drafts and discard and search restore expected fields`() {
        Fixture().use { f ->
            val saved = f.engine.settings()
            SwingUtilities.invokeAndWait {
                f.button("重新生成").doClick()
                assertTrue(f.input("网关 API Key").text.startsWith("sk-ocean-")); assertEquals(57, f.input("网关 API Key").text.length)
                assertEquals(saved, f.engine.settings())
                f.button("显示").doClick(); assertEquals(0, (f.input("网关 API Key") as JPasswordField).echoChar.code)
                assertNotNull(f.button("隐藏"))
                f.button("放弃修改").doClick(); assertEquals(saved.apiKey, f.input("网关 API Key").text)
                assertNotEquals(0, (f.input("网关 API Key") as JPasswordField).echoChar.code); assertNotNull(f.button("显示"))
                f.input("监听端口").text = "11223"; f.button("恢复默认值").doClick()
                assertEquals("8790", f.input("监听端口").text); assertEquals(saved, f.engine.settings())
                f.input("搜索设置").text = "代理节点端口"
                val titles = f.all().filterIsInstance<JLabel>()
                assertTrue(titles.first { it.text == "代理节点端口" }.parent.parent.isVisible)
                assertFalse(titles.first { it.text == "本地监听端口" }.parent.parent.isVisible)
                f.input("搜索设置").text = ""
                assertTrue(titles.first { it.text == "本地监听端口" }.parent.parent.isVisible)
            }
        }
    }

    @Test fun `proxy port test performs actual local TCP connection`() {
        Fixture().use { f ->
            ServerSocket(0).use { server ->
                server.soTimeout = 4000
                SwingUtilities.invokeAndWait {
                    f.input("代理地址").text = "127.0.0.1"; f.input("代理端口").text = server.localPort.toString()
                    f.button("测试代理端口").doClick()
                }
                server.accept().use { }
                f.await {
                    var connected = false
                    SwingUtilities.invokeAndWait { connected = f.all().filterIsInstance<JLabel>().any { it.text.startsWith("TCP 可连接") } }
                    connected
                }
                assertFalse(f.engine.proxySettings().enabled)
            }
        }
    }
}
