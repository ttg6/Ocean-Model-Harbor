package dev.aigw.core.usage

import dev.aigw.core.InMemoryKeyValueStore
import dev.aigw.core.gateway.GatewayEngine
import dev.aigw.core.pool.AccountStatus
import dev.aigw.core.provider.*
import java.math.BigDecimal
import kotlin.test.*

class TokenBudgetStoreTest {
    private fun account(uid: String = "a", enabled: Boolean = true, disabled: Boolean = false, provider: String = "trae") =
        AccountStatus(provider, uid, uid, 0, false, "", "", disabled, enabled)
    private fun configure(budget: TokenBudgetStore, source: TokenBudgetSource = TokenBudgetSource.ENTITLEMENT, rate: String = "1000.25") {
        budget.saveSettings(TokenBudgetSettings("trae/test-model", source, mapOf(
            "trae/test-model" to TokenConversion(BigDecimal(rate), "本地测试参考系数，非官方比例"),
        ), automatic = false))
    }

    @Test fun `estimate increases with credits decreases with spending and survives restart`() {
        val store = InMemoryKeyValueStore()
        var budget = TokenBudgetStore(store)
        configure(budget)
        budget.observe("a", TokenBudgetSource.ENTITLEMENT, 100, true, 1000)
        assertEquals(100025L, budget.summary(listOf(account()), true).estimatedTokens)
        budget.observe("a", TokenBudgetSource.ENTITLEMENT, 200, true, 2000)
        assertEquals(200050L, budget.summary(listOf(account()), true).estimatedTokens)
        budget = TokenBudgetStore(store)
        assertEquals(2000L, budget.summary(listOf(account()), true).observedAtMillis)
        budget.observe("a", TokenBudgetSource.ENTITLEMENT, 40, true, 3000)
        assertEquals(40010L, budget.summary(listOf(account()), true).estimatedTokens)
        budget.observe("a", TokenBudgetSource.ENTITLEMENT, 0, true, 4000)
        assertEquals(0L, budget.summary(listOf(account()), true).estimatedTokens)
    }

    @Test fun `unknown balance and unconfigured models never display fabricated tokens`() {
        val budget = TokenBudgetStore(InMemoryKeyValueStore())
        assertNull(budget.summary(listOf(account()), true).credits)
        budget.observe("a", TokenBudgetSource.ENTITLEMENT, 100, true, 1000)
        assertNull(budget.summary(listOf(account()), true).estimatedTokens)
        configure(budget)
        budget.saveSettings(budget.settings().copy(modelId = "trae/another-model"))
        assertNull(budget.summary(listOf(account()), true).estimatedTokens)
    }

    @Test fun `entitlement refresh cannot overwrite the billing snapshot`() {
        val budget = TokenBudgetStore(InMemoryKeyValueStore())
        configure(budget, TokenBudgetSource.BILLING)
        budget.observe("a", TokenBudgetSource.ENTITLEMENT, 1000, true, 1000)
        budget.observe("a", TokenBudgetSource.BILLING, 900, true, 2000)
        budget.observe("a", TokenBudgetSource.ENTITLEMENT, 1000, true, 3000)
        assertEquals(900L, budget.summary(listOf(account()), true).credits)
        budget.saveSettings(budget.settings().copy(source = TokenBudgetSource.ENTITLEMENT))
        assertEquals(1000L, budget.summary(listOf(account()), true).credits)
    }

    @Test fun `only eligible TRAE accounts contribute and incomplete totals are marked`() {
        val budget = TokenBudgetStore(InMemoryKeyValueStore())
        configure(budget)
        budget.observe("a", TokenBudgetSource.ENTITLEMENT, 10, true, 1000)
        budget.observe("b", TokenBudgetSource.ENTITLEMENT, 100, true, 2000)
        val accounts = listOf(account(), account("b", enabled = false), account("c"), account("d", disabled = true), account("e", provider = "loomy"))
        val summary = budget.summary(accounts, true)
        assertEquals(10L, summary.credits)
        assertEquals(2, summary.eligibleAccounts)
        assertEquals(1, summary.knownAccounts)
        assertTrue(summary.partial)
        assertNull(budget.summary(accounts, false).credits)
        budget.removeAccount("a")
        assertNull(budget.summary(listOf(account()), true).credits)
    }

