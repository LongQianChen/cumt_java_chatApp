package cn.edu.course.chat;

import cn.edu.course.chat.client.ChatConnection;
import cn.edu.course.chat.client.HistoryStore;
import cn.edu.course.chat.client.MainFrame;
import cn.edu.course.chat.common.Frame;
import cn.edu.course.chat.common.MessageType;
import cn.edu.course.chat.server.ChatServer;

import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Stream;

public final class SwingTest {
    private static final String HOST = "127.0.0.1";
    private static final long WAIT_MILLIS = 8_000;
    private static final String PUBLIC_MESSAGE = "GUI 公共消息 中文 English 😀";
    private static final String PRIVATE_ONE = "GUI 私聊消息一";
    private static final String PRIVATE_TWO = "GUI 私聊消息二";
    private static final String PRIVATE_BARRIER = "GUI 私聊隔离检查后的公共消息";
    private static final String KEYBOARD_MESSAGE = "gui-keyboard-line-one\ngui-keyboard-line-two";
    private static final String RECONNECTED_MESSAGE = "GUI 重新连接后的公共消息";
    private static final AtomicReference<Throwable> BACKGROUND_FAILURE = new AtomicReference<>();

    private SwingTest() {
    }

    public static void main(String[] args) throws Exception {
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));
        check(!GraphicsEnvironment.isHeadless(), "SwingTest 需要真实桌面环境");
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            BACKGROUND_FAILURE.compareAndSet(null, failure);
            System.err.println("SwingTest 后台线程异常：" + thread.getName());
            failure.printStackTrace(System.err);
        });
        Path project = Path.of("").toAbsolutePath().normalize();
        Path temporaryParent = project.resolve("tmp");
        Files.createDirectories(temporaryParent);
        Path dataRoot = Files.createTempDirectory(temporaryParent, "swing-test-");
        Path evidence = project.resolve("docs/development/evidence");
        Files.createDirectories(evidence);
        Robot robot = new Robot();
        robot.setAutoDelay(65);
        List<GuiClient> windows = new ArrayList<>();
        System.out.println("SwingTest：真实 Swing 三窗口，显式 127.0.0.1 开发回环自测");
        try {
            try (ChatServer server = new ChatServer(HOST, 0)) {
                server.start();
                try {
                    GuiClient alpha = open(dataRoot, "gui-alpha", "甲", server.port(), 0);
                    windows.add(alpha);
                    GuiClient beta = open(dataRoot, "gui-beta", "乙", server.port(), 1);
                    windows.add(beta);
                    GuiClient gamma = open(dataRoot, "gui-gamma", "丙", server.port(), 2);
                    windows.add(gamma);
                    List<GuiClient> peers = List.of(alpha, beta, gamma);
                    for (GuiClient peer : peers) {
                        connect(peer);
                    }
                    await("三个窗口的在线列表", () -> peers.stream().allMatch(peer -> {
                        try {
                            return onEdt(() -> listValues(peer.online()).containsAll(List.of("甲", "乙", "丙")));
                        } catch (Exception failure) {
                            throw new IllegalStateException(failure);
                        }
                    }));
                    send(alpha, PUBLIC_MESSAGE);
                    for (GuiClient peer : peers) {
                        awaitRecord(peer, PUBLIC_MESSAGE);
                        check(onEdt(() -> occurrences(peer.messages().getText(), PUBLIC_MESSAGE)) == 1,
                                "群聊消息应在每个真实窗口各出现一次");
                    }
                    await("群聊回显清除未确认列表", () -> onEdt(() -> alpha.pending().getText().isEmpty()));
                    capture(robot, alpha, evidence.resolve("2026-11-02-swing-public-alpha.png"));
                    capture(robot, beta, evidence.resolve("2026-11-02-swing-public-beta.png"));
                    capture(robot, gamma, evidence.resolve("2026-11-02-swing-public-gamma.png"));
                    System.out.println("SwingTest：三窗口公共消息各显示一次，通过");

                    doubleClickOnline(robot, alpha, "乙");
                    await("甲打开乙的私聊会话", () -> onEdt(() -> "乙".equals(alpha.conversations().getSelectedValue())));
                    send(alpha, PRIVATE_ONE);
                    awaitRecord(alpha, PRIVATE_ONE);
                    send(alpha, PRIVATE_TWO);
                    awaitRecord(alpha, PRIVATE_TWO);
                    await("乙的非当前私聊未读增加 2", () -> onEdt(() ->
                            "PUBLIC".equals(beta.conversations().getSelectedValue())
                                    && renderedItem(beta.conversations(), "甲").contains("[2]")));
                    check(onEdt(() -> !beta.messages().getText().contains(PRIVATE_ONE)
                            && !beta.messages().getText().contains(PRIVATE_TWO)), "公共会话不能展示私聊正文");
                    check(onEdt(() -> !renderedItem(alpha.conversations(), "乙").contains("[")),
                            "自己发送的私聊不能增加未读");
                    capture(robot, beta, evidence.resolve("2026-11-02-swing-private-unread-beta.png"));
                    capture(robot, alpha, evidence.resolve("2026-11-02-swing-private-alpha.png"));
                    select(alpha, "PUBLIC");
                    send(alpha, PRIVATE_BARRIER);
                    for (GuiClient peer : peers) {
                        awaitRecord(peer, PRIVATE_BARRIER);
                    }
                    check(onEdt(() -> !gamma.messages().getText().contains(PRIVATE_ONE)
                            && !gamma.messages().getText().contains(PRIVATE_TWO)
                            && !listValues(gamma.conversations()).contains("甲")),
                            "第三个窗口不能收到私聊正文或创建私聊未读");
                    select(beta, "甲");
                    awaitRecord(beta, PRIVATE_ONE);
                    awaitRecord(beta, PRIVATE_TWO);
                    check(onEdt(() -> !renderedItem(beta.conversations(), "甲").contains("[")),
                            "切入私聊会话后未读应清零");
                    check(onEdt(() -> occurrences(beta.messages().getText(), PRIVATE_ONE) == 1
                            && occurrences(beta.messages().getText(), PRIVATE_TWO) == 1),
                            "接收方的两条私聊均只显示一次");
                    capture(robot, beta, evidence.resolve("2026-11-02-swing-private-read-beta.png"));
                    System.out.println("SwingTest：真实窗口私聊隔离、未读增加 2 与切入清零，通过");

                    exerciseKeyboard(robot, alpha, peers);
                    capture(robot, alpha, evidence.resolve("2026-11-02-swing-keyboard-alpha.png"));
                    System.out.println("SwingTest：Robot 的 Shift+Enter 换行和 Enter 发送，通过");
                    exerciseWindowDrag(robot, alpha, peers, evidence);

                    select(beta, "甲");
                    awaitRecord(beta, PRIVATE_ONE);
                    act(() -> beta.input().setText("GUI 断开后保留的草稿"));
                    click(beta, "断开");
                    await("乙主动断开", () -> onEdt(() -> beta.hasLabel("已断开")
                            && beta.online().getModel().getSize() == 0 && !beta.button("发送").isEnabled()));
                    check(onEdt(() -> beta.input().getText().equals("GUI 断开后保留的草稿")
                            && beta.messages().getText().contains(PRIVATE_ONE)), "断开应保留草稿与已有记录");
                    connect(beta);
                    await("乙重新加入在线列表", () -> onEdt(() ->
                            listValues(beta.online()).containsAll(List.of("甲", "乙", "丙"))));
                    check(onEdt(() -> beta.input().getText().equals("GUI 断开后保留的草稿")),
                            "重新连接应保留草稿");
                    select(beta, "PUBLIC");
                    send(beta, RECONNECTED_MESSAGE);
                    for (GuiClient peer : peers) {
                        awaitRecord(peer, RECONNECTED_MESSAGE);
                    }
                    capture(robot, beta, evidence.resolve("2026-11-02-swing-reconnected-beta.png"));
                    System.out.println("SwingTest：断开禁发、保留草稿与记录、手动重连继续通信，通过");

                    closeWindows(windows);
                    GuiClient restored = open(dataRoot, "gui-beta", "", server.port(), 1);
                    windows.add(restored);
                    awaitRecord(restored, PUBLIC_MESSAGE);
                    check(onEdt(() -> restored.messages().getText().contains("[历史记录]")
                            && restored.hasLabel("未连接") && !restored.button("发送").isEnabled()
                            && restored.pending().getText().isEmpty()),
                            "相同 profile 重开应显示历史标记，保持未连接与禁止发送");
                    check(onEdt(() -> occurrences(restored.messages().getText(), PUBLIC_MESSAGE) == 1
                            && occurrences(restored.messages().getText(), KEYBOARD_MESSAGE) == 1
                            && occurrences(restored.messages().getText(), RECONNECTED_MESSAGE) == 1),
                            "公共历史应完整读取且不能重复显示");
                    check(onEdt(() -> !renderedItem(restored.conversations(), "甲").contains("[")),
                            "加载私聊历史不能增加未读");
                    capture(robot, restored, evidence.resolve("2026-11-02-swing-history-public-beta.png"));
                    select(restored, "甲");
                    awaitRecord(restored, PRIVATE_ONE);
                    awaitRecord(restored, PRIVATE_TWO);
                    check(onEdt(() -> restored.messages().getText().contains("[历史记录]")
                            && occurrences(restored.messages().getText(), PRIVATE_ONE) == 1
                            && occurrences(restored.messages().getText(), PRIVATE_TWO) == 1
                            && !restored.button("发送").isEnabled()),
                            "私聊历史应正确归类、标记历史并禁止向离线对象发送");
                    capture(robot, restored, evidence.resolve("2026-11-02-swing-history-private-beta.png"));
                    restored.close();
                    System.out.println("SwingTest：关闭窗口并使用同 profile 重开，公共与私聊历史读取及未读状态，通过");
                    exerciseOverload(robot, dataRoot, server, windows, evidence);
                } finally {
                    closeWindows(windows);
                }
            }
            await("所有聊天后台线程退出", () -> chatThreads().isEmpty());
            backgroundCheck();
            System.out.println("SwingTest：全部窗口已 dispose，聊天后台线程数量=" + chatThreads().size());
            System.out.println("SwingTest：中文输入法候选确认未执行，需要实际中文输入法人工验证");
        } finally {
            closeWindows(windows);
            cleanTemporaryDirectory(dataRoot, temporaryParent);
        }
    }

    private static GuiClient open(Path dataRoot, String profile, String nickname, int port,
                                  int position) throws Exception {
        AtomicReference<MainFrame> reference = new AtomicReference<>();
        AtomicReference<String> status = new AtomicReference<>("历史读取完成");
        HistoryStore store = new HistoryStore(dataRoot, profile, text -> {
            status.set(text);
            SwingUtilities.invokeLater(() -> {
                MainFrame frame = reference.get();
                if (frame != null && frame.isDisplayable()) {
                    frame.setHistoryStatus(text);
                }
            });
        });
        try {
            List<Frame> history = store.load().get(WAIT_MILLIS, TimeUnit.MILLISECONDS);
            MainFrame frame = onEdt(() -> {
                MainFrame created = new MainFrame(HOST, port, nickname, store);
                reference.set(created);
                created.showHistory(history);
                created.setHistoryStatus(status.get());
                Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment()
                        .getDefaultScreenDevice().getDefaultConfiguration().getBounds();
                created.setSize(920, 680);
                int x = Math.max(screen.x, Math.min(screen.x + 30 + position * 35,
                        screen.x + screen.width - created.getWidth()));
                int y = Math.max(screen.y, Math.min(screen.y + 35 + position * 28,
                        screen.y + screen.height - created.getHeight()));
                created.setLocation(x, y);
                created.setVisible(true);
                return created;
            });
            GuiClient peer = new GuiClient(frame);
            act(() -> {
                peer.field("服务端 IP").setText(HOST);
                peer.field("服务端端口").setText(Integer.toString(port));
                if (!nickname.isEmpty()) {
                    peer.field("昵称").setText(nickname);
                }
            });
            return peer;
        } catch (Exception | AssertionError failure) {
            MainFrame frame = reference.get();
            if (frame != null) {
                act(() -> frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING)));
                await("创建失败的窗口关闭", () -> onEdt(() -> !frame.isDisplayable()));
            } else {
                store.close();
            }
            throw failure;
        }
    }

    private static void connect(GuiClient peer) throws Exception {
        await("连接按钮可用", () -> onEdt(() -> peer.button("连接").isEnabled()));
        click(peer, "连接");
        await("窗口连接成功", () -> onEdt(() -> peer.hasLabel("已连接")
                && peer.button("断开").isEnabled()));
    }

    private static void send(GuiClient peer, String text) throws Exception {
        act(() -> {
            check(peer.button("发送").isEnabled(), "发送按钮应当可以使用");
            peer.input().setText(text);
            peer.button("发送").doClick();
        });
    }

    private static void click(GuiClient peer, String text) throws Exception {
        act(() -> {
            AbstractButton button = peer.button(text);
            check(button.isEnabled(), "按钮应当可以使用：" + text);
            button.doClick();
        });
    }

    private static void select(GuiClient peer, String conversation) throws Exception {
        act(() -> {
            check(listValues(peer.conversations()).contains(conversation), "会话应当已经存在：" + conversation);
            peer.conversations().setSelectedValue(conversation, true);
        });
    }

    private static void exerciseKeyboard(Robot robot, GuiClient alpha,
                                         List<GuiClient> peers) throws Exception {
        select(alpha, "PUBLIC");
        act(() -> {
            alpha.frame.toFront();
            alpha.input().setText("gui-keyboard-line-one");
            alpha.input().setCaretPosition(alpha.input().getDocument().getLength());
        });
        focusInput(robot, alpha);
        robot.keyPress(KeyEvent.VK_SHIFT);
        robot.keyPress(KeyEvent.VK_ENTER);
        robot.keyRelease(KeyEvent.VK_ENTER);
        robot.keyRelease(KeyEvent.VK_SHIFT);
        robot.waitForIdle();
        await("Shift+Enter 插入换行", () -> onEdt(() -> alpha.input().getText().equals("gui-keyboard-line-one\n")));
        check(onEdt(() -> !alpha.messages().getText().contains("gui-keyboard-line-one")),
                "Shift+Enter 不能发送正文");
        act(() -> {
            alpha.input().append("gui-keyboard-line-two");
            alpha.input().setCaretPosition(alpha.input().getDocument().getLength());
        });
        focusInput(robot, alpha);
        robot.keyPress(KeyEvent.VK_ENTER);
        robot.keyRelease(KeyEvent.VK_ENTER);
        robot.waitForIdle();
        for (GuiClient peer : peers) {
            if (!onEdt(() -> "PUBLIC".equals(peer.conversations().getSelectedValue()))) {
                select(peer, "PUBLIC");
            }
            awaitRecord(peer, KEYBOARD_MESSAGE);
            check(onEdt(() -> occurrences(peer.messages().getText(), KEYBOARD_MESSAGE)) == 1,
                    "Enter 发送的多行消息应在每个窗口只显示一次");
        }
        await("Enter 发送后清空输入与未确认消息", () -> onEdt(() ->
                alpha.input().getText().isEmpty() && alpha.pending().getText().isEmpty()));
    }

    private static void exerciseOverload(Robot robot, Path dataRoot, ChatServer server,
                                         List<GuiClient> windows, Path evidence) throws Exception {
        String targetNickname = "GuiOverload";
        String senderNickname = "FloodSender";
        GuiClient target = open(dataRoot, "gui-overload", targetNickname, server.port(), 0);
        windows.add(target);
        connect(target);
        try (RealSender sender = new RealSender()) {
            sender.connection.connect(HOST, server.port(), senderNickname);
            await("真实过载发送者加入", () -> sender.connection.state() == ChatConnection.State.CONNECTED);
            sender.awaitFrame(frame -> frame.type() == MessageType.WELCOME
                    && frame.from().equals(senderNickname));
            await("过载目标显示真实发送者在线", () -> onEdt(() ->
                    listValues(target.online()).containsAll(List.of(targetNickname, senderNickname))));

            CountDownLatch blocked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch resumed = new CountDownLatch(1);
            SwingUtilities.invokeLater(() -> {
                blocked.countDown();
                try {
                    check(release.await(WAIT_MILLIS, TimeUnit.MILLISECONDS), "过载测试须在期限内释放 EDT");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("过载测试的 EDT 等待受到中断", failure);
                } finally {
                    resumed.countDown();
                }
            });
            int acknowledged = 0;
            try {
                check(blocked.await(WAIT_MILLIS, TimeUnit.MILLISECONDS), "EDT 应当已经进入真实等待");
                for (int sequence = 0; sequence < 384; sequence++) {
                    String body = "gui-overload-" + sequence;
                    long requestId = sender.connection.send(targetNickname, body);
                    sender.awaitFrame(frame -> frame.type() == MessageType.CHAT_EVENT
                            && frame.requestId() == requestId && frame.text().equals(body));
                    acknowledged++;
                }
                check(acknowledged == 384, "全部 384 条真实发送都应收到各自回显");
            } finally {
                release.countDown();
                check(resumed.await(WAIT_MILLIS, TimeUnit.MILLISECONDS), "EDT 应当从等待恢复");
            }

            await("过载终止状态保留且允许重新连接", () -> onEdt(() -> target.hasLabel("已断开")
                    && target.hasLabelContaining("界面消息队列已满")
                    && target.button("连接").isEnabled()
                    && !target.button("发送").isEnabled()));
            capture(robot, target, evidence.resolve("2026-11-02-swing-overload-disconnected.png"));
            connect(target);
            await("过载窗口重新加入后在线列表恢复", () -> onEdt(() ->
                    listValues(target.online()).containsAll(List.of(targetNickname, senderNickname))));
            String recovery = "gui-overload-reconnected-message";
            long requestId = sender.connection.send(targetNickname, recovery);
            sender.awaitFrame(frame -> frame.type() == MessageType.CHAT_EVENT
                    && frame.requestId() == requestId && frame.text().equals(recovery));
            await("恢复后的私聊会话出现", () -> onEdt(() ->
                    listValues(target.conversations()).contains(senderNickname)));
            select(target, senderNickname);
            awaitRecord(target, recovery);
            check(onEdt(() -> occurrences(target.messages().getText(), recovery)) == 1,
                    "过载恢复后的新消息应当仅显示一次");
            String reply = "gui-overload-reconnected-reply";
            send(target, reply);
            sender.awaitFrame(frame -> frame.type() == MessageType.CHAT_EVENT
                    && frame.from().equals(targetNickname) && frame.text().equals(reply));
            awaitRecord(target, reply);
            await("过载恢复后的发送回显完成", () -> onEdt(() -> target.pending().getText().isEmpty()));
            capture(robot, target, evidence.resolve("2026-11-02-swing-overload-reconnected.png"));
            System.out.println("SwingTest：EDT 暂缓时 384 条真实私聊逐条获得回显；界面队列超限后显示已断开与原因，重新连接后双向通信，通过");
        } finally {
            target.close();
        }
    }

    private static void exerciseWindowDrag(Robot robot, GuiClient alpha,
                                           List<GuiClient> peers, Path evidence) throws Exception {
        act(alpha.frame::toFront);
        robot.waitForIdle();
        Rectangle original = onEdt(alpha.frame::getBounds);
        Rectangle screen = onEdt(() -> alpha.frame.getGraphicsConfiguration().getBounds());
        check(screen.contains(original), "拖动前的完整窗口应当位于屏幕内");
        int dx = movement(original.x - screen.x, screen.x + screen.width - original.x - original.width);
        int dy = movement(original.y - screen.y, screen.y + screen.height - original.y - original.height);
        check(dx != 0 || dy != 0, "屏幕应当具有可验证窗口拖动的空间");
        int titleHeight = onEdt(() -> alpha.frame.getInsets().top);
        check(titleHeight >= 12, "真实窗口应当具有可以拖动的标题栏");
        int sourceX = original.x + original.width / 2;
        int sourceY = original.y + titleHeight / 2;
        robot.mouseMove(sourceX, sourceY);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        try {
            for (int step = 1; step <= 5; step++) {
                robot.mouseMove(sourceX + dx * step / 5, sourceY + dy * step / 5);
            }
        } finally {
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        }
        robot.waitForIdle();
        await("Robot 实际拖动窗口改变位置", () -> onEdt(() ->
                !alpha.frame.getLocation().equals(original.getLocation())));
        check(onEdt(() -> screen.contains(alpha.frame.getBounds())), "拖动后的完整窗口应当仍位于屏幕内");
        String body = "GUI 窗口拖动后的公共消息";
        send(alpha, body);
        for (GuiClient peer : peers) {
            awaitRecord(peer, body);
            check(onEdt(() -> occurrences(peer.messages().getText(), body)) == 1,
                    "拖动窗口后仍应当正常发送并接收一次群聊消息");
        }
        act(() -> alpha.frame.setLocation(original.getLocation()));
        capture(robot, alpha, evidence.resolve("2026-11-02-swing-window-drag-alpha.png"));
        System.out.println("SwingTest：Robot 拖动真实窗口改变位置，窗口保持在屏幕内并继续群聊，通过");
    }

    private static int movement(int before, int after) {
        if (after >= 30) {
            return 30;
        }
        if (before >= 30) {
            return -30;
        }
        return after >= before ? Math.min(30, after) : -Math.min(30, before);
    }

    private static void focusInput(Robot robot, GuiClient peer) throws Exception {
        act(() -> {
            peer.frame.toFront();
            peer.frame.requestFocus();
        });
        robot.waitForIdle();
        Point point = onEdt(() -> {
            JTextArea area = peer.input();
            Point location = area.getLocationOnScreen();
            return new Point(location.x + Math.min(25, area.getWidth() / 2),
                    location.y + Math.min(20, area.getHeight() / 2));
        });
        robot.mouseMove(point.x, point.y);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        act(() -> {
            peer.input().requestFocusInWindow();
            peer.input().setCaretPosition(peer.input().getDocument().getLength());
        });
        await("消息输入获得真实键盘焦点", () -> onEdt(() ->
                KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() == peer.input()));
    }

    private static void doubleClickOnline(Robot robot, GuiClient peer, String nickname) throws Exception {
        act(() -> {
            peer.frame.toFront();
            peer.frame.requestFocus();
        });
        robot.waitForIdle();
        await("私聊窗口成为活动窗口", () -> onEdt(peer.frame::isActive));
        Point point = onEdt(() -> {
            JList<?> list = peer.online();
            int index = listValues(list).indexOf(nickname);
            check(index >= 0, "私聊对象应当在线");
            Rectangle cell = list.getCellBounds(index, index);
            Point location = new Point(cell.x + Math.min(40, cell.width / 2), cell.y + cell.height / 2);
            SwingUtilities.convertPointToScreen(location, list);
            return location;
        });
        robot.mouseMove(point.x, point.y);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        robot.waitForIdle();
        await("在线用户列表通过真实点击选择目标并获得焦点", () -> onEdt(() ->
                nickname.equals(peer.online().getSelectedValue())
                        && KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() == peer.online()));
        Object intervalProperty = Toolkit.getDefaultToolkit().getDesktopProperty("awt.multiClickInterval");
        check(intervalProperty instanceof Integer && (Integer) intervalProperty > 0,
                "桌面应当提供有效的双击间隔设置");
        int interval = (Integer) intervalProperty;
        robot.delay(interval + 60);
        AtomicInteger observedCount = new AtomicInteger();
        AtomicReference<String> observedSelection = new AtomicReference<>("");
        MouseAdapter observer = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                observedCount.accumulateAndGet(event.getClickCount(), Math::max);
                observedSelection.set(String.valueOf(peer.online().getSelectedValue()));
            }
        };
        act(() -> peer.online().addMouseListener(observer));
        int previousDelay = robot.getAutoDelay();
        try {
            robot.setAutoDelay(0);
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            robot.delay(Math.max(1, Math.min(35, interval / 4)));
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            robot.waitForIdle();
            check(observedCount.get() == 2 && nickname.equals(observedSelection.get()),
                    "在线列表应当收到真实双击；clickCount=" + observedCount.get()
                            + "，目标昵称=" + observedSelection.get());
        } finally {
            robot.setAutoDelay(previousDelay);
            act(() -> peer.online().removeMouseListener(observer));
        }
    }

    private static void capture(Robot robot, GuiClient peer, Path destination) throws Exception {
        act(() -> {
            peer.frame.toFront();
            peer.frame.repaint();
        });
        robot.waitForIdle();
        robot.delay(200);
        Toolkit.getDefaultToolkit().sync();
        Rectangle bounds = onEdt(peer.frame::getBounds);
        Rectangle screen = onEdt(() -> peer.frame.getGraphicsConfiguration().getBounds());
        check(screen.contains(bounds), "证据截图必须只包含完整的测试窗口区域");
        check(ImageIO.write(robot.createScreenCapture(bounds), "png", destination.toFile()),
                "截图 PNG 编码器必须存在");
        check(Files.size(destination) > 0, "截图文件不能为空");
        System.out.println("SwingTest 截图：" + destination);
    }

    private static void awaitRecord(GuiClient peer, String text) throws Exception {
        await("窗口显示正文：" + text, () -> onEdt(() -> peer.messages().getText().contains(text)));
    }

    private static void closeWindows(List<GuiClient> windows) throws Exception {
        for (GuiClient window : windows) {
            window.close();
        }
    }

    private static void cleanTemporaryDirectory(Path dataRoot, Path temporaryParent) throws IOException {
        Path actualParent = temporaryParent.toRealPath();
        Path actualRoot = dataRoot.toRealPath();
        check(actualRoot.getParent().equals(actualParent)
                && actualRoot.getFileName().toString().startsWith("swing-test-"), "临时资料清理路径检查失败");
        List<Path> paths;
        try (Stream<Path> stream = Files.walk(actualRoot)) {
            paths = stream.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : paths) {
            check(path.normalize().startsWith(actualRoot), "清理路径必须位于测试资料目录内");
            Files.delete(path);
        }
        check(!Files.exists(actualRoot), "测试资料目录应当已经清理");
        System.out.println("SwingTest：临时资料已清理，保留窗口截图证据");
    }

    private static List<String> chatThreads() {
        return Thread.getAllStackTraces().keySet().stream().filter(Thread::isAlive)
                .map(Thread::getName).filter(name -> name.startsWith("chat-")).toList();
    }

    private static void backgroundCheck() {
        Throwable failure = BACKGROUND_FAILURE.get();
        if (failure != null) {
            throw new AssertionError("SwingTest 存在后台线程异常", failure);
        }
    }

    private static void await(String description, Condition condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MILLIS);
        while (System.nanoTime() < deadline) {
            backgroundCheck();
            if (condition.get()) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("等待超时：" + description);
    }

    private static <T> T onEdt(Callable<T> operation) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return operation.call();
        }
        FutureTask<T> task = new FutureTask<>(operation);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }

    private static void act(Action action) throws Exception {
        onEdt(() -> {
            action.run();
            return null;
        });
    }

    private static List<String> listValues(JList<?> list) {
        List<String> values = new ArrayList<>();
        for (int index = 0; index < list.getModel().getSize(); index++) {
            values.add(list.getModel().getElementAt(index).toString());
        }
        return values;
    }

    private static String renderedItem(JList<?> list, String value) {
        int index = listValues(list).indexOf(value);
        check(index >= 0, "会话列表中缺少：" + value);
        return renderedAt(list, index);
    }

    private static <T> String renderedAt(JList<T> list, int index) {
        Component rendered = list.getCellRenderer().getListCellRendererComponent(list,
                list.getModel().getElementAt(index), index, false, false);
        check(rendered instanceof JLabel, "会话列表应当使用文字 renderer");
        return ((JLabel) rendered).getText();
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int position = 0;
        while ((position = text.indexOf(needle, position)) >= 0) {
            count++;
            position += needle.length();
        }
        return count;
    }

    private static List<Component> descendants(Component root) {
        List<Component> components = new ArrayList<>();
        components.add(root);
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                components.addAll(descendants(child));
            }
        }
        return components;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface Condition {
        boolean get() throws Exception;
    }

    private static final class RealSender implements AutoCloseable {
        private final BlockingQueue<Frame> frames = new LinkedBlockingQueue<>(512);
        private final ChatConnection connection = new ChatConnection(new ChatConnection.Listener() {
            @Override
            public void onState(ChatConnection.State state, String detail) {
            }

            @Override
            public void onFrame(Frame frame) {
                check(frames.offer(frame), "真实过载发送者的测试接收队列达到上限");
            }
        });

        private Frame awaitFrame(Predicate<Frame> expected) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MILLIS);
            while (System.nanoTime() < deadline) {
                backgroundCheck();
                Frame frame = frames.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (frame != null) {
                    check(frame.type() != MessageType.ERROR, "真实过载发送请求返回错误：" + frame.text());
                    if (expected.test(frame)) {
                        return frame;
                    }
                }
            }
            throw new AssertionError("真实过载发送者等待服务端帧超时；状态=" + connection.state());
        }

        @Override
        public void close() {
            connection.close();
        }
    }

    private static final class GuiClient implements AutoCloseable {
        private final MainFrame frame;

        private GuiClient(MainFrame frame) {
            this.frame = frame;
        }

        private <T extends JComponent> T named(String name, Class<T> type) {
            return descendants(frame).stream().filter(type::isInstance).map(type::cast)
                    .filter(component -> name.equals(component.getAccessibleContext().getAccessibleName()))
                    .findFirst().orElseThrow(() -> new AssertionError("没有找到界面组件：" + name));
        }

        private JTextField field(String name) {
            return named(name, JTextField.class);
        }

        private JTextArea input() {
            return named("消息输入", JTextArea.class);
        }

        private JTextArea messages() {
            return named("聊天记录", JTextArea.class);
        }

        private JTextArea pending() {
            return named("未确认消息", JTextArea.class);
        }

        private JList<?> conversations() {
            return named("会话列表", JList.class);
        }

        private JList<?> online() {
            return named("在线用户列表", JList.class);
        }

        private AbstractButton button(String text) {
            return descendants(frame).stream().filter(AbstractButton.class::isInstance)
                    .map(AbstractButton.class::cast).filter(button -> text.equals(button.getText()))
                    .findFirst().orElseThrow(() -> new AssertionError("没有找到按钮：" + text));
        }

        private boolean hasLabel(String text) {
            return descendants(frame).stream().filter(JLabel.class::isInstance).map(JLabel.class::cast)
                    .anyMatch(label -> text.equals(label.getText()));
        }

        private boolean hasLabelContaining(String text) {
            return descendants(frame).stream().filter(JLabel.class::isInstance).map(JLabel.class::cast)
                    .anyMatch(label -> label.getText() != null && label.getText().contains(text));
        }

        @Override
        public void close() throws Exception {
            if (onEdt(frame::isDisplayable)) {
                act(() -> frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING)));
                await("窗口 dispose 与后台资料关闭", () -> onEdt(() -> !frame.isDisplayable()));
            }
        }
    }
}
