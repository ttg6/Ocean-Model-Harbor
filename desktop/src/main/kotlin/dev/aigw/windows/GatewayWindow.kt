package dev.aigw.windows

import com.google.gson.GsonBuilder
import dev.aigw.core.gateway.*
import dev.aigw.core.pool.AccountStatus
import dev.aigw.core.provider.*
import dev.aigw.core.provider.antigravity.AntigravityOAuthCredentials
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.File
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.Executors
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.DefaultTableModel
import kotlin.system.exitProcess

/** Windows desktop shell: all network operations run outside the Swing event thread. */
class GatewayWindow(
    private val engine: GatewayEngine,
    private val dataDir: File,
    private val browserOpener: (String) -> Unit = { Desktop.getDesktop().browse(URI(it)) },
    private val confirmTraeInvitation: (GatewayWindow) -> Boolean = { window ->
        JOptionPane.showOptionDialog(window,
            "已打开 TRAE 邀请登录页。\n请在浏览器中完成注册或登录，再回到这里点击“继续授权”，将账号接入网关。",
            "TRAE 登录与授权", JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE,
            null, arrayOf("继续授权", "取消"), "继续授权") == 0
    },
    private val loginStartup: LoginStartup = WindowsLoginStartup(dataDir),
) : JFrame("${AppIdentity.NAME} ${AppIdentity.VERSION} · Windows x64") {
    companion object {
        const val TRAE_INVITE_URL = "https://www.trae.cn/events/code-fission/BX52NUATZKGF?utm_source=copy_link&utm_medium=code_fission"
    }
    private val navy = OceanUi.navy
    private val teal = OceanUi.teal
    private val background = OceanUi.mist
    private val worker = Executors.newFixedThreadPool(4) { r -> Thread(r, "windows-worker").apply { isDaemon = true } }
    private val footer = JLabel("准备就绪")
    private val state = JLabel("网关已停止")
    private val endpoint = JTextField(engine.localUrl())
    private val metrics = List(4) { JLabel("0") }
    private val tokenBudget = TokenBudgetPanel(engine) {
        background("刷新 TRAE 积分（对话余额由下一次计费回报更新）") {
            val failures = engine.refreshAllCredits("trae").filter { it.error.isNotEmpty() }
            if (failures.isNotEmpty()) showText("积分刷新结果", failures.joinToString("\n") { it.error })
        }
    }
    private val accessHint = JLabel()
    private val sidebarState = JLabel()
    private val sidebarAccess = JLabel()
    private val pageLayout = CardLayout()
    private val pageHost = JPanel(pageLayout)
    private val navigation = linkedMapOf<String, JButton>()
    private val serviceButton = JButton("启动网关")
    private val providerModel = rows("供应商", "标识", "账号", "可用", "状态")
    private val providerTable = OceanUi.table(providerModel, "尚未添加供应商", "使用“添加中转站”接入自定义供应商。")
    private val accountModel = rows("供应商", "账号", "额度", "状态", "说明")
    private val accountTable = OceanUi.table(accountModel, "还没有模型账号", "在上方选择供应商，再点击“浏览器登录”或“导入凭证”。")
    private var accounts = emptyList<AccountStatus>()
    private val modelRows = rows("模型 ID", "名称", "上下文", "延迟")
    private val modelTable = OceanUi.table(modelRows, "查找你要使用的模型", "点击“刷新目录”获取模型，再复制完整模型 ID。")
    private val search = JTextField()
    private var models = emptyList<RoutedModel>()
    private val latencies = mutableMapOf<String, String>()
    private val logRows = rows("时间", "供应商 / 模型", "账号", "结果", "耗时", "Tokens")
    private val logTable = OceanUi.table(logRows, "暂无调用记录", "客户端通过网关发送请求后，可在这里查看结果和用量。")
    private val runtimeLogs = JTextArea()
    private var tray: TrayIcon? = null
    private var trayMenu: ChineseTrayMenu? = null
    private val header = HarborBanner { if (engine.isRunning()) background("重启网关") { engine.stop(); engine.start() } else startGateway() }
    @Volatile private var browserGeneration = 0
    private var modelsLoading = false
    private var busyCount = 0
    private val clock = Timer(2000) { refreshStatus(); refreshLogs() }

    init {
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        minimumSize = Dimension(940, 650)
        size = Dimension(1280, 880)
        setLocationRelativeTo(null)
        iconImages = OceanIcon.windowImages()
        contentPane.background = background
        layout = BorderLayout()
        add(header, BorderLayout.NORTH)
        pageHost.apply {
            background = OceanUi.mist; preferredSize = Dimension(1060, 650)
            add(dashboard(), "网关"); add(providersPage(), "供应商与账号")
            add(modelsPage(), "模型"); add(logsPage(), "调用记录"); add(settingsPage(), "设置")
        }
        add(JPanel(BorderLayout()).apply { add(sidebar(), BorderLayout.WEST); add(pageHost, BorderLayout.CENTER) }, BorderLayout.CENTER)
        add(footer.apply {
            border = BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, OceanUi.line), BorderFactory.createEmptyBorder(9, 22, 9, 22))
            foreground = teal; background = Color.WHITE; isOpaque = true
        }, BorderLayout.SOUTH)
        showPage("网关")
        engine.onAccountsChanged = { SwingUtilities.invokeLater { refreshAccounts(); refreshStatus(); footer.text = "账号已更新" } }
        installTray()
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) {
                if (tray != null) { isVisible = false; tray?.displayMessage(AppIdentity.NAME, "程序继续在托盘运行。右键托盘可退出。", TrayIcon.MessageType.INFO) }
                else quit()
            }
            override fun windowClosed(e: WindowEvent) {
                clock.stop()
                tray?.let { SystemTray.getSystemTray().remove(it) }
                trayMenu?.dispose()
                worker.shutdownNow()
            }
        })
        refreshAccounts(); refreshStatus(); refreshLogs(); clock.start()
    }

    private fun sidebar() = JPanel(BorderLayout()).apply {
        background = navy; preferredSize = Dimension(192, 0)
        border = BorderFactory.createEmptyBorder(14, 6, 14, 6)
        add(vertical().apply {
            add(JLabel("导航").apply { font = font.deriveFont(11f); foreground = Color(142, 176, 192); border = BorderFactory.createEmptyBorder(0, 12, 12, 0) })
            listOf("网关", "供应商与账号", "模型", "调用记录", "设置").forEachIndexed { index, label ->
                val control = button(label) { showPage(label) }.apply {
                    icon = OceanUi.navIcon(index); iconTextGap = 12; horizontalAlignment = SwingConstants.LEFT
                    margin = Insets(8, 12, 8, 10); maximumSize = Dimension(Int.MAX_VALUE, 38)
                    isBorderPainted = false; isOpaque = true
                    putClientProperty("JButton.buttonType", null)
                }
                navigation[label] = control; add(control); add(Box.createVerticalStrut(3))
            }
        }, BorderLayout.NORTH)
        add(vertical().apply {
            background = Color(22, 51, 67); isOpaque = true; border = BorderFactory.createEmptyBorder(12, 0, 12, 0)
            add(JLabel("状态监听").apply { foreground = Color(175, 201, 230); font = font.deriveFont(11f); border = BorderFactory.createEmptyBorder(0, 12, 12, 0) })
            add(sidebarState.apply { foreground = Color(144, 220, 215); border = BorderFactory.createEmptyBorder(0, 12, 10, 0) })
            add(sidebarAccess.apply { foreground = Color(157, 183, 198); font = font.deriveFont(12f); border = BorderFactory.createEmptyBorder(0, 12, 0, 0) })
        }, BorderLayout.SOUTH)
    }

    private fun showPage(name: String) {
        pageLayout.show(pageHost, name)
        navigation.forEach { (label, control) ->
            val selected = name == label
            control.background = if (selected) Color(35, 77, 94) else navy
            control.foreground = if (selected) Color(174, 245, 233) else Color(203, 220, 230)
            control.font = control.font.deriveFont(if (selected) Font.BOLD else Font.PLAIN)
            control.border = BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, if (selected) 3 else 0, 0, 0, OceanUi.ice), BorderFactory.createEmptyBorder(8, if (selected) 9 else 12, 8, 10))
        }
    }

    private fun dashboard(): JPanel = page().apply {
        val body = vertical()
        body.add(heading("模型网关", "把分散的模型账号汇集到一个地址，供你的 AI 工具调用。"))
        serviceButton.addActionListener { if (engine.isRunning()) background("停止网关") { engine.stop() } else startGateway() }
        body.add(OceanUi.surface(JPanel(BorderLayout(18, 16)).apply {
            isOpaque = false
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                add(vertical().apply {
                    add(state.apply { font = font.deriveFont(Font.BOLD, 22f) })
                    add(accessHint.apply { foreground = OceanUi.muted; border = BorderFactory.createEmptyBorder(9, 0, 0, 0) })
                }, BorderLayout.CENTER)
                add(OceanUi.primary(serviceButton).apply { preferredSize = Dimension(132, 45) }, BorderLayout.EAST)
            }, BorderLayout.NORTH)
            add(vertical().apply {
                add(note("OpenAI 接入地址"))
                add(JPanel(BorderLayout(10, 0)).apply {
                    isOpaque = false
                    add(endpoint.apply { isEditable = false; font = Font("Consolas", Font.PLAIN, 20); preferredSize = Dimension(0, 46); background = Color(244, 249, 251) })
                    add(button("复制地址") { copy(engine.localUrl()) }, BorderLayout.EAST)
                })
            }, BorderLayout.CENTER)
        }, 18))
        body.add(Box.createVerticalStrut(14))
        body.add(JPanel(GridLayout(1, 4)).apply {
            background = Color.WHITE; border = BorderFactory.createLineBorder(OceanUi.line)
            listOf("供应商", "可用 / 全部账号", "今日调用", "今日 Tokens").forEachIndexed { index, label ->
                add(vertical().apply {
                    border = BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 0, if (index < 3) 1 else 0, OceanUi.line), BorderFactory.createEmptyBorder(12, 20, 12, 20))
                    add(metrics[index].apply { font = Font("Microsoft YaHei UI", Font.BOLD, 25); foreground = navy })
                    add(note(label))
                })
            }
        })
        body.add(Box.createVerticalStrut(14))
        body.add(OceanUi.surface(tokenBudget, 16))
        body.add(Box.createVerticalStrut(18))
        body.add(title("开始接入"))
        body.add(OceanUi.surface(vertical().apply {
            add(step("1", "添加模型账号", "选择供应商，通过浏览器授权或导入凭证。", button("管理账号") { showPage("供应商与账号") }))
            add(step("2", "选择模型", "刷新目录，复制带有供应商前缀的模型 ID。", button("查看模型") { showPage("模型") }))
            add(step("3", "连接 AI 工具", "选择 OpenAI 兼容协议，填入接入地址与模型 ID。", button("接入设置") { showPage("设置") }))
        }, 12))
        body.add(Box.createVerticalStrut(10))
        body.add(line(button("打开数据目录") { Desktop.getDesktop().open(dataDir) }, button("查看开源项目") { browse("https://github.com/xibai6668/Android-AI-gateway") }))
        add(scrollBody(body), BorderLayout.CENTER)
    }

    private fun step(number: String, label: String, help: String, action: JButton) = JPanel(BorderLayout(16, 0)).apply {
        isOpaque = false; border = BorderFactory.createEmptyBorder(6, 8, 6, 8)
        add(JLabel(number, SwingConstants.CENTER).apply { preferredSize = Dimension(34, 34); font = font.deriveFont(Font.BOLD, 19f); foreground = teal }, BorderLayout.WEST)
        add(vertical().apply { add(JLabel(label).apply { foreground = navy; font = font.deriveFont(Font.BOLD, 14f) }); add(note(help)) })
        add(action, BorderLayout.EAST)
    }

    private fun providersPage(): JPanel = page().apply {
        add(heading("供应商与账号", "选择供应商接入账号；同一供应商支持多个账号轮换调用。"), BorderLayout.NORTH)
        val split = object : JSplitPane(VERTICAL_SPLIT) {
            private var positioned = false
            override fun doLayout() {
                if (!positioned && height > 0) {
                    dividerLocation = (height * .53).toInt().coerceAtMost((height - 164 - dividerSize).coerceAtLeast(0))
                    positioned = true
                }
                super.doLayout()
            }
        }
        val providers = JPanel(BorderLayout(0, 8)).apply {
            minimumSize = Dimension(0, 170)
            add(vertical().apply {
                add(line(OceanUi.primary(button("浏览器登录") { browserLogin() }), button("短信登录") { smsLogin() }, button("导入凭证") { importAccount() }, button("添加中转站") { customProvider(null) },
                    more("更多操作", "取消登录" to { browserGeneration++; engine.stopCallbackServer(); footer.text = "已取消登录" }, "Antigravity OAuth 配置" to { configureAntigravityOAuth() }, "编辑中转站" to { editCustom() }, "启用 / 停用供应商" to { toggleProvider() }, "删除中转站" to { deleteCustom() })))
                add(line(button("TRAE 邀请注册") { traeInvitationLogin() }, note("添加其他 TRAE 账号前，先在浏览器中切换账号。")))
            }, BorderLayout.NORTH)
            add(OceanUi.scroll(providerTable), BorderLayout.CENTER)
        }
        val accountPane = JPanel(BorderLayout(0, 8)).apply {
            minimumSize = Dimension(0, 164)
            add(vertical().apply {
                add(JLabel("账号池").apply { foreground = navy; font = font.deriveFont(Font.BOLD, 16f) })
                add(line(OceanUi.primary(button("刷新额度") { background("刷新额度") {
                    val failures = engine.refreshAllCredits().filter { it.error.isNotEmpty() }
                    if (failures.isNotEmpty()) showText("额度刷新结果", failures.joinToString("\n") { "${it.providerId}/${it.nickname}: ${it.error}" })
                } }), button("启用 / 停用账号") { selectedAccount()?.let { a -> background("更新账号") { engine.setAccountEnabled(a.providerId, a.uid, !a.enabled) } } },
                    button("签到") { accountAction(ACTION_CHECKIN) }, more("更多账号操作", "查看任务" to { taskList() }, "领取任务奖励" to { accountAction(ACTION_TASKS) }, "删除账号" to { removeAccount() })))
            }, BorderLayout.NORTH)
            add(OceanUi.scroll(accountTable), BorderLayout.CENTER)
        }
        split.topComponent = providers; split.bottomComponent = accountPane; split.resizeWeight = .45
        split.border = null; split.dividerSize = 12; split.background = OceanUi.mist
        providers.isOpaque = false; accountPane.isOpaque = false
        add(split, BorderLayout.CENTER)
    }

    private fun modelsPage(): JPanel = page().apply {
        add(vertical().apply {
            add(heading("模型目录", "按名称或供应商筛选，复制完整模型 ID 到你的客户端。"))
            add(line(OceanUi.primary(button("刷新目录") { loadModels() }), button("复制模型 ID") { selectedModel()?.let { copy(it.fullId) } },
                button("测试延迟") { selectedModel()?.let { m -> background("测试 ${m.fullId}") {
                    val elapsed = engine.probeModelLatency(m.providerId, m.model.id, m.region)
                    SwingUtilities.invokeLater { latencies[m.fullId] = "${elapsed} ms"; renderModels() }
                } } }))
            add(search.apply { toolTipText = "搜索模型或供应商"; putClientProperty("JTextField.placeholderText", "搜索模型名称、模型 ID 或供应商…"); putClientProperty("JTextField.showClearButton", true); preferredSize = Dimension(0, 42); maximumSize = Dimension(Int.MAX_VALUE, 42) })
            add(note("能否调用取决于账号状态和上游额度；目录包含供应商内置快照。"))
        }, BorderLayout.NORTH)
        search.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = renderModels()
            override fun removeUpdate(e: DocumentEvent) = renderModels()
            override fun changedUpdate(e: DocumentEvent) = renderModels()
        })
        modelTable.columnModel.getColumn(0).preferredWidth = 400
        modelTable.columnModel.getColumn(1).preferredWidth = 260
        add(OceanUi.scroll(modelTable), BorderLayout.CENTER)
    }

    private fun logsPage(): JPanel = page().apply {
        add(vertical().apply {
            add(heading("调用记录", "查看请求结果、响应耗时和用量，定位客户端调用问题。"))
            add(line(button("刷新") { refreshLogs() }, button("查看请求详情") {
                val index = selectedIndex(logTable)
                engine.callLogStore.list().getOrNull(index)?.let {
                    showText("调用详情", "${it.providerId}/${it.model}\nHTTP ${it.httpStatus} · ${it.durationMillis} ms\n${it.error}\n\n请求\n${it.requestBody}\n\n响应\n${it.responseBody}\n\n原始响应\n${it.rawResponse}")
                }
            }, button("清空调用记录") {
                if (confirm("清空调用记录和相关用量统计？")) background("清空记录") { engine.callLogStore.clear() }
            }))
        }, BorderLayout.NORTH)
        val console = JPanel(BorderLayout()).apply {
            background = navy
            add(JLabel("运行日志").apply { foreground = Color(175, 221, 223); border = BorderFactory.createEmptyBorder(10, 14, 10, 14); font = font.deriveFont(Font.BOLD) }, BorderLayout.NORTH)
            add(OceanUi.scroll(runtimeLogs.apply {
                isEditable = false; font = Font("Microsoft YaHei UI", Font.PLAIN, 12)
                background = navy; foreground = Color(210, 231, 238); caretColor = Color.WHITE
                border = BorderFactory.createEmptyBorder(10, 14, 14, 14)
            }).apply { border = null })
        }
        console.minimumSize = Dimension(0, 100)
        val split = object : JSplitPane(VERTICAL_SPLIT, OceanUi.scroll(logTable), console) {
            private var positioned = false
            override fun doLayout() {
                if (!positioned && height > 0) { dividerLocation = (height * .60).toInt(); positioned = true }
                super.doLayout()
            }
        }
        split.resizeWeight = .6; split.dividerSize = 12; split.border = null; split.background = OceanUi.mist
        add(split, BorderLayout.CENTER)
    }

    private lateinit var stitchSettings: StitchSettingsPanel
    private fun settingsPage(): JPanel = StitchSettingsPanel(engine, dataDir, ::background, ::error, ::confirm, ::advancedFailover, loginStartup).also { stitchSettings = it }
    fun showOnLaunch(startup: Boolean) { isVisible = !startup || tray == null }
    fun startGateway() = background("启动网关") { engine.start() }

    private fun refreshStatus() {
        tokenBudget.refresh()
        val status = engine.status()
        state.text = if (status.running) "网关正在运行" else "网关已停止"
        state.foreground = if (status.running) teal else navy
        serviceButton.text = if (status.running) "停止网关" else "启动网关"
        endpoint.text = status.localUrl
        endpoint.toolTipText = if (engine.settings().exposeLan) status.lanUrls.joinToString("  ") else "仅本机连接"
        metrics[0].text = status.providerCount.toString()
        metrics[1].text = "${status.pool.usable} / ${status.pool.total}"
        metrics[2].text = status.today.requests.toString(); metrics[3].text = status.today.totalTokens.toString()
        val settings = engine.settings()
        accessHint.text = "${if (settings.exposeLan) "允许局域网连接" else "仅本机连接"} · ${if (settings.allowNoKey) "允许无 Key 调用" else "需要 API Key"}"
        sidebarState.text = if (status.running) "● 网关运行中" else "○ 网关已停止"
        sidebarAccess.text = if (settings.exposeLan) "允许局域网连接" else "仅本机连接"
        header.updateStatus(status.running, status.port, busyCount == 0)
        if (::stitchSettings.isInitialized) stitchSettings.refreshRuntimeStatus()
        trayMenu?.updateState(status.running, serviceButton.isEnabled)
        tray?.toolTip = "${AppIdentity.NAME} ${AppIdentity.VERSION} · ${if (status.running) "运行中 :${status.port}" else "已停止"}"
    }

    private fun refreshAccounts() {
        val selectedProvider = selectedProviderId(false)
        providerModel.rowCount = 0
        engine.providers().forEach { providerModel.addRow(arrayOf<Any>(it.displayName, it.id, it.accountCount, it.usableCount, if (it.enabled) "已启用" else "已停用")) }
        if (selectedProvider != null) for (row in 0 until providerModel.rowCount) if (providerModel.getValueAt(row, 1) == selectedProvider) providerTable.setRowSelectionInterval(row, row)
        val selection = selectedAccount(false)?.let { it.providerId to it.uid }
        accounts = engine.accounts()
        accountModel.rowCount = 0
        accounts.forEach { accountModel.addRow(arrayOf(it.providerId, it.nickname.ifEmpty { it.uid }, if (it.creditsKnown) it.credits.toString() else "未知",
            when { it.disabled -> "需重新登录"; !it.enabled -> "已停用"; else -> "可用" }, it.reason.ifEmpty { it.detail })) }
        accounts.indexOfFirst { it.providerId == selection?.first && it.uid == selection?.second }.takeIf { it >= 0 }?.let { accountTable.setRowSelectionInterval(it, it) }
    }

    private fun refreshLogs() {
        val selectedId = engine.callLogStore.list().getOrNull(selectedIndex(logTable))?.id
        logRows.rowCount = 0
        val records = engine.callLogStore.list()
        records.forEach { logRows.addRow(arrayOf(SimpleDateFormat("MM-dd HH:mm:ss").format(Date(it.startedAtMillis)), "${it.providerId}/${it.model}",
            it.accountNickname, it.status.toString(), "${it.durationMillis} ms", it.totalTokens)) }
        records.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 }?.let { logTable.setRowSelectionInterval(it, it) }
        val lines = engine.requestLog.lines().take(120).joinToString("\n") { it.render() }
        if (runtimeLogs.text != lines) runtimeLogs.text = lines
    }

    private fun loadModels() {
        if (modelsLoading) return
        modelsLoading = true
        val chunks = linkedMapOf<String, List<RoutedModel>>()
        background("刷新模型目录") {
            try {
                val catalog = engine.modelsStreaming { chunk ->
                    SwingUtilities.invokeLater { chunks[chunk.providerId] = chunk.models; models = chunks.values.flatten(); renderModels() }
                }
                SwingUtilities.invokeLater { models = catalog.models; renderModels() }
                if (catalog.failures.isNotEmpty()) showText("目录刷新结果", catalog.failures.entries.joinToString("\n") { "${it.key}: ${it.value}" })
            } finally { SwingUtilities.invokeLater { modelsLoading = false } }
        }
    }

    private fun renderModels() {
        tokenBudget.updateModels(models.map { it.fullId })
        val selectedId = modelTable.selectedRow.takeIf { it >= 0 }?.let { modelTable.getValueAt(it, 0).toString() }
        modelRows.rowCount = 0
        models.filter { it.fullId.contains(search.text, true) || it.model.name.contains(search.text, true) }.forEach {
            modelRows.addRow(arrayOf(it.fullId, it.model.name, if (it.model.contextWindow > 0) it.model.contextWindow.toString() else "未知", latencies[it.fullId] ?: "未测试"))
        }
        for (row in 0 until modelRows.rowCount) if (modelRows.getValueAt(row, 0) == selectedId) modelTable.setRowSelectionInterval(row, row)
    }

    private fun selectedModel(): RoutedModel? {
        val index = selectedIndex(modelTable)
        if (index < 0) { error("请先选择模型"); return null }
        return models.firstOrNull { it.fullId == modelRows.getValueAt(index, 0) }
    }

    private fun selectedProviderId(showError: Boolean = true): String? {
        val index = selectedIndex(providerTable)
        if (index < 0) { if (showError) error("请先选择供应商"); return null }
        return providerModel.getValueAt(index, 1).toString()
    }

    private fun selectedAccount(showError: Boolean = true): AccountStatus? {
        val account = accounts.getOrNull(selectedIndex(accountTable))
        if (account == null && showError) error("请先选择账号")
        return account
    }

    private fun browserLogin() {
        val id = selectedProviderId() ?: return
        val kind = engine.registry.get(id)?.authKind
        if (kind == AuthKind.SMS_CODE) { smsLogin(); return }
        if (kind == AuthKind.NONE) { editCustom(); return }
        if (id == "codebuddy") {
            val region = JOptionPane.showOptionDialog(this, "选择 WorkBuddy 账号区域", "浏览器登录", JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE, null, arrayOf("国内版", "国际版"), "国内版")
            if (region < 0) return
            val settings = engine.providerSettings(id)
            engine.updateProviderSettings(id, settings.copy(options = settings.options + ("region" to if (region == 0) "cn" else "global")))
        }
        authorizeBrowserLogin(id)
    }

    private fun configureAntigravityOAuth() {
        val settings = engine.providerSettings("antigravity")
        val clientId = JTextField(settings.options[AntigravityOAuthCredentials.CLIENT_ID_OPTION].orEmpty(), 34)
        val clientSecret = JPasswordField(settings.options[AntigravityOAuthCredentials.CLIENT_SECRET_OPTION].orEmpty(), 34)
        val form = formPanel()
        field(form, "OAuth Client ID", clientId)
        field(form, "OAuth Client Secret", clientSecret)
        field(form, "", note("填写您有权使用的 Google 桌面应用 OAuth 凭据；配置在本机加密保存。"))
        if (JOptionPane.showConfirmDialog(this, form, "Antigravity OAuth 配置", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return
        val id = clientId.text.trim()
        val secretChars = clientSecret.password
        val secret = String(secretChars).trim()
        secretChars.fill('\u0000')
        clientSecret.text = ""
        if (id.isEmpty() || secret.isEmpty()) { error("请同时填写 Client ID 和 Client Secret"); return }
        background("保存 Antigravity OAuth 配置") {
            val current = engine.providerSettings("antigravity")
            engine.updateProviderSettings("antigravity", current.copy(options = current.options + mapOf(
                AntigravityOAuthCredentials.CLIENT_ID_OPTION to id,
                AntigravityOAuthCredentials.CLIENT_SECRET_OPTION to secret,
            )))
        }
    }

    private fun traeInvitationLogin() {
        val generation = ++browserGeneration
        background("打开 TRAE 邀请登录页") {
            browse(TRAE_INVITE_URL)
            SwingUtilities.invokeLater {
                if (worker.isShutdown || generation != browserGeneration) return@invokeLater
                if (confirmTraeInvitation(this) && generation == browserGeneration) authorizeBrowserLogin("trae")
            }
        }
    }

    private fun authorizeBrowserLogin(id: String) {
        val generation = ++browserGeneration
        background("准备浏览器登录") {
            val ticket = engine.beginBrowserLogin(id)
            browse(ticket.loginUrl)
            SwingUtilities.invokeLater { footer.text = "请在浏览器中完成登录；授权最多等待 5 分钟" }
            if (ticket.pollState.isNotEmpty()) {
                val region = engine.providerSettings(id).option("region", "")
                val deadline = System.currentTimeMillis() + 300_000
                while (System.currentTimeMillis() < deadline && generation == browserGeneration) {
                    when (val result = engine.pollDeviceAuth(id, ticket.pollState, region)) {
                        is DeviceAuthPoll.Success -> return@background
                        is DeviceAuthPoll.Failed -> throw IllegalStateException(result.error)
                        DeviceAuthPoll.Pending -> Thread.sleep(2500)
                    }
                }
                if (generation == browserGeneration) throw IllegalStateException("浏览器登录超时，请重新登录")
            }
        }
    }

    private fun smsLogin() {
        val id = selectedProviderId() ?: return
        if (engine.registry.get(id)?.authKind != AuthKind.SMS_CODE) { error("该供应商不支持短信登录"); return }
        val phone = JOptionPane.showInputDialog(this, "手机号", "Loomy 短信登录", JOptionPane.QUESTION_MESSAGE)?.trim()?.takeIf { it.isNotEmpty() } ?: return
        background("发送验证码") {
            val msgid = engine.sendSmsCode(id, phone)
            SwingUtilities.invokeLater {
                val code = JOptionPane.showInputDialog(this, "请输入收到的验证码")?.trim()?.takeIf { it.isNotEmpty() } ?: return@invokeLater
                background("短信登录") { report(engine.completeSmsLogin(id, phone, code, msgid)) }
            }
        }
    }

    private fun importAccount() {
        val id = selectedProviderId() ?: return
        if (id.startsWith("custom:")) { editCustom(); return }
        val input = JTextArea(10, 55).apply { lineWrap = true; wrapStyleWord = true }
        if (JOptionPane.showConfirmDialog(this, JScrollPane(input), "导入 $id 凭证 JSON 或登录回调链接", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return
        val raw = input.text.trim(); input.text = ""
        if (raw.isEmpty()) return
        background("导入账号") {
            val outcome = when {
                raw.startsWith("http", true) && id == "antigravity" -> engine.completeOAuth(id, raw)
                raw.startsWith("http", true) -> engine.completeWebLogin(id, raw)
                else -> engine.importAccountJson(id, raw)
            }
            report(outcome)
        }
    }

    private fun report(result: LoginOutcome) { if (!result.ok) throw IllegalStateException(result.error) }

    private fun editCustom() {
        val id = selectedProviderId() ?: return
        val config = engine.settingsRepository.loadCustomProviders().firstOrNull { it.providerId == id }
        if (config == null) { error("请先选择自定义中转站"); return }
        customProvider(config)
    }

    private fun customProvider(existing: CustomProviderConfig?) {
        val key = JTextField(existing?.key ?: "").apply { isEditable = existing == null }
        val name = JTextField(existing?.name ?: "")
        val url = JTextField(existing?.baseUrl ?: "https://")
        val keys = JTextArea(existing?.apiKeys?.joinToString("\n") ?: "", 4, 42)
        val modelNames = JTextArea(existing?.models?.joinToString("\n") ?: "", 5, 42)
        val form = formPanel()
        field(form, "标识（字母数字）", key); field(form, "名称", name); field(form, "Base URL", url)
        field(form, "API Keys（每行一个）", JScrollPane(keys)); field(form, "模型（每行一个）", JScrollPane(modelNames))
        field(form, "", note("例如 https://example.com/v1；模型每行填写一个，或点击读取上游目录。"))
        field(form, "", button("读取上游模型目录") {
            val base = url.text.trim()
            val token = keys.text.lines().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            if (token.isEmpty()) { error("请先填写 API Key"); return@button }
            background("读取上游模型目录") {
                val found = dev.aigw.core.provider.custom.CustomProvider.fetchModels(base, token)
                if (found.isEmpty()) throw IllegalStateException("上游没有返回模型，请手动填写模型名")
                SwingUtilities.invokeLater { modelNames.text = found.joinToString("\n") }
            }
        })
        if (JOptionPane.showConfirmDialog(this, form, if (existing == null) "添加中转站" else "编辑中转站", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return
        val suffix = key.text.trim()
        if (!suffix.matches(Regex("[A-Za-z0-9_-]{1,40}"))) { error("标识只能包含 1–40 个字母、数字、下划线或短横线"); return }
        val list = engine.settingsRepository.loadCustomProviders()
        if (existing == null && list.any { it.key == suffix }) { error("标识已存在，请使用其他标识"); return }
        val base = url.text.trim().trimEnd('/')
        val valid = runCatching { URI(base).let { it.scheme in listOf("http", "https") && it.host != null && it.userInfo == null && it.query == null && it.fragment == null } }.getOrDefault(false)
        if (!valid) { error("Base URL 必须是有效的 HTTP 或 HTTPS 地址"); return }
        val apiKeys = keys.text.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (apiKeys.isEmpty()) { error("请至少填写一个 API Key"); return }
        val config = CustomProviderConfig(suffix, name.text.trim().ifEmpty { suffix }, base, apiKeys,
            models = modelNames.text.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct(), enabled = existing?.enabled ?: true)
        keys.text = ""
        background("保存中转站") {
            engine.settingsRepository.saveCustomProviders(list.filter { it.key != suffix } + config)
            engine.syncCustomAccounts(config)
            engine.updateProviderSettings(config.providerId, engine.providerSettings(config.providerId).copy(enabled = config.enabled))
            engine.reloadCustomProviders()
        }
    }

    private fun toggleProvider() {
        val id = selectedProviderId() ?: return
        val custom = engine.settingsRepository.loadCustomProviders().firstOrNull { it.providerId == id }
        background("更新供应商") {
            if (custom != null) {
                engine.settingsRepository.saveCustomProviders(engine.settingsRepository.loadCustomProviders().map { if (it.providerId == id) it.copy(enabled = !it.enabled) else it })
                engine.updateProviderSettings(id, engine.providerSettings(id).copy(enabled = !custom.enabled))
                engine.reloadCustomProviders()
            } else {
                val settings = engine.providerSettings(id)
                engine.updateProviderSettings(id, settings.copy(enabled = !settings.enabled))
            }
        }
    }

    private fun deleteCustom() {
        val id = selectedProviderId() ?: return
        if (!id.startsWith("custom:")) { error("内置供应商可停用，无法删除"); return }
        if (!confirm("删除 $id 及其账号？")) return
        background("删除中转站") {
            engine.accounts(id).forEach { engine.removeAccount(id, it.uid) }
            engine.settingsRepository.saveCustomProviders(engine.settingsRepository.loadCustomProviders().filter { it.providerId != id })
            engine.reloadCustomProviders()
        }
    }

    private fun removeAccount() {
        val account = selectedAccount() ?: return
        if (!confirm("删除账号 ${account.nickname.ifEmpty { account.uid }}？")) return
        background("删除账号") {
            // Custom keys are also stored in the provider config; remove both to prevent resurrection on restart.
            val custom = engine.settingsRepository.loadCustomProviders().firstOrNull { it.providerId == account.providerId }
            if (custom != null) {
                val secret = engine.account(account.providerId, account.uid)?.secret
                val apiKey = runCatching { com.google.gson.JsonParser.parseString(secret).asJsonObject.get("apiKey").asString }.getOrNull()
                engine.settingsRepository.saveCustomProviders(engine.settingsRepository.loadCustomProviders().map {
                    if (it.providerId == account.providerId) it.copy(apiKeys = it.apiKeys.filterNot { key -> key == apiKey }) else it
                })
            }
            engine.removeAccount(account.providerId, account.uid)
        }
    }

    private fun accountAction(action: String) {
        val account = selectedAccount() ?: return
        background(if (action == ACTION_CHECKIN) "签到" else "领取奖励") {
            val result = engine.performAction(account.providerId, account.uid, action)
            if (!result.ok) throw IllegalStateException(result.message)
            showText("执行结果", result.message.ifEmpty { "操作完成" })
        }
    }

    private fun taskList() {
        val account = selectedAccount() ?: return
        background("读取任务") {
            val tasks = engine.taskList(account.providerId, account.uid)
            showText("任务中心", tasks?.let { list ->
                if (list.error.isNotEmpty()) list.error
                else if (list.tasks.isEmpty()) "当前没有可查询的任务"
                else list.tasks.joinToString("\n\n") { "${it.title}  ${it.progressText()}\n${it.description}\n奖励：${it.rewardCredit}" }
            } ?: "该账号没有可查询的任务")
        }
    }

    private fun advancedFailover() {
        val json = JTextArea(GsonBuilder().setPrettyPrinting().create().toJson(engine.failoverSettings().toJson()), 20, 65)
        if (JOptionPane.showConfirmDialog(this, JScrollPane(json), "故障转移配置 JSON", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return
        try {
            com.google.gson.JsonParser.parseString(json.text).asJsonObject
            val settings = dev.aigw.core.failover.ModelFailoverSettings.fromJson(json.text)
            background("保存故障转移配置") { engine.updateFailoverSettings(settings) }
        } catch (e: Exception) { error("JSON 格式不正确") }
    }

    private fun installTray() {
        if (!SystemTray.isSupported()) return
        runCatching {
            val menu = ChineseTrayMenu(this, onOpen = { isVisible = true; extendedState = NORMAL; toFront() },
                onToggle = { serviceButton.doClick() }, onQuit = { quit() }, endpoint = { engine.localUrl() })
            trayMenu = menu
            tray = TrayIcon(OceanIcon.trayImage(), AppIdentity.NAME).apply {
                isImageAutoSize = true
                addActionListener { SwingUtilities.invokeLater { isVisible = true; extendedState = NORMAL; toFront() } }
                addMouseListener(object : MouseAdapter() {
                    override fun mouseReleased(e: MouseEvent) {
                        if (e.button != MouseEvent.BUTTON3) return
                        // AWT pointer coordinates use the same monitor scale as Swing window bounds.
                        val point = MouseInfo.getPointerInfo()?.location ?: Point(e.x, e.y)
                        SwingUtilities.invokeLater { menu.showAt(point, engine.isRunning(), serviceButton.isEnabled) }
                    }
                })
                SystemTray.getSystemTray().add(this)
            }
        }.onFailure { tray = null; trayMenu?.dispose(); trayMenu = null }
    }

    private fun quit() {
        browserGeneration++
        trayMenu?.dispose()
        clock.stop(); tray?.let { SystemTray.getSystemTray().remove(it) }
        engine.stopCallbackServer(); engine.stop(); worker.shutdownNow(); dispose(); exitProcess(0)
    }

    fun saveScreenshot(file: File) {
        Timer(600) {
            val screenshot = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            val graphics = screenshot.createGraphics(); paint(graphics); graphics.dispose()
            file.parentFile?.mkdirs(); ImageIO.write(screenshot, "png", file)
        }.apply { isRepeats = false; start() }
    }

    fun saveBannerScreenshot(file: File) = header.export(file)

    fun savePageScreenshots(directory: File) {
        directory.mkdirs()
        val pages = navigation.keys.map { it to null } + listOf("设置" to "网络代理", "设置" to "偏好与数据")
        var index = 0
        Timer(450) { event ->
            if (index > 0) {
                val screenshot = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
                val g = screenshot.createGraphics()
                try { paint(g) } finally { g.dispose() }
                val selected = pages[index - 1]
                ImageIO.write(screenshot, "png", File(directory, "${index}-${selected.second ?: selected.first}.png"))
            }
            if (index == pages.size) { (event.source as Timer).stop(); showPage("网关") }
            else {
                val selected = pages[index]
                showPage(selected.first)
                selected.second?.let { stitchSettings.showSection(it) }
                index++
            }
        }.apply { initialDelay = 650; start() }
    }

    fun saveTrayScreenshot(file: File) {
        Timer(900) {
            val menu = trayMenu ?: return@Timer
            val screen = graphicsConfiguration.bounds
            menu.showAt(Point(screen.x + screen.width - 20, screen.y + screen.height - 20), engine.isRunning())
            val screenshot = BufferedImage(menu.width, menu.height, BufferedImage.TYPE_INT_RGB)
            val graphics = screenshot.createGraphics()
            try { menu.paint(graphics) } finally { graphics.dispose() }
            file.parentFile?.mkdirs(); ImageIO.write(screenshot, "png", file)
            menu.isVisible = false
        }.apply { isRepeats = false; start() }
    }

    private fun background(label: String, operation: () -> Unit) {
        busyCount++; footer.text = "$label…"
        serviceButton.isEnabled = false
        header.updateStatus(engine.isRunning(), engine.settings().port, false)
        trayMenu?.updateState(engine.isRunning(), false)
        worker.submit {
            var failure: String? = null
            try { operation() } catch (e: Exception) { failure = e.message ?: "操作失败" }
            SwingUtilities.invokeLater {
                busyCount--; serviceButton.isEnabled = busyCount == 0
                refreshAccounts(); refreshStatus(); refreshLogs()
                footer.text = if (failure == null) "$label：完成" else "$label：失败"
                failure?.let { error(it) }
            }
        }
    }

    private fun copy(value: String) { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(value), null); footer.text = "已复制：$value" }
    private fun browse(url: String) { browserOpener(url) }
    private fun error(message: String) {
        if (!isVisible) { isVisible = true; extendedState = NORMAL; toFront() }
        JOptionPane.showMessageDialog(this, message, "操作失败", JOptionPane.ERROR_MESSAGE)
    }
    private fun confirm(message: String) = JOptionPane.showConfirmDialog(this, message, "确认操作", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION
    private fun showText(title: String, text: String) {
        SwingUtilities.invokeLater { JOptionPane.showMessageDialog(this, JScrollPane(JTextArea(text, 20, 70).apply { isEditable = false; lineWrap = true; wrapStyleWord = true }), title, JOptionPane.INFORMATION_MESSAGE) }
    }
    private fun page() = JPanel(BorderLayout(0, 12)).apply { border = BorderFactory.createEmptyBorder(20, 26, 16, 26); background = OceanUi.mist }
    private fun heading(label: String, help: String) = vertical().apply {
        add(JLabel(label).apply { font = font.deriveFont(Font.BOLD, 25f); foreground = navy })
        add(note(help)); add(Box.createVerticalStrut(6))
    }
    private fun scrollBody(component: JComponent): JScrollPane {
        val wrapper = object : JPanel(BorderLayout()), Scrollable {
            override fun getPreferredScrollableViewportSize() = preferredSize
            override fun getScrollableUnitIncrement(rect: Rectangle, orientation: Int, direction: Int) = 20
            override fun getScrollableBlockIncrement(rect: Rectangle, orientation: Int, direction: Int) = (rect.height - 20).coerceAtLeast(20)
            override fun getScrollableTracksViewportWidth() = true
            override fun getScrollableTracksViewportHeight() = false
        }.apply { isOpaque = false; add(component, BorderLayout.NORTH) }
        return JScrollPane(wrapper).apply {
        border = null; viewport.isOpaque = false; isOpaque = false
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBar.unitIncrement = 20
        }
    }
    private fun more(label: String, vararg actions: Pair<String, () -> Unit>) = button(label) {}.apply {
        val menu = JPopupMenu().apply { actions.forEach { (name, action) -> add(JMenuItem(name).apply { addActionListener { action() } }) } }
        addActionListener { menu.show(this, 0, height) }
    }
    private fun title(text: String) = JLabel(text).apply { font = font.deriveFont(Font.BOLD, 19f); foreground = navy; border = BorderFactory.createEmptyBorder(0, 0, 8, 0) }
    private fun note(text: String) = JLabel(text).apply { foreground = OceanUi.muted; border = BorderFactory.createEmptyBorder(4, 0, 5, 0) }
    private fun vertical() = object : JPanel() {
        override fun addImpl(component: Component, constraints: Any?, index: Int) {
            if (component is JComponent) component.alignmentX = Component.LEFT_ALIGNMENT
            super.addImpl(component, constraints, index)
        }
    }.apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false }
    private fun line(vararg components: Component) = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply { isOpaque = false; components.forEach { add(it) }; alignmentX = Component.LEFT_ALIGNMENT }
    private fun button(label: String, action: () -> Unit) = JButton(label).apply { margin = Insets(7, 14, 7, 14); addActionListener { action() } }
    private fun formPanel() = JPanel(GridBagLayout()).apply { isOpaque = false }
    private fun field(panel: JPanel, label: String, component: Component) {
        if (component is JTextField || component is JComboBox<*> || component is JSpinner) {
            component.preferredSize = Dimension(260, 36)
        }
        val row = panel.componentCount / 2
        panel.add(JLabel(label), GridBagConstraints().apply { gridx = 0; gridy = row; anchor = GridBagConstraints.NORTHWEST; insets = Insets(7, 0, 7, 18) })
        panel.add(component, GridBagConstraints().apply { gridx = 1; gridy = row; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; anchor = GridBagConstraints.NORTHWEST; insets = Insets(7, 0, 7, 0) })
    }
    private fun rows(vararg columns: String) = object : DefaultTableModel(columns, 0) { override fun isCellEditable(row: Int, column: Int) = false }
    private fun selectedIndex(table: JTable) = table.selectedRow.takeIf { it >= 0 }?.let { table.convertRowIndexToModel(it) } ?: -1
}
