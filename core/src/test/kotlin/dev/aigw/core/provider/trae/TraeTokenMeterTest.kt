package dev.aigw.core.provider.trae

import com.sun.net.httpserver.HttpServer
import dev.aigw.core.InMemoryKeyValueStore
import dev.aigw.core.gateway.GatewayEngine
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderHooks
import java.net.InetSocketAddress
import java.time.Instant
import kotlin.test.*

class TraeTokenMeterTest {
    private val now = Instant.parse("2026-10-13T04:00:00Z").toEpochMilli()
    private fun sse(balance: Long, done: Boolean = true, usage: Boolean = true): String = buildString {
        append("event: output\ndata: {\"response\":\"你好\"}\n\n")
        if (usage) append("event: token_usage\ndata: {\"prompt_tokens\":1500,\"completion_tokens\":500,\"total_tokens\":2000}\n\n")
        append("event: notify_usage\ndata: {\"cn_credits_remain_info\":{\"ide_credits\":$balance}}\n\n")
        if (done) append("event: done\ndata: {\"finish_reason\":\"stop\"}\n\n")
    }

    @Test fun `real Trae HTTP streams and aggregated replies feed automatic calculation without manual rates`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var balance = 100L
        var done = true
        server.createContext("/") { exchange ->
            val bytes = sse(balance, done).toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }; exchange.close()
        }
        server.start()
        val engine = GatewayEngine(InMemoryKeyValueStore(), nowMillis = { now })
        val account = ProviderAccount("trae", "a", "测试", TraeAccount("a", "test-access", "", Long.MAX_VALUE,
            "测试", "", "a".repeat(64), "1700000000000000").toJson().toString())
        engine.pool.upsert(account)
        val provider = TraeProvider(InMemoryKeyValueStore(), { TraeVersion() }, { "test" }, engine.hooks,
            chatHost = "http://127.0.0.1:${server.address.port}")
        try {
            provider.openChat(account, """{"model":"test","stream":false,"messages":[{"role":"user","content":"hi"}]}""").close()
            assertEquals("trae/test", engine.tokenBudgetStore.settings().modelId)
            assertNull(engine.tokenBudgetSummary().estimatedTokens)
            balance = 98
            val call = provider.openChat(account, """{"model":"test","stream":true,"messages":[{"role":"user","content":"hi"}]}""")
            val received = call.stream!!.bufferedReader().readText()
            call.close()
            assertTrue(received.contains("你好")); assertTrue(received.contains("[DONE]"))
            assertEquals(98000L, engine.tokenBudgetSummary().estimatedTokens)
            assertTrue(engine.tokenBudgetStore.settings().conversions.isEmpty())
            done = false; balance = 90
            provider.openChat(account, """{"model":"test","stream":false,"messages":[{"role":"user","content":"hi"}]}""").close()
            assertEquals(1, engine.tokenBudgetRate("a", "trae/test")!!.calls)
        } finally { server.stop(0) }
    }

    @Test fun `only explicit done with usage and billing is a complete sample and usage is not double counted`() {
        var result: List<Any?>? = null
        val hooks = ProviderHooks(onTokenSampleStart = { _, _, _ -> 1L }, onTokenSampleFinish = { _, tokens, credits, complete -> result = listOf(tokens, credits, complete) })
        val meter = TraeTokenMeter(hooks, "a", "test")
        val parser = SoloSseParser()
        (sse(98) + "event: token_usage\ndata: {\"total_tokens\":2000}\n\n").lineSequence().forEach { parser.feed(it)?.let(meter::accept) }
        meter.finish(true); meter.finish(false)
        assertEquals(listOf(2000L, 98L, true), result)
        val missing = TraeTokenMeter(hooks, "a", "test")
        val incomplete = SoloSseParser()
        sse(90, done = false).lineSequence().forEach { incomplete.feed(it)?.let(missing::accept) }
        missing.finish(true)
        assertEquals(listOf(2000L, 90L, false), result)
        val noUsage = TraeTokenMeter(hooks, "a", "test")
        val noUsageParser = SoloSseParser()
        sse(90, usage = false).lineSequence().forEach { noUsageParser.feed(it)?.let(noUsage::accept) }
        noUsage.finish(true)
        assertEquals(listOf(null, 90L, true), result) // calculator rejects missing tokens even with done
    }
}
