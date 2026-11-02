package cn.edu.course.chat;

import cn.edu.course.chat.client.HistoryStore;
import cn.edu.course.chat.client.MainFrame;
import cn.edu.course.chat.common.AppConfig;
import cn.edu.course.chat.common.Frame;
import cn.edu.course.chat.server.ChatServer;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

public final class Main {
    private Main() {
    }

    public static void main(String[] arguments) {
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));
        try {
            AppConfig config = AppConfig.parse(arguments);
            System.out.println("应用目录：" + config.applicationRoot());
            System.out.println("配置文件：" + config.configFile());
            if (config.developmentLoopback()) {
                System.out.println("开发回环自测模式：" + config.host() + ":" + config.port()
                        + "，指定 IP 验收仍需单独执行");
            }
            if (config.mode() == AppConfig.Mode.SERVER) {
                runServer(config);
            } else {
                runClient(config);
            }
        } catch (Exception failure) {
            Throwable cause = failure;
            while ((cause instanceof ExecutionException || cause instanceof InvocationTargetException)
                    && cause.getCause() != null) {
                cause = cause.getCause();
            }
            System.err.println("启动失败：" + cause.getClass().getSimpleName() + "：" + cause.getMessage());
            System.exit(1);
        }
    }

    private static void runServer(AppConfig config) throws Exception {
        try (ChatServer server = new ChatServer(config.host(), config.port())) {
            Thread hook = new Thread(server::close, "chat-server-shutdown");
            Runtime.getRuntime().addShutdownHook(hook);
            try {
                server.start();
                System.out.println("实际监听端点：" + server.endpoint());
                server.awaitTermination();
            } finally {
                try {
                    Runtime.getRuntime().removeShutdownHook(hook);
                } catch (IllegalStateException shuttingDown) {
                    // JVM 关闭期间由已注册的关闭线程释放资源。
                }
            }
        }
    }

    private static void runClient(AppConfig config) throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("客户端需要桌面图形环境");
        }
        AtomicReference<MainFrame> frameReference = new AtomicReference<>();
        AtomicReference<String> latestStatus = new AtomicReference<>("记录尚未写入");
        HistoryStore store = new HistoryStore(config.dataRoot(), config.profile(), status -> {
            latestStatus.set(status);
            MainFrame frame = frameReference.get();
            if (frame != null) {
                frame.publishHistoryStatus(status);
            }
        });
        boolean opened = false;
        try {
            System.out.println("客户端资料目录：" + store.directory());
            List<Frame> history = store.load().get();
            SwingUtilities.invokeAndWait(() -> {
                MainFrame frame = new MainFrame(config.host(), config.port(), "", store);
                frameReference.set(frame);
                frame.showHistory(history);
                frame.setHistoryStatus(latestStatus.get());
                frame.setVisible(true);
            });
            opened = true;
        } finally {
            if (!opened) {
                store.close();
            }
        }
    }
}
