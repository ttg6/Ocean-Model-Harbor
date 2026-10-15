package dev.aigw.core.gateway

import com.google.gson.JsonParser
import dev.aigw.core.provider.ProviderModel
import dev.aigw.core.provider.RoutedModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenAiApiTest {

    private fun routed(providerId: String, id: String, context: Long = 0, vision: Boolean = false) =
        RoutedModel(providerId, providerId, ProviderModel(id = id, name = id, contextWindow = context, supportsVision = vision))

    @Test
    fun `模型 id 带 provider 前缀且标注归属`() {
        val list = listOf(
            routed("trae", "glm-5.2", context = 131_072),
            routed("loomy", "MiniMax-M2.5"),
        )
        val body = JsonParser.parseString(OpenAiApi.modelList(list)).asJsonObject
        assertEquals("list", body.get("object").asString)

        val data = body.getAsJsonArray("data")
        assertEquals(2, data.size())

        val first = data[0].asJsonObject
        assertEquals("trae/glm-5.2", first.get("id").asString)
        assertEquals("trae", first.get("owned_by").asString)
        assertEquals(131_072L, first.get("context_length").asLong)

        val second = data[1].asJsonObject
        assertEquals("loomy/MiniMax-M2.5", second.get("id").asString)
        assertEquals("loomy", second.get("owned_by").asString)
        assertTrue(!second.has("context_length"), "未知上下文长度不应编造")
    }

    @Test
    fun `视觉模型名称带后缀但 id 保持可调用`() {
        val body = JsonParser.parseString(OpenAiApi.modelList(listOf(routed("codebuddy-cn", "glm-5.3", 1_000_000, vision = true)))).asJsonObject
        val model = body.getAsJsonArray("data")[0].asJsonObject
        assertEquals("codebuddy-cn/glm-5.3", model.get("id").asString)
        assertEquals("glm-5.3（视觉）", model.get("name").asString)
        assertEquals("glm-5.3（视觉）", model.get("display_name").asString)
        assertEquals(true, model.get("supports_vision").asBoolean)
    }

    @Test
    fun `错误体是 OpenAI 结构`() {
        val body = JsonParser.parseString(OpenAiApi.errorBody("invalid_api_key", "缺少 Key")).asJsonObject
        val error = body.getAsJsonObject("error")
        assertEquals("缺少 Key", error.get("message").asString)
        assertEquals("invalid_api_key", error.get("code").asString)
        assertTrue(error.get("type").isJsonPrimitive)
    }
}
