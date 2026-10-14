package dev.aigw.core.provider.codebuddy

import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import dev.aigw.core.provider.ProviderAccount
import java.net.InetSocketAddress
import kotlin.test.*

class CodeBuddySseTest {
    private fun frame(delta: String, finish: String = "null") =
        """data: {"id":"reply-test","model":"kimi-k3-1","choices":[{"index":0,"delta":$delta,"finish_reason":$finish}]}"""
    private fun delta(line: String) = JsonParser.parseString(line.substring(5)).asJsonObject
        .getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("delta")

    @Test fun `empty tool arrays cannot split reasoning but actual tool deltas and metadata survive`() {
        val normalized = normalizeCodeBuddySseLine(frame("""{"reasoning_content":"分析","content":"","tool_calls":[]}"""))
        assertEquals("分析", delta(normalized).get("reasoning_content").asString)
        assertFalse(delta(normalized).has("tool_calls"))
        val actual = frame("""{"tool_calls":[{"index":0,"id":"call-test","type":"function","function":{"name":"test","arguments":"{}"}}]}""", "\"tool_calls\"")
        assertEquals(actual, normalizeCodeBuddySseLine(actual))
        val finish = frame("""{"content":"回答","tool_calls":[]}""", "\"stop\"")
        val parsed = JsonParser.parseString(normalizeCodeBuddySseLine(finish).substring(5)).asJsonObject
        assertEquals("reply-test", parsed.get("id").asString)
        assertEquals("stop", parsed.getAsJsonArray("choices")[0].asJsonObject.get("finish_reason").asString)
    }

    @Test fun `malformed data terminal frames comments and unrelated fields pass through unchanged`() {
        for (line in listOf("", ": heartbeat", "event: message", "data: [DONE]", "data: invalid", "data: []",
            frame("""{"tool_calls":null,"reasoning_content":"分析"}"""), frame("""{"content":"你好"}"""))) {
            assertEquals(line, normalizeCodeBuddySseLine(line))
        }
    }

    @Test fun `real provider HTTP streaming keeps one reasoning span while aggregate and tool calls remain complete`() {
        val fixture = listOf(frame("""{"role":"assistant","content":"","reasoning_content":"","tool_calls":[]}"""),
            frame("""{"reasoning_content":"先分析","content":"","tool_calls":[]}"""),
            frame("""{"reasoning_content":"再判断","content":"","tool_calls":[]}"""),
            frame("""{"content":"结果","reasoning_content":"","tool_calls":[]}"""),
            frame("""{"tool_calls":[{"index":0,"id":"call-test","type":"function","function":{"name":"test","arguments":"{}"}}]}""", "\"tool_calls\""),
            """data: {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":20,"total_tokens":30}}""",
            "data: [DONE]").joinToString("\n\n", postfix = "\n\n")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            val bytes = fixture.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }; exchange.close()
        }
        server.start()
        val provider = CodeBuddyProvider(chatHost = "http://127.0.0.1:${server.address.port}")
        val account = ProviderAccount("codebuddy", "test", "test", """{"accessToken":"test-access","domain":"codebuddy.cn"}""")
        try {
            val received = provider.openChat(account, """{"model":"kimi-k3-1","stream":true,"messages":[]}""").use { call ->
                assertEquals(200, call.status)
                call.stream!!.bufferedReader().readText()
            }
            assertTrue(received.endsWith("data: [DONE]\n\n"))
            var thinking = false
            var starts = 0
            var tools = 0
            for (line in received.lineSequence().filter { it.startsWith("data: {") }) {
                val obj = JsonParser.parseString(line.substring(5)).asJsonObject
                for (choice in obj.getAsJsonArray("choices")) {
                    val d = choice.asJsonObject.getAsJsonObject("delta")
                    if (!d.get("reasoning_content")?.asString.isNullOrEmpty()) { if (!thinking) starts++; thinking = true }
                    if (!d.get("content")?.asString.isNullOrEmpty() || d.get("tool_calls")?.isJsonNull == false) thinking = false
                    tools += d.getAsJsonArray("tool_calls")?.size() ?: 0
                }
            }
            assertEquals(1, starts, "Reasoning must remain one continuous span for compatible SDK clients")
            assertEquals(1, tools)
            val aggregate = provider.openChat(account, """{"model":"kimi-k3-1","stream":false,"messages":[]}""").use {
                JsonParser.parseString(it.aggregated).asJsonObject
            }
            val message = aggregate.getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("message")
            assertEquals("先分析再判断", message.get("reasoning_content").asString)
            assertEquals("结果", message.get("content").asString)
            assertEquals(1, message.getAsJsonArray("tool_calls").size())
            assertEquals(30, aggregate.getAsJsonObject("usage").get("total_tokens").asInt)
        } finally { server.stop(0) }
    }
}
