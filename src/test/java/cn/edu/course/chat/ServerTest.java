package cn.edu.course.chat;

import cn.edu.course.chat.common.Frame;
import cn.edu.course.chat.common.MessageType;
import cn.edu.course.chat.common.ProtocolCodec;
import cn.edu.course.chat.server.ChatServer;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

public final class ServerTest {
    private static final String HOST = "127.0.0.1";
    private static final long WAIT_MILLIS = 5_000;

    private ServerTest() {
    }

    public static void main(String[] args) throws Exception {
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));
        run();
    }

    public static void run() throws Exception {
        System.out.println("ServerTest：显式启用 127.0.0.1 开发回环自测，不代替指定 IP 验收");
        joinAndRouting();
        framingAndIsolation();
        capacityAndTimeouts();
        slowReader();
        shutdownAndBinding();
        boundedObservation();
        List<String> alive = Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .map(Thread::getName)
                .filter(name -> name.startsWith("chat-server-") || name.startsWith("server-test-"))
                .toList();
        check(alive.isEmpty(), "测试结束后不能保留服务端或测试线程：" + alive);
    }

    private static void joinAndRouting() throws Exception {
        try (ChatServer server = started();
             Peer alpha = joined(server, "Alpha");
             Peer beta = joined(server, "Beta");
             Peer gamma = joined(server, "Gamma")) {
            alpha.awaitUsers(Set.of("Alpha", "Beta", "Gamma"));
            beta.awaitUsers(Set.of("Alpha", "Beta", "Gamma"));
            gamma.awaitUsers(Set.of("Alpha", "Beta", "Gamma"));

            try (Peer duplicate = new Peer(server.port())) {
                duplicate.send(Frame.request(MessageType.JOIN, 1, "Alpha", "", ""));
                duplicate.awaitError(1, "NICK_IN_USE");
            }
            for (String nickname : List.of("", " ", "PUBLIC", "SYSTEM", "bad name", "bad/name",
                    "abcdefghijklmnopq", "甲".repeat(17), "😀")) {
                try (Peer invalid = new Peer(server.port())) {
                    invalid.send(Frame.request(MessageType.JOIN, 1, nickname, "", ""));
                    invalid.awaitError(1, "NICK_INVALID");
                }
            }
            try (Peer boundary = joined(server, "abcdefghijklmnop")) {
                boundary.send(Frame.request(MessageType.PING, 2, "", "", ""));
                boundary.awaitType(MessageType.PONG, 2);
            }
            try (Peer boundary = joined(server, "甲".repeat(16))) {
                boundary.barrier(2);
            }

            alpha.send(Frame.request(MessageType.JOIN, 2, "Changed", "", ""));
            alpha.awaitError(2, "ALREADY_JOINED");
            String unicode = "中文 English 😀\n第二行\t<>&\\\"";
            Frame forged = new Frame(MessageType.PUBLIC_SEND, 3, 1, "client-generated-id",
                    "Impostor", "PUBLIC", unicode, List.of());
            alpha.send(forged);
            Frame publicEcho = alpha.awaitChat(unicode);
            Frame betaPublic = beta.awaitChat(unicode);
            Frame gammaPublic = gamma.awaitChat(unicode);
            check(publicEcho.from().equals("Alpha"), "发送者必须来自已经加入的连接");
            check(publicEcho.serverTime() > 1, "聊天时间必须由服务端生成");
            check(!publicEcho.messageId().isBlank()
                    && !publicEcho.messageId().equals("client-generated-id"), "消息 ID 必须由服务端生成");
            sameEvent(publicEcho, betaPublic);
            sameEvent(publicEcho, gammaPublic);
            check(publicEcho.requestId() == 3 && betaPublic.requestId() == 0
                    && gammaPublic.requestId() == 0, "公共回显只保留发送者的 requestId");
            for (Peer peer : List.of(alpha, beta, gamma)) {
                peer.barrier(900);
                check(peer.chatCount(unicode) == 1, "每个客户端只收到一次公共消息");
            }

            alpha.send(Frame.request(MessageType.PRIVATE_SEND, 4, "Impostor", "Beta", "private-only"));
            Frame privateEcho = alpha.awaitChat("private-only");
            Frame privateDelivery = beta.awaitChat("private-only");
            sameEvent(privateEcho, privateDelivery);
            check(privateEcho.from().equals("Alpha") && privateEcho.to().equals("Beta"),
                    "私聊使用连接身份与指定目标");
            check(privateEcho.requestId() == 4 && privateDelivery.requestId() == 0,
                    "私聊回显只保留发送者的 requestId");
            gamma.barrier(901);
            check(gamma.chatCount("private-only") == 0, "第三个客户端不能收到私聊正文");

            alpha.send(Frame.request(MessageType.PRIVATE_SEND, 5, "", "Nobody", "offline"));
            alpha.awaitError(5, "USER_OFFLINE");
            alpha.send(Frame.request(MessageType.PRIVATE_SEND, 6, "", "Alpha", "self"));
            alpha.awaitError(6, "SELF_PRIVATE");
            for (String text : List.of("", " \t\n", "a".repeat(2_001))) {
                alpha.send(Frame.request(MessageType.PUBLIC_SEND, 7, "", "PUBLIC", text));
                alpha.awaitError(7, "TEXT_INVALID");
            }
            String boundaryText = "😀".repeat(2_000);
            alpha.send(Frame.request(MessageType.PUBLIC_SEND, 8, "", "PUBLIC", boundaryText));
            alpha.awaitChat(boundaryText);
            beta.awaitChat(boundaryText);
            gamma.awaitChat(boundaryText);

            alpha.send(Frame.request(MessageType.PUBLIC_SEND, 777, "", "PUBLIC", "alpha-same-request"));
            beta.send(Frame.request(MessageType.PUBLIC_SEND, 777, "", "PUBLIC", "beta-same-request"));
            Frame alphaOwn = alpha.awaitChat("alpha-same-request");
            Frame betaOwn = beta.awaitChat("beta-same-request");
            alpha.barrier(902);
            beta.barrier(903);
            gamma.barrier(904);
            check(alphaOwn.requestId() == 777 && betaOwn.requestId() == 777,
                    "相同 requestId 的两个发送者各自收到有效回显");
            check(alpha.findChat("beta-same-request").requestId() == 0
                    && beta.findChat("alpha-same-request").requestId() == 0,
                    "其他发送者的消息不能确认本人的同号请求");

            gamma.send(Frame.request(MessageType.LEAVE, 9, "", "", ""));
            gamma.awaitClosed();
            alpha.awaitUsers(Set.of("Alpha", "Beta"));
            try (Peer rejoined = joined(server, "Gamma")) {
                alpha.awaitUsers(Set.of("Alpha", "Beta", "Gamma"));
                rejoined.socket.setSoLinger(true, 0);
            }
            alpha.awaitUsers(Set.of("Alpha", "Beta"));
            try (Peer rejoined = joined(server, "Gamma")) {
                rejoined.barrier(905);
            }
            alpha.send(Frame.request(MessageType.PUBLIC_SEND, 10, "", "PUBLIC", "still-running"));
            alpha.awaitChat("still-running");
            beta.awaitChat("still-running");
            check(alpha.chatCount("offline") == 0 && alpha.chatCount("self") == 0,
                    "错误私聊不能生成成功消息");
        }
        System.out.println("ServerTest：A02～A08 的服务端自动部分、A19、A25 通过");
    }

    private static void framingAndIsolation() throws Exception {
        try (ChatServer server = started(); Peer healthy = joined(server, "Healthy")) {
            try (Peer stranger = new Peer(server.port())) {
                stranger.send(Frame.request(MessageType.PUBLIC_SEND, 1, "Healthy", "PUBLIC", "unjoined"));
                stranger.awaitError(1, "NOT_JOINED");
                healthy.barrier(1_001);
                check(healthy.chatCount("unjoined") == 0, "未加入连接不能发送聊天");
            }
            try (Peer fragmented = new Peer(server.port())) {
                byte[] join = encoded(Frame.request(MessageType.JOIN, 1, "Fragmented", "", ""));
                for (byte value : join) {
                    fragmented.socket.getOutputStream().write(value);
                    fragmented.socket.getOutputStream().flush();
                }
                check(fragmented.next().type() == MessageType.WELCOME, "分片 JOIN 的首个响应应为 WELCOME");
                ByteArrayOutputStream merged = new ByteArrayOutputStream();
                ProtocolCodec.write(merged, Frame.request(MessageType.PUBLIC_SEND, 2, "", "PUBLIC", "merged-a"));
                ProtocolCodec.write(merged, Frame.request(MessageType.PUBLIC_SEND, 3, "", "PUBLIC", "merged-b"));
                fragmented.socket.getOutputStream().write(merged.toByteArray());
                fragmented.socket.getOutputStream().flush();
                healthy.awaitChat("merged-a");
                healthy.awaitChat("merged-b");
            }
            try (Socket half = raw(server.port())) {
                byte[] frame = encoded(Frame.request(MessageType.JOIN, 1, "Half", "", ""));
                half.getOutputStream().write(frame, 0, frame.length / 2);
                half.getOutputStream().flush();
            }
            List<byte[]> malformed = new ArrayList<>();
            malformed.add(lengthOnly(-1));
            malformed.add(lengthOnly(16_385));
            malformed.add(lengthOnly(Integer.MAX_VALUE));
            byte[] badVersion = encoded(Frame.request(MessageType.JOIN, 1, "BadVersion", "", ""));
            badVersion[4] = 99;
            malformed.add(badVersion);
            byte[] badType = encoded(Frame.request(MessageType.JOIN, 1, "BadType", "", ""));
            badType[5] = 99;
            malformed.add(badType);
            byte[] badUtf8 = encoded(Frame.request(MessageType.JOIN, 1, "Z", "", ""));
            badUtf8[30] = (byte) 0xC0;
            malformed.add(badUtf8);
            for (byte[] bytes : malformed) {
                try (Socket invalid = raw(server.port())) {
                    invalid.getOutputStream().write(bytes);
                    invalid.getOutputStream().flush();
                    expectClosed(invalid, "畸形帧连接必须关闭");
                }
                healthy.send(Frame.request(MessageType.PING, 1_002, "", "", ""));
                healthy.awaitType(MessageType.PONG, 1_002);
            }
            healthy.send(Frame.request(MessageType.PUBLIC_SEND, 1_003, "", "PUBLIC", "after-malformed"));
            healthy.awaitChat("after-malformed");
        }
        System.out.println("ServerTest：A17、A18 通过");
    }

    private static void capacityAndTimeouts() throws Exception {
        try (ChatServer server = started()) {
            List<Socket> sockets = new ArrayList<>();
            try {
                for (int index = 0; index < 10; index++) {
                    Socket socket = raw(server.port());
                    sockets.add(socket);
                    ProtocolCodec.write(socket.getOutputStream(),
                            Frame.request(MessageType.JOIN, 1, "Capacity" + index, "", ""));
                    Frame first = ProtocolCodec.read(socket.getInputStream());
                    check(first != null && first.type() == MessageType.WELCOME,
                            "前十个连接应完成加入");
                }
                try (Socket overflow = raw(server.port())) {
                    expectClosed(overflow, "第十一个同时连接必须被拒绝");
                }
            } finally {
                for (Socket socket : sockets) {
                    socket.close();
                }
            }
        }
        ChatServer.Options options = new ChatServer.Options(1, 128, 250, 350, 20);
        try (ChatServer server = new ChatServer(HOST, 0, options)) {
            server.start();
            try (Socket unjoined = raw(server.port())) {
                expectClosed(unjoined, "未加入连接应达到握手期限后关闭");
            }
            try (Peer idle = joined(server, "Idle")) {
                idle.awaitClosed();
            }
            try (Peer replacement = joined(server, "Idle")) {
                replacement.barrier(1_100);
            }
        }
        System.out.println("ServerTest：A20 连接容量与服务端握手/静默期限通过");
    }

    private static void slowReader() throws Exception {
        ChatServer.Options options = new ChatServer.Options(10, 8, 10_000, 45_000, 50);
        try (ChatServer server = new ChatServer(HOST, 0, options)) {
            server.start();
            try (Peer healthy = joined(server, "Reader"); Socket slow = new Socket()) {
                slow.setReceiveBufferSize(1_024);
                slow.connect(new InetSocketAddress(HOST, server.port()), 2_000);
                slow.setSoTimeout((int) WAIT_MILLIS);
                ProtocolCodec.write(slow.getOutputStream(),
                        Frame.request(MessageType.JOIN, 1, "SlowReader", "", ""));
                Frame welcome = ProtocolCodec.read(slow.getInputStream());
                check(welcome != null && welcome.type() == MessageType.WELCOME,
                        "慢客户端应先完成真实 JOIN");
                healthy.awaitUsers(Set.of("Reader", "SlowReader"));
                String body = "界".repeat(2_000);
                int sent = 0;
                for (; sent < 4_000 && !healthy.sawUsers(Set.of("Reader")); sent++) {
                    healthy.send(Frame.request(MessageType.PUBLIC_SEND, sent + 2L, "", "PUBLIC", body));
                    Frame echo = healthy.awaitChat(body);
                    check(echo.requestId() == sent + 2L, "正常客户端应持续收到当前发送的回显");
                }
                healthy.awaitUsers(Set.of("Reader"));
                healthy.send(Frame.request(MessageType.PUBLIC_SEND, 5_000, "", "PUBLIC", "after-slow-reader"));
                healthy.awaitChat("after-slow-reader");
                healthy.barrier(5_001);
                try (Peer replacement = joined(server, "SlowReader")) {
                    replacement.barrier(5_002);
                }
                System.out.println("ServerTest：A20 慢客户端停止读取，发送 " + sent
                        + " 条有界长正文后释放其昵称；正常客户端继续通信，通过");
            }
        }
    }

    private static void shutdownAndBinding() throws Exception {
        int port;
        Peer peer;
        try (ChatServer server = started()) {
            port = server.port();
            peer = joined(server, "Shutdown");
        }
        try (peer) {
            peer.awaitClosed();
        }
        try (ChatServer restarted = new ChatServer(HOST, port)) {
            restarted.start();
            try (Peer peerAgain = joined(restarted, "Restarted")) {
                peerAgain.barrier(6_000);
            }
        }
        try (ServerSocket occupied = new ServerSocket(0, 1, InetAddress.getByName(HOST))) {
            expectBindFailure(HOST, occupied.getLocalPort(), "端口占用必须明确失败");
        }
        String unavailable = "192.0.2.123";
        check(NetworkInterface.getByInetAddress(InetAddress.getByName(unavailable)) == null,
                "未配置地址测试需要一个当前未分配给接口的地址");
        expectBindFailure(unavailable, 0, "未配置地址必须明确失败");
        System.out.println("ServerTest：A22、A24 的服务端绑定与重新监听通过");
    }

    private static void boundedObservation() throws Exception {
        try (ChatServer server = started();
             Peer alpha = joined(server, "ObserveA");
             Peer beta = joined(server, "ObserveB");
             Peer gamma = joined(server, "ObserveC")) {
            List<Peer> peers = List.of(alpha, beta, gamma);
            Set<String> names = Set.of("ObserveA", "ObserveB", "ObserveC");
            for (Peer peer : peers) {
                peer.awaitUsers(names);
            }
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            List<Thread> senders = new ArrayList<>();
            for (int sender = 0; sender < peers.size(); sender++) {
                Peer peer = peers.get(sender);
                int senderIndex = sender;
                Thread thread = new Thread(() -> {
                    try {
                        start.await();
                        for (int sequence = 0; sequence < 20; sequence++) {
                            String text = "observation-" + senderIndex + "-" + sequence;
                            peer.send(Frame.request(MessageType.PUBLIC_SEND, sequence + 1L,
                                    "", "PUBLIC", text));
                        }
                    } catch (Throwable error) {
                        failure.compareAndSet(null, error);
                    }
                }, "server-test-sender-" + sender);
                senders.add(thread);
                thread.start();
            }
            long startedNanos = System.nanoTime();
            start.countDown();
            for (Thread sender : senders) {
                sender.join(WAIT_MILLIS);
                check(!sender.isAlive(), "观察测试的发送线程必须结束");
            }
            if (failure.get() != null) {
                throw new AssertionError("观察测试发送失败", failure.get());
            }
            Set<String> expectedTexts = new HashSet<>();
            for (int sender = 0; sender < 3; sender++) {
                for (int sequence = 0; sequence < 20; sequence++) {
                    expectedTexts.add("observation-" + sender + "-" + sequence);
                }
            }
            for (Peer peer : peers) {
                Set<String> deliveredTexts = new HashSet<>();
                while (deliveredTexts.size() < 60) {
                    Frame frame = peer.await(frameValue -> frameValue.type() == MessageType.CHAT_EVENT);
                    check(expectedTexts.contains(frame.text()), "观察测试不能收到计划外正文");
                    check(deliveredTexts.add(frame.text()), "观察测试不能重复收到同一正文");
                }
                peer.barrier(7_000);
            }
            long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
            Set<String> messageIds = new HashSet<>();
            for (Frame frame : alpha.chats()) {
                check(messageIds.add(frame.messageId()), "计划的 60 条消息应具有不同的 messageId");
            }
            check(messageIds.size() == 60, "应生成 60 个唯一 messageId");
            for (int index = 0; index < peers.size(); index++) {
                List<Frame> chats = peers.get(index).chats();
                Set<String> ids = new HashSet<>();
                chats.forEach(frame -> ids.add(frame.messageId()));
                int duplicates = chats.size() - ids.size();
                Set<String> missing = new HashSet<>(messageIds);
                missing.removeAll(ids);
                check(chats.size() == 60 && duplicates == 0 && missing.isEmpty(),
                        "每个客户端应收到 60 条，重复 0，遗漏 0");
                System.out.println("ServerTest：观察客户端 " + (index + 1) + "，收到=" + chats.size()
                        + "，重复=" + duplicates + "，遗漏=" + missing.size());
            }
            System.out.println("ServerTest：3 客户端各发送 20 条；唯一 messageId=" + messageIds.size()
                    + "，合计投递=180，耗时=" + elapsedMillis + " ms；Java="
                    + System.getProperty("java.version") + "，系统=" + System.getProperty("os.name"));
        }
    }

    private static ChatServer started() throws IOException {
        ChatServer server = new ChatServer(HOST, 0);
        server.start();
        return server;
    }

    private static Peer joined(ChatServer server, String nickname) throws Exception {
        Peer peer = new Peer(server.port());
        try {
            peer.send(Frame.request(MessageType.JOIN, 1, nickname, "", ""));
            Frame first = peer.next();
            check(first.type() == MessageType.WELCOME && first.requestId() == 1
                    && first.from().equals(nickname), "JOIN 的第一个响应必须为本人的 WELCOME");
            return peer;
        } catch (Exception | AssertionError error) {
            peer.close();
            throw error;
        }
    }

    private static Socket raw(int port) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(HOST, port), 2_000);
        socket.setSoTimeout((int) WAIT_MILLIS);
        return socket;
    }

    private static byte[] encoded(Frame frame) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ProtocolCodec.write(bytes, frame);
        return bytes.toByteArray();
    }

    private static byte[] lengthOnly(int length) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new DataOutputStream(bytes).writeInt(length);
        return bytes.toByteArray();
    }

    private static void expectClosed(Socket socket, String message) throws IOException {
        try {
            check(socket.getInputStream().read() == -1, message);
        } catch (java.net.SocketException error) {
            check(error.getMessage() != null, message);
        }
    }

    private static void expectBindFailure(String host, int port, String message) throws Exception {
        try (ChatServer server = new ChatServer(host, port)) {
            try {
                server.start();
            } catch (IOException expected) {
                return;
            }
            throw new AssertionError(message);
        }
    }

    private static void sameEvent(Frame left, Frame right) {
        check(left.messageId().equals(right.messageId()) && left.serverTime() == right.serverTime()
                && left.from().equals(right.from()) && left.to().equals(right.to())
                && left.text().equals(right.text()), "同一聊天事件的 ID、时间、身份与正文应相同");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class Peer implements AutoCloseable {
        private final Socket socket;
        private final BlockingQueue<Frame> queue = new LinkedBlockingQueue<>(10_000);
        private final List<Frame> received = new ArrayList<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final CountDownLatch closed = new CountDownLatch(1);
        private final Thread reader;
        private volatile boolean closing;

        private Peer(int port) throws IOException {
            socket = raw(port);
            socket.setSoTimeout(0);
            reader = new Thread(() -> {
                try {
                    Frame frame;
                    while ((frame = ProtocolCodec.read(socket.getInputStream())) != null) {
                        synchronized (received) {
                            received.add(frame);
                        }
                        check(queue.offer(frame), "测试客户端的接收队列达到上限");
                    }
                } catch (Throwable error) {
                    if (!closing) {
                        failure.set(error);
                    }
                } finally {
                    closed.countDown();
                }
            }, "server-test-reader-" + socket.getLocalPort());
            reader.start();
        }

        private void send(Frame frame) throws IOException {
            ProtocolCodec.write(socket.getOutputStream(), frame);
        }

        private Frame next() throws Exception {
            Frame frame = queue.poll(WAIT_MILLIS, TimeUnit.MILLISECONDS);
            if (frame == null) {
                throw new AssertionError("测试客户端等待响应超时；连接结束="
                        + (closed.getCount() == 0), failure.get());
            }
            return frame;
        }

        private Frame await(Predicate<Frame> condition) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MILLIS);
            while (System.nanoTime() < deadline) {
                long remaining = deadline - System.nanoTime();
                Frame frame = queue.poll(Math.max(1, remaining), TimeUnit.NANOSECONDS);
                if (frame != null && condition.test(frame)) {
                    return frame;
                }
            }
            throw new AssertionError("测试客户端未在期限内收到所需帧；连接结束="
                    + (closed.getCount() == 0), failure.get());
        }

        private Frame awaitType(MessageType type, long requestId) throws Exception {
            return await(frame -> frame.type() == type && frame.requestId() == requestId);
        }

        private void awaitError(long requestId, String code) throws Exception {
            Frame error = awaitType(MessageType.ERROR, requestId);
            check(error.text().equals(code), "错误码应为 " + code + "，实际为 " + error.text());
        }

        private Frame awaitChat(String text) throws Exception {
            return await(frame -> frame.type() == MessageType.CHAT_EVENT && frame.text().equals(text));
        }

        private void awaitUsers(Set<String> users) throws Exception {
            if (!sawUsers(users)) {
                await(frame -> frame.type() == MessageType.USER_LIST
                        && Set.copyOf(frame.users()).equals(users));
            }
        }

        private boolean sawUsers(Set<String> users) {
            synchronized (received) {
                for (int index = received.size() - 1; index >= 0; index--) {
                    Frame frame = received.get(index);
                    if (frame.type() == MessageType.USER_LIST) {
                        return Set.copyOf(frame.users()).equals(users);
                    }
                }
                return false;
            }
        }

        private void barrier(long requestId) throws Exception {
            send(Frame.request(MessageType.PING, requestId, "", "", ""));
            awaitType(MessageType.PONG, requestId);
        }

        private long chatCount(String text) {
            return chats().stream().filter(frame -> frame.text().equals(text)).count();
        }

        private Frame findChat(String text) {
            return chats().stream().filter(frame -> frame.text().equals(text)).findFirst()
                    .orElseThrow(() -> new AssertionError("没有收到正文：" + text));
        }

        private List<Frame> chats() {
            synchronized (received) {
                return received.stream().filter(frame -> frame.type() == MessageType.CHAT_EVENT).toList();
            }
        }

        private void awaitClosed() throws Exception {
            check(closed.await(WAIT_MILLIS, TimeUnit.MILLISECONDS), "连接读线程应在期限内结束");
            reader.join(WAIT_MILLIS);
            check(!reader.isAlive(), "测试客户端读线程应结束");
        }

        @Override
        public void close() throws Exception {
            closing = true;
            socket.close();
            reader.join(WAIT_MILLIS);
            check(!reader.isAlive(), "关闭测试客户端后读线程应结束");
        }
    }
}
