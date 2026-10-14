package dev.aigw.core.usage

import dev.aigw.core.InMemoryKeyValueStore
import dev.aigw.core.pool.AccountStatus
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.*

class AutomaticTokenCalculatorTest {
    @Test fun `missing billing and usage have distinct persistent diagnostics and removal clears status`() {
        val store = InMemoryKeyValueStore()
        val calc = AutomaticTokenCalculator(store)
        call(calc, start, null)
        assertTrue(calc.status("a").contains("未回报对话积分余额"))
        val restored = AutomaticTokenCalculator(store)
        assertEquals(calc.status("a"), restored.status("a"))
        assertNull(restored.rate("a", "trae/test", start + 200))
        call(restored, start + 1000, 100, tokens = null)
        assertTrue(restored.status("a").contains("未回报有效 Tokens"))
        call(restored, start + 2000, 100)
        assertTrue(restored.status("a").contains("已建立余额基准"))
        call(restored, start + 3000, 99)
        assertTrue(restored.status("a").contains("达到 2 积分"))
        restored.removeAccount("a")
        assertEquals("尚未收到新版的完整调用数据", AutomaticTokenCalculator(store).status("a"))
    }

    private val start = Instant.parse("2026-10-13T04:00:00Z").toEpochMilli()
    private fun call(calc: AutomaticTokenCalculator, time: Long, balance: Long?, tokens: Long? = 2000,
        uid: String = "a", model: String = "trae/test", complete: Boolean = true) {
        calc.finish(calc.begin(uid, model, time), tokens, balance, complete, time + 100)
    }
    private fun account(uid: String) = AccountStatus("trae", uid, uid, 0, false, "", "", false, true)

    @Test fun `first call establishes baseline then usage divided by spend is calculated automatically`() {
        val calc = AutomaticTokenCalculator(InMemoryKeyValueStore())
        call(calc, start, 100)
        assertNull(calc.rate("a", "trae/test", start + 100))
        call(calc, start + 1000, 98)
        val rate = calc.rate("a", "trae/test", start + 1200)!!
        assertEquals(BigDecimal("1000.00000000"), rate.tokensPerCredit)
        assertEquals(1, rate.calls)
    }

    @Test fun `zero and one point drops accumulate all tokens until the balance resolution is sufficient`() {
        val calc = AutomaticTokenCalculator(InMemoryKeyValueStore())
        call(calc, start, 100)
        call(calc, start + 1000, 100, tokens = 300)
        call(calc, start + 2000, 99, tokens = 400)
        assertNull(calc.rate("a", "trae/test", start + 2200))
        call(calc, start + 3000, 98, tokens = 500)
        val rate = calc.rate("a", "trae/test", start + 3200)!!
        assertEquals(BigDecimal("600.00000000"), rate.tokensPerCredit)
        assertEquals(3, rate.calls)
    }

    @Test fun `parallel calls on same account are excluded while other accounts remain independent`() {
        val calc = AutomaticTokenCalculator(InMemoryKeyValueStore())
        call(calc, start, 100)
        val one = calc.begin("a", "trae/test", start + 1000)
        val two = calc.begin("a", "trae/test", start + 1001)
        calc.finish(one, 2000, 98, true, start + 1100)
        calc.finish(two, 3000, 95, true, start + 1200)
        assertNull(calc.rate("a", "trae/test", start + 1300))
        call(calc, start + 2000, 95)
        call(calc, start + 3000, 93)
        assertNotNull(calc.rate("a", "trae/test", start + 3200))
        call(calc, start, 100, uid = "b")
        call(calc, start + 1000, 96, uid = "b")
        assertEquals(BigDecimal("500.00000000"), calc.rate("b", "trae/test", start + 3200)!!.tokensPerCredit)
    }

    @Test fun `rewards failures missing usage missing billing and cancellation do not contaminate samples`() {
        for ((tokens, balance, complete) in listOf(Triple(2000L, 120L, true), Triple(2000L, 98L, false),
            Triple(null, 98L, true), Triple(2000L, null, true))) {
            val calc = AutomaticTokenCalculator(InMemoryKeyValueStore())
            call(calc, start, 100)
            call(calc, start + 1000, balance, tokens, complete = complete)
            assertNull(calc.rate("a", "trae/test", start + 1200))
        }
        val calc = AutomaticTokenCalculator(InMemoryKeyValueStore())
        call(calc, start, 100)
        val ticket = calc.begin("a", "trae/test", start + 1000)
        calc.invalidate("a")
        calc.finish(ticket, 2000, 98, true, start + 1100)
        assertNull(calc.rate("a", "trae/test", start + 1200))
    }

    @Test fun `models discount periods and long gaps are isolated and historical rates expire`() {
        val store = InMemoryKeyValueStore()
        val calc = AutomaticTokenCalculator(store)
        call(calc, start, 100)
        call(calc, start + 1000, 98)
        call(calc, start + 2000, 94, model = "trae/other")
        assertEquals(BigDecimal("500.00000000"), calc.rate("a", "trae/other", start + 2200)!!.tokensPerCredit)
        call(calc, start + 20 * 60 * 1000, 80, model = "trae/unseen")
        assertNull(calc.rate("a", "trae/unseen", start + 20 * 60 * 1000 + 200))
        val nighttime = Instant.parse("2026-10-13T15:00:00Z").toEpochMilli()
        assertNull(calc.rate("a", "trae/test", nighttime))
        assertNull(calc.rate("a", "trae/test", start + 2 * 86400000L))
        val restored = AutomaticTokenCalculator(store)
        assertNotNull(restored.rate("a", "TRAE/TEST", start + 2300))
        // Baselines must not survive downtime; first post-restart call can't be a sample.
        call(restored, start + 3000, 10, model = "trae/new")
        assertNull(restored.rate("a", "trae/new", start + 3200))
        restored.removeAccount("a")
        assertNull(restored.rate("a", "trae/test", start + 3200))
    }

    @Test fun `multi account totals use each accounts own measured rate and report partial coverage`() {
        val budget = TokenBudgetStore(InMemoryKeyValueStore())
        fun sample(uid: String, initial: Long, final: Long) {
            call(budget.calculator, start, initial, uid = uid)
            call(budget.calculator, start + 1000, final, uid = uid)
            budget.observe(uid, TokenBudgetSource.BILLING, final, true, start + 1100)
        }
        budget.saveSettings(budget.settings().copy(modelId = "trae/test"))
        sample("a", 100, 98)
        sample("b", 100, 96)
        budget.observe("c", TokenBudgetSource.BILLING, 100, true, start + 1100)
        val result = budget.summary(listOf(account("a"), account("b"), account("c")), true, start + 1200)
        assertEquals(294L, result.credits)
        assertEquals(146000L, result.estimatedTokens)
        assertEquals(2, result.estimatedAccounts)
        assertEquals(2, result.automaticCalls)
        budget.observe("a", TokenBudgetSource.BILLING, 198, true, start + 2000)
        assertEquals(246000L, budget.summary(listOf(account("a"), account("b"), account("c")), true, start + 2200).estimatedTokens)
    }
}
