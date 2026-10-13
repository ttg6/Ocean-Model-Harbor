package dev.aigw.windows

import com.formdev.flatlaf.FlatLightLaf
import dev.aigw.core.gateway.GatewayEngine
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.usage.TokenBudgetSource
import java.awt.Component
import java.awt.Container
import java.awt.Window
import java.nio.file.Files
import java.time.Instant
import javax.swing.*
import kotlin.test.*

class TokenBudgetPanelTest {
    private fun descendants(c: Component): List<Component> = listOf(c) + if (c is Container) c.components.flatMap { descendants(it) } else emptyList()

    @Test fun `automatic mode calculates from paired calls without entering a conversion and survives restart`() {
        val dir = Files.createTempDirectory("ocean-auto-budget-")
        val now = Instant.parse("2026-10-13T04:00:00Z").toEpochMilli()
        val engine = GatewayEngine(WindowsKeyValueStore(dir.resolve("store")), nowMillis = { now })
        engine.pool.upsert(ProviderAccount("trae", "a", "自动测算测试", "{}"))
        fun sample(balance: Long) {
            val ticket = engine.tokenBudgetStore.calculator.begin("a", "trae/test", now)
            engine.tokenBudgetStore.observe("a", TokenBudgetSource.BILLING, balance, true, now)
            engine.tokenBudgetStore.completeSample(ticket, 2000, balance, true, now)
        }
        var window: GatewayWindow? = null
        try {
            sample(100)
            SwingUtilities.invokeAndWait {
                FlatLightLaf.setup()
                window = GatewayWindow(engine, dir.toFile()).apply { setSize(940, 650); isVisible = true }
                val controls = descendants(window!!)
                val panel = controls.filterIsInstance<TokenBudgetPanel>().single()
                fun amount() = controls.filterIsInstance<JLabel>().single { it.name == "token-budget-remaining" }.text
                assertEquals("采集样本中", amount())
                assertTrue(controls.filterIsInstance<JCheckBox>().single { it.name == "token-budget-automatic" }.isSelected)
                assertFalse(controls.filterIsInstance<JButton>().single { it.text == "高级参考" }.isEnabled)
                sample(98); panel.refresh()
                assertEquals("约 9.80 万", amount())
                sample(96); panel.refresh()
                assertEquals("约 9.60 万", amount())
                assertTrue(engine.tokenBudgetStore.settings().conversions.isEmpty())
                engine.pool.upsert(ProviderAccount("trae", "b", "缺少样本", "{}"))
                engine.tokenBudgetStore.observe("b", TokenBudgetSource.BILLING, 50, true, now)
                panel.refresh()
                assertTrue(controls.filterIsInstance<JTextArea>().single { it.name == "token-budget-details" }.text.contains("仅估算已有样本的账号"))
            }
            val restored = GatewayEngine(WindowsKeyValueStore(dir.resolve("store")), nowMillis = { now })
            assertEquals(96000L, restored.tokenBudgetSummary().estimatedTokens)
        } finally {
            SwingUtilities.invokeAndWait { window?.dispose() }
            engine.stop(); dir.toFile().deleteRecursively()
        }
    }

    @Test fun `dashboard configures persistent estimates reacts to balance changes and marks incomplete data`() {
        val dir = Files.createTempDirectory("ocean-token-budget-")
        val engine = GatewayEngine(WindowsKeyValueStore(dir.resolve("store")))
        engine.tokenBudgetStore.saveSettings(engine.tokenBudgetStore.settings().copy(automatic = false, source = TokenBudgetSource.ENTITLEMENT))
        engine.pool.upsert(ProviderAccount("trae", "a", "测试账号", "{}"))
        engine.tokenBudgetStore.observe("a", TokenBudgetSource.ENTITLEMENT, 100, true, 1000)
        var window: GatewayWindow? = null
        try {
            SwingUtilities.invokeAndWait {
                FlatLightLaf.setup()
                window = GatewayWindow(engine, dir.toFile()).apply { setSize(940, 650); isVisible = true }
                val controls = descendants(window!!)
                val panel = controls.filterIsInstance<TokenBudgetPanel>().single()
                fun label(name: String) = controls.filterIsInstance<JLabel>().single { it.name == name }
                assertEquals("暂无法估算", label("token-budget-remaining").text)
                val selector = controls.filterIsInstance<JComboBox<*>>().single { it.name == "token-budget-model" }
                selector.selectedItem = "trae/test-model"
                val timer = Timer(100) {
                    val dialog = Window.getWindows().filterIsInstance<JDialog>().firstOrNull { it.isShowing && it.title == "配置参考换算" }
                    if (dialog != null) {
                        val form = descendants(dialog)
                        form.filterIsInstance<JTextField>().single { it.name == "token-budget-coefficient" }.text = "1000"
                        form.filterIsInstance<JTextField>().single { it.name == "token-budget-basis" }.text = "测试参考，非官方比例"
                        form.filterIsInstance<JButton>().first { it.text in listOf("确定", "OK") }.doClick()
                    }
                }.apply { isRepeats = false; start() }
                controls.filterIsInstance<JButton>().single { it.text == "高级参考" }.doClick()
                timer.stop()
                assertEquals("约 10.00 万", label("token-budget-remaining").text)
                engine.tokenBudgetStore.observe("a", TokenBudgetSource.ENTITLEMENT, 50, true, 2000)
                panel.refresh()
                assertEquals("约 5.00 万", label("token-budget-remaining").text)
                engine.tokenBudgetStore.observe("a", TokenBudgetSource.BILLING, 20, true, 3000)
                val source = controls.filterIsInstance<JComboBox<*>>().single { it.name == "token-budget-source" }
                source.selectedItem = TokenBudgetSource.BILLING
                assertEquals("约 2.00 万", label("token-budget-remaining").text)
                engine.pool.upsert(ProviderAccount("trae", "b", "余额未知", "{}"))
                panel.refresh()
                val details = controls.filterIsInstance<JTextArea>().single { it.name == "token-budget-details" }
                assertTrue(details.text.contains("已知 1/2"))
                assertTrue(details.text.contains("部分余额未知"))
                assertEquals("0", label("token-budget-used").text)
            }
            val restored = GatewayEngine(WindowsKeyValueStore(dir.resolve("store")))
            assertEquals(20000L, restored.tokenBudgetStore.summary(restored.accounts(), true).estimatedTokens)
            assertEquals("测试参考，非官方比例", restored.tokenBudgetStore.settings().conversions["trae/test-model"]?.basis)
        } finally {
            SwingUtilities.invokeAndWait { window?.dispose() }
            engine.stop(); dir.toFile().deleteRecursively()
        }
    }
}
