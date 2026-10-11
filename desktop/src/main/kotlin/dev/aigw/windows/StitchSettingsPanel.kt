package dev.aigw.windows

import dev.aigw.core.gateway.GatewayEngine
import dev.aigw.core.gateway.GatewaySettings
import dev.aigw.core.gateway.ProxySettings
import dev.aigw.core.gateway.ProxyType
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.io.File
import java.lang.management.ManagementFactory
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/** Native implementation of the user's Stitch settings design; every action uses real state. */
internal class StitchSettingsPanel(
    private val engine: GatewayEngine,
    private val dataDir: File,
    private val work: (String, () -> Unit) -> Unit,
    private val showError: (String) -> Unit,
    private val confirm: (String) -> Boolean,
    private val failover: () -> Unit,
) : JPanel(BorderLayout(18, 16)) {
    private val port = input("监听端口")
    private val apiKey = JPasswordField().apply { name = "网关 API Key"; styleInput(this) }
    private val keyReveal = JButton("显示")
    private val noKey = OceanUi.switch("允许无 Key 调用", false)
    private val lan = OceanUi.switch("允许局域网连接", false)
    private val provider = JComboBox(engine.providers().map { it.id }.toTypedArray()).apply { name = "默认供应商"; isEditable = true; styleInput(this) }
    private val proxyEnabled = OceanUi.switch("启用代理", false)
    private val proxyType = JComboBox(ProxyType.entries.toTypedArray()).apply {
        name = "代理协议"; styleInput(this)
        renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, selected: Boolean, focus: Boolean): Component =
                super.getListCellRendererComponent(list, (value as? ProxyType)?.label ?: value, index, selected, focus)
        }
    }
    private val proxyHost = input("代理地址")
    private val proxyPort = input("代理端口")
    private val proxyUser = input("代理用户名")
    private val proxyPassword = JPasswordField().apply { name = "代理密码"; styleInput(this) }
    private val usable = OceanUi.switch("隐藏非对话模型", false)
    private val verbose = OceanUi.switch("保存详细调用日志", false)
    private val autoStart = OceanUi.switch("打开程序时自动启动网关", false)
    private val rotate = JSpinner(SpinnerNumberModel(3, 1, 20, 1)).apply { name = "最多换号次数"; styleInput(this) }
    private val retention = JSpinner(SpinnerNumberModel(30, 1, 3650, 1)).apply { name = "记录保留天数"; styleInput(this) }
    private val search = input("搜索设置").apply {
        putClientProperty("JTextField.placeholderText", "搜索端口、代理、日志…")
        putClientProperty("JTextField.showClearButton", true)
    }
    private val message = JLabel("就绪 · 修改后点击保存生效")
    private val memory = JLabel()
    private val threads = JLabel()
    private val runtimeState = JLabel()
    private val save = OceanUi.primary(action("保存设置") { saveSettings() })
    private val entries = mutableListOf<Pair<JPanel, String>>()
    private val sections = linkedMapOf<String, JPanel>()
    private val nav = linkedMapOf<String, JButton>()
    private val scrollContent = OceanUi.column()
    private val scroll = JScrollPane(object : JPanel(BorderLayout()), Scrollable {
        override fun getPreferredScrollableViewportSize() = preferredSize
        override fun getScrollableUnitIncrement(r: Rectangle, o: Int, d: Int) = 24
        override fun getScrollableBlockIncrement(r: Rectangle, o: Int, d: Int) = (r.height - 24).coerceAtLeast(24)
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
    }.apply { isOpaque = false; add(scrollContent, BorderLayout.NORTH) }).apply {
        border = null; isOpaque = false; viewport.isOpaque = false
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBar.unitIncrement = 24
    }
    private var saving = false
    private val editingControls = mutableListOf<AbstractButton>()

    init {
        background = OceanUi.mist; border = BorderFactory.createEmptyBorder(20, 24, 16, 24)
        val heading = OceanUi.column().apply {
            add(JLabel("系统与网关设置").apply { font = font.deriveFont(Font.BOLD, 24f); foreground = OceanUi.navy })
            add(Box.createVerticalStrut(6))
            add(JLabel("控制本地中继参数、网络代理与访问凭证").apply { foreground = OceanUi.muted; font = font.deriveFont(12f) })
        }
        add(JPanel(BorderLayout(12, 0)).apply {
            isOpaque = false; add(heading)
            add(JPanel(GridBagLayout()).apply {
                isOpaque = false; preferredSize = Dimension(235, 36)
                add(search, GridBagConstraints().apply { weightx = 1.0; fill = GridBagConstraints.HORIZONTAL })
            }, BorderLayout.EAST)
        }, BorderLayout.NORTH)

        val rail = JPanel(BorderLayout()).apply {
            isOpaque = false; preferredSize = Dimension(165, 0)
            add(OceanUi.column().apply {
                listOf("网关与访问", "网络代理", "偏好与数据").forEachIndexed { index, name ->
                    val control = action(name) { selectSection(name) }.apply {
                        horizontalAlignment = SwingConstants.LEFT; icon = OceanUi.navIcon(if (index == 0) 0 else if (index == 1) 4 else 3)
                        iconTextGap = 8; maximumSize = Dimension(Int.MAX_VALUE, 42); margin = Insets(10, 8, 10, 8)
                        isBorderPainted = false
                    }
                    nav[name] = control; add(control); add(Box.createVerticalStrut(6))
                }
                add(Box.createVerticalStrut(18))
                add(OceanUi.surface(OceanUi.column().apply {
                    add(JLabel("运行时状态").apply { foreground = OceanUi.muted; font = font.deriveFont(12f) })
                    add(Box.createVerticalStrut(10)); add(runtimeState); add(Box.createVerticalStrut(14))
                    add(memory); add(Box.createVerticalStrut(8)); add(threads)
                }, 12))
            }, BorderLayout.NORTH)
        }
        add(JPanel(BorderLayout(16, 0)).apply { isOpaque = false; add(rail, BorderLayout.WEST); add(scroll) }, BorderLayout.CENTER)

        section("网关与访问", "核心协议与鉴权设置") { body ->
            row(body, "本地监听端口", "监听端口范围 1–65535。运行中修改端口，保存后自动重启服务。", port)
            keyReveal.margin = Insets(7, 10, 7, 10)
            keyReveal.addActionListener {
                val hidden = apiKey.echoChar.code != 0
                apiKey.echoChar = if (hidden) '\u0000' else '●'
                keyReveal.text = if (hidden) "隐藏" else "显示"
            }
            val regenerate = action("重新生成") {
                val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
                apiKey.text = "sk-ocean-" + bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
                message.text = "已生成新 Key，保存后生效"
            }
            editingControls += listOf(keyReveal, regenerate)
            row(body, "本地 API Key", "客户端访问网关时使用的密钥。保存新 Key 后需同步更新客户端。", OceanUi.column().apply {
                add(JPanel(BorderLayout(6, 0)).apply { isOpaque = false; add(apiKey); add(keyReveal, BorderLayout.EAST) })
                add(flow(regenerate, action("复制 Key") {
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(String(apiKey.password)), null)
                    message.text = "API Key 已复制到剪贴板"
                }))
            })
            row(body, "允许无 Key 调用", "开启后客户端可以不填 Key；关闭后必须填写上方配置的 Key。", noKey)
            row(body, "允许局域网连接", "默认仅监听 127.0.0.1。开启后同一局域网的设备可连接，请配置访问 Key。", lan)
            row(body, "默认路由供应商", "模型 ID 不带供应商前缀时，使用这里选择的供应商。", provider)
        }
        section("网络代理", "上游网络连接设置") { body ->
            row(body, "启用上游代理", "国内上游与本机地址按核心规则直连，其他连接使用配置的代理。", proxyEnabled)
            row(body, "代理协议类型", "支持 HTTP 与 SOCKS5 代理协议。", proxyType)
            row(body, "代理节点地址", "填写本机代理或远端代理的主机地址。", proxyHost)
            row(body, "代理节点端口", "填写代理工具实际监听的端口。", proxyPort)
            row(body, "认证用户名", "代理要求账号认证时填写，否则留空。", proxyUser)
            row(body, "认证密码", "代理密码默认隐藏显示。", proxyPassword)
            val probeResult = JLabel("尚未测试").apply { foreground = OceanUi.muted }
            val probe = action("测试代理端口") {
                val host = proxyHost.text.trim(); val targetPort = proxyPort.text.toIntOrNull()
                if (host.isEmpty() || targetPort == null || targetPort !in 1..65535) showError("请先填写有效的代理地址与端口")
                else work("测试代理端口") {
                    SwingUtilities.invokeLater { probeResult.text = "测试中…" }
                    val started = System.nanoTime()
                    try {
                        Socket().use { it.connect(InetSocketAddress(host, targetPort), 3000) }
                        val elapsed = (System.nanoTime() - started) / 1_000_000
                        SwingUtilities.invokeLater { probeResult.text = "TCP 可连接 · ${elapsed} ms"; probeResult.foreground = OceanUi.teal }
                    } catch (e: Exception) {
                        SwingUtilities.invokeLater { probeResult.text = "连接失败"; probeResult.foreground = Color(186, 26, 26) }
                        throw e
                    }
                }
            }
            row(body, "端口连通性检查", "检查草稿地址的 TCP 端口可连接性；上游请求结果请在模型页验证。", OceanUi.column().apply { add(probe); add(Box.createVerticalStrut(8)); add(probeResult) })
        }
        section("偏好与数据", "运行策略与本地存储管理") { body ->
            row(body, "模型列表展示策略", "隐藏上游非对话内部条目。账号权限与额度仍由供应商决定。", usable)
            row(body, "详细调用日志", "保存完整调用内容以便排查，会增加存储占用。", verbose)
            row(body, "程序启动时启动网关", "每次打开桌面程序时自动启服务，不是 Windows 开机自启。", autoStart)
            row(body, "换号重试次数", "单次失败请求最多轮换账号的次数，范围 1–20。", rotate)
            row(body, "日志保留天数", "记录超过保留期后自动清理，范围 1–3650 天。", retention)
            row(body, "故障转移策略", "配置同模型后备供应商与模型映射。", action("故障转移设置") { failover() })
            row(body, "本地数据存储目录", "账号、设置和记录使用 Windows 用户加密保存。", OceanUi.column().apply {
                add(JTextArea(dataDir.absolutePath, 3, 15).apply { isEditable = false; lineWrap = true; wrapStyleWord = true; background = OceanUi.input; font = Font("Consolas", Font.PLAIN, 11); border = BorderFactory.createEmptyBorder(8, 10, 8, 10) })
                add(Box.createVerticalStrut(8)); add(action("在资源管理器中打开") { Desktop.getDesktop().open(dataDir) })
            })
            row(body, "记录管理", "清空操作将删除调用记录与相关用量统计。", OceanUi.column().apply {
                add(action("清理过期记录") { work("清理过期记录") { engine.purgeExpiredRecords() } })
                add(Box.createVerticalStrut(8)); add(action("清空全部历史记录") {
                    if (confirm("清空全部调用记录和相关用量统计？此操作无法撤销。")) work("清空记录") { engine.callLogStore.clear() }
                }.apply { foreground = Color(186, 26, 26) })
            })
        }
        val discard = action("放弃修改") { load(engine.settings(), engine.proxySettings(), engine.store().read("windows/autoStart") == "true"); message.text = "已还原为当前保存的设置" }
        val defaults = action("恢复默认值") { load(GatewaySettings(exposeLan = false), ProxySettings(), false); message.text = "已填入默认值，点击保存后生效" }
        editingControls += listOf(discard, defaults)
        add(JPanel(BorderLayout(8, 0)).apply {
            background = Color.WHITE; border = BorderFactory.createEmptyBorder(10, 12, 10, 12)
            add(message.apply { foreground = OceanUi.muted; font = font.deriveFont(11f) })
            add(flow(defaults, discard, save), BorderLayout.EAST)
        }, BorderLayout.SOUTH)
        save.toolTipText = "保存全部设置 (Ctrl+S)"
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("control S"), "save-settings")
        actionMap.put("save-settings", object : AbstractAction() { override fun actionPerformed(e: ActionEvent?) { save.doClick() } })
        search.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = filter()
            override fun removeUpdate(e: DocumentEvent) = filter()
            override fun changedUpdate(e: DocumentEvent) = filter()
        })
        load(engine.settings(), engine.proxySettings(), engine.store().read("windows/autoStart") == "true")
        selectSection("网关与访问"); refreshRuntimeStatus()
    }

    private fun styleInput(component: JComponent) {
        component.background = OceanUi.input; component.foreground = OceanUi.navy
        component.preferredSize = Dimension(180, 34); component.minimumSize = Dimension(100, 34)
        component.getAccessibleContext().accessibleName = component.name
    }
    private fun input(name: String) = JTextField().apply { this.name = name; styleInput(this) }
    private fun action(label: String, block: () -> Unit) = JButton(label).apply { margin = Insets(7, 10, 7, 10); addActionListener { block() } }
    private fun flow(vararg components: Component) = JPanel(FlowLayout(FlowLayout.LEFT, 6, 3)).apply { isOpaque = false; components.forEach { add(it) } }

    private fun section(name: String, help: String, build: (JPanel) -> Unit) {
        val body = JPanel(GridBagLayout()).apply { isOpaque = false }
        build(body)
        val section = JPanel(BorderLayout(0, 10)).apply {
            isOpaque = false; alignmentX = LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder(0, 0, 22, 0)
            add(JLabel(name).apply { foreground = OceanUi.navy; font = font.deriveFont(Font.BOLD, 18f); toolTipText = help }, BorderLayout.NORTH)
            add(OceanUi.surface(body, 16))
        }
        sections[name] = section; scrollContent.add(section)
    }
    private fun row(body: JPanel, title: String, help: String, control: JComponent) {
        val item = JPanel(GridBagLayout()).apply {
            isOpaque = false; border = BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(if (body.componentCount > 0) 1 else 0, 0, 0, 0, OceanUi.line), BorderFactory.createEmptyBorder(16, 0, 16, 0))
            add(OceanUi.column().apply {
                add(JLabel(title).apply { foreground = OceanUi.navy; font = font.deriveFont(Font.BOLD, 14f) })
                add(Box.createVerticalStrut(6)); add(OceanUi.description(help, 150))
                preferredSize = Dimension(190, preferredSize.height); minimumSize = preferredSize
            }, GridBagConstraints().apply { gridx = 0; weightx = 0.0; anchor = GridBagConstraints.NORTHWEST; fill = GridBagConstraints.HORIZONTAL; insets = Insets(0, 0, 0, 16) })
            add(control, GridBagConstraints().apply { gridx = 1; weightx = 1.0; anchor = GridBagConstraints.NORTHWEST; fill = GridBagConstraints.HORIZONTAL })
        }
        entries += item to "$title $help"
        body.add(item, GridBagConstraints().apply { gridx = 0; gridy = body.componentCount; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL })
    }
    private fun selectSection(name: String) {
        search.text = ""
        nav.forEach { (text, button) ->
            button.background = if (text == name) Color.WHITE else OceanUi.mist
            button.foreground = if (text == name) OceanUi.teal else OceanUi.muted
            button.font = button.font.deriveFont(if (text == name) Font.BOLD else Font.PLAIN)
        }
        SwingUtilities.invokeLater {
            scrollContent.revalidate(); scrollContent.doLayout()
            sections[name]?.let { scroll.viewport.viewPosition = Point(0, it.y.coerceAtMost((scroll.viewport.view.height - scroll.viewport.height).coerceAtLeast(0))) }
        }
    }
    fun showSection(name: String) { if (sections.containsKey(name)) selectSection(name) }
    private fun filter() {
        val query = search.text.trim()
        entries.forEach { (panel, text) -> panel.isVisible = text.contains(query, true) }
        sections.values.forEach { section -> section.isVisible = entries.any { (row, _) -> SwingUtilities.isDescendingFrom(row, section) && row.isVisible } }
        scrollContent.revalidate(); scrollContent.repaint(); scroll.viewport.viewPosition = Point(0, 0)
        message.text = if (entries.none { it.first.isVisible }) "没有匹配的设置，试试其他关键词" else "修改后点击保存生效"
    }
    private fun load(settings: GatewaySettings, proxy: ProxySettings, auto: Boolean) {
        port.text = settings.port.toString(); apiKey.text = settings.apiKey; apiKey.echoChar = '●'; keyReveal.text = "显示"
        noKey.isSelected = settings.allowNoKey; lan.isSelected = settings.exposeLan
        provider.selectedItem = settings.defaultProvider; usable.isSelected = settings.onlyUsableModels
        verbose.isSelected = settings.verboseLogging; rotate.value = settings.maxRotate; retention.value = settings.logRetentionDays; autoStart.isSelected = auto
        proxyEnabled.isSelected = proxy.enabled; proxyType.selectedItem = proxy.type; proxyHost.text = proxy.host; proxyPort.text = proxy.port.toString(); proxyUser.text = proxy.username; proxyPassword.text = proxy.password
    }
    private fun saveSettings() {
        if (saving) return
        val parsedPort = port.text.toIntOrNull(); val parsedProxyPort = proxyPort.text.toIntOrNull(); val key = String(apiKey.password)
        if (parsedPort == null || parsedPort !in 1..65535) { showError("监听端口应为 1–65535"); return }
        if (!noKey.isSelected && key.isBlank()) { showError("关闭无 Key 调用前，请填写 API Key"); return }
        if (proxyEnabled.isSelected && (proxyHost.text.isBlank() || parsedProxyPort == null || parsedProxyPort !in 1..65535)) { showError("请填写有效的代理地址和端口"); return }
        try { rotate.commitEdit(); retention.commitEdit() } catch (_: Exception) { showError("请填写有效的换号次数和记录保留天数"); return }
        val updated = engine.settings().copy(port = parsedPort, apiKey = key, allowNoKey = noKey.isSelected, exposeLan = lan.isSelected,
            onlyUsableModels = usable.isSelected, verboseLogging = verbose.isSelected, defaultProvider = provider.selectedItem.toString(),
            maxRotate = rotate.value as Int, logRetentionDays = retention.value as Int)
        val proxy = engine.proxySettings().copy(enabled = proxyEnabled.isSelected, type = proxyType.selectedItem as ProxyType,
            host = proxyHost.text.trim(), port = parsedProxyPort ?: 8080, username = proxyUser.text, password = String(proxyPassword.password))
        val auto = autoStart.isSelected
        saving = true; setEditingEnabled(false); message.text = "正在保存…"
        work("保存设置") {
            var success = false
            try {
                val previous = engine.settings(); val restart = engine.isRunning() && (previous.port != updated.port || previous.exposeLan != updated.exposeLan)
                if (restart) engine.stop()
                engine.updateSettings(updated); engine.updateProxySettings(proxy); engine.store().write("windows/autoStart", auto.toString())
                if (restart) engine.start()
                success = true
            } finally {
                val saved = success
                SwingUtilities.invokeLater {
                    saving = false; setEditingEnabled(true)
                    message.text = if (saved) "✓ 设置已保存并生效" else "保存未完成，请检查错误提示"
                    refreshRuntimeStatus()
                }
            }
        }
    }
    private fun setEditingEnabled(enabled: Boolean) {
        listOf(port, apiKey, noKey, lan, provider, proxyEnabled, proxyType, proxyHost, proxyPort, proxyUser, proxyPassword, usable, verbose, autoStart, rotate, retention, save).forEach { it.isEnabled = enabled }
        editingControls.forEach { it.isEnabled = enabled }
    }
    fun refreshRuntimeStatus() {
        runtimeState.text = if (engine.isRunning()) "● 网关运行中" else "○ 网关已停止"; runtimeState.foreground = OceanUi.teal
        val heap = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used / 1024.0 / 1024.0
        memory.text = "堆内存  %.1f MB".format(heap); threads.text = "线程数量  ${ManagementFactory.getThreadMXBean().threadCount}"
        memory.font = Font("Microsoft YaHei UI", Font.PLAIN, 11); threads.font = memory.font
    }
}
