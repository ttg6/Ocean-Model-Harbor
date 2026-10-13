package dev.aigw.core.usage

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.pool.AccountStatus
import dev.aigw.core.store.KeyValueStore
import java.math.BigDecimal
import java.math.RoundingMode

/** These two upstream balances are different ledgers and must never overwrite each other. */
enum class TokenBudgetSource(val label: String) {
    BILLING("最近对话余额"), ENTITLEMENT("权益包参考余额");
    override fun toString() = label
}

data class TokenConversion(val tokensPerCredit: BigDecimal, val basis: String)
data class TokenBudgetSettings(
    val modelId: String = "",
    val source: TokenBudgetSource = TokenBudgetSource.BILLING,
    val conversions: Map<String, TokenConversion> = emptyMap(),
    val automatic: Boolean = true,
)

data class TokenBudgetSummary(
    val credits: Long?,
    val estimatedTokens: Long?,
    val eligibleAccounts: Int,
    val knownAccounts: Int,
    /** Oldest included balance observation; null means the observation time is unknown. */
    val observedAtMillis: Long?,
    val refreshFailures: Int,
    val conversion: TokenConversion?,
    val estimatedAccounts: Int = 0,
    val automaticCalls: Int = 0,
) {
    val partial: Boolean get() = knownAccounts < eligibleAccounts
}

/** Reference estimate only: no fabricated universal points/token exchange rate. No credentials stored. */
class TokenBudgetStore(private val store: KeyValueStore) {
    val calculator = AutomaticTokenCalculator(store)
    private data class Balance(val credits: Long, val observedAt: Long, val refreshFailed: Boolean = false)
    private var settings = loadSettings()
    private val balances = linkedMapOf<String, Balance>()

    init {
        for (key in store.keys(BALANCE_PREFIX)) {
            val balance = runCatching {
                val obj = JsonParser.parseString(store.read(key)).asJsonObject
                Balance(obj.get("credits").asLong.coerceAtLeast(0), obj.get("observedAt").asLong,
                    obj.get("refreshFailed")?.asBoolean ?: false)
            }.getOrNull() ?: continue
            balances[key] = balance
        }
    }

    @Synchronized fun settings(): TokenBudgetSettings = settings

    @Synchronized fun saveSettings(updated: TokenBudgetSettings) {
        require(updated.modelId.isEmpty() || updated.modelId.startsWith("trae/") && updated.modelId.length > 5) {
            "请填写模型页复制的完整 TRAE 模型 ID（trae/ 开头）"
        }
        for ((model, conversion) in updated.conversions) {
            require(model.startsWith("trae/") && model.length > 5) { "换算系数必须绑定完整 TRAE 模型 ID" }
            require(conversion.tokensPerCredit > BigDecimal.ZERO && conversion.tokensPerCredit <= MAX_RATE) {
                "每积分 Tokens 必须大于 0，且不超过 1 万亿"
            }
            require(conversion.basis.isNotBlank()) { "请填写参考系数的来源或测量条件" }
        }
        val obj = JsonObject().apply {
            addProperty("modelId", updated.modelId); addProperty("source", updated.source.name)
            addProperty("automatic", updated.automatic)
            add("conversions", JsonObject().apply {
                for ((model, conversion) in updated.conversions) add(model, JsonObject().apply {
                    addProperty("tokensPerCredit", conversion.tokensPerCredit.toPlainString())
                    addProperty("basis", conversion.basis.trim())
                })
            })
        }
        store.write(SETTINGS_KEY, obj.toString())
        settings = updated.copy(conversions = updated.conversions.toMap())
    }

    @Synchronized fun observe(uid: String, source: TokenBudgetSource, credits: Long, known: Boolean, now: Long) {
        val key = balanceKey(uid, source)
        if (!known) {
            balances[key]?.let { persist(key, it.copy(refreshFailed = true)) }
            return
        }
        if (source == TokenBudgetSource.BILLING) calculator.noteBalance(uid, credits)
        if (source == TokenBudgetSource.ENTITLEMENT && balances[key]?.credits?.let { credits > it } == true) calculator.invalidate(uid)
        persist(key, Balance(credits.coerceAtLeast(0), now))
    }

    /** Legacy account balances have no timestamp; don't pretend they were refreshed just now. */
    @Synchronized fun importLegacy(accounts: List<AccountStatus>) {
        for (account in accounts.filter { it.providerId == "trae" && it.creditsKnown }) {
            val source = when {
                account.detail.startsWith("权益包剩余") -> TokenBudgetSource.ENTITLEMENT
                account.detail == "来自对话计费回报" -> TokenBudgetSource.BILLING
                else -> continue
            }
            val key = balanceKey(account.uid, source)
            if (key !in balances) persist(key, Balance(account.credits.coerceAtLeast(0), 0))
        }
    }

