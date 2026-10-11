package dev.aigw.windows

import java.awt.*
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

internal object OceanUi {
    val navy = Color(16, 44, 67)
    val teal = Color(0, 104, 122)
    val mist = Color(245, 250, 255)
    val muted = Color(67, 71, 77)
    val line = Color(221, 241, 255)
    val input = Color(233, 245, 255)
    val ice = Color(169, 240, 228)

    fun primary(button: JButton) = button.apply {
        background = teal; foreground = Color.WHITE
        putClientProperty("JButton.buttonType", null)
        font = font.deriveFont(Font.BOLD)
    }

    fun surface(component: JComponent, padding: Int = 18) = object : JPanel(BorderLayout()) {
        override fun paintComponent(graphics: Graphics) {
            val g = graphics.create() as Graphics2D
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.color = Color(16, 44, 67, 8); g.fillRoundRect(0, 2, width, height - 2, 16, 16)
                g.color = background; g.fillRoundRect(0, 0, width, height - 2, 16, 16)
            } finally { g.dispose() }
        }
    }.apply {
        background = Color.WHITE; isOpaque = false
        border = BorderFactory.createEmptyBorder(padding, padding, padding + 2, padding)
        add(component)
    }

    fun description(text: String, width: Int = 190) = JLabel("<html><div style='width:${width}px'>${text.replace("&", "&amp;").replace("<", "&lt;")}</div></html>").apply {
        foreground = muted; font = Font("Microsoft YaHei UI", Font.PLAIN, 12)
    }

    fun column() = object : JPanel() {
        override fun addImpl(component: Component, constraints: Any?, index: Int) {
            if (component is JComponent) component.alignmentX = LEFT_ALIGNMENT
            super.addImpl(component, constraints, index)
        }
    }.apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false }

    fun switch(label: String, selected: Boolean): JCheckBox = JCheckBox(label, selected).apply {
        isOpaque = false; iconTextGap = 10
        fun switchIcon(on: Boolean) = object : Icon {
            override fun getIconWidth() = 42
            override fun getIconHeight() = 24
            override fun paintIcon(c: Component, graphics: Graphics, x: Int, y: Int) {
                val g = graphics.create() as Graphics2D
                try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g.color = if (on) teal else Color(201, 231, 251)
                    g.fillRoundRect(x, y + 1, 42, 22, 22, 22)
                    g.color = Color.WHITE; g.fillOval(x + if (on) 22 else 2, y + 3, 18, 18)
                } finally { g.dispose() }
            }
        }
        icon = switchIcon(false); selectedIcon = switchIcon(true)
        fun updateLabel() { text = if (isSelected) "已开启" else "已关闭"; foreground = if (isSelected) teal else muted }
        getAccessibleContext().accessibleName = label
        toolTipText = label; name = label
        addItemListener { updateLabel() }; updateLabel()
    }

    fun scroll(component: Component) = JScrollPane(component).apply {
        border = BorderFactory.createLineBorder(line)
        viewport.background = Color.WHITE
        verticalScrollBar.unitIncrement = 20
    }

    fun table(model: DefaultTableModel, emptyTitle: String, emptyHelp: String): JTable = object : JTable(model) {
        override fun paintComponent(graphics: Graphics) {
            super.paintComponent(graphics)
            if (rowCount != 0) return
            val g = graphics.create() as Graphics2D
            try {
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                val y = (height / 2 - 10).coerceAtLeast(35)
                g.font = font.deriveFont(Font.BOLD, 17f); g.color = navy
                g.drawString(emptyTitle, ((width - g.fontMetrics.stringWidth(emptyTitle)) / 2).coerceAtLeast(12), y)
                g.font = font.deriveFont(12f); g.color = muted
                g.drawString(emptyHelp, ((width - g.fontMetrics.stringWidth(emptyHelp)) / 2).coerceAtLeast(12), y + 28)
            } finally { g.dispose() }
        }
    }.apply {
        rowHeight = 42; autoCreateRowSorter = true
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        fillsViewportHeight = true; showHorizontalLines = true; showVerticalLines = false
        gridColor = Color(238, 243, 246); intercellSpacing = Dimension(0, 1)
        selectionBackground = Color(219, 241, 241); selectionForeground = navy
        foreground = navy; background = Color.WHITE
        tableHeader.apply {
            reorderingAllowed = false; preferredSize = Dimension(0, 39)
            background = Color(242, 247, 249); foreground = muted
            font = font.deriveFont(Font.BOLD)
        }
        setDefaultRenderer(Any::class.java, object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(t: JTable, value: Any?, selected: Boolean, focus: Boolean, row: Int, column: Int): Component {
                super.getTableCellRendererComponent(t, value, selected, focus, row, column)
                border = BorderFactory.createEmptyBorder(0, 12, 0, 12)
                if (!selected) {
                    background = if (row % 2 == 0) Color.WHITE else Color(248, 251, 252)
                    foreground = when (value?.toString()) {
                        "可用", "已启用", "SUCCESS" -> teal
                        "需重新登录", "FAILED", "FAILURE" -> Color(172, 74, 53)
                        "已停用", "未知", "未测试" -> muted
                        else -> navy
                    }
                }
                toolTipText = value?.toString()
                return this
            }
        })
    }

    fun navIcon(index: Int) = object : Icon {
        override fun getIconWidth() = 22
        override fun getIconHeight() = 22
        override fun paintIcon(c: Component, graphics: Graphics, x: Int, y: Int) {
            val g = graphics.create() as Graphics2D
            try {
                g.translate(x, y); g.color = c.foreground
                g.stroke = BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                when (index) {
                    0 -> { g.drawRoundRect(2, 3, 18, 13, 3, 3); g.drawLine(7, 20, 15, 20); g.drawLine(11, 16, 11, 20); g.drawLine(6, 8, 10, 8); g.drawLine(6, 12, 16, 12) }
                    1 -> { g.drawOval(3, 2, 7, 7); g.drawArc(1, 11, 12, 10, 0, 180); g.drawOval(14, 5, 5, 5); g.drawArc(12, 13, 10, 8, 0, 160) }
                    2 -> { g.drawRect(5, 5, 12, 12); for (a in listOf(8, 14)) { g.drawLine(a, 1, a, 5); g.drawLine(a, 17, a, 21); g.drawLine(1, a, 5, a); g.drawLine(17, a, 21, a) }; g.drawRect(9, 9, 4, 4) }
                    3 -> { g.drawRoundRect(4, 2, 14, 18, 2, 2); for (a in listOf(7, 11, 15)) g.drawLine(8, a, 14, a) }
                    else -> { g.drawOval(3, 3, 16, 16); g.drawOval(8, 8, 6, 6); g.drawLine(11, 0, 11, 3); g.drawLine(11, 19, 11, 22); g.drawLine(0, 11, 3, 11); g.drawLine(19, 11, 22, 11) }
                }
            } finally { g.dispose() }
        }
    }
}
