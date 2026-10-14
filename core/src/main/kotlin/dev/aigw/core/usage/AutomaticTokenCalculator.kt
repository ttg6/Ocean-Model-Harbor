package dev.aigw.core.usage

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.store.KeyValueStore
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

data class AutomaticTokenRate(
    val tokensPerCredit: BigDecimal,
    val calls: Int,
    val tokens: BigDecimal,
    val credits: BigDecimal,
    val updatedAt: Long,
)

/** Pair the usage and final billing event of a complete, non-overlapping call. */
class AutomaticTokenCalculator(private val store: KeyValueStore) {
    private data class Anchor(val balance: Long, val at: Long, val model: String, val period: String,
        val tokens: BigDecimal = BigDecimal.ZERO, val credits: BigDecimal = BigDecimal.ZERO, val calls: Int = 0)
    private data class Ticket(val uid: String, val model: String, val at: Long, val period: String,
        val anchor: Anchor?, var invalid: Boolean = false)
    private data class Sample(val tokens: BigDecimal, val credits: BigDecimal, val calls: Int, val at: Long)
    private data class Profile(val uid: String, val model: String, val period: String, val samples: ArrayDeque<Sample>)
    private val anchors = mutableMapOf<String, Anchor>()
    private val tickets = mutableMapOf<Long, Ticket>()
    private val profiles = mutableMapOf<String, Profile>()
    private val statuses = mutableMapOf<String, String>()
    private var nextId = 0L

    init {
        for (key in store.keys(STATUS_PREFIX)) runCatching {
            val obj = JsonParser.parseString(store.read(key)).asJsonObject
            statuses[obj.get("uid").asString] = obj.get("message").asString
        }
        for (key in store.keys(PREFIX)) runCatching {
            val obj = JsonParser.parseString(store.read(key)).asJsonObject
            val uid = obj.get("uid").asString; val model = obj.get("model").asString; val period = obj.get("period").asString
            val samples = ArrayDeque<Sample>()
            for (element in obj.getAsJsonArray("samples")) {
                val s = element.asJsonObject
                val tokens = s.get("tokens").asBigDecimal; val credits = s.get("credits").asBigDecimal
                val calls = s.get("calls").asInt; val at = s.get("at").asLong
                if (tokens > BigDecimal.ZERO && credits >= MIN_SPEND && calls in 1..MAX_CARRY_CALLS) samples.addLast(Sample(tokens, credits, calls, at))
            }
            while (samples.size > MAX_SAMPLES) samples.removeFirst()
            if (key == profileKey(uid, model, period)) profiles[key] = Profile(uid, model, period, samples)
        }
    }

    @Synchronized fun begin(uid: String, modelId: String, now: Long): Long {
        // Stalled/cancelled clients must not retain tickets indefinitely.
        tickets.entries.filter { now - it.value.at > MAX_GAP }.map { it.value.uid }.distinct().forEach(::invalidate)
        tickets.entries.removeIf { now - it.value.at > MAX_GAP }
        val overlapping = tickets.values.any { it.uid == uid }
        if (overlapping) invalidate(uid)
        val anchor = anchors[uid]?.takeIf { now >= it.at && now - it.at <= MAX_GAP }
        val id = ++nextId
        tickets[id] = Ticket(uid, normalize(modelId), now, period(now), anchor, invalid = overlapping)
        return id
    }

    @Synchronized fun finish(id: Long, tokens: Long?, balance: Long?, complete: Boolean, now: Long): String? {
        val ticket = tickets.remove(id) ?: return null
        if (ticket.invalid || !complete || tokens == null || tokens <= 0 || balance == null || balance < 0 ||
            now < ticket.at || now - ticket.at > MAX_GAP || tickets.values.any { it.uid == ticket.uid }) {
            invalidate(ticket.uid)
            status(ticket.uid, when {
                ticket.invalid || tickets.values.any { it.uid == ticket.uid } -> "并发或账号操作打断了测算"
                !complete -> "上游请求未完整结束，未计入样本"
                tokens == null || tokens <= 0 -> "上游未回报有效 Tokens 用量"
                balance == null || balance < 0 -> "上游未回报对话积分余额，无法计算实际扣分"
                else -> "调用间隔过长，需重新建立余额基准"
            })
            return null
        }
        val currentPeriod = period(now)
        val previous = ticket.anchor
        var next = Anchor(balance, now, ticket.model, currentPeriod)
        status(ticket.uid, when {
            previous == null -> "已建立余额基准，等待后续积分扣减"
            balance > previous.balance -> "积分增加，已重新建立余额基准"
            currentPeriod != ticket.period || currentPeriod != previous.period -> "时段变化，已重新建立余额基准"
            else -> "正在累积有效样本"
        })
        if (previous != null && currentPeriod == ticket.period && currentPeriod == previous.period && balance <= previous.balance) {
            val sameModel = previous.model == ticket.model
            val totalTokens = tokens.toBigDecimal() + if (sameModel) previous.tokens else BigDecimal.ZERO
            val totalCredits = previous.balance.toBigDecimal() - balance.toBigDecimal() + if (sameModel) previous.credits else BigDecimal.ZERO
            val calls = 1 + if (sameModel) previous.calls else 0
            if (totalCredits >= MIN_SPEND) {
                addSample(ticket.uid, ticket.model, currentPeriod, Sample(totalTokens, totalCredits, calls, now))
                status(ticket.uid, "自动计算已更新")
            } else if (calls < MAX_CARRY_CALLS) {
                // Integer balances can stay unchanged across several calls; retain their tokens.
                next = next.copy(tokens = totalTokens, credits = totalCredits, calls = calls)
                status(ticket.uid, "已配对 $calls 次调用，累计扣 ${totalCredits.toPlainString()} 积分；达到 2 积分后计算")
            }
        }
        // Increases, missing baselines and discount-boundary calls only establish a new baseline.
        anchors[ticket.uid] = next
        return ticket.model
    }

