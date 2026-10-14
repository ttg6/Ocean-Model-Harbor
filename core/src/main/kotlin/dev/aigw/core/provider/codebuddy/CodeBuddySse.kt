package dev.aigw.core.provider.codebuddy

import com.google.gson.JsonParser
import dev.aigw.core.util.arrayOrNull
import dev.aigw.core.util.asObjectOrNull
import dev.aigw.core.util.objOrNull

/** An empty tool array is not a tool event: SDKs otherwise end reasoning on every chunk. */
internal fun normalizeCodeBuddySseLine(line: String): String {
    if (!line.startsWith("data:")) return line
    return runCatching {
        val chunk = JsonParser.parseString(line.substring(5).trim()).asObjectOrNull() ?: return line
        var changed = false
        chunk.arrayOrNull("choices")?.forEach { choice ->
            val delta = choice.asObjectOrNull()?.objOrNull("delta") ?: return@forEach
            if (delta.arrayOrNull("tool_calls")?.size() == 0) {
                delta.remove("tool_calls")
                changed = true
            }
        }
        if (changed) "data: $chunk" else line
    }.getOrDefault(line)
}
