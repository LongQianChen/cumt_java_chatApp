package cn.edu.course.chat;

import cn.edu.course.chat.client.ChatConnection;
import cn.edu.course.chat.common.Frame;
import cn.edu.course.chat.common.MessageType;
import cn.edu.course.chat.common.ProtocolCodec;
import cn.edu.course.chat.server.ChatServer;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

public final class ConnectionTest {
    private static final ChatConnection.Options FAST =
            new ChatConnection.Options(1000, 250, 300, 60, 16, 10);

    private ConnectionTest() {
    }

    public static void main(String[] args) throws Exception {
        run();
        new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true)
                .println("ConnectionTest: PASS A14 A15 A16 A22（显式 127.0.0.1 开发回环自测）");
    }

    public static void run() throws Exception {
        explicitLocalBinding();
        unavailableLocalBinding();
        connectionRefused();
        faultDeadlines();
        rejectionAndProtocolFailure();
        normalLifecycleAndRapidReconnect();
        callbacksAcrossGenerations();
        slowListenerBound();
        closeDuringHandshake();
        waitUntil(() -> Thread.getAllStackTraces().keySet().stream()
                .noneMatch(thread -> thread.isAlive() && thread.getName().startsWith("chat-client-")),
                "A22 客户端后台线程存在残留");
    }

    private static void explicitLocalBinding() throws Exception {
        Probe firstProbe = new Probe();
        Probe secondProbe = new Probe();
        try (ServerSocket server = loopbackServer();
             ChatConnection first = new ChatConnection(firstProbe);
             ChatConnection second = new ChatConnection(secondProbe)) {
            server.setSoTimeout(5000);
            first.connect("127.0.0.1", "127.0.0.1", server.getLocalPort(), "绑定甲");
            try (Socket firstAccepted = server.accept()) {
                welcome(firstAccepted);
                firstProbe.awaitState(ChatConnection.State.CONNECTED);
                checkBoundEndpoint(first, firstAccepted, "127.0.0.1");
                second.connect("127.0.0.1", "127.0.0.1", server.getLocalPort(), "绑定乙");
                try (Socket secondAccepted = server.accept()) {
                    welcome(secondAccepted);
                    secondProbe.awaitState(ChatConnection.State.CONNECTED);
                    checkBoundEndpoint(second, secondAccepted, "127.0.0.1");
                    check(firstAccepted.getPort() != secondAccepted.getPort(),
                            "同一本地地址的两个连接未使用不同临时端口");
                    check(first.state() == ChatConnection.State.CONNECTED
                                    && second.state() == ChatConnection.State.CONNECTED,
                            "同一本地地址的两个客户端未能同时在线");
                    first.disconnect();
                    firstProbe.awaitState(ChatConnection.State.DISCONNECTED);
                    first.connect("127.0.0.1", "127.0.0.1", server.getLocalPort(), "重连甲");
                    try (Socket reconnected = server.accept()) {
                        welcome(reconnected);
                        firstProbe.awaitState(ChatConnection.State.CONNECTED);
                        checkBoundEndpoint(first, reconnected, "127.0.0.1");
                        check(second.state() == ChatConnection.State.CONNECTED,
                                "同地址客户端重连影响了另一连接");
                    }
                }
            }
        }
    }

    private static void unavailableLocalBinding() throws Exception {
        Set<String> localAddresses = new HashSet<>();
        var interfaces = NetworkInterface.getNetworkInterfaces();
        check(interfaces != null, "无法枚举本机网络接口");
        for (NetworkInterface networkInterface : Collections.list(interfaces)) {
            for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                localAddresses.add(address.getHostAddress());
            }
        }
        String unavailable = List.of("192.0.2.123", "198.51.100.123", "203.0.113.123").stream()
                .filter(address -> !localAddresses.contains(address))
                .findFirst().orElseThrow(() -> new AssertionError("测试所用地址均已配置到本机"));
        check(NetworkInterface.getByInetAddress(InetAddress.getByName(unavailable)) == null,
                "测试地址已经属于本机接口");
        Probe probe = new Probe();
        try (ServerSocket server = loopbackServer();
             ChatConnection connection = new ChatConnection(probe)) {
            server.setSoTimeout(5000);
            connection.connect("127.0.0.1", "127.0.0.1", server.getLocalPort(), "绑定恢复");
            try (Socket accepted = server.accept()) {
                welcome(accepted);
                probe.awaitState(ChatConnection.State.CONNECTED);
                checkBoundEndpoint(connection, accepted, "127.0.0.1");
                connection.disconnect();
                probe.awaitState(ChatConnection.State.DISCONNECTED);
            }
            connection.connect(unavailable, "127.0.0.1", server.getLocalPort(), "绑定失败");
            StateEvent failed = probe.awaitState(ChatConnection.State.DISCONNECTED);
            check(failed.detail().contains("本地绑定失败") && failed.detail().contains(unavailable),
                    "未配置地址绑定失败缺少阶段或地址说明：" + failed.detail());
            check(connection.endpoint().isEmpty(), "绑定失败后仍显示活动连接端点");
            server.setSoTimeout(250);
            expect(SocketTimeoutException.class, () -> {
                try (Socket unexpected = server.accept()) {
                    throw new AssertionError("本地绑定失败后服务端仍接入了连接："
                            + unexpected.getRemoteSocketAddress());
                }
            });
            server.setSoTimeout(5000);
            connection.connect("127.0.0.1", "127.0.0.1", server.getLocalPort(), "重新绑定");
            try (Socket recovered = server.accept()) {
                welcome(recovered);
                probe.awaitState(ChatConnection.State.CONNECTED);
                checkBoundEndpoint(connection, recovered, "127.0.0.1");
            }
        }
    }

    private static void welcome(Socket accepted) throws Exception {
        accepted.setSoTimeout(5000);
        Frame join = ProtocolCodec.read(accepted.getInputStream());
        check(join != null && join.type() == MessageType.JOIN, "本地绑定测试未收到 JOIN");
        ProtocolCodec.write(accepted.getOutputStream(), new Frame(MessageType.WELCOME, join.requestId(),
                System.currentTimeMillis(), "", join.from(), "", "", List.of()));
    }

    private static void checkBoundEndpoint(ChatConnection connection, Socket accepted, String localHost) {
        check(accepted.getInetAddress().getHostAddress().equals(localHost),
                "服务端观察到的来源地址与本地绑定地址不一致");
        String expected = accepted.getInetAddress().getHostAddress() + ":" + accepted.getPort()
                + " → " + accepted.getLocalAddress().getHostAddress() + ":" + accepted.getLocalPort();
        check(connection.endpoint().equals(expected), "客户端端点与服务端实际 Socket 端点不一致");
    }

    private static void connectionRefused() throws Exception {
        int unavailablePort;
        try (ServerSocket reserved = loopbackServer()) {
            unavailablePort = reserved.getLocalPort();
        }
        Probe probe = new Probe();
        try (ChatConnection connection = new ChatConnection(probe, FAST)) {
            expect(IllegalStateException.class, () -> connection.send("PUBLIC", "尚未连接"));
            expect(IllegalArgumentException.class,
                    () -> connection.connect("127.0.0.1", "localhost", 5000, "甲"));
            expect(IllegalArgumentException.class,
                    () -> connection.connect("0.0.0.0", "127.0.0.1", 5000, "甲"));
            connection.connect("127.0.0.1", "127.0.0.1", unavailablePort, "甲");
            StateEvent event = probe.awaitState(ChatConnection.State.DISCONNECTED);
            check(event.detail().contains("远端连接失败"), "A15 连接失败缺少远端连接说明");
            check(connection.state() == ChatConnection.State.DISCONNECTED, "A15 连接失败未恢复状态");
            check(probe.connectedCount.get() == 0, "A15 连接失败却宣称已经连接");
        }
    }

    private static void faultDeadlines() throws Exception {
        for (Fault fault : List.of(Fault.NO_WELCOME, Fault.HALF_WELCOME, Fault.NO_PONG, Fault.HALF_PONG)) {
            Probe probe = new Probe();
            boolean welcomed = fault == Fault.NO_PONG || fault == Fault.HALF_PONG;
            try (FaultPeer peer = new FaultPeer(fault);
                 ChatConnection connection = new ChatConnection(probe, FAST)) {
                long start = System.nanoTime();
                connection.connect("127.0.0.1", "127.0.0.1", peer.port(), "期限测试");
                peer.awaitJoined();
                expect(IllegalStateException.class,
                        () -> connection.connect("127.0.0.1", "127.0.0.1", peer.port(), "重复连接"));
                if (welcomed) {
                    probe.awaitState(ChatConnection.State.CONNECTED);
                    check(connection.endpoint().contains("127.0.0.1:" + peer.port()),
                            "实际 Socket 端点未提供");
                }
                StateEvent ended = probe.awaitState(ChatConnection.State.DISCONNECTED);
                long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                check(ended.detail().contains("超时"), "A15 期限断开缺少超时说明：" + ended.detail());
                check(millis >= (welcomed ? FAST.idleMillis() : FAST.handshakeMillis()),
                        "A15 在指定期限之前断开连接");
                if (welcomed) {
                    check(peer.pings.get() > 0, "A15 客户端没有发送 PING");
                } else {
                    check(probe.connectedCount.get() == 0, "A15 收到完整 WELCOME 前进入已连接状态");
                }
                expect(IllegalStateException.class, () -> connection.send("PUBLIC", "断线发送"));
            }
        }
    }

    private static void rejectionAndProtocolFailure() throws Exception {
        for (Fault fault : List.of(Fault.REJECT_JOIN, Fault.BAD_FRAME)) {
            Probe probe = new Probe();
            try (FaultPeer peer = new FaultPeer(fault);
                 ChatConnection connection = new ChatConnection(probe, FAST)) {
                connection.connect("127.0.0.1", "127.0.0.1", peer.port(), "甲");
                StateEvent event = probe.awaitState(ChatConnection.State.DISCONNECTED);
                check(!event.detail().isBlank(), "协议失败缺少可见原因");
                if (fault == Fault.REJECT_JOIN) {
                    Frame error = probe.awaitFrame(MessageType.ERROR);
                    check(error.requestId() == peer.joinId && error.text().equals("NICK_IN_USE"),
                            "昵称拒绝丢失关联请求号或错误码");
                }
                check(probe.connectedCount.get() == 0, "协议失败却进入已连接状态");
            }
        }
    }

    private static void normalLifecycleAndRapidReconnect() throws Exception {
        Probe probe = new Probe();
        ChatServer.Options serverOptions = new ChatServer.Options(10, 128, 1000, 1000, 10);
        try (ChatServer server = new ChatServer("127.0.0.1", 0, serverOptions);
             ChatConnection connection = new ChatConnection(probe, FAST)) {
            server.start();
            for (int attempt = 0; attempt < 8; attempt++) {
                connection.connect("127.0.0.1", "127.0.0.1", server.port(), "连接甲");
                probe.awaitState(ChatConnection.State.CONNECTED);
                long requestId = connection.send("PUBLIC", "中文😀\n连接 " + attempt);
                Frame echo = probe.awaitFrame(MessageType.CHAT_EVENT);
                check(echo.requestId() == requestId && echo.text().equals("中文😀\n连接 " + attempt),
                        "回显与请求未关联");
                check(echo.from().equals("连接甲"), "回显昵称不正确");
                expect(IllegalArgumentException.class, () -> connection.send("连接甲", "私聊自己"));
                expect(IllegalArgumentException.class, () -> connection.send("PUBLIC", " \t\n"));
                expect(IllegalStateException.class,
                        () -> connection.connect("127.0.0.1", "127.0.0.1", server.port(), "连接乙"));
                if (attempt == 0) {
                    Frame pong = probe.awaitFrame(MessageType.PONG);
                    check(pong.requestId() > 0, "PONG 缺少关联请求号");
                    Thread.sleep(FAST.idleMillis() + 80);
                    check(connection.state() == ChatConnection.State.CONNECTED,
                            "正常 PONG 未保持静默连接");
                }
                connection.disconnect();
                probe.awaitState(ChatConnection.State.DISCONNECTED);
                check(connection.endpoint().isEmpty(), "断开后仍显示活动端点");
                probe.clearFrames();
            }
            check(probe.maxCallbackConcurrency.get() == 1, "A16 监听回调发生并行执行");
            connection.connect("127.0.0.1", "127.0.0.1", server.port(), "服务退出");
            probe.awaitState(ChatConnection.State.CONNECTED);
            server.close();
            StateEvent event = probe.awaitState(ChatConnection.State.DISCONNECTED);
            check(event.detail().contains("断开"), "A14 服务关闭缺少连接断开提示");
            expect(IllegalStateException.class, () -> connection.send("PUBLIC", "服务已退出"));
        }
    }

    private static void closeDuringHandshake() throws Exception {
        Probe probe = new Probe();
        try (FaultPeer peer = new FaultPeer(Fault.NO_WELCOME)) {
            ChatConnection connection = new ChatConnection(probe, FAST);
            connection.connect("127.0.0.1", "127.0.0.1", peer.port(), "关闭测试");
            peer.awaitJoined();
            connection.close();
            check(connection.state() == ChatConnection.State.DISCONNECTED,
                    "A22 握手中关闭后状态未结束");
            expect(IllegalStateException.class,
                    () -> connection.connect("127.0.0.1", "127.0.0.1", peer.port(), "已经关闭"));
            connection.close();
        }
    }

    private static void callbacksAcrossGenerations() throws Exception {
        Probe probe = new Probe();
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallbacks = new CountDownLatch(1);
        AtomicInteger starts = new AtomicInteger();
        ChatConnection.Listener delayed = new ChatConnection.Listener() {
            @Override
            public void onState(ChatConnection.State state, String detail) {
                if (state == ChatConnection.State.CONNECTING && starts.incrementAndGet() == 1) {
                    callbackEntered.countDown();
                    try {
                        check(releaseCallbacks.await(5, TimeUnit.SECONDS), "串行回调测试未释放等待");
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("串行回调测试被中断", exception);
                    }
                }
                probe.onState(state, detail);
            }

            @Override
            public void onFrame(Frame frame) {
                probe.onFrame(frame);
            }
        };
        try (ChatServer server = new ChatServer("127.0.0.1", 0);
             ChatConnection connection = new ChatConnection(delayed, FAST)) {
            server.start();
            try {
                connection.connect("127.0.0.1", "127.0.0.1", server.port(), "旧连接");
                check(callbackEntered.await(5, TimeUnit.SECONDS), "串行回调测试未开始");
                waitUntil(() -> connection.state() == ChatConnection.State.CONNECTED, "旧连接未建立");
                connection.disconnect();
                waitUntil(() -> connection.state() == ChatConnection.State.DISCONNECTED, "旧连接未结束");
                connection.connect("127.0.0.1", "127.0.0.1", server.port(), "新连接");
                waitUntil(() -> connection.state() == ChatConnection.State.CONNECTED, "新连接未建立");
                releaseCallbacks.countDown();
                StateEvent connected = probe.awaitState(ChatConnection.State.CONNECTED);
                check(connected.detail().contains("已连接"), "新连接缺少已连接提示");
                Frame welcome = probe.awaitFrame(MessageType.WELCOME);
                check(welcome.from().equals("新连接"), "A16 旧连接回调覆盖新连接");
                check(probe.connectedCount.get() == 1, "A16 旧连接 CONNECTED 回调未过滤");
                check(probe.states.stream().noneMatch(event -> event.state() == ChatConnection.State.DISCONNECTED),
                        "A16 旧连接 DISCONNECTED 回调未过滤");
                connection.disconnect();
                probe.awaitState(ChatConnection.State.DISCONNECTED);
            } finally {
                releaseCallbacks.countDown();
            }
        }
    }

    private static void slowListenerBound() throws Exception {
        Probe probe = new Probe();
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallbacks = new CountDownLatch(1);
        ChatConnection.Listener slowListener = new ChatConnection.Listener() {
            @Override
            public void onState(ChatConnection.State state, String detail) {
                if (state == ChatConnection.State.CONNECTED) {
                    callbackEntered.countDown();
                    try {
                        check(releaseCallbacks.await(5, TimeUnit.SECONDS), "慢监听器测试未释放等待");
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("慢监听器测试被中断", exception);
                    }
                }
                probe.onState(state, detail);
            }

            @Override
            public void onFrame(Frame frame) {
                probe.onFrame(frame);
            }
        };
        ChatConnection.Listener discardListener = new ChatConnection.Listener() {
            @Override
            public void onState(ChatConnection.State state, String detail) {
            }

            @Override
            public void onFrame(Frame frame) {
            }
        };
        try (ChatServer server = new ChatServer("127.0.0.1", 0);
             ChatConnection receiver = new ChatConnection(slowListener);
             ChatConnection sender = new ChatConnection(discardListener)) {
            server.start();
            try {
                receiver.connect("127.0.0.1", "127.0.0.1", server.port(), "慢监听甲");
                check(callbackEntered.await(5, TimeUnit.SECONDS), "慢监听器未进入已连接回调");
                sender.connect("127.0.0.1", "127.0.0.1", server.port(), "发送乙");
                waitUntil(() -> sender.state() == ChatConnection.State.CONNECTED, "高频消息发送端未连接");
                int sent = 0;
                while (receiver.state() == ChatConnection.State.CONNECTED && sent < 512) {
                    sender.send("慢监听甲", "有界队列消息 " + sent++);
                    Thread.sleep(2);
                }
                waitUntil(() -> receiver.state() == ChatConnection.State.DISCONNECTED,
                        "慢监听器队列已满后连接未结束");
                check(sent > 0 && sent < 512, "慢监听器消息边界未触发有界队列");
                releaseCallbacks.countDown();
                StateEvent ended = probe.awaitState(ChatConnection.State.DISCONNECTED);
                check(ended.detail().contains("队列已满"), "慢监听器缺少队列已满终止说明");
                check(probe.frames.isEmpty(), "慢监听器队列溢出后未清理待处理消息");
                check(probe.maxCallbackConcurrency.get() == 1, "队列溢出导致监听回调并行执行");
                check(sender.state() == ChatConnection.State.CONNECTED, "慢监听器影响其他连接");
            } finally {
                releaseCallbacks.countDown();
            }
        }
    }

    private static ServerSocket loopbackServer() throws IOException {
        return new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
    }

    private static void waitUntil(BooleanSupplier condition, String message) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < end) {
            Thread.sleep(10);
        }
        check(condition.getAsBoolean(), message);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void expect(Class<? extends Throwable> type, Action action) throws Exception {
        try {
            action.run();
        } catch (Throwable failure) {
            if (type.isInstance(failure)) {
                return;
            }
            throw new AssertionError("预期异常 " + type.getName() + "，实际为 " + failure, failure);
        }
        throw new AssertionError("未出现预期异常：" + type.getName());
    }

    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }

    private record StateEvent(ChatConnection.State state, String detail) {
    }

    private static final class Probe implements ChatConnection.Listener {
        final BlockingQueue<StateEvent> states = new LinkedBlockingQueue<>();
        final BlockingQueue<Frame> frames = new LinkedBlockingQueue<>();
        final AtomicInteger concurrentCallbacks = new AtomicInteger();
        final AtomicInteger maxCallbackConcurrency = new AtomicInteger();
        final AtomicInteger connectedCount = new AtomicInteger();

        @Override
        public void onState(ChatConnection.State state, String detail) {
            enter();
            try {
                if (state == ChatConnection.State.CONNECTED) {
                    connectedCount.incrementAndGet();
                }
                states.add(new StateEvent(state, detail));
            } finally {
                concurrentCallbacks.decrementAndGet();
            }
        }

        @Override
        public void onFrame(Frame frame) {
            enter();
            try {
                frames.add(frame);
            } finally {
                concurrentCallbacks.decrementAndGet();
            }
        }

        private void enter() {
            maxCallbackConcurrency.accumulateAndGet(concurrentCallbacks.incrementAndGet(), Math::max);
        }

        StateEvent awaitState(ChatConnection.State wanted) throws Exception {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            List<StateEvent> observed = new ArrayList<>();
            while (System.nanoTime() < end) {
                StateEvent event = states.poll(Math.max(1, end - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (event == null) {
                    break;
                }
                observed.add(event);
                if (event.state() == wanted) {
                    return event;
                }
            }
            throw new AssertionError("没有收到状态 " + wanted + "，实际事件：" + observed);
        }

        Frame awaitFrame(MessageType wanted) throws Exception {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            List<MessageType> observed = new ArrayList<>();
            while (System.nanoTime() < end) {
                Frame frame = frames.poll(Math.max(1, end - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (frame == null) {
                    break;
                }
                observed.add(frame.type());
                if (frame.type() == wanted) {
                    return frame;
                }
            }
            throw new AssertionError("没有收到消息 " + wanted + "，实际类型：" + observed);
        }

        void clearFrames() {
            frames.clear();
        }
    }

    private enum Fault { NO_WELCOME, HALF_WELCOME, NO_PONG, HALF_PONG, REJECT_JOIN, BAD_FRAME }

    // 真实 ServerSocket 故障注入端点，按场景故意省略 WELCOME、截断帧或停止回复 PONG。
    private static final class FaultPeer implements AutoCloseable {
        final ServerSocket server;
        final Thread thread;
        final Fault fault;
        final CountDownLatch joined = new CountDownLatch(1);
        final AtomicInteger pings = new AtomicInteger();
        volatile Socket socket;
        volatile boolean closed;
        volatile Throwable failure;
        volatile long joinId;

        FaultPeer(Fault fault) throws IOException {
            this.fault = fault;
            server = loopbackServer();
            thread = new Thread(this::serve, "connection-test-fault-" + fault);
            thread.start();
        }

        int port() {
            return server.getLocalPort();
        }

        void awaitJoined() throws Exception {
            check(joined.await(5, TimeUnit.SECONDS), "故障注入端点未收到 JOIN");
            check(failure == null, "故障注入端点失败：" + failure);
        }

        private void serve() {
            try (Socket accepted = server.accept()) {
                socket = accepted;
                Frame join = ProtocolCodec.read(accepted.getInputStream());
                check(join != null && join.type() == MessageType.JOIN, "故障端点预期 JOIN");
                joinId = join.requestId();
                if (fault == Fault.NO_PONG || fault == Fault.HALF_PONG) {
                    ProtocolCodec.write(accepted.getOutputStream(), new Frame(MessageType.WELCOME,
                            join.requestId(), System.currentTimeMillis(), "", join.from(), "", "", List.of()));
                    if (fault == Fault.HALF_PONG) {
                        accepted.getOutputStream().write(new byte[] {0, 0, 0, 64, 1});
                    }
                } else if (fault == Fault.HALF_WELCOME) {
                    accepted.getOutputStream().write(new byte[] {0, 0, 0, 64, 1});
                } else if (fault == Fault.REJECT_JOIN) {
                    ProtocolCodec.write(accepted.getOutputStream(), new Frame(MessageType.ERROR,
                            join.requestId(), System.currentTimeMillis(), "", "SYSTEM", "", "NICK_IN_USE", List.of()));
                } else if (fault == Fault.BAD_FRAME) {
                    accepted.getOutputStream().write(new byte[] {-1, -1, -1, -1});
                }
                joined.countDown();
                Frame frame;
                while ((frame = ProtocolCodec.read(accepted.getInputStream())) != null) {
                    if (frame.type() == MessageType.PING) {
                        pings.incrementAndGet();
                    }
                }
            } catch (IOException exception) {
                if (!closed) {
                    failure = exception;
                }
            } catch (Throwable exception) {
                failure = exception;
            } finally {
                joined.countDown();
            }
        }

        @Override
        public void close() throws Exception {
            closed = true;
            server.close();
            if (socket != null) {
                socket.close();
            }
            thread.join(5000);
            check(!thread.isAlive(), "故障注入端点线程未结束");
            if (failure != null) {
                throw new AssertionError("故障注入端点失败", failure);
            }
        }
    }
}
