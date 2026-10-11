package dev.aigw.windows

import dev.aigw.core.gateway.GatewayEngine
import java.awt.Component
import java.awt.Container
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.JButton
import javax.swing.JTable
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TraeInvitationLoginTest {
    @Test fun `invitation registration button opens invitation before official callback authorization`() = exercise(true)
    @Test fun `canceling invitation does not open official authorization`() = exercise(false)

    private fun exercise(continueAuthorization: Boolean) {
        val dir = Files.createTempDirectory("aigw-invite-ui-")
        val engine = GatewayEngine(WindowsKeyValueStore(dir.resolve("store")))
        val opened = CopyOnWriteArrayList<String>()
        val confirmed = CountDownLatch(1)
        val authorized = CountDownLatch(1)
        var window: GatewayWindow? = null
        try {
            SwingUtilities.invokeAndWait {
                window = GatewayWindow(engine, dir.toFile(), browserOpener = { url ->
                    opened.add(url)
                    if (opened.size == 2) authorized.countDown()
                }, confirmTraeInvitation = {
                    // A slow registration must not spend the local callback's five-minute lifetime.
                    assertEquals(listOf(GatewayWindow.TRAE_INVITE_URL), opened.toList())
                    confirmed.countDown()
                    continueAuthorization
                })
                window!!.pack()
                val components = descendants(window!!).toList()
                val table = components.filterIsInstance<JTable>().first { it.getColumnName(1) == "标识" }
                val row = (0 until table.rowCount).first { table.getValueAt(it, 1) == "trae" }
                table.setRowSelectionInterval(row, row)
                components.filterIsInstance<JButton>().first { it.text == "TRAE 邀请注册" }.doClick()
            }
            assertTrue(confirmed.await(10, TimeUnit.SECONDS), "Invitation confirmation was not reached")
            if (continueAuthorization) {
                assertTrue(authorized.await(10, TimeUnit.SECONDS), "Official authorization was not opened")
                val uri = URI(opened[1])
                assertEquals("https", uri.scheme)
                assertEquals("www.trae.cn", uri.host)
                assertEquals("/authorization", uri.path)
                val query = uri.rawQuery.split('&').associate {
                    val parts = it.split('=', limit = 2)
                    parts[0] to URLDecoder.decode(parts[1], "UTF-8")
                }
                assertEquals("http://127.0.0.1:51120/authorize", query["auth_callback_url"])
            } else {
                // Flush the event handler after the cancellation decision.
                SwingUtilities.invokeAndWait { }
                assertEquals(listOf(GatewayWindow.TRAE_INVITE_URL), opened.toList())
            }
        } finally {
            SwingUtilities.invokeAndWait { window?.dispose() }
            engine.stopCallbackServer()
            engine.stop()
            dir.toFile().deleteRecursively()
        }
    }

    private fun descendants(component: Component): Sequence<Component> = sequence {
        yield(component)
        if (component is Container) component.components.forEach { yieldAll(descendants(it)) }
    }
}
