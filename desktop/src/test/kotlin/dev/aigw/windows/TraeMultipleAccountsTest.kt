package dev.aigw.windows

import dev.aigw.core.gateway.GatewayEngine
import dev.aigw.core.provider.CreditInfo
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.WebLoginSupport
import java.awt.Component
import java.awt.Container
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.swing.JButton
import javax.swing.JTable
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TraeMultipleAccountsTest {
    @Test fun `browser login adds two accounts through callbacks without opening invitations and persists both`() {
        val dir = Files.createTempDirectory("aigw-multi-ui-")
        val engine = GatewayEngine(WindowsKeyValueStore(dir.resolve("store")))
        val original = engine.registry.require("trae")
        // Keep the real official URL builder and local callback server, replace only remote account exchange.
        engine.registry.register(object : Provider by original, WebLoginSupport by (original as WebLoginSupport) {
            override fun completeWebLogin(callbackUrl: String): ProviderAccount {
                val query = query(URI(callbackUrl))
                val uid = query.getValue("test_uid")
                return ProviderAccount("trae", uid, query.getValue("test_name"), "{\"test\":true}")
            }
            override fun creditInfo(account: ProviderAccount): CreditInfo? = null
        })
        val opened = LinkedBlockingQueue<String>()
        var window: GatewayWindow? = null
        val traceIds = mutableSetOf<String>()
        try {
            SwingUtilities.invokeAndWait {
                window = GatewayWindow(engine, dir.toFile(), browserOpener = { opened.add(it) },
                    confirmTraeInvitation = { error("Adding accounts must not enter invitation registration") })
                window!!.pack()
            }
            for ((uid, nickname) in listOf("test-user-a" to "Account A", "test-user-b" to "Account B", "test-user-a" to "Account A updated")) {
                SwingUtilities.invokeAndWait {
                    val components = descendants(window!!).toList()
                    val table = components.filterIsInstance<JTable>().first { it.getColumnName(1) == "标识" }
                    val row = (0 until table.rowCount).first { table.getValueAt(it, 1) == "trae" }
                    table.setRowSelectionInterval(row, row)
                    components.filterIsInstance<JButton>().first { it.text == "浏览器登录" }.doClick()
                }
                val loginUrl = assertNotNull(opened.poll(10, TimeUnit.SECONDS), "Authorization URL was not opened")
                assertNotEquals(GatewayWindow.TRAE_INVITE_URL, loginUrl)
                val uri = URI(loginUrl)
                assertEquals("https", uri.scheme)
                assertEquals("www.trae.cn", uri.host)
                assertEquals("/authorization", uri.path)
                val parameters = query(uri)
                assertTrue(traceIds.add(parameters.getValue("login_trace_id")), "Each login must have a fresh authorization ticket")
                val callbackUrl = parameters.getValue("auth_callback_url")
                assertEquals("http://127.0.0.1:51120/authorize", callbackUrl)
                val connection = URL(callbackUrl + "?test_uid=$uid&test_name=" + java.net.URLEncoder.encode(nickname, "UTF-8"))
                    .openConnection() as HttpURLConnection
                connection.connectTimeout = 3000
                connection.readTimeout = 5000
                try {
                    assertEquals(200, connection.responseCode)
                    assertTrue(connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }.contains("成功"))
                } finally { connection.disconnect() }
                // A successful callback releases the temporary port after its HTML has been sent.
                val deadline = System.currentTimeMillis() + 5000
                while (System.currentTimeMillis() < deadline) {
                    val listening = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", 51120), 150) } }.isSuccess
                    if (!listening) break
                    Thread.sleep(50)
                }
                SwingUtilities.invokeAndWait { }
                assertTrue(engine.accounts("trae").any { it.uid == uid && it.nickname == nickname })
            }
            assertEquals(setOf("test-user-a", "test-user-b"), engine.accounts("trae").map { it.uid }.toSet())
            assertEquals(2, engine.accounts("trae").size, "Repeat authorization must update, rather than duplicate, the same UID")
            SwingUtilities.invokeAndWait {
                val table = descendants(window!!).filterIsInstance<JTable>().first { it.getColumnName(1) == "账号" }
                assertEquals(2, table.rowCount)
            }
            val restarted = GatewayEngine(WindowsKeyValueStore(dir.resolve("store")))
            assertEquals(setOf("test-user-a", "test-user-b"), restarted.accounts("trae").map { it.uid }.toSet())
            assertTrue(restarted.accounts("trae").any { it.nickname == "Account A updated" })
            restarted.stop()
            assertTrue(opened.isEmpty(), "Unexpected extra browser page")
        } finally {
            SwingUtilities.invokeAndWait { window?.dispose() }
            engine.stopCallbackServer()
            engine.stop()
            dir.toFile().deleteRecursively()
        }
    }

    private fun query(uri: URI): Map<String, String> = uri.rawQuery.split('&').associate {
        val parts = it.split('=', limit = 2)
        parts[0] to URLDecoder.decode(parts[1], "UTF-8")
    }

    private fun descendants(component: Component): Sequence<Component> = sequence {
        yield(component)
        if (component is Container) component.components.forEach { yieldAll(descendants(it)) }
    }
}
