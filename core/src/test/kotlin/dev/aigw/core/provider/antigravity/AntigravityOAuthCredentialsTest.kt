package dev.aigw.core.provider.antigravity

import dev.aigw.core.InMemoryKeyValueStore
import dev.aigw.core.gateway.GatewayEngine
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AntigravityOAuthCredentialsTest {
    @Test
    fun `saved credentials override environment while blank values fall back`() {
        val environment = mapOf(
            AntigravityOAuthCredentials.CLIENT_ID_ENV to " env-client ",
            AntigravityOAuthCredentials.CLIENT_SECRET_ENV to " env-secret ",
        )
        val fallback = AntigravityOAuthCredentials.fromOptions(emptyMap(), environment::get)
        assertEquals("env-client", fallback.clientId)
        assertEquals("env-secret", fallback.clientSecret)
        val saved = AntigravityOAuthCredentials.fromOptions(mapOf(
            AntigravityOAuthCredentials.CLIENT_ID_OPTION to " saved-client ",
            AntigravityOAuthCredentials.CLIENT_SECRET_OPTION to " ",
        ), environment::get)
        assertEquals("saved-client", saved.clientId)
        assertEquals("env-secret", saved.clientSecret)
        assertFalse(saved.toString().contains("env-secret"))
    }

    @Test
    fun `missing configuration fails before the callback port is opened`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        engine.registry.register(AntigravityProvider(oauthCredentials = { AntigravityOAuthCredentials("", "") }))
        val error = assertFailsWith<IllegalStateException> { engine.beginBrowserLogin(AntigravityProvider.ID) }
        assertTrue(error.message.orEmpty().contains("OAuth 配置"))
        ServerSocket(AntigravityProvider.CALLBACK_PORT).use { assertTrue(it.isBound) }
    }

    @Test
    fun `builtin provider uses updated persisted configuration and never puts secret in authorization URL`() {
        val store = InMemoryKeyValueStore()
        val engine = GatewayEngine(store)
        val options = mapOf(
            AntigravityOAuthCredentials.CLIENT_ID_OPTION to "client with space",
            AntigravityOAuthCredentials.CLIENT_SECRET_OPTION to "private-example-secret",
        )
        val settings = engine.providerSettings(AntigravityProvider.ID)
        engine.updateProviderSettings(AntigravityProvider.ID, settings.copy(options = options))
        val provider = engine.registry.get(AntigravityProvider.ID) as AntigravityProvider
        val first = provider.buildAuthUrl()
        assertTrue(first.contains("client_id=client+with+space"))
        assertFalse(first.contains("private-example-secret"))
        val restored = GatewayEngine(store)
        assertEquals(options, restored.providerSettings(AntigravityProvider.ID).options)
        val updated = options + (AntigravityOAuthCredentials.CLIENT_ID_OPTION to "replacement-client")
        engine.updateProviderSettings(AntigravityProvider.ID, settings.copy(options = updated))
        assertTrue(provider.buildAuthUrl().contains("client_id=replacement-client"))
    }
}
