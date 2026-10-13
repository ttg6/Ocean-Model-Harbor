package dev.aigw.core.provider.trae

import dev.aigw.core.provider.ProviderHooks

/** Only complete upstream streams with reported usage and billing can calibrate an estimate. */
internal class TraeTokenMeter(private val hooks: ProviderHooks, uid: String, model: String) {
    private val ticket = runCatching { hooks.onTokenSampleStart("trae", uid, model) }.getOrDefault(0L)
    private var tokens: Long? = null
    private var credits: Long? = null
    private var done = false
    private var failed = false
    private var finished = false

    fun accept(event: SoloEvent) {
        when (event) {
            is SoloEvent.Usage -> {
                fun number(name: String): Long? = runCatching { event.usage.get(name)?.takeUnless { it.isJsonNull }?.asLong }.getOrNull()
                tokens = number("total_tokens") ?: run {
                    val input = number("prompt_tokens") ?: number("input_tokens")
                    val output = number("completion_tokens") ?: number("output_tokens")
                    if (input != null && output != null && input >= 0 && output >= 0) runCatching { Math.addExact(input, output) }.getOrNull() else null
                }
            }
            is SoloEvent.NotifyUsage -> event.ideCredits?.let { credits = it }
            is SoloEvent.Done -> done = true
            is SoloEvent.Failure -> failed = true
            else -> Unit
        }
    }

    @Synchronized fun finish(consumed: Boolean) {
        if (finished) return
        finished = true
        // Statistics must not break an otherwise valid model response if local persistence fails.
        runCatching { hooks.onTokenSampleFinish(ticket, tokens, credits, consumed && done && !failed) }
            .onFailure { hooks.onLog("Token 自动测算记录失败") }
    }
}