    @Synchronized fun invalidate(uid: String) {
        anchors.remove(uid)
        tickets.values.filter { it.uid == uid }.forEach { it.invalid = true }
    }

    @Synchronized fun noteBalance(uid: String, balance: Long) {
        if (tickets.values.none { it.uid == uid } && anchors[uid]?.balance?.let { it != balance } == true) invalidate(uid)
    }

    @Synchronized fun rate(uid: String, modelId: String, now: Long): AutomaticTokenRate? {
        val profile = profiles[profileKey(uid, normalize(modelId), period(now))] ?: return null
        val samples = profile.samples.filter { now >= it.at && now - it.at <= MAX_SAMPLE_AGE }
        if (samples.isEmpty()) return null
        val tokens = samples.fold(BigDecimal.ZERO) { sum, s -> sum + s.tokens }
        val credits = samples.fold(BigDecimal.ZERO) { sum, s -> sum + s.credits }
        return AutomaticTokenRate(tokens.divide(credits, 8, RoundingMode.DOWN), samples.sumOf { it.calls }, tokens, credits, samples.maxOf { it.at })
    }

    @Synchronized fun status(uid: String): String = statuses[uid] ?: "尚未收到新版的完整调用数据"

    private fun status(uid: String, message: String) {
        statuses[uid] = message
        store.write(STATUS_PREFIX + uid, JsonObject().apply {
            addProperty("uid", uid); addProperty("message", message)
        }.toString())
    }

    @Synchronized fun removeAccount(uid: String) {
        invalidate(uid)
        statuses.remove(uid); store.delete(STATUS_PREFIX + uid)
        tickets.entries.removeIf { it.value.uid == uid }
        profiles.entries.filter { it.value.uid == uid }.map { it.key }.forEach { profiles.remove(it); store.delete(it) }
    }

    private fun addSample(uid: String, model: String, period: String, sample: Sample) {
        val key = profileKey(uid, model, period)
        val profile = profiles.getOrPut(key) { Profile(uid, model, period, ArrayDeque()) }
        profile.samples.addLast(sample)
        while (profile.samples.size > MAX_SAMPLES) profile.samples.removeFirst()
        store.write(key, JsonObject().apply {
            addProperty("uid", uid); addProperty("model", model); addProperty("period", period)
            add("samples", JsonArray().apply { profile.samples.forEach { s -> add(JsonObject().apply {
                addProperty("tokens", s.tokens); addProperty("credits", s.credits)
                addProperty("calls", s.calls); addProperty("at", s.at)
            }) } })
        }.toString())
    }

    companion object {
        private const val PREFIX = "token-budget/automatic/"
        private const val STATUS_PREFIX = "token-budget/automatic-status/"
        private const val MAX_GAP = 10L * 60 * 1000
        private const val MAX_SAMPLE_AGE = 24L * 60 * 60 * 1000
        private const val MAX_SAMPLES = 20
        private const val MAX_CARRY_CALLS = 100
        private val MIN_SPEND = BigDecimal("2")
        private val ZONE = ZoneId.of("Asia/Shanghai")
        private fun normalize(model: String) = model.trim().lowercase(Locale.ROOT).substringBefore("__")
        private fun period(now: Long) = if (Instant.ofEpochMilli(now).atZone(ZONE).hour in 8..21) "忙时" else "闲时"
        private fun profileKey(uid: String, model: String, period: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest("${uid.length}:$uid|$model|$period".toByteArray(Charsets.UTF_8))
            return PREFIX + bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