    @Synchronized fun removeAccount(uid: String) {
        calculator.removeAccount(uid)
        for (source in TokenBudgetSource.entries) {
            val key = balanceKey(uid, source)
            store.delete(key); balances.remove(key)
        }
    }

    @Synchronized fun summary(accounts: List<AccountStatus>, providerEnabled: Boolean, now: Long = System.currentTimeMillis()): TokenBudgetSummary {
        val eligible = accounts.filter { it.providerId == "trae" && it.usable && providerEnabled }
        val included = eligible.mapNotNull { balances[balanceKey(it.uid, settings.source)] }
        val sum = if (included.isEmpty()) null else included.fold(BigDecimal.ZERO) { total, balance -> total + balance.credits.toBigDecimal() }
        val credits = sum?.let(::clamp)
        val conversion = settings.conversions[settings.modelId].takeIf { !settings.automatic }
        var calls = 0
        var estimatedAccounts = 0
        var automaticSum = BigDecimal.ZERO
        if (settings.automatic) for (account in eligible) {
            val balance = balances[balanceKey(account.uid, settings.source)] ?: continue
            val rate = calculator.rate(account.uid, settings.modelId, now) ?: continue
            automaticSum += balance.credits.toBigDecimal() * rate.tokensPerCredit
            calls += rate.calls; estimatedAccounts++
        }
        val estimate = if (settings.automatic) automaticSum.takeIf { estimatedAccounts > 0 }?.let(::clamp)
            else if (sum != null && conversion != null) clamp(sum * conversion.tokensPerCredit) else null
        val oldest = included.minOfOrNull { it.observedAt }?.takeIf { it > 0 }
        return TokenBudgetSummary(credits, estimate, eligible.size, included.size, oldest,
            included.count { it.refreshFailed }, conversion, if (settings.automatic) estimatedAccounts else if (conversion != null) included.size else 0, calls)
    }

    @Synchronized fun completeSample(id: Long, tokens: Long?, credits: Long?, completed: Boolean, now: Long) {
        val model = calculator.finish(id, tokens, credits, completed, now) ?: return
        if (settings.modelId.isEmpty() && settings.automatic) saveSettings(settings.copy(modelId = model, source = TokenBudgetSource.BILLING))
    }

    private fun persist(key: String, value: Balance) {
        store.write(key, JsonObject().apply {
            addProperty("credits", value.credits); addProperty("observedAt", value.observedAt)
            addProperty("refreshFailed", value.refreshFailed)
        }.toString())
        balances[key] = value
    }

    private fun loadSettings(): TokenBudgetSettings = runCatching {
        val obj = JsonParser.parseString(store.read(SETTINGS_KEY)).asJsonObject
        val rates = linkedMapOf<String, TokenConversion>()
        obj.getAsJsonObject("conversions")?.entrySet()?.forEach { (model, raw) ->
            runCatching {
                val value = raw.asJsonObject
                val rate = value.get("tokensPerCredit").asBigDecimal
                val basis = value.get("basis").asString
                if (model.startsWith("trae/") && rate > BigDecimal.ZERO && rate <= MAX_RATE && basis.isNotBlank()) {
                    rates[model] = TokenConversion(rate, basis)
                }
            }
        }
        val migrating = !obj.has("automatic")
        TokenBudgetSettings(obj.get("modelId")?.asString.orEmpty(),
            if (migrating) TokenBudgetSource.BILLING else runCatching { TokenBudgetSource.valueOf(obj.get("source").asString) }.getOrDefault(TokenBudgetSource.BILLING),
            rates, obj.get("automatic")?.asBoolean ?: true)
    }.getOrDefault(TokenBudgetSettings())

    companion object {
        private const val SETTINGS_KEY = "token-budget/settings"
        private const val BALANCE_PREFIX = "token-budget/balances/"
        private val MAX_RATE = BigDecimal("1000000000000")
        private fun balanceKey(uid: String, source: TokenBudgetSource) = "$BALANCE_PREFIX${source.name}/$uid"
        private fun clamp(value: BigDecimal): Long = value.coerceAtMost(Long.MAX_VALUE.toBigDecimal())
            .coerceAtLeast(BigDecimal.ZERO).setScale(0, RoundingMode.DOWN).toLong()
    }
}
