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
    val maxContextWindow: Long = 0,
    val supportsMaxMode: Boolean = false,
    val scene: String = TraeConstants.SECONDARY_FUNCTION,
) {
    val effectiveContextWindow: Long
        get() = if (supportsMaxMode && maxContextWindow > contextWindow) maxContextWindow else contextWindow
}

/**
 * `get_detail_param` 的请求体构造与响应解析。
 * 响应结构：`{"config_info_list":[{"config_name","display_config":{"display_name"}}]}`
 */
internal object TraePayloadModels {

    fun requestBody(function: String = TraeConstants.FUNCTION): String = JsonObject().apply {
        addProperty("function", function)
        add("config_names", null)
        addProperty("need_prompt", false)
        add("current_config_info", null)
        addProperty("poly_prompt", true)
        add("mode_type", null)
        add("agent_type", null)
    }.toString()

    fun parse(raw: String, scene: String = TraeConstants.FUNCTION): List<TraeModel> {
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
            val devContext = contextWindowOf(cfg, displayConfig)
            val maxContext = maxContextWindowOf(cfg, displayConfig, devContext)
            out.add(
                TraeModel(
                    id = id,
                    name = display.ifEmpty { id },
                    // 新版 TRAE 在 Max/企业模型目录中会返回上下文字段；
                    // 没有字段时继续使用保守默认值，绝不把模型原生上限猜成 1M。
                    contextWindow = devContext,
                    supportsVision = supportsVision,
                    maxContextWindow = maxContext,
                    supportsMaxMode = scene == TraeConstants.MAX_FUNCTION && maxContext > devContext && maxContext >= 1_000_000L,
                    scene = scene,
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
        val nested = listOfNotNull(root.objOrNull("context_window_tokens"), displayConfig?.objOrNull("context_window_tokens"))
            .mapNotNull { it.get("dev")?.let(::parseContextValue) }
            .firstOrNull { it > 0 }
        if (nested != null) return nested
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

    private fun maxContextWindowOf(root: JsonObject, displayConfig: JsonObject?, dev: Long): Long {
        val tokenObjects = listOfNotNull(root.objOrNull("context_window_tokens"), displayConfig?.objOrNull("context_window_tokens"))
        val explicit = tokenObjects.mapNotNull { obj -> obj.get("max")?.let(::parseContextValue) }.maxOrNull() ?: 0L
        val detailArrays = listOfNotNull(
            root.get("model_detail_list") as? JsonArray,
            displayConfig?.get("model_detail_list") as? JsonArray,
        )
        val detail = detailArrays.flatMap { array -> array.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            (obj.get("prompt_max_tokens") ?: obj.get("max_tokens"))?.let(::parseContextValue)
        } }.maxOrNull() ?: 0L
        val flat = listOf("max_context_window", "maxContextWindow", "max_context_length", "maxContextLength")
            .flatMap { key -> listOf(root.get(key), displayConfig?.get(key)).mapNotNull { it?.let(::parseContextValue) } }
            .maxOrNull() ?: 0L
        return maxOf(explicit, detail, flat, if (dev >= 1_000_000L) dev else 0L)
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
