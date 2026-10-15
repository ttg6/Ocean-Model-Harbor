package dev.aigw.core.provider.trae

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.str

/** 上游模型目录的一项。 */
data class TraeModel(
    val id: String,
    val name: String,
    val contextWindow: Long = 131_072,
    val supportsVision: Boolean = false,
)

/**
 * `get_detail_param` 的请求体构造与响应解析。
 * 响应结构：`{"config_info_list":[{"config_name","display_config":{"display_name"}}]}`
 */
internal object TraePayloadModels {

    fun requestBody(): String = JsonObject().apply {
        addProperty("function", TraeConstants.FUNCTION)
        add("config_names", null)
        addProperty("need_prompt", false)
        add("current_config_info", null)
        addProperty("poly_prompt", true)
        add("mode_type", null)
        add("agent_type", null)
    }.toString()

    fun parse(raw: String): List<TraeModel> {
        val root = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return emptyList()
        val list = root.get("config_info_list") as? JsonArray ?: return emptyList()
        val out = ArrayList<TraeModel>(list.size())
        for (element in list) {
            val cfg = element as? JsonObject ?: continue
            val id = cfg.str("config_name")
            if (id.isEmpty()) continue
            val displayConfig = cfg.objOrNull("display_config")
            val display = displayConfig?.str("display_name").orEmpty()
            val supportsVision = runCatching { displayConfig?.get("multimodal")?.asBoolean == true }.getOrDefault(false)
            out.add(
                TraeModel(
                    id = id,
                    name = display.ifEmpty { id },
                    // 新版 TRAE 在 Max/企业模型目录中会返回上下文字段；
                    // 没有字段时继续使用保守默认值，绝不把模型原生上限猜成 1M。
                    contextWindow = contextWindowOf(cfg, displayConfig),
                    supportsVision = supportsVision,
                ),
            )
        }
        return out
    }

    /**
     * 兼容 TRAE 不同目录版本的上下文字段命名。
     * 个人 SOLO 目录经常不返回这些字段，只有在上游明确声明时才覆盖默认值。
     */
    private fun contextWindowOf(root: JsonObject, displayConfig: JsonObject?): Long {
        val keys = listOf(
            "context_window", "contextWindow", "context_length", "contextLength",
            "max_context_length", "maxContextLength", "max_input_tokens", "maxInputTokens",
            "input_context_length", "inputContextLength",
        )
        val sources = listOfNotNull(root, displayConfig)
        for (source in sources) {
            for (key in keys) {
                val value = source.get(key) ?: continue
                val parsed = parseContextValue(value)
                if (parsed > 0) return parsed
            }
        }
        return TraeModel(id = "", name = "").contextWindow
    }

    private fun parseContextValue(value: com.google.gson.JsonElement): Long {
        if (value.isJsonPrimitive && value.asJsonPrimitive.isNumber) {
            return runCatching { value.asLong }.getOrDefault(0L)
        }
        val raw = runCatching { value.asString.trim().lowercase() }.getOrDefault("")
        val match = Regex("^(\\d+(?:\\.\\d+)?)\\s*(k|m)?$").find(raw) ?: return 0L
        val number = match.groupValues[1].toDoubleOrNull() ?: return 0L
        val multiplier = when (match.groupValues[2]) {
            "k" -> 1_000.0
            "m" -> 1_000_000.0
            else -> 1.0
        }
        return (number * multiplier).toLong()
    }
}
