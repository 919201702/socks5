package com.itjiang.gui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.itjiang.core.ClientBoot; // 假设这是你的类

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

public class App extends JFrame {

    private static final Logger logger = LoggerFactory.getLogger(App.class);

    // 换用 JTextPane 以支持彩色
    private JTextPane logPane;
    private final JScrollPane scrollPane;
    private final JToggleButton filterToggleBtn;

    // 样式定义
    private SimpleAttributeSet infoStyle;
    private SimpleAttributeSet errorStyle;
    private SimpleAttributeSet warnStyle;
    private SimpleAttributeSet debugStyle;
    private SimpleAttributeSet defaultStyle;

    // 过滤控制标志
    public static volatile boolean isFilterEnabled = true;

    public App() {
        // 1. UI 初始化
        setTitle("Socks5 Tunnel Monitor");
        setSize(900, 650);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        // --- 样式初始化 ---
        initStyles();

        // --- 顶部控制栏 ---
        filterToggleBtn = new JToggleButton("过滤中");
        filterToggleBtn.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 12));
        filterToggleBtn.setBackground(new Color(45, 45, 45)); // 深色按钮
        filterToggleBtn.setForeground(Color.GREEN);
        filterToggleBtn.setFocusPainted(false);
        filterToggleBtn.setSelected(true);
        filterToggleBtn.addActionListener(e -> toggleFilter());

        JButton clearBtn = new JButton("清空日志");
        clearBtn.setFocusPainted(false);
        clearBtn.addActionListener(e -> logPane.setText(""));

        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        topPanel.setBackground(new Color(60, 63, 65)); // 深灰色背景
        topPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        JLabel label = new JLabel("控制台操作: ");
        label.setForeground(Color.WHITE);
        topPanel.add(label);
        topPanel.add(filterToggleBtn);
        topPanel.add(clearBtn);
        add(topPanel, BorderLayout.NORTH);

        // --- 中间日志显示区域 (JTextPane) ---
        logPane = new JTextPane();
        logPane.setEditable(false);
        logPane.setFont(new Font("JetBrains Mono", Font.PLAIN, 13)); // 推荐等宽字体
        if (logPane.getFont().getFamily().equals("Dialog")) {
            logPane.setFont(new Font("Consolas", Font.PLAIN, 14)); // 备选
        }
        logPane.setBackground(new Color(30, 30, 30)); // 深邃黑
        logPane.setForeground(new Color(187, 187, 187)); // 柔和白
        // 内边距，让字不要贴着边
        logPane.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        scrollPane = new JScrollPane(logPane);
        scrollPane.setBorder(null); // 去掉滚动条边框
        // 隐藏水平滚动条（自动换行）
        // scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

        add(scrollPane, BorderLayout.CENTER);

        // 2. 重定向 System.err
        redirectSystemErr();

        // 3. 启动客户端
        startClient();
    }

    private void initStyles() {
        // 通用基础样式
        defaultStyle = new SimpleAttributeSet();
        StyleConstants.setForeground(defaultStyle, new Color(187, 187, 187));

        infoStyle = new SimpleAttributeSet();
        StyleConstants.setForeground(infoStyle, new Color(98, 151, 85)); // 绿色

        errorStyle = new SimpleAttributeSet();
        StyleConstants.setForeground(errorStyle, new Color(204, 102, 102)); // 红色
        StyleConstants.setBold(errorStyle, true);

        warnStyle = new SimpleAttributeSet();
        StyleConstants.setForeground(warnStyle, new Color(209, 154, 102)); // 橙色

        debugStyle = new SimpleAttributeSet();
        StyleConstants.setForeground(debugStyle, new Color(104, 151, 187)); // 蓝色
    }

    private void toggleFilter() {
        isFilterEnabled = filterToggleBtn.isSelected();
        if (isFilterEnabled) {
            filterToggleBtn.setText("过滤中");
            filterToggleBtn.setForeground(Color.GREEN);
        } else {
            filterToggleBtn.setText("已暂停");
            filterToggleBtn.setForeground(Color.GRAY);
        }
    }

    private void redirectSystemErr() {
        OutputStream guiOutputStream = new OutputStream() {
            @Override
            public void write(int b) {
                write(new byte[]{(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) {
                String logMsg = new String(b, off, len, StandardCharsets.UTF_8);
                // 在 EDT 中更新
                SwingUtilities.invokeLater(() -> appendToPane(logMsg));
            }
        };
        System.setErr(new PrintStream(guiOutputStream, true, StandardCharsets.UTF_8));
    }

    /**
     * 核心方法：带颜色解析 + 智能滚动的追加逻辑
     */
    private void appendToPane(String msg) {
        // 1. 判断当前是否在底部 (智能滚动核心)
        JScrollBar verticalBar = scrollPane.getVerticalScrollBar();
        // 允许 10px 的误差，因为 swing 这里的计算有时候有微小偏差
        boolean isAtBottom = (verticalBar.getValue() + verticalBar.getVisibleAmount() >= verticalBar.getMaximum() - 20);

        // 2. 解析日志级别并选择样式
        SimpleAttributeSet currentStyle = defaultStyle;
        if (msg.contains("INFO")) {
            currentStyle = infoStyle;
        } else if (msg.contains("ERROR") || msg.contains("Exception")) {
            currentStyle = errorStyle;
        } else if (msg.contains("WARN")) {
            currentStyle = warnStyle;
        } else if (msg.contains("DEBUG")) {
            currentStyle = debugStyle;
        }

        // 3. 插入文本
        StyledDocument doc = logPane.getStyledDocument();
        try {
            doc.insertString(doc.getLength(), msg, currentStyle);
        } catch (BadLocationException e) {
            e.printStackTrace();
        }

        // 4. 只有当用户原本就在最底部时，才自动滚动；
        //    如果用户滑上去看历史了，就不要打扰他。
        if (isAtBottom) {
            // 必须 invokeLater 否则 scrollTo 可能会因为文档还没渲染完而计算错误
            SwingUtilities.invokeLater(() -> {
                verticalBar.setValue(verticalBar.getMaximum());
            });
        }
    }

    private void startClient() {
        Thread.ofVirtual().name("Log-Simulator").start(() -> {
            ClientBoot.boot(null);
        });
    }

    public static void main(String[] args) {
        // 启用抗锯齿，让文字更清晰
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");

        try {
            // 尝试使用系统原生风格（Windows下更自然）
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        SwingUtilities.invokeLater(() -> new App().setVisible(true));
    }
}