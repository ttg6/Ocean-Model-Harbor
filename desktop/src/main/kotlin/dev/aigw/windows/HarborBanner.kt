package dev.aigw.windows

import java.awt.*
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.*

/** Offline, native rendering of the compact brand/status header supplied by Stitch. */
internal class HarborBanner(onRestart: () -> Unit = {}) : JPanel(BorderLayout(20, 0)) {
    private val status = JLabel("○ 服务已停止")
    private val port = JLabel("本地端口 8790")
    private val restart = OceanUi.primary(JButton("启动服务").apply {
        margin = Insets(7, 12, 7, 12); addActionListener { onRestart() }
    })
    init {
        preferredSize = Dimension(1280, 100); background = OceanUi.navy
        border = BorderFactory.createEmptyBorder(18, 24, 18, 24)
        getAccessibleContext().accessibleName = "${AppIdentity.NAME}，多账号 AI 模型本地中继网关"
        add(JPanel(BorderLayout(16, 0)).apply {
            isOpaque = false
            add(JLabel(OceanIcon.swing(46)), BorderLayout.WEST)
            add(OceanUi.column().apply {
                add(Box.createVerticalStrut(5))
                add(JLabel("${AppIdentity.NAME}   ").apply { foreground = Color.WHITE; font = Font("Microsoft YaHei UI", Font.BOLD, 23) })
                add(Box.createVerticalStrut(7))
                add(JLabel("多账号 AI 模型本地中继网关 · 汇聚模型，连接你的工具").apply { foreground = Color(175, 201, 230); font = Font("Microsoft YaHei UI", Font.PLAIN, 11) })
            })
        })
        add(JPanel(BorderLayout(14, 0)).apply {
            background = Color(22, 51, 67); border = BorderFactory.createEmptyBorder(8, 14, 8, 12)
            add(OceanUi.column().apply {
                add(port.apply { foreground = Color(175, 201, 230); font = Font("Microsoft YaHei UI", Font.PLAIN, 11) })
                add(Box.createVerticalStrut(6)); add(status.apply { foreground = OceanUi.ice; font = Font("Microsoft YaHei UI", Font.PLAIN, 12) })
            })
            add(restart, BorderLayout.EAST)
        }, BorderLayout.EAST)
    }
    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val g = graphics.create() as Graphics2D
        try {
            g.paint = GradientPaint(width * .60f, 0f, OceanUi.navy, width.toFloat(), height.toFloat(), Color(24, 66, 78))
            g.fillRect(0, 0, width, height)
            g.color = Color(41, 81, 100); g.drawLine(0, height - 1, width, height - 1)
        } finally { g.dispose() }
    }
    fun updateStatus(running: Boolean, localPort: Int, enabled: Boolean = true) {
        port.text = "本地端口 $localPort"
        status.text = if (running) "● 服务运行中" else "○ 服务已停止"
        restart.text = if (running) "重启网关" else "启动服务"; restart.isEnabled = enabled
    }
    fun export(file: File) {
        val image = BufferedImage(width.coerceAtLeast(940), height.coerceAtLeast(100), BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try { paint(graphics) } finally { graphics.dispose() }
        file.parentFile?.mkdirs(); ImageIO.write(image, "png", file)
    }
}
