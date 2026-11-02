package cn.edu.course.chat;

import cn.edu.course.chat.client.HistoryStore;
import cn.edu.course.chat.client.MainFrame;
import cn.edu.course.chat.common.Frame;

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
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class IpAcceptanceTest {
    private static final String SERVER = "172.16.1.10";
    private static final int PORT = 5_000;
    private static final String ALPHA_IP = "172.16.1.11";
    private static final String BETA_IP = "172.16.1.12";
    private static final String UNASSIGNED = "172.16.1.254";
    private static final long STAGE_MILLIS = 20_000;
    private static final String PUBLIC_ALPHA = "指定IP 公共消息 甲→乙 中文 😀";
    private static final String PUBLIC_BETA = "指定IP 公共消息 乙→甲 中文 😀";
    private static final String PRIVATE_ALPHA = "指定IP 私聊消息 甲→乙";
    private static final String PRIVATE_BETA = "指定IP 私聊消息 乙→甲";
    private static final String RECONNECT_ALPHA = "指定IP 重新连接后 甲→乙";
    private static final String RECONNECT_BETA = "指定IP 重新连接后 乙→甲";
    private static final AtomicReference<Throwable> BACKGROUND_FAILURE = new AtomicReference<>();

    private IpAcceptanceTest() {
    }

    public static void main(String[] args) throws Exception {
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));
        if (args.length == 0) {
            parent();
        } else {
            check(args.length == 3 && args[0].equals("child")
                    && List.of("甲", "乙").contains(args[1]), "参数只接受 child 甲|乙 临时目录");
            child(args[1], Path.of(args[2]).toAbsolutePath().normalize());
        }
    }

    private static void parent() throws Exception {
        check(!GraphicsEnvironment.isHeadless(), "指定 IP 验收需要真实桌面环境");
        for (String address : List.of(SERVER, ALPHA_IP, BETA_IP)) {
            requireAssignedAddress(address);
        }
        requireUnassignedAddress();
        try (ServerSocket probe = new ServerSocket()) {
            probe.bind(new InetSocketAddress(SERVER, PORT));
        }
        Path project = Path.of("").toAbsolutePath().normalize();
        Path jar = project.resolve("dist/chat.jar");
        check(Files.isRegularFile(jar), "应当已经构建 dist/chat.jar");
        Path temporaryParent = project.resolve("tmp");
        Files.createDirectories(temporaryParent);
        Path shared = Files.createTempDirectory(temporaryParent, "ip-acceptance-");
        Path logs = project.resolve("docs/evidence");
        Files.createDirectories(logs);
        Path parentLog = logs.resolve("2026-11-02-ip-parent.log");
        Files.writeString(parentLog, "", StandardCharsets.UTF_8);
        Path serverLog = logs.resolve("2026-11-02-ip-server.log");
        Path alphaLog = logs.resolve("2026-11-02-ip-alpha.log");
        Path betaLog = logs.resolve("2026-11-02-ip-beta.log");
        List<Process> owned = new ArrayList<>();
        String java = javaExecutable().toString();
        String classPath = project.resolve("build/main") + System.getProperty("path.separator")
                + project.resolve("build/test");
        try {
            record(parentLog, "父进程 PID=" + ProcessHandle.current().pid()
                    + "，指定地址验收：服务端=" + SERVER + ":" + PORT
                    + "，客户端=" + ALPHA_IP + "、" + BETA_IP);
            Process server = start(project, serverLog, List.of(java, "-Dfile.encoding=UTF-8",
                    "-jar", jar.toString(), "server"));
            owned.add(server);
            record(parentLog, "服务端 PID=" + server.pid());
            await("服务端真实指定地址监听", () -> {
                requireAlive(server, "服务端");
                return readLog(serverLog).contains("实际监听端点：" + SERVER + ":" + PORT);
            });
            Process alpha = start(project, alphaLog, List.of(java, "-ea", "-Dfile.encoding=UTF-8",
                    "-cp", classPath, IpAcceptanceTest.class.getName(), "child", "甲", shared.toString()));
            owned.add(alpha);
            Process beta = start(project, betaLog, List.of(java, "-ea", "-Dfile.encoding=UTF-8",
                    "-cp", classPath, IpAcceptanceTest.class.getName(), "child", "乙", shared.toString()));
            owned.add(beta);
            record(parentLog, "客户端甲 PID=" + alpha.pid() + "，客户端乙 PID=" + beta.pid());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(150);
            while (alpha.isAlive() || beta.isAlive()) {
                requireAlive(server, "验收期间的服务端");
                if (!alpha.isAlive()) {
                    check(alpha.exitValue() == 0, "客户端甲退出失败，参见 " + alphaLog);
                }
                if (!beta.isAlive()) {
                    check(beta.exitValue() == 0, "客户端乙退出失败，参见 " + betaLog);
                }
                check(System.nanoTime() < deadline, "指定 IP 多进程验收超过总期限");
                Thread.sleep(50);
            }
            check(alpha.exitValue() == 0 && beta.exitValue() == 0, "两个 GUI 子进程均应当退出 0");
            String serverText = readLog(serverLog);
            check(serverText.contains("实际监听端点：" + SERVER + ":" + PORT), "应当保留真实监听证据");
            requireServerSource(serverText, ALPHA_IP);
            requireServerSource(serverText, BETA_IP);
            check(!serverText.contains("远端端点=/" + UNASSIGNED + ":"), "未配置本地地址不能接入服务端");
            String alphaText = readLog(alphaLog);
            String betaText = readLog(betaLog);
            check(actualClientEndpoints(alphaText, ALPHA_IP) >= 2, "甲应当保留首次与重新连接的真实 Socket 端点");
            check(actualClientEndpoints(betaText, BETA_IP) >= 1, "乙应当保留真实 Socket 端点");
            check(alphaText.contains("未配置本地地址绑定失败且没有地址回退")
                    && alphaText.contains("指定IP GUI 验收通过") && betaText.contains("指定IP GUI 验收通过"),
                    "两个 GUI 子进程应当记录完整功能验收结论");
            record(parentLog, "实际监听端点=" + SERVER + ":" + PORT
                    + "；服务端接入日志已证实来源 " + ALPHA_IP + "、" + BETA_IP);
            record(parentLog, "甲乙真实 GUI 群聊双向、私聊双向、在线列表与上下线提示、甲本地绑定失败和恢复重连通过");
            record(parentLog, "客户端甲退出=" + alpha.exitValue() + "，客户端乙退出=" + beta.exitValue());
            stopOwned(server);
            record(parentLog, "本次创建的服务端已退出，退出码=" + server.exitValue());
        } finally {
            Throwable cleanupFailure = null;
            try {
                for (Process process : owned) {
                    try {
                        stopOwned(process);
                    } catch (Exception | AssertionError failure) {
                        if (cleanupFailure == null) {
                            cleanupFailure = failure;
                        } else {
                            cleanupFailure.addSuppressed(failure);
                        }
                    }
                }
            } finally {
                check(owned.stream().noneMatch(Process::isAlive), "仅本次创建的进程应当全部退出");
                clean(shared, temporaryParent);
            }
            if (cleanupFailure != null) {
                throw new AssertionError("本次验收创建的进程清理失败", cleanupFailure);
            }
        }
        record(parentLog, "指定 IP 单机多进程验收通过；临时资料已清理，日志与窗口截图已保留");
    }

    private static void child(String nickname, Path shared) throws Exception {
        check(!GraphicsEnvironment.isHeadless(), "GUI 子进程需要真实桌面环境");
        check(shared.getFileName().toString().startsWith("ip-acceptance-") && Files.isDirectory(shared),
                "GUI 子进程需要本次独立验收目录");
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            BACKGROUND_FAILURE.compareAndSet(null, failure);
            failure.printStackTrace(System.err);
        });
        String localHost = nickname.equals("甲") ? ALPHA_IP : BETA_IP;
        String peer = nickname.equals("甲") ? "乙" : "甲";
        AtomicReference<MainFrame> reference = new AtomicReference<>();
        HistoryStore store = new HistoryStore(shared.resolve("data"), nickname.equals("甲") ? "alpha" : "beta",
                status -> {
                    MainFrame frame = reference.get();
                    if (frame != null) {
                        frame.publishHistoryStatus(status);
                    }
                });
        MainFrame frame = null;
        try {
            List<Frame> history = store.load().get(STAGE_MILLIS, TimeUnit.MILLISECONDS);
            frame = onEdt(() -> {
                MainFrame created = new MainFrame(localHost, SERVER, PORT, nickname, store);
                reference.set(created);
                created.showHistory(history);
                created.setHistoryStatus("指定 IP 验收临时资料");
                Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment()
                        .getDefaultScreenDevice().getDefaultConfiguration().getBounds();
                created.setSize(screen.width >= 1_800 ? 900 : 850, Math.min(700, screen.height));
                int margin = Math.max(0, Math.min(8, screen.width - created.getWidth()));
                int x = nickname.equals("甲") ? screen.x + margin
                        : screen.x + screen.width - created.getWidth() - margin;
                created.setLocation(x,
                        screen.y + Math.max(0, Math.min(25, screen.height - created.getHeight())));
                created.setVisible(true);
                return created;
            });
            Gui gui = new Gui(frame, shared, nickname);
            act(() -> {
                gui.field("客户端本地 IP").setText(localHost);
                gui.field("服务端 IP").setText(SERVER);
                gui.field("服务端端口").setText(Integer.toString(PORT));
                gui.field("昵称").setText(nickname);
            });
            gui.connect();
            gui.endpoint(localHost);
            signal(shared, "ready-" + nickname);
            waitSignal(shared, "ready-" + peer);
            gui.online(List.of("甲", "乙"));
            await("本人加入提示", () -> onEdt(() -> gui.messages().getText().contains(nickname + " 已加入")));
            if (nickname.equals("甲")) {
                gui.send(PUBLIC_ALPHA);
                signal(shared, "public-alpha");
                gui.record(PUBLIC_BETA);
            } else {
                waitSignal(shared, "public-alpha");
                gui.record(PUBLIC_ALPHA);
                gui.send(PUBLIC_BETA);
            }
            gui.record(PUBLIC_ALPHA);
            gui.record(PUBLIC_BETA);
            gui.once(PUBLIC_ALPHA);
            gui.once(PUBLIC_BETA);
            gui.capture("public");
            signal(shared, "public-ready-" + nickname);
            waitSignal(shared, "public-ready-" + peer);
            if (nickname.equals("甲")) {
                gui.openPrivate("乙");
                gui.send(PRIVATE_ALPHA);
                gui.record(PRIVATE_ALPHA);
                signal(shared, "private-alpha");
                gui.record(PRIVATE_BETA);
            } else {
                waitSignal(shared, "private-alpha");
                await("私聊会话真实出现", () -> onEdt(() -> values(gui.conversations()).contains("甲")));
                check(onEdt(() -> !gui.messages().getText().contains(PRIVATE_ALPHA)),
                        "公共会话应当保持私聊正文隔离");
                gui.openPrivate("甲");
                gui.record(PRIVATE_ALPHA);
                gui.send(PRIVATE_BETA);
                gui.record(PRIVATE_BETA);
            }
            gui.once(PRIVATE_ALPHA);
            gui.once(PRIVATE_BETA);
            gui.capture("private");
            System.out.println("指定IP GUI " + nickname + "：真实群聊和私聊双向各显示一次");
            signal(shared, "private-ready-" + nickname);
            waitSignal(shared, "private-ready-" + peer);
            if (nickname.equals("甲")) {
                alphaReconnect(gui);
            } else {
                betaObserveReconnect(gui);
            }
            gui.capture("reconnected");
            signal(shared, "finish-" + nickname);
            waitSignal(shared, "finish-" + peer);
            gui.close();
            await("GUI 子进程全部聊天线程退出", () -> chatThreads().isEmpty());
            System.out.println("指定IP GUI 验收通过：" + nickname + "，PID=" + ProcessHandle.current().pid()
                    + "，窗口已 dispose，聊天后台线程=" + chatThreads().size());
        } finally {
            MainFrame created = frame;
            if (created == null) {
                store.close();
            } else if (onEdt(created::isDisplayable)) {
                act(() -> created.dispatchEvent(new WindowEvent(created, WindowEvent.WINDOW_CLOSING)));
                await("异常结束时关闭本子进程窗口", () -> onEdt(() -> !created.isDisplayable()));
            }
        }
    }

    private static void alphaReconnect(Gui gui) throws Exception {
        gui.select("PUBLIC");
        gui.click("断开");
        await("甲主动断开", () -> onEdt(() -> gui.label("已断开")
                && values(gui.online()).isEmpty() && !gui.button("发送").isEnabled()));
        signal(gui.shared, "alpha-disconnected");
        waitSignal(gui.shared, "beta-saw-disconnect");
        requireUnassignedAddress();
        act(() -> gui.field("客户端本地 IP").setText(UNASSIGNED));
        gui.click("连接");
        await("未配置地址必须在真实本地绑定阶段失败", () -> onEdt(() -> gui.label("已断开")
                && gui.labelContains("本地绑定失败") && gui.labelContains(UNASSIGNED)
                && gui.button("连接").isEnabled() && !gui.button("发送").isEnabled()));
        System.out.println("未配置本地地址绑定失败且没有地址回退：" + UNASSIGNED);
        gui.capture("unassigned-local-failure");
        signal(gui.shared, "alpha-bind-failed");
        waitSignal(gui.shared, "beta-saw-bind-failed");
        act(() -> gui.field("客户端本地 IP").setText(ALPHA_IP));
        gui.connect();
        gui.endpoint(ALPHA_IP);
        gui.online(List.of("甲", "乙"));
        signal(gui.shared, "alpha-reconnected");
        gui.record(RECONNECT_BETA);
        gui.send(RECONNECT_ALPHA);
        gui.record(RECONNECT_ALPHA);
        gui.once(RECONNECT_ALPHA);
        gui.once(RECONNECT_BETA);
        signal(gui.shared, "alpha-reconnect-message");
    }

    private static void betaObserveReconnect(Gui gui) throws Exception {
        waitSignal(gui.shared, "alpha-disconnected");
        gui.online(List.of("乙"));
        gui.select("PUBLIC");
        await("乙实际观察到甲离开提示", () -> onEdt(() -> gui.messages().getText().contains("甲 已离开")));
        signal(gui.shared, "beta-saw-disconnect");
        waitSignal(gui.shared, "alpha-bind-failed");
        gui.online(List.of("乙"));
        gui.send("指定IP 未配置本地地址失败期间乙保持连接");
        gui.record("指定IP 未配置本地地址失败期间乙保持连接");
        signal(gui.shared, "beta-saw-bind-failed");
        waitSignal(gui.shared, "alpha-reconnected");
        gui.online(List.of("甲", "乙"));
        await("乙实际观察到甲再次加入提示", () -> onEdt(() -> gui.messages().getText().contains("甲 已加入")));
        gui.endpoint(BETA_IP);
        gui.send(RECONNECT_BETA);
        gui.record(RECONNECT_BETA);
        waitSignal(gui.shared, "alpha-reconnect-message");
        gui.record(RECONNECT_ALPHA);
        gui.once(RECONNECT_ALPHA);
        gui.once(RECONNECT_BETA);
        System.out.println("指定IP GUI 乙：列表实际显示甲离开和重新加入，未配置地址失败期间无非法新昵称");
    }

    private static void requireAssignedAddress(String host) throws Exception {
        InetAddress address = InetAddress.getByName(host);
        NetworkInterface network = NetworkInterface.getByInetAddress(address);
        check(network != null && network.isUp(), "指定地址必须配置在启用的真实接口：" + host);
        check(network.getInterfaceAddresses().stream().anyMatch(entry -> entry.getAddress().equals(address)
                && entry.getNetworkPrefixLength() == 24), "指定地址必须具有 /24 前缀：" + host);
        System.out.println("指定地址检查：" + host + "/24，接口=" + network.getDisplayName());
    }

    private static void requireUnassignedAddress() throws Exception {
        check(NetworkInterface.getByInetAddress(InetAddress.getByName(UNASSIGNED)) == null,
                "本地绑定失败测试需要尚未配置的地址 " + UNASSIGNED);
    }

    private static Path javaExecutable() {
        String name = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path executable = Path.of(System.getProperty("java.home"), "bin", name);
        check(Files.isRegularFile(executable), "当前 JDK 应当提供 java 启动程序");
        return executable;
    }

    private static Process start(Path directory, Path log, List<String> command) throws IOException {
        return new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
    }

    private static void requireAlive(Process process, String description) {
        check(process.isAlive(), description + "已经退出，退出码=" + (process.isAlive() ? "未退出" : process.exitValue()));
    }

    private static void stopOwned(Process process) throws Exception {
        if (process.isAlive()) {
            process.destroy();
            if (!process.waitFor(STAGE_MILLIS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                check(process.waitFor(STAGE_MILLIS, TimeUnit.MILLISECONDS), "本次创建的进程未能退出，PID=" + process.pid());
                throw new AssertionError("本次创建的进程未能在正常终止期限内退出，PID=" + process.pid());
            }
        }
    }

    private static void requireServerSource(String text, String host) {
        Pattern pattern = Pattern.compile("本地端点=/?172\\.16\\.1\\.10:5000，远端端点=/?"
                + Pattern.quote(host) + ":([1-9][0-9]*)");
        Matcher match = pattern.matcher(text);
        check(match.find() && Integer.parseInt(match.group(1)) <= 65_535,
                "服务端应当记录真实来源地址和非零端口：" + host);
    }

    private static int actualClientEndpoints(String log, String host) {
        Pattern pattern = Pattern.compile("\\[客户端] Socket 已连接：" + Pattern.quote(host)
                + ":([1-9][0-9]*) → 172\\.16\\.1\\.10:5000");
        Matcher matches = pattern.matcher(log);
        int count = 0;
        while (matches.find()) {
            check(Integer.parseInt(matches.group(1)) <= 65_535, "客户端本地端口应当有效");
            count++;
        }
        return count;
    }

    private static String readLog(Path path) throws IOException {
        return Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : "";
    }

    private static void record(Path log, String message) throws IOException {
        System.out.println(message);
        Files.writeString(log, message + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
    }

    private static void signal(Path shared, String name) throws IOException {
        Files.writeString(shared.resolve(name + ".signal"), "OK", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static void waitSignal(Path shared, String name) throws Exception {
        await("跨进程信号 " + name, () -> Files.isRegularFile(shared.resolve(name + ".signal")));
    }

    private static void clean(Path shared, Path parent) throws IOException {
        Path actual = shared.toRealPath();
        check(actual.getParent().equals(parent.toRealPath())
                && actual.getFileName().toString().startsWith("ip-acceptance-"), "临时验收目录边界检查失败");
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(actual)) {
            paths = walk.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : paths) {
            check(path.normalize().startsWith(actual), "清理路径必须位于本次验收目录内");
            Files.delete(path);
        }
        check(!Files.exists(actual), "验收临时目录应当已经清理");
    }

    private static void await(String description, Condition condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STAGE_MILLIS);
        while (System.nanoTime() < deadline) {
            Throwable failure = BACKGROUND_FAILURE.get();
            if (failure != null) {
                throw new AssertionError("GUI 后台线程异常", failure);
            }
            if (condition.get()) {
                return;
            }
            Thread.sleep(30);
        }
        throw new AssertionError("等待超时：" + description);
    }

    private static <T> T onEdt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }

    private static void act(Action action) throws Exception {
        onEdt(() -> {
            action.run();
            return null;
        });
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

    private static List<String> values(JList<?> list) {
        List<String> values = new ArrayList<>();
        for (int index = 0; index < list.getModel().getSize(); index++) {
            values.add(list.getModel().getElementAt(index).toString());
        }
        return values;
    }

    private static List<String> chatThreads() {
        return Thread.getAllStackTraces().keySet().stream().filter(Thread::isAlive)
                .map(Thread::getName).filter(name -> name.startsWith("chat-")).toList();
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

    private static final class Gui {
        private final MainFrame frame;
        private final Path shared;
        private final String nickname;
        private final Robot robot;

        private Gui(MainFrame frame, Path shared, String nickname) throws Exception {
            this.frame = frame;
            this.shared = shared;
            this.nickname = nickname;
            robot = new Robot();
            robot.setAutoDelay(60);
        }

        private <T extends JComponent> T named(String name, Class<T> type) {
            return descendants(frame).stream().filter(type::isInstance).map(type::cast)
                    .filter(component -> name.equals(component.getAccessibleContext().getAccessibleName()))
                    .findFirst().orElseThrow(() -> new AssertionError("缺少界面组件：" + name));
        }

        private JTextField field(String name) {
            return named(name, JTextField.class);
        }

        private JTextArea messages() {
            return named("聊天记录", JTextArea.class);
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
                    .findFirst().orElseThrow(() -> new AssertionError("缺少按钮：" + text));
        }

        private boolean label(String text) {
            return descendants(frame).stream().filter(JLabel.class::isInstance).map(JLabel.class::cast)
                    .anyMatch(label -> text.equals(label.getText()));
        }

        private boolean labelContains(String text) {
            return descendants(frame).stream().filter(JLabel.class::isInstance).map(JLabel.class::cast)
                    .anyMatch(label -> label.getText() != null && label.getText().contains(text));
        }

        private void click(String text) throws Exception {
            await("按钮可以操作：" + text, () -> onEdt(() -> button(text).isEnabled()));
            act(() -> button(text).doClick());
        }

        private void connect() throws Exception {
            click("连接");
            await("指定 IP GUI 连接成功", () -> onEdt(() -> label("已连接") && button("断开").isEnabled()));
        }

        private void endpoint(String localHost) throws Exception {
            Pattern pattern = Pattern.compile("实际连接：" + Pattern.quote(localHost)
                    + ":([1-9][0-9]*) → 172\\.16\\.1\\.10:5000");
            String actual = onEdt(() -> descendants(frame).stream().filter(JLabel.class::isInstance)
                    .map(JLabel.class::cast).map(JLabel::getText)
                    .filter(text -> text != null && text.startsWith("实际连接：")).findFirst().orElse(""));
            Matcher matches = pattern.matcher(actual);
            check(matches.matches() && Integer.parseInt(matches.group(1)) <= 65_535,
                    "窗口应当显示真实本地地址、非零端口和目标地址；实际=" + actual);
            System.out.println("指定IP GUI " + nickname + " " + actual);
        }

        private void online(List<String> expected) throws Exception {
            await("完整在线名单 " + expected, () -> onEdt(() -> {
                List<String> actual = values(online());
                return actual.size() == expected.size() && actual.containsAll(expected)
                        && actual.stream().distinct().count() == expected.size();
            }));
        }

        private void send(String text) throws Exception {
            act(() -> {
                check(button("发送").isEnabled(), "真实 GUI 发送按钮应当可以使用");
                named("消息输入", JTextArea.class).setText(text);
                button("发送").doClick();
            });
            record(text);
            await("发送后服务端真实回显清除待确认项", () -> onEdt(() ->
                    named("未确认消息", JTextArea.class).getText().isEmpty()));
        }

        private void record(String text) throws Exception {
            await("真实窗口显示正文：" + text, () -> onEdt(() -> messages().getText().contains(text)));
        }

        private void once(String text) throws Exception {
            String content = onEdt(() -> messages().getText());
            int first = content.indexOf(text);
            check(first >= 0 && content.indexOf(text, first + text.length()) < 0,
                    "真实窗口中的正文应当仅出现一次：" + text);
        }

        private void select(String conversation) throws Exception {
            act(() -> {
                check(values(conversations()).contains(conversation), "目标会话应当已经存在");
                conversations().setSelectedValue(conversation, true);
            });
        }

        private void foreground() throws Exception {
            Point caption = onEdt(() -> {
                Rectangle bounds = frame.getBounds();
                int titleHeight = frame.getInsets().top;
                check(titleHeight >= 12, "真实测试窗口应当具有标题栏");
                return new Point(bounds.x + (nickname.equals("甲") ? 80 : bounds.width - 160),
                        bounds.y + titleHeight / 2);
            });
            robot.mouseMove(caption.x, caption.y);
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            robot.waitForIdle();
            await("真实点击暴露标题栏激活对应子进程窗口", () -> onEdt(frame::isActive));
            act(() -> {
                frame.toFront();
                frame.requestFocus();
            });
            robot.waitForIdle();
            Point point = onEdt(() -> {
                JTextArea area = messages();
                Rectangle visible = area.getVisibleRect();
                check(visible.width > 0 && visible.height > 0, "聊天记录必须具有可点击的可见区域");
                Point location = new Point(visible.x + Math.min(15, visible.width / 2),
                        visible.y + Math.min(15, visible.height / 2));
                SwingUtilities.convertPointToScreen(location, area);
                return location;
            });
            robot.mouseMove(point.x, point.y);
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            act(() -> messages().requestFocusInWindow());
            await("目标子进程窗口及其消息组件实际获得焦点", () -> onEdt(() ->
                    frame.isActive() && KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() == messages()));
            robot.waitForIdle();
        }

        private void desktop(Action operation) throws Exception {
            try (FileChannel channel = FileChannel.open(shared.resolve("desktop.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                FileLock lock = null;
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STAGE_MILLIS);
                while (lock == null && System.nanoTime() < deadline) {
                    lock = channel.tryLock();
                    if (lock == null) {
                        Thread.sleep(30);
                    }
                }
                check(lock != null, "真实鼠标操作应当在期限内取得跨进程独占权");
                try {
                    // 鼠标与截图阶段由当前测试窗口占据最前方，结束后恢复窗口属性。
                    check(Toolkit.getDefaultToolkit().isAlwaysOnTopSupported(), "桌面应当支持独立测试窗口的前台排列");
                    act(() -> frame.setAlwaysOnTop(true));
                    operation.run();
                } finally {
                    try {
                        act(() -> frame.setAlwaysOnTop(false));
                    } finally {
                        lock.release();
                    }
                }
            }
        }

        private void openPrivate(String peer) throws Exception {
            desktop(() -> {
                foreground();
                Point point = onEdt(() -> {
                    JList<?> list = online();
                    int index = values(list).indexOf(peer);
                    check(index >= 0, "真实私聊对象应当在线");
                    Rectangle cell = list.getCellBounds(index, index);
                    Point location = new Point(cell.x + Math.min(40, cell.width / 2), cell.y + cell.height / 2);
                    SwingUtilities.convertPointToScreen(location, list);
                    return location;
                });
                robot.mouseMove(point.x, point.y);
                robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
                robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
                robot.waitForIdle();
                await("真实点击选择私聊对象并获得焦点", () -> onEdt(() ->
                        peer.equals(online().getSelectedValue())
                                && KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() == online()));
                Object setting = Toolkit.getDefaultToolkit().getDesktopProperty("awt.multiClickInterval");
                check(setting instanceof Integer && (Integer) setting > 0, "系统应当提供双击间隔");
                int interval = (Integer) setting;
                robot.delay(interval + 60);
                int oldDelay = robot.getAutoDelay();
                try {
                    robot.setAutoDelay(0);
                    robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
                    robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
                    robot.delay(Math.max(1, Math.min(35, interval / 4)));
                    robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
                    robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
                    robot.waitForIdle();
                } finally {
                    robot.setAutoDelay(oldDelay);
                }
                await("真实双击打开私聊会话", () -> onEdt(() -> peer.equals(conversations().getSelectedValue())));
            });
        }

        private void capture(String phase) throws Exception {
            desktop(() -> {
                foreground();
                act(frame::repaint);
                robot.waitForIdle();
                robot.delay(200);
                Toolkit.getDefaultToolkit().sync();
                Rectangle bounds = onEdt(frame::getBounds);
                check(onEdt(() -> frame.getGraphicsConfiguration().getBounds()).contains(bounds),
                        "截图必须限制在完整测试窗口的屏幕区域内");
                Path evidence = Path.of("").toAbsolutePath().normalize().resolve("docs/development/evidence");
                Files.createDirectories(evidence);
                Path path = evidence.resolve("2026-11-02-ip-" + (nickname.equals("甲") ? "alpha" : "beta")
                        + "-" + phase + ".png");
                check(ImageIO.write(robot.createScreenCapture(bounds), "png", path.toFile()), "PNG 编码器应当存在");
                check(Files.size(path) > 0, "窗口证据截图应当包含内容");
                System.out.println("指定IP GUI 窗口截图：" + path);
            });
        }

        private void close() throws Exception {
            if (onEdt(frame::isDisplayable)) {
                act(() -> frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING)));
                await("本 GUI 子进程窗口 dispose", () -> onEdt(() -> !frame.isDisplayable()));
            }
        }
    }
}
