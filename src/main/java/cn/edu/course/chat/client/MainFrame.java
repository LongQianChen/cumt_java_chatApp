package cn.edu.course.chat.client;

import cn.edu.course.chat.common.Frame;
import cn.edu.course.chat.common.MessageType;
import cn.edu.course.chat.common.Validation;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.InputMethodEvent;
import java.awt.event.InputMethodListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class MainFrame extends JFrame {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private static final int MAX_PENDING = 128;
    private final HistoryStore store;
    private final ConversationModel model = new ConversationModel();
    private final ExecutorService cleanup = Executors.newSingleThreadExecutor(runnable ->
            new Thread(runnable, "chat-window-cleanup"));
    private final JTextField hostField;
    private final JTextField portField;
    private final JTextField nicknameField;
    private final JButton connectButton = new JButton("连接");
    private final JButton sendButton = new JButton("发送");
    private final JLabel stateLabel = new JLabel("未连接");
    private final JLabel endpointLabel = new JLabel("尚未建立连接");
    private final JLabel conversationLabel = new JLabel("公共聊天室");
    private final JLabel historyLabel = new JLabel("正在加载历史记录");
    private final JLabel feedbackLabel = new JLabel("Enter 发送，Shift+Enter 换行；双击在线用户打开私聊");
    private final DefaultListModel<String> conversationItems = new DefaultListModel<>();
    private final DefaultListModel<String> onlineItems = new DefaultListModel<>();
    private final JList<String> conversationList = new JList<>(conversationItems);
    private final JList<String> onlineList = new JList<>(onlineItems);
    private final JTextArea messagesArea = new JTextArea();
    private final JTextArea inputArea = new JTextArea(4, 30);
    private final JTextArea pendingArea = new JTextArea(2, 30);
    private final Set<String> online = new HashSet<>();
    private final List<Frame> notices = new ArrayList<>();
    private final Map<Long, Pending> pending = new LinkedHashMap<>();
    private final List<Pending> unconfirmed = new ArrayList<>();
    private final ArrayBlockingQueue<Runnable> uiEvents = new ArrayBlockingQueue<>(256);
    private final AtomicBoolean drainScheduled = new AtomicBoolean();
    private final AtomicBoolean deliveryOverloaded = new AtomicBoolean();
    private final AtomicReference<String> latestHistoryStatus = new AtomicReference<>();
    private final AtomicBoolean historyStatusScheduled = new AtomicBoolean();
    private volatile ChatConnection connection;
    private ChatConnection.State state = ChatConnection.State.DISCONNECTED;
    private String ownNickname = "";
    private String joinedHost = "";
    private int joinedPort;
    private volatile long generation;
    private boolean loading = true;
    private boolean preparing;
    private volatile boolean closing;
    private boolean composing;
    private long lastCompositionCommit;
    private boolean rebuildingConversations;

    public MainFrame(String host, int port, String nickname, HistoryStore store) {
        super("局域网聊天 · " + store.directory().getFileName());
        requireEdt();
        this.store = store;
        hostField = new JTextField(host, 13);
        portField = new JTextField(Integer.toString(port), 5);
        nicknameField = new JTextField(nickname, 10);
        ownNickname = nickname;
        hostField.getAccessibleContext().setAccessibleName("服务端 IP");
        portField.getAccessibleContext().setAccessibleName("服务端端口");
        nicknameField.getAccessibleContext().setAccessibleName("昵称");
        conversationList.getAccessibleContext().setAccessibleName("会话列表");
        onlineList.getAccessibleContext().setAccessibleName("在线用户列表");
        messagesArea.getAccessibleContext().setAccessibleName("聊天记录");
        inputArea.getAccessibleContext().setAccessibleName("消息输入");
        pendingArea.getAccessibleContext().setAccessibleName("未确认消息");
        buildWindow();
        wireActions();
        refreshControls();
    }

    private void buildWindow() {
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(850, 620));
        setSize(1080, 760);
        setLocationByPlatform(true);

        JPanel connectionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 9));
        connectionPanel.add(new JLabel("服务端 IP"));
        connectionPanel.add(hostField);
        connectionPanel.add(new JLabel("端口"));
        connectionPanel.add(portField);
        connectionPanel.add(new JLabel("昵称"));
        connectionPanel.add(nicknameField);
        connectionPanel.add(connectButton);
        connectionPanel.add(stateLabel);
        add(connectionPanel, BorderLayout.NORTH);

        conversationList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        onlineList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        conversationList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean selected, boolean focused) {
                super.getListCellRendererComponent(list, value, index, selected, focused);
                String key = (String) value;
                String label = key.equals("PUBLIC") ? "公共聊天室" : key;
                if (!key.equals("PUBLIC") && !online.contains(key)) {
                    label += "（离线）";
                }
                int unread = model.unread(key);
                setText(label + (unread == 0 ? "" : "  [" + unread + "]"));
                setBorder(BorderFactory.createEmptyBorder(7, 8, 7, 6));
                return this;
            }
        });
        onlineList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean selected, boolean focused) {
                super.getListCellRendererComponent(list, value, index, selected, focused);
                setText(value + (value.equals(ownNickname) ? "（自己）" : ""));
                setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 6));
                return this;
            }
        });
        JPanel sessionsPanel = labeledPanel("会话", new JScrollPane(conversationList));
        JPanel usersPanel = labeledPanel("在线用户", new JScrollPane(onlineList));
        JSplitPane left = new JSplitPane(JSplitPane.VERTICAL_SPLIT, sessionsPanel, usersPanel);
        left.setResizeWeight(0.6);
        left.setBorder(BorderFactory.createEmptyBorder());

        messagesArea.setEditable(false);
        messagesArea.setLineWrap(true);
        messagesArea.setWrapStyleWord(true);
        messagesArea.setFont(new Font(Font.DIALOG, Font.PLAIN, 15));
        messagesArea.setMargin(new java.awt.Insets(10, 12, 10, 12));
        inputArea.setLineWrap(true);
        inputArea.setWrapStyleWord(true);
        inputArea.setFont(messagesArea.getFont());
        inputArea.setMargin(new java.awt.Insets(8, 10, 8, 10));
        pendingArea.setEditable(false);
        pendingArea.setLineWrap(true);
        pendingArea.setWrapStyleWord(true);
        pendingArea.setForeground(new Color(140, 80, 0));
        JPanel compose = new JPanel(new BorderLayout(8, 6));
        compose.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        compose.add(new JScrollPane(inputArea), BorderLayout.CENTER);
        compose.add(sendButton, BorderLayout.EAST);
        JPanel composeStatus = new JPanel(new BorderLayout(0, 4));
        composeStatus.add(feedbackLabel, BorderLayout.NORTH);
        JScrollPane pendingScroll = new JScrollPane(pendingArea);
        pendingScroll.setPreferredSize(new Dimension(200, 65));
        composeStatus.add(pendingScroll, BorderLayout.CENTER);
        compose.add(composeStatus, BorderLayout.SOUTH);

        JPanel chat = new JPanel(new BorderLayout());
        conversationLabel.setBorder(BorderFactory.createEmptyBorder(9, 12, 9, 12));
        chat.add(conversationLabel, BorderLayout.NORTH);
        chat.add(new JScrollPane(messagesArea), BorderLayout.CENTER);
        chat.add(compose, BorderLayout.SOUTH);
        JSplitPane main = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, chat);
        main.setDividerLocation(220);
        main.setResizeWeight(0.2);
        add(main, BorderLayout.CENTER);

        JPanel status = new JPanel(new GridLayout(0, 1, 0, 4));
        status.setBorder(BorderFactory.createEmptyBorder(6, 10, 8, 10));
        status.add(endpointLabel);
        status.add(historyLabel);
        JLabel directoryLabel = new JLabel("本地资料：" + store.directory());
        directoryLabel.setToolTipText(store.directory().toString());
        status.add(directoryLabel);
        add(status, BorderLayout.SOUTH);
        rebuildConversationList();
    }

    private static JPanel labeledPanel(String title, Component content) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder(title));
        panel.add(content, BorderLayout.CENTER);
        return panel;
    }

    private void wireActions() {
        connectButton.addActionListener(event -> {
            if (state == ChatConnection.State.CONNECTED || state == ChatConnection.State.CONNECTING) {
                connection.disconnect();
            } else if (!preparing) {
                connect();
            }
        });
        sendButton.addActionListener(event -> send());
        inputArea.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "send-message");
        inputArea.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,
                KeyEvent.SHIFT_DOWN_MASK), "insert-break");
        inputArea.getActionMap().put("send-message", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                if (!composing && System.nanoTime() - lastCompositionCommit > 150_000_000L) {
                    send();
                }
            }
        });
        inputArea.addInputMethodListener(new InputMethodListener() {
            @Override
            public void inputMethodTextChanged(InputMethodEvent event) {
                int count = event.getText() == null ? 0 : event.getText().getEndIndex()
                        - event.getText().getBeginIndex();
                composing = count > event.getCommittedCharacterCount();
                if (event.getCommittedCharacterCount() > 0) {
                    lastCompositionCommit = System.nanoTime();
                }
            }

            @Override
            public void caretPositionChanged(InputMethodEvent event) {
            }
        });
        conversationList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && !rebuildingConversations
                    && conversationList.getSelectedValue() != null) {
                model.select(conversationList.getSelectedValue());
                renderConversation();
                conversationList.repaint();
                refreshControls();
            }
        });
        onlineList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) {
                    String nickname = onlineList.getSelectedValue();
                    if (nickname != null && !nickname.equals(ownNickname)) {
                        model.select(nickname);
                        rebuildConversationList();
                        renderConversation();
                        refreshControls();
                        inputArea.requestFocusInWindow();
                    }
                }
            }
        });
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                closeWindow();
            }
        });
    }

    public void showHistory(List<Frame> frames) {
        requireEdt();
        if (!store.nickname().isEmpty()) {
            ownNickname = store.nickname();
            nicknameField.setText(ownNickname);
            hostField.setText(store.host());
            portField.setText(Integer.toString(store.port()));
        }
        for (Frame frame : frames) {
            model.receive(frame, ownNickname, true);
        }
        store.olderConversations().forEach(model::markOlder);
        loading = false;
        rebuildConversationList();
        renderConversation();
        refreshControls();
    }

    public void setHistoryStatus(String text) {
        requireEdt();
        historyLabel.setText("记录状态：" + text);
        historyLabel.setToolTipText(text);
        boolean warning = text.contains("失败") || text.contains("暂停") || text.contains("损坏")
                || text.contains("异常") || text.contains("上限") || text.contains("截断");
        historyLabel.setForeground(warning ? new Color(170, 35, 35) : new Color(35, 90, 45));
    }

    public void publishHistoryStatus(String text) {
        latestHistoryStatus.set(text);
        if (historyStatusScheduled.compareAndSet(false, true)) {
            SwingUtilities.invokeLater(() -> {
                historyStatusScheduled.set(false);
                if (!closing) {
                    setHistoryStatus(latestHistoryStatus.get());
                }
            });
        }
    }

    private void connect() {
        String host;
        String nickname;
        int port;
        try {
            host = hostField.getText().strip();
            port = Integer.parseInt(portField.getText().strip());
            nickname = Validation.nickname(nicknameField.getText());
            Validation.host(host);
            Validation.port(port);
        } catch (IllegalArgumentException exception) {
            feedback(exception.getMessage(), true);
            return;
        }
        nicknameField.setText(nickname);
        preparing = true;
        long attempt;
        synchronized (uiEvents) {
            attempt = ++generation;
            uiEvents.clear();
            deliveryOverloaded.set(false);
        }
        refreshControls();
        store.checkIdentity(host, port, nickname).whenComplete((ignored, error) ->
                SwingUtilities.invokeLater(() -> {
                    if (closing || attempt != generation) {
                        return;
                    }
                    preparing = false;
                    if (error != null) {
                        feedback(errorMessage(error), true);
                        refreshControls();
                        return;
                    }
                    if (connection != null) {
                        ChatConnection previous = connection;
                        cleanup.execute(previous::close);
                    }
                    ownNickname = nickname;
                    joinedHost = host;
                    joinedPort = port;
                    connection = new ChatConnection(new ChatConnection.Listener() {
                        @Override
                        public void onState(ChatConnection.State next, String detail) {
                            deliver(attempt, () -> updateState(next, detail), true);
                        }

                        @Override
                        public void onFrame(Frame frame) {
                            deliver(attempt, () -> receive(frame), false);
                        }
                    });
                    state = ChatConnection.State.CONNECTING;
                    refreshControls();
                    connection.connect(host, port, nickname);
                }));
    }

    private void deliver(long attempt, Runnable action, boolean stateEvent) {
        synchronized (uiEvents) {
            if (closing || attempt != generation) {
                return;
            }
            if (!stateEvent && deliveryOverloaded.get()) {
                return;
            }
            Runnable delivery = () -> {
                if (!closing && attempt == generation) {
                    action.run();
                }
            };
            if (stateEvent && deliveryOverloaded.get()) {
                uiEvents.clear();
            }
            if (!uiEvents.offer(delivery)) {
                if (deliveryOverloaded.compareAndSet(false, true)) {
                    uiEvents.clear();
                    SwingUtilities.invokeLater(() -> {
                        if (!closing && attempt == generation) {
                            feedback("界面消息队列已满，连接正在关闭；部分消息未显示或保存", true);
                            connection.disconnect();
                        }
                    });
                }
                if (stateEvent) {
                    uiEvents.clear();
                    uiEvents.add(delivery);
                }
            }
        }
        scheduleDrain();
    }

    private void scheduleDrain() {
        if (drainScheduled.compareAndSet(false, true)) {
            SwingUtilities.invokeLater(this::drainEvents);
        }
    }

    private void drainEvents() {
        try {
            for (int count = 0; count < 16; count++) {
                Runnable action = uiEvents.poll();
                if (action == null) {
                    break;
                }
                action.run();
            }
        } finally {
            drainScheduled.set(false);
            if (!uiEvents.isEmpty()) {
                scheduleDrain();
            }
        }
    }

    private void updateState(ChatConnection.State next, String detail) {
        if (detail.contains("NICK_IN_USE")) {
            detail = "昵称已经被使用，请修改后连接";
        } else if (detail.contains("NICK_INVALID")) {
            detail = "昵称格式无效，请修改后连接";
        }
        if (next == ChatConnection.State.DISCONNECTED && deliveryOverloaded.get()) {
            detail = "界面消息队列已满，连接已断开；部分消息未显示或保存";
        }
        state = next;
        stateLabel.setText(switch (next) {
            case DISCONNECTED -> connection == null ? "未连接" : "已断开";
            case CONNECTING -> "连接中";
            case CONNECTED -> "已连接";
            case CLOSING -> "正在断开";
        });
        if (next == ChatConnection.State.CONNECTED) {
            String mode = joinedHost.startsWith("127.") ? "开发回环自测 · " : "";
            endpointLabel.setText(mode + "实际连接：" + connection.endpoint());
            store.bind(joinedHost, joinedPort, ownNickname).whenComplete((ignored, error) -> {
                if (error != null) {
                    publishHistoryStatus(errorMessage(error));
                }
            });
        }
        if (next == ChatConnection.State.DISCONNECTED) {
            online.clear();
            onlineItems.clear();
            pending.values().forEach(item -> unconfirmed.add(
                    new Pending(item.recipient(), item.text(), true)));
            pending.clear();
            while (unconfirmed.size() > MAX_PENDING) {
                unconfirmed.remove(0);
            }
            refreshPending();
            conversationList.repaint();
            endpointLabel.setText("已断开；在线列表已失效。" + detail);
        }
        feedback(detail, next == ChatConnection.State.DISCONNECTED);
        refreshControls();
    }

    private void receive(Frame frame) {
        switch (frame.type()) {
            case USER_LIST -> {
                online.clear();
                online.addAll(frame.users());
                onlineItems.clear();
                frame.users().forEach(onlineItems::addElement);
                conversationList.repaint();
                refreshControls();
            }
            case CHAT_EVENT -> {
                if (frame.requestId() > 0 && frame.from().equals(ownNickname)) {
                    pending.remove(frame.requestId());
                    refreshPending();
                    feedback("服务端已接受消息", false);
                }
                model.receive(frame, ownNickname, false);
                store.append(frame);
                rebuildConversationList();
                renderConversation();
            }
            case NOTICE -> {
                notices.add(frame);
                if (notices.size() > 500) {
                    notices.remove(0);
                }
                if (model.active().equals("PUBLIC")) {
                    renderConversation();
                }
            }
            case ERROR -> {
                Pending rejected = pending.remove(frame.requestId());
                if (rejected != null && inputArea.getText().isEmpty()) {
                    inputArea.setText(rejected.text());
                }
                refreshPending();
                feedback(errorCode(frame.text()), true);
            }
            default -> {
            }
        }
    }

    private void send() {
        if (!canSend()) {
            feedback(state == ChatConnection.State.CONNECTED ? "当前私聊对象已经离线" : "请连接服务端后发送", true);
            return;
        }
        if (pending.size() >= MAX_PENDING) {
            feedback("等待确认的消息达到 128 条，请等待服务端响应", true);
            return;
        }
        String text = inputArea.getText();
        try {
            Validation.text(text);
            long id = connection.send(model.active(), text);
            pending.put(id, new Pending(model.active(), text, false));
            inputArea.setText("");
            refreshPending();
            feedback("等待服务端确认", false);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            feedback(exception.getMessage(), true);
        }
    }

    private boolean canSend() {
        return !loading && !closing && state == ChatConnection.State.CONNECTED
                && (model.active().equals("PUBLIC") || online.contains(model.active()));
    }

    private void refreshControls() {
        boolean editable = !closing && !loading && !preparing && state == ChatConnection.State.DISCONNECTED;
        hostField.setEnabled(editable);
        portField.setEnabled(editable);
        nicknameField.setEnabled(editable);
        connectButton.setEnabled(!closing && !loading && !preparing && state != ChatConnection.State.CLOSING);
        connectButton.setText(preparing ? "检查资料中" : switch (state) {
            case DISCONNECTED -> "连接";
            case CONNECTING -> "取消连接";
            case CONNECTED -> "断开";
            case CLOSING -> "正在断开";
        });
        sendButton.setEnabled(canSend());
        inputArea.setEnabled(!closing);
        conversationLabel.setText(model.active().equals("PUBLIC") ? "公共聊天室"
                : "私聊 · " + model.active() + (online.contains(model.active()) ? "" : "（对方离线）"));
    }

    private void rebuildConversationList() {
        rebuildingConversations = true;
        conversationItems.clear();
        model.conversations().forEach(conversationItems::addElement);
        conversationList.setSelectedValue(model.active(), true);
        rebuildingConversations = false;
    }

    private void renderConversation() {
        List<Frame> frames = new ArrayList<>(model.messages(model.active()));
        if (model.active().equals("PUBLIC")) {
            frames.addAll(notices);
        }
        frames.sort(Comparator.comparingLong(Frame::serverTime));
        StringBuilder text = new StringBuilder();
        int begin = Math.max(0, frames.size() - 500);
        if (model.hasOlder(model.active()) || begin > 0) {
            text.append("当前会话仅显示最近 500 条记录。\n\n");
        }
        for (int index = begin; index < frames.size(); index++) {
            Frame frame = frames.get(index);
            if (model.isHistorical(frame.messageId())) {
                text.append("[历史记录] ");
            }
            text.append(TIME.format(Instant.ofEpochMilli(frame.serverTime()))).append("  ");
            if (frame.type() == MessageType.NOTICE) {
                text.append("[系统]");
            } else {
                text.append(frame.from().equals(ownNickname) ? "[自己] " : "[对方] ")
                        .append(frame.from());
            }
            text.append('\n').append(frame.text()).append("\n\n");
        }
        messagesArea.setText(text.toString());
        messagesArea.setCaretPosition(messagesArea.getDocument().getLength());
        refreshControls();
    }

    private void refreshPending() {
        StringBuilder text = new StringBuilder();
        if (!unconfirmed.isEmpty()) {
            text.append("未确认消息保留最近 128 条，重连后不会自动重发。\n");
        }
        List<Pending> items = new ArrayList<>(unconfirmed);
        items.addAll(pending.values());
        for (Pending item : items) {
            text.append(item.unconfirmed() ? "[未确认，可能未送达] " : "[等待服务端确认] ")
                    .append(item.recipient().equals("PUBLIC") ? "公共聊天室" : item.recipient())
                    .append("：").append(item.text()).append('\n');
        }
        pendingArea.setText(text.toString());
    }

    private void feedback(String text, boolean error) {
        feedbackLabel.setText(text);
        feedbackLabel.setToolTipText(text);
        feedbackLabel.setForeground(error ? new Color(170, 35, 35) : new Color(45, 65, 85));
    }

    private void closeWindow() {
        if (closing) {
            return;
        }
        closing = true;
        synchronized (uiEvents) {
            ++generation;
            uiEvents.clear();
        }
        feedback("正在关闭连接并保存已收到的记录", false);
        refreshControls();
        cleanup.execute(() -> {
            try {
                try {
                    if (connection != null) {
                        connection.close();
                    }
                } finally {
                    store.close();
                }
            } catch (IOException | RuntimeException exception) {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                        exception.getMessage(), "关闭资料失败", JOptionPane.ERROR_MESSAGE));
            } finally {
                SwingUtilities.invokeLater(this::dispose);
            }
        });
        cleanup.shutdown();
    }

    private static String errorMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static String errorCode(String code) {
        return switch (code) {
            case "NICK_INVALID" -> "昵称格式无效";
            case "NICK_IN_USE" -> "昵称已经被使用，请修改后连接";
            case "NOT_JOINED" -> "尚未加入聊天室";
            case "ALREADY_JOINED" -> "当前连接已经加入聊天室";
            case "TEXT_INVALID" -> "正文须为 1～2000 个字符，且不能只有空白";
            case "USER_OFFLINE" -> "私聊对象已经离线";
            case "SELF_PRIVATE" -> "不能私聊自己";
            case "BAD_REQUEST" -> "请求格式无效";
            default -> "服务端返回错误：" + code;
        };
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Swing 操作须在 EDT 执行");
        }
    }

    private record Pending(String recipient, String text, boolean unconfirmed) {
    }
}