    @Test fun `failed refresh retains previous balance and time but is visibly marked`() {
        val store = InMemoryKeyValueStore()
        val budget = TokenBudgetStore(store)
        budget.saveSettings(budget.settings().copy(source = TokenBudgetSource.ENTITLEMENT))
        budget.observe("a", TokenBudgetSource.ENTITLEMENT, 100, true, 1000)
        budget.observe("a", TokenBudgetSource.ENTITLEMENT, 0, false, 2000)
        val result = TokenBudgetStore(store).summary(listOf(account()), true)
        assertEquals(100L, result.credits)
        assertEquals(1000L, result.observedAtMillis)
        assertEquals(1, result.refreshFailures)
        budget.observe("a", TokenBudgetSource.ENTITLEMENT, 150, true, 3000)
        assertEquals(0, budget.summary(listOf(account()), true).refreshFailures)
    }

    @Test fun `legacy balances do not acquire invented refresh times or change ledger`() {
        val budget = TokenBudgetStore(InMemoryKeyValueStore())
        val legacy = account().copy(credits = 200, creditsKnown = true, detail = "来自对话计费回报")
        budget.importLegacy(listOf(legacy))
        budget.saveSettings(budget.settings().copy(source = TokenBudgetSource.ENTITLEMENT))
        assertNull(budget.summary(listOf(legacy), true).credits)
        budget.saveSettings(budget.settings().copy(source = TokenBudgetSource.BILLING))
        assertEquals(200L, budget.summary(listOf(legacy), true).credits)
        assertNull(budget.summary(listOf(legacy), true).observedAtMillis)
    }

    @Test fun `clearing call usage leaves conversion settings and balances intact`() {
        val store = InMemoryKeyValueStore()
        val budget = TokenBudgetStore(store)
        configure(budget)
        budget.observe("a", TokenBudgetSource.ENTITLEMENT, 100, true, 1000)
        CallLogStore(store).clear()
        val restored = TokenBudgetStore(store)
        assertEquals(budget.settings(), restored.settings())
        assertEquals(100025L, restored.summary(listOf(account()), true).estimatedTokens)
    }

    @Test fun `invalid conversion rates are rejected and arithmetic is overflow safe`() {
        val budget = TokenBudgetStore(InMemoryKeyValueStore())
        assertFailsWith<IllegalArgumentException> { configure(budget, rate = "0") }
        assertFailsWith<IllegalArgumentException> { configure(budget, rate = "-1") }
        assertFailsWith<IllegalArgumentException> { configure(budget, rate = "1000000000001") }
        assertFailsWith<IllegalArgumentException> { budget.saveSettings(TokenBudgetSettings("loomy/test")) }
        assertFailsWith<IllegalArgumentException> {
            budget.saveSettings(TokenBudgetSettings("trae/test", conversions = mapOf("trae/test" to TokenConversion(BigDecimal.ONE, ""))))
        }
        configure(budget)
        budget.observe("a", TokenBudgetSource.ENTITLEMENT, Long.MAX_VALUE, true, 1000)
        budget.observe("b", TokenBudgetSource.ENTITLEMENT, Long.MAX_VALUE, true, 1000)
        assertEquals(Long.MAX_VALUE, budget.summary(listOf(account(), account("b")), true).estimatedTokens)
        configure(budget, rate = "0.1")
        assertEquals(1844674407370955161L, budget.summary(listOf(account(), account("b")), true).estimatedTokens)
    }

    @Test fun `engine records real refresh and billing events in independent persistent ledgers`() {
        val store = InMemoryKeyValueStore()
        val engine = GatewayEngine(store, nowMillis = { 1234L })
        var balance = 100L
        engine.registry.register(object : Provider {
            override val id = "trae"
            override val displayName = "测试"
            override val authKind = AuthKind.NONE
            override fun listModels(account: ProviderAccount?) = ProviderModelCatalogView(emptyList(), true, "")
            override fun resolveModel(requested: String) = requested
            override fun classify(status: Int, body: String) = UpstreamError(ErrorKind.CLIENT, body)
            override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall = error("No model request needed")
            override fun creditInfo(account: ProviderAccount) = CreditInfo(balance)
        })
        engine.pool.upsert(ProviderAccount("trae", "a", "测试账号", "{}"))
        engine.refreshCredits("trae", "a")
        engine.hooks.onBilling("trae", "a", 70)
        balance = 150
        engine.refreshCredits("trae", "a")
        configure(engine.tokenBudgetStore)
        assertEquals(150L, engine.tokenBudgetStore.summary(engine.accounts(), true).credits)
        engine.tokenBudgetStore.saveSettings(engine.tokenBudgetStore.settings().copy(source = TokenBudgetSource.BILLING))
        assertEquals(70L, engine.tokenBudgetStore.summary(engine.accounts(), true).credits)
        val restarted = GatewayEngine(store)
        assertEquals(70L, restarted.tokenBudgetStore.summary(restarted.accounts(), true).credits)
        engine.removeAccount("trae", "a")
        assertNull(engine.tokenBudgetStore.summary(listOf(account()), true).credits)
    }
}
