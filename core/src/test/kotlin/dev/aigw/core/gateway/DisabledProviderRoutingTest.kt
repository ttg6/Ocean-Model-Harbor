package dev.aigw.core.gateway

import dev.aigw.core.InMemoryKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DisabledProviderRoutingTest {
    private fun engine(): GatewayEngine = GatewayEngine(InMemoryKeyValueStore()).apply {
        val configs = listOf("first", "second").map {
            CustomProviderConfig(it, it, "http://127.0.0.1:1/v1", listOf("fake-$it"), models = listOf("unique-desktop-model"))
        }
        settingsRepository.saveCustomProviders(configs)
        configs.forEach { syncCustomAccounts(it) }
        reloadCustomProviders()
        updateProviderSettings("custom:first", ProviderSettings(enabled = false))
    }

    @Test fun `disabled explicit provider is omitted while enabled equivalent remains available`() {
        val routes = engine().resolveCandidateRoutes("custom:first/unique-desktop-model")
        assertEquals(listOf("custom:second"), routes.map { it.providerId })
    }

    @Test fun `all disabled candidates produce no route`() {
        val engine = engine()
        engine.updateProviderSettings("custom:second", ProviderSettings(enabled = false))
        assertTrue(engine.resolveCandidateRoutes("custom:first/unique-desktop-model").isEmpty())
    }
}
