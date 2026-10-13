package dev.aigw.windows

import dev.aigw.core.gateway.GatewayEngine
import dev.aigw.core.usage.TokenBudgetSource
import dev.aigw.core.usage.TokenConversion
import java.awt.*
import java.math.BigDecimal
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.*

/** All values come from local snapshots; refresh is delegated to the window's background worker. */
internal class TokenBudgetPanel(
    private val engine: GatewayEngine,
    private val refreshCredits: () -> Unit,
) : JPanel(BorderLayout(0, 12)) {
    private val model = JComboBox<String>().apply {
        name = "token-budget-model"; isEditable = true; preferredSize = Dimension(270, 34)
        toolTipText = "选择或粘贴模型页的完整 TRAE 模型 ID；这里只切换估算，不会改变客户端调用模型。"
    }
    private val source = JComboBox(TokenBudgetSource.entries.toTypedArray()).apply {
        name = "token-budget-source"; preferredSize = Dimension(180, 34)
    }
    private val credits = value("token-budget-credits")
    private val remaining = value("token-budget-remaining")
    private val used = value("token-budget-used")
    private val details = JTextArea().apply {
        name = "token-budget-details"; isEditable = false; isOpaque = false; isFocusable = false
        lineWrap = true; wrapStyleWord = true; rows = 3
        font = Font("Microsoft YaHei UI", Font.PLAIN, 12); foreground = OceanUi.muted
    }
    private var updating = false

    init {
        isOpaque = false
        val saved = engine.tokenBudgetStore.settings()
        val ids = engine.registry.get("trae")?.listModels(null)?.models.orEmpty().map { "trae/${it.id}" }
        updating = true
        (listOf("") + ids + saved.conversions.keys + saved.modelId).distinct().forEach { model.addItem(it) }
        model.selectedItem = saved.modelId; source.selectedItem = saved.source
        updating = false
        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JLabel("Token 余额概览 · TRAE").apply {
                foreground = OceanUi.navy; font = font.deriveFont(Font.BOLD, 18f)
            }, BorderLayout.WEST)
            add(JButton("刷新积分").apply { addActionListener { refreshCredits() } }, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        add(JPanel(BorderLayout(0, 10)).apply {
            isOpaque = false
            add(JPanel(GridLayout(2, 1, 0, 4)).apply {
                isOpaque = false
                add(row(JLabel("估算模型"), model, JButton("配置换算").apply { addActionListener { configureConversion() } }))
                add(row(JLabel("余额来源"), source, JLabel("两种余额分开记录，按所选口径估算。")))
            }, BorderLayout.NORTH)
            add(JPanel(GridLayout(1, 3, 12, 0)).apply {
                isOpaque = false
                add(card("剩余积分（快照）", credits))
                add(card("预计剩余 Tokens", remaining))
                add(card("累计已用 Tokens", used))
            }, BorderLayout.CENTER)
        }, BorderLayout.CENTER)
        add(details, BorderLayout.SOUTH)
        model.addActionListener { if (!updating) saveSelection() }
        source.addActionListener { if (!updating) saveSelection() }
        refresh()
    }

    private fun saveSelection() {
        val current = engine.tokenBudgetStore.settings()
        val id = model.editor.item?.toString()?.trim().orEmpty()
        try {
            engine.tokenBudgetStore.saveSettings(current.copy(modelId = id, source = source.selectedItem as TokenBudgetSource))
        } catch (e: IllegalArgumentException) {
            updating = true; model.selectedItem = current.modelId; source.selectedItem = current.source; updating = false
            JOptionPane.showMessageDialog(this, e.message, "Token 估算配置", JOptionPane.ERROR_MESSAGE)
        }
        refresh()
    }

    private fun configureConversion() {
        saveSelection()
        val current = engine.tokenBudgetStore.settings()
        if (current.modelId.isEmpty()) {
            JOptionPane.showMessageDialog(this, "请先选择或粘贴完整 TRAE 模型 ID。", "配置参考换算", JOptionPane.INFORMATION_MESSAGE)
            return
        }
        val conversion = current.conversions[current.modelId]
        val coefficient = JTextField(conversion?.tokensPerCredit?.toPlainString().orEmpty(), 26).apply { name = "token-budget-coefficient" }
        val basis = JTextField(conversion?.basis.orEmpty(), 26).apply { name = "token-budget-basis" }
        val form = JPanel(GridLayout(0, 1, 0, 7)).apply {
            add(JLabel("模型：${current.modelId}"))
            add(JLabel("参考每积分可用 Tokens（需要可靠来源，不是模型倍率）")); add(coefficient)
            add(JLabel("依据 / 测量条件（例如同模型、同会员和同一时段的实测）")); add(basis)
            add(JLabel("不预置统一比例。留空系数并保存可取消该模型的估算。"))
        }
        if (JOptionPane.showConfirmDialog(this, form, "配置参考换算", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return
        try {
            val raw = coefficient.text.trim()
            val rates = current.conversions.toMutableMap()
            if (raw.isEmpty()) rates.remove(current.modelId)
            else {
                require(raw.length <= 32 && raw.matches(Regex("[0-9]+(\\.[0-9]{1,8})?"))) { "请输入有效的正数，最多 8 位小数" }
                rates[current.modelId] = TokenConversion(BigDecimal(raw), basis.text.trim())
            }
            engine.tokenBudgetStore.saveSettings(current.copy(conversions = rates))
            refresh()
        } catch (e: IllegalArgumentException) {
            JOptionPane.showMessageDialog(this, e.message, "无法保存参考系数", JOptionPane.ERROR_MESSAGE)
        }
    }

    fun updateModels(ids: List<String>) {
        updating = true
        val selected = model.editor.item
        ids.filter { it.startsWith("trae/") }.forEach { id ->
            if ((0 until model.itemCount).none { model.getItemAt(it) == id }) model.addItem(id)
        }
        model.editor.item = selected
        updating = false
    }

    fun refresh() {
        val config = engine.tokenBudgetStore.settings()
        val snapshot = engine.tokenBudgetStore.summary(engine.accounts("trae"), engine.providerSettings("trae").enabled)
        credits.text = snapshot.credits?.let(::format) ?: "未知"
        remaining.text = snapshot.estimatedTokens?.let { "约 ${format(it)}" } ?: "暂无法估算"
        used.text = format(engine.callLogStore.totalStats().totalTokens)
        credits.toolTipText = snapshot.credits?.let { "${NumberFormat.getIntegerInstance().format(it)} 积分；${config.source.label}" }
        remaining.toolTipText = snapshot.estimatedTokens?.let { "参考估算：${NumberFormat.getIntegerInstance().format(it)} Tokens，非上游承诺额度" }
        used.toolTipText = "全部供应商通过本网关调用并回报的 Tokens；清空调用记录会同时重置此累计值。"
        val time = snapshot.observedAtMillis?.let { SimpleDateFormat("MM-dd HH:mm:ss").format(Date(it)) } ?: "未知，请同步余额"
        val coverage = "已知 ${snapshot.knownAccounts}/${snapshot.eligibleAccounts} 个可用 TRAE 账号"
        val flags = buildString {
            if (snapshot.partial) append("；部分余额未知，只显示已知合计")
            if (snapshot.refreshFailures > 0) append("；${snapshot.refreshFailures} 个账号刷新失败，保留上次数据")
        }
        val explanation = if (config.source == TokenBudgetSource.BILLING)
            "来自最近对话计费回报；签到、充值或外部消费需等下一次回报同步。"
        else "权益包可能包含其他通道额度，不保证等于当前模型可用积分；消费不一定立即反映。"
        val basis = snapshot.conversion?.let { "自定义参考系数：${it.tokensPerCredit.stripTrailingZeros().toPlainString()} Tokens/积分；依据：${it.basis}" }
            ?: "尚无参考换算系数，请选模型后点击“配置换算”；不使用未经验证的默认比例。"
        details.text = "$coverage$flags · 最早余额更新时间：$time\n${config.source.label}：$explanation\n$basis"
    }

    private fun value(id: String) = JLabel("—").apply {
        name = id; foreground = OceanUi.navy; font = Font("Microsoft YaHei UI", Font.BOLD, 21)
    }
    private fun row(vararg components: Component) = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
        isOpaque = false; components.forEach { add(it) }
    }
    private fun card(title: String, amount: JLabel) = JPanel(BorderLayout(0, 7)).apply {
        background = OceanUi.mist; border = BorderFactory.createEmptyBorder(13, 14, 13, 14)
        add(JLabel(title).apply { foreground = OceanUi.muted }, BorderLayout.NORTH); add(amount, BorderLayout.CENTER)
    }
    private fun format(value: Long): String = when {
        value >= 100_000_000 -> String.format(java.util.Locale.ROOT, "%.2f 亿", value / 100_000_000.0)
        value >= 10_000 -> String.format(java.util.Locale.ROOT, "%.2f 万", value / 10_000.0)
        else -> NumberFormat.getIntegerInstance().format(value)
    }
}
