package dev.aigw.windows

import com.sun.net.httpserver.HttpServer
import dev.aigw.core.gateway.CustomProviderConfig
import dev.aigw.core.gateway.GatewayEngine
import dev.aigw.core.gateway.GatewayHttpServer
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.nio.file.Files
import kotlin.test.*

class WindowsGatewayIntegrationTest {
    @Test fun `encrypted Windows accounts forward authenticated JSON and SSE and persist through restart`() {
        val dir = Files.createTempDirectory("aigw-integration-")
        val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        upstream.createContext("/v1/chat/completions") { exchange ->
            assertEquals("Bearer fake-upstream-key", exchange.requestHeaders.getFirst("Authorization"))
            val request = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            assertTrue(request.contains("\"model\":\"test-model\""), request)
            val streaming = request.contains("\"stream\":true")
            val body = if (streaming) "data: {\"id\":\"test\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Windows OK\"},\"finish_reason\":null}]}\n\ndata: [DONE]\n\n"
                else """{"id":"test","object":"chat.completion","model":"test-model","choices":[{"index":0,"message":{"role":"assistant","content":"Windows OK"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":2,"total_tokens":3}}"""
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", if (streaming) "text/event-stream" else "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        upstream.start()
        val engine = GatewayEngine(WindowsKeyValueStore(dir))
        val config = CustomProviderConfig("local-test", "测试上游", "http://127.0.0.1:${upstream.address.port}/v1", listOf("fake-upstream-key"), models = listOf("test-model"))
        engine.settingsRepository.saveCustomProviders(listOf(config)); engine.syncCustomAccounts(config); engine.reloadCustomProviders()
        engine.updateSettings(engine.settings().copy(apiKey = "fake-gateway-key", allowNoKey = false, exposeLan = false))
        val gateway = GatewayHttpServer(engine, "127.0.0.1", 0)
        gateway.start(0, true)
        try {
            fun call(path: String, body: String? = null, key: Boolean = true): Pair<Int, String> {
                val connection = URL("http://127.0.0.1:${gateway.listeningPort}$path").openConnection() as HttpURLConnection
                connection.connectTimeout = 5000; connection.readTimeout = 10000
                if (key) connection.setRequestProperty("Authorization", "Bearer fake-gateway-key")
                if (body != null) {
                    connection.requestMethod = "POST"; connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val status = connection.responseCode
                val text = (if (status >= 400) connection.errorStream else connection.inputStream).use { it.readBytes().toString(Charsets.UTF_8) }
                connection.disconnect(); return status to text
            }
            assertEquals(401, call("/v1/models", key = false).first)
            assertTrue(call("/v1/models").second.contains("custom:local-test/test-model"))
            for (streaming in listOf(false, true)) {
                val (status, body) = call("/v1/chat/completions", """{"model":"custom:local-test/test-model","stream":$streaming,"messages":[{"role":"user","content":"ping"}]}""")
                assertEquals(200, status, body)
                assertTrue(body.contains("Windows OK"), body)
                if (streaming) assertTrue(body.contains("[DONE]"), body)
            }
            val restart = GatewayEngine(WindowsKeyValueStore(dir))
            assertEquals(1, restart.accounts(config.providerId).size)
            assertEquals("fake-gateway-key", restart.settings().apiKey)
            assertTrue(restart.callLogStore.totalStats().requests >= 1)
        } finally {
            gateway.stop(); engine.stop(); upstream.stop(0); dir.toFile().deleteRecursively()
        }
    }
}
