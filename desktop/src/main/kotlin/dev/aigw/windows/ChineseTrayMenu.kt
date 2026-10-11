package dev.aigw.windows

import com.sun.jna.Native
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import java.awt.*
import java.awt.event.*
import javax.swing.*

/** Independent control window: a hidden main frame must not prevent focus or dismissal. */
internal class ChineseTrayMenu(private val mainWindow: Frame, onOpen: () -> Unit, onToggle: () -> Unit,
    onQuit: () -> Unit, private val endpoint: () -> String = { "http://127.0.0.1:8790/v1" }) :
    JDialog(null as Window?, "${AppIdentity.NAME} · 快捷控制", Dialog.ModalityType.MODELESS) {
    private val chineseFont = Font("Microsoft YaHei UI", Font.PLAIN, 14)
    private val status = JLabel()
    private val address = JLabel()
    private val openButton = menuButton("打开 ${AppIdentity.NAME}", onOpen)
    private val toggleButton = menuButton("启动网关", onToggle)
    private val quitButton = menuButton("退出程序", onQuit)
    private val buttons = listOf(openButton, toggleButton, quitButton)
    private var openedAt = 0L
    private val escape = KeyEventDispatcher { event ->
        if (isVisible && isFocused && event.id == KeyEvent.KEY_PRESSED && event.keyCode == KeyEvent.VK_ESCAPE) {
            isVisible = false
            true
        } else false
    }
    private val dismissal = Timer(40) {
        if (isVisible && System.nanoTime() - openedAt > 150_000_000 && outsideMouseDown()) isVisible = false
    }

    init {
        isUndecorated = true; isResizable = false; isAlwaysOnTop = true
        type = Window.Type.UTILITY; focusableWindowState = true
        iconImages = OceanIcon.windowImages()
        defaultCloseOperation = HIDE_ON_CLOSE
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(escape)
        contentPane = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS); background = Color.WHITE
            border = BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(OceanUi.line),
                BorderFactory.createEmptyBorder(14, 10, 10, 10))
            add(JPanel(BorderLayout(10, 0)).apply {
                isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT
                border = BorderFactory.createEmptyBorder(0, 6, 12, 6)
                add(JLabel(OceanIcon.swing(36)), BorderLayout.WEST)
                add(JPanel(GridLayout(2, 1)).apply {
                    isOpaque = false
                    add(JLabel(AppIdentity.NAME).apply { font = chineseFont.deriveFont(Font.BOLD, 16f); foreground = OceanUi.navy })
                    add(JLabel("快捷控制 · ${AppIdentity.VERSION}").apply { font = chineseFont.deriveFont(11f); foreground = OceanUi.muted })
                })
            })
            add(JPanel(GridLayout(2, 1, 0, 5)).apply {
                alignmentX = Component.LEFT_ALIGNMENT; background = OceanUi.mist
                border = BorderFactory.createEmptyBorder(10, 12, 10, 12)
                add(status.apply { font = chineseFont.deriveFont(Font.BOLD, 12f) })
                add(address.apply { font = chineseFont.deriveFont(11f); foreground = OceanUi.muted })
            })
            add(Box.createVerticalStrut(8)); add(openButton); add(toggleButton)
            add(JSeparator().apply { maximumSize = Dimension(Int.MAX_VALUE, 8) })
            add(quitButton.apply { foreground = Color(154, 54, 54) })
        }
        rootPane.registerKeyboardAction({ isVisible = false }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW)
        buttons.forEachIndexed { index, button ->
            for ((key, step) in listOf(KeyEvent.VK_UP to -1, KeyEvent.VK_DOWN to 1)) {
                button.registerKeyboardAction({ buttons[(index + step + buttons.size) % buttons.size].requestFocusInWindow() },
                    KeyStroke.getKeyStroke(key, 0), JComponent.WHEN_FOCUSED)
            }
            button.registerKeyboardAction({ button.doClick() }, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), JComponent.WHEN_FOCUSED)
        }
        addWindowFocusListener(object : WindowAdapter() {
            override fun windowGainedFocus(e: WindowEvent) { openButton.requestFocusInWindow() }
            override fun windowLostFocus(e: WindowEvent) {
                // Explorer can close its overflow window while our tray window is being activated.
                if (System.nanoTime() - openedAt > 150_000_000) isVisible = false
            }
        })
        addComponentListener(object : ComponentAdapter() {
            override fun componentHidden(e: ComponentEvent) { dismissal.stop() }
        })
    }

    private fun menuButton(label: String, action: () -> Unit) = JButton(label).apply {
        font = chineseFont; horizontalAlignment = SwingConstants.LEFT; alignmentX = Component.LEFT_ALIGNMENT
        minimumSize = Dimension(264, 40); preferredSize = Dimension(264, 40); maximumSize = Dimension(Int.MAX_VALUE, 40)
        border = BorderFactory.createEmptyBorder(8, 12, 8, 12); foreground = OceanUi.navy
        putClientProperty("JButton.buttonType", "borderless")
        addActionListener { this@ChineseTrayMenu.isVisible = false; action() }
    }

    fun updateState(running: Boolean, canToggle: Boolean = true) {
        status.text = if (running) "● 网关运行中" else "○ 网关已停止"
        status.foreground = if (running) OceanUi.teal else OceanUi.muted
        address.text = endpoint()
        toggleButton.text = if (running) "停止网关" else "启动网关"
        toggleButton.isEnabled = canToggle
    }

    fun showAt(point: Point, running: Boolean, canToggle: Boolean = true) {
        check(SwingUtilities.isEventDispatchThread())
        updateState(running, canToggle); pack()
        val configuration = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
            .map { it.defaultConfiguration }.firstOrNull { it.bounds.contains(point) } ?: mainWindow.graphicsConfiguration
        val bounds = Rectangle(configuration.bounds)
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration)
        bounds.x += insets.left; bounds.y += insets.top
        bounds.width -= insets.left + insets.right; bounds.height -= insets.top + insets.bottom
        location = TrayPopupPlacement.position(point, size, bounds)
        openedAt = System.nanoTime(); isVisible = true; toFront()
        // Native tray popups need foreground activation for outside-click dismissal.
        if (System.getProperty("os.name").startsWith("Windows")) runCatching {
            val handle = HWND(Native.getWindowPointer(this))
            User32.INSTANCE.SetForegroundWindow(handle)
        }
        requestFocus(); openButton.requestFocusInWindow(); dismissal.start()
    }

    private fun outsideMouseDown(): Boolean {
        if (!System.getProperty("os.name").startsWith("Windows")) return false
        return runCatching {
            // Only the high bit indicates a button currently held down.
            val down = listOf(1, 2, 4).any { User32.INSTANCE.GetAsyncKeyState(it).toInt() < 0 }
            down && MouseInfo.getPointerInfo()?.location?.let { !bounds.contains(it) } == true
        }.getOrDefault(false)
    }

    override fun dispose() {
        dismissal.stop()
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(escape)
        super.dispose()
    }
}
