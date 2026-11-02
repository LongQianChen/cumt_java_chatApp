package cn.edu.course.chat.server;

import cn.edu.course.chat.common.Frame;
import cn.edu.course.chat.common.MessageType;
import cn.edu.course.chat.common.ProtocolCodec;
import cn.edu.course.chat.common.Validation;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** 服务端只保存在线连接与待发送帧。 */
public final class ChatServer implements AutoCloseable {
    public record Options(int maxConnections, int queueCapacity, long handshakeMillis,
                          long idleMillis, long checkMillis) {
        public Options {
            if (maxConnections < 1 || maxConnections > 10) {
                throw new IllegalArgumentException("连接上限必须为 1～10");
            }
            if (queueCapacity < 1 || queueCapacity > 128) {
                throw new IllegalArgumentException("发送队列容量必须为 1～128");
            }
            if (handshakeMillis <= 0 || idleMillis <= 0 || checkMillis <= 0) {
                throw new IllegalArgumentException("连接检查期限必须为正数");
            }
        }

        public static Options defaults() {
            return new Options(10, 128, 10_000, 45_000, 1_000);
        }
    }

    private static final String PUBLIC = "PUBLIC";
    private static final String SYSTEM = "SYSTEM";
    private static final long CLOSE_WAIT_MILLIS = 3_000;

    private final String host;
    private final int requestedPort;
    private final Options options;
    private final Object registryLock = new Object();
    private final Object startLock = new Object();
    private final Set<Session> sessions = new LinkedHashSet<>();
    private final Map<String, Session> joined = new LinkedHashMap<>();
    private final ArrayDeque<String> pendingNotices = new ArrayDeque<>();
    private final Set<Thread> workers = ConcurrentHashMap.newKeySet();
    private final AtomicLong sessionSequence = new AtomicLong();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final CountDownLatch terminated = new CountDownLatch(1);
    private volatile ServerSocket listener;
    private volatile boolean running;
    private boolean started;

    public ChatServer(String host, int port) {
        this(host, port, Options.defaults());
    }

    public ChatServer(String host, int port, Options options) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("监听地址不能为空");
        }
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("监听端口必须为 0～65535");
        }
        if (options == null) {
            throw new IllegalArgumentException("服务端选项不能为空");
        }
        this.host = host;
        this.requestedPort = port;
        this.options = options;
    }

    public void start() throws IOException {
        synchronized (startLock) {
            if (started || closing.get()) {
                throw new IllegalStateException("服务端已经启动或关闭");
            }
            ServerSocket socket = new ServerSocket();
            try {
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(host, requestedPort));
            } catch (IOException | RuntimeException exception) {
                try {
                    socket.close();
                } catch (IOException closeException) {
                    exception.addSuppressed(closeException);
                }
                throw exception;
            }
            listener = socket;
            started = true;
            running = true;
            synchronized (registryLock) {
                startWorker("chat-server-accept-" + port(), this::acceptLoop);
                startWorker("chat-server-check-" + port(), this::checkLoop);
            }
            String mode = socket.getInetAddress().isLoopbackAddress() ? "（开发回环自测）" : "";
            log("服务端正在监听 " + endpoint() + mode);
        }
    }

    public int port() {
        ServerSocket socket = listener;
        if (socket == null) {
            throw new IllegalStateException("服务端尚未启动");
        }
        return socket.getLocalPort();
    }

    public String endpoint() {
        ServerSocket socket = listener;
        if (socket == null) {
            throw new IllegalStateException("服务端尚未启动");
        }
        InetSocketAddress address = (InetSocketAddress) socket.getLocalSocketAddress();
        String actualHost = address.getAddress().getHostAddress();
        return (actualHost.contains(":") ? "[" + actualHost + "]" : actualHost)
                + ":" + address.getPort();
    }

    public void awaitTermination() throws InterruptedException {
        terminated.await();
    }

    private void acceptLoop() {
        try {
            while (running) {
                Socket socket = listener.accept();
                log("接入连接：本地端点=" + socket.getLocalSocketAddress()
                        + "，远端端点=" + socket.getRemoteSocketAddress());
                try {
                    socket.setTcpNoDelay(true);
                    socket.setSendBufferSize(8_192);
                    Session session = new Session(socket);
                    boolean accepted;
                    synchronized (registryLock) {
                        accepted = running && sessions.size() < options.maxConnections();
                        if (accepted) {
                            sessions.add(session);
                            session.start();
                        }
                    }
                    if (!accepted) {
                        closeSocket(socket);
                        log("拒绝新连接：服务端已关闭或连接数量达到上限");
                    }
                } catch (IOException exception) {
                    closeSocket(socket);
                    log("初始化连接失败：" + exception.getClass().getSimpleName());
                }
            }
        } catch (IOException exception) {
            if (running) {
                log("监听连接失败：" + exception.getClass().getSimpleName());
                close();
            }
        }
    }

    private void checkLoop() {
        try {
            while (running) {
                Thread.sleep(options.checkMillis());
                long now = System.nanoTime();
                List<Session> closingSessions = new ArrayList<>();
                synchronized (registryLock) {
                    for (Session session : List.copyOf(sessions)) {
                        long elapsed = now - (session.nickname == null
                                ? session.acceptedAt : session.lastFrameAt);
                        long limit = TimeUnit.MILLISECONDS.toNanos(session.nickname == null
                                ? options.handshakeMillis() : options.idleMillis());
                        if (elapsed >= limit) {
                            removeLocked(session, session.nickname == null
                                    ? "加入握手超时" : "连接接收超时", closingSessions);
                        }
                    }
                    flushPresenceLocked(closingSessions);
                }
                finishClosing(closingSessions);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void handle(Session session, Frame request) {
        List<Session> closingSessions = new ArrayList<>();
        synchronized (registryLock) {
            if (session.closed) {
                return;
            }
            session.lastFrameAt = System.nanoTime();
            if (request.requestId() <= 0 || !request.users().isEmpty()
                    || !isRequestType(request.type())) {
                errorLocked(session, request.requestId(), "BAD_REQUEST", closingSessions);
            } else if (request.type() == MessageType.JOIN) {
                joinLocked(session, request, closingSessions);
            } else if (session.nickname == null) {
                errorLocked(session, request.requestId(), "NOT_JOINED", closingSessions);
            } else {
                switch (request.type()) {
                    case PUBLIC_SEND -> publicLocked(session, request, closingSessions);
                    case PRIVATE_SEND -> privateLocked(session, request, closingSessions);
                    case PING -> {
                        if (validControl(request)) {
                            enqueueLocked(session, response(MessageType.PONG, request.requestId(),
                                    "", "", "", List.of()), closingSessions);
                        } else {
                            errorLocked(session, request.requestId(), "BAD_REQUEST", closingSessions);
                        }
                    }
                    case LEAVE -> {
                        if (validControl(request)) {
                            removeLocked(session, "主动退出", closingSessions);
                        } else {
                            errorLocked(session, request.requestId(), "BAD_REQUEST", closingSessions);
                        }
                    }
                    default -> throw new IllegalStateException("未处理的客户端请求");
                }
            }
            flushPresenceLocked(closingSessions);
        }
        finishClosing(closingSessions);
    }

    private static boolean isRequestType(MessageType type) {
        return type == MessageType.JOIN || type == MessageType.PUBLIC_SEND
                || type == MessageType.PRIVATE_SEND || type == MessageType.LEAVE
                || type == MessageType.PING;
    }

    private static boolean validControl(Frame frame) {
        return frame.serverTime() == 0 && frame.messageId().isEmpty()
                && frame.from().isEmpty() && frame.to().isEmpty() && frame.text().isEmpty();
    }

    private void joinLocked(Session session, Frame request, List<Session> closingSessions) {
        if (session.nickname != null) {
            errorLocked(session, request.requestId(), "ALREADY_JOINED", closingSessions);
            return;
        }
        if (request.serverTime() != 0 || !request.messageId().isEmpty()
                || !request.to().isEmpty() || !request.text().isEmpty()) {
            errorLocked(session, request.requestId(), "BAD_REQUEST", closingSessions);
            return;
        }
        String nickname;
        try {
            nickname = Validation.nickname(request.from());
        } catch (IllegalArgumentException exception) {
            errorLocked(session, request.requestId(), "NICK_INVALID", closingSessions);
            return;
        }
        if (joined.containsKey(nickname)) {
            errorLocked(session, request.requestId(), "NICK_IN_USE", closingSessions);
            return;
        }
        // 同一临界区中，WELCOME 必须排在该会话的在线列表和加入通知之前。
        if (!enqueueLocked(session, response(MessageType.WELCOME, request.requestId(),
                nickname, "", "", List.of()), closingSessions)) {
            return;
        }
        session.nickname = nickname;
        joined.put(nickname, session);
        queuePresenceLocked(nickname + " 已加入公共聊天室");
    }

    private void publicLocked(Session session, Frame request, List<Session> closingSessions) {
        if (!PUBLIC.equals(request.to())) {
            errorLocked(session, request.requestId(), "BAD_REQUEST", closingSessions);
            return;
        }
        if (!validTextLocked(session, request, closingSessions)) {
            return;
        }
        long time = System.currentTimeMillis();
        String messageId = UUID.randomUUID().toString();
        for (Session recipient : List.copyOf(joined.values())) {
            enqueueLocked(recipient, new Frame(MessageType.CHAT_EVENT,
                    recipient == session ? request.requestId() : 0, time, messageId,
                    session.nickname, PUBLIC, request.text(), List.of()), closingSessions);
        }
    }

    private void privateLocked(Session session, Frame request, List<Session> closingSessions) {
        String target;
        try {
            target = Validation.nickname(request.to());
        } catch (IllegalArgumentException exception) {
            errorLocked(session, request.requestId(), "BAD_REQUEST", closingSessions);
            return;
        }
        if (!target.equals(request.to())) {
            errorLocked(session, request.requestId(), "BAD_REQUEST", closingSessions);
            return;
        }
        if (!validTextLocked(session, request, closingSessions)) {
            return;
        }
        if (target.equals(session.nickname)) {
            errorLocked(session, request.requestId(), "SELF_PRIVATE", closingSessions);
            return;
        }
        Session recipient = joined.get(target);
        if (recipient == null) {
            errorLocked(session, request.requestId(), "USER_OFFLINE", closingSessions);
            return;
        }
        long time = System.currentTimeMillis();
        String messageId = UUID.randomUUID().toString();
        enqueueLocked(recipient, new Frame(MessageType.CHAT_EVENT, 0, time, messageId,
                session.nickname, target, request.text(), List.of()), closingSessions);
        enqueueLocked(session, new Frame(MessageType.CHAT_EVENT, request.requestId(), time,
                messageId, session.nickname, target, request.text(), List.of()), closingSessions);
    }

    private boolean validTextLocked(Session session, Frame request, List<Session> closingSessions) {
        try {
            Validation.text(request.text());
            return true;
        } catch (IllegalArgumentException exception) {
            errorLocked(session, request.requestId(), "TEXT_INVALID", closingSessions);
            return false;
        }
    }

    private void errorLocked(Session session, long requestId, String code,
                             List<Session> closingSessions) {
        enqueueLocked(session, response(MessageType.ERROR, requestId, SYSTEM, "", code,
                List.of()), closingSessions);
    }

    private static Frame response(MessageType type, long requestId, String from,
                                  String to, String text, List<String> users) {
        return new Frame(type, requestId, System.currentTimeMillis(), "", from, to, text, users);
    }

    private boolean enqueueLocked(Session session, Frame frame, List<Session> closingSessions) {
        if (session.closed) {
            return false;
        }
        if (!session.outgoing.offer(frame)) {
            removeLocked(session, "发送队列已满", closingSessions);
            return false;
        }
        return true;
    }

    private void queuePresenceLocked(String notice) {
        pendingNotices.add(notice);
    }

    private void flushPresenceLocked(List<Session> closingSessions) {
        while (!pendingNotices.isEmpty()) {
            String notice = pendingNotices.remove();
            Frame list = response(MessageType.USER_LIST, 0, "", "", "", List.copyOf(joined.keySet()));
            Frame event = response(MessageType.NOTICE, 0, SYSTEM, PUBLIC, notice, List.of());
            for (Session recipient : List.copyOf(joined.values())) {
                enqueueLocked(recipient, list, closingSessions);
                enqueueLocked(recipient, event, closingSessions);
            }
        }
    }

    private void removeLocked(Session session, String reason, List<Session> closingSessions) {
        if (session.closed) {
            return;
        }
        session.closed = true;
        session.closeReason = reason;
        sessions.remove(session);
        session.outgoing.clear();
        closingSessions.add(session);
        if (session.nickname != null && joined.remove(session.nickname, session) && running) {
            queuePresenceLocked(session.nickname + " 已离开公共聊天室");
        }
    }

    private void disconnect(Session session, String reason) {
        List<Session> closingSessions = new ArrayList<>();
        synchronized (registryLock) {
            removeLocked(session, reason, closingSessions);
            flushPresenceLocked(closingSessions);
        }
        finishClosing(closingSessions);
    }

    private void finishClosing(List<Session> closingSessions) {
        // 关闭 Socket 可以唤醒阻塞读写，始终在全局临界区以外执行。
        for (Session session : closingSessions) {
            closeSocket(session.socket);
            session.reader.interrupt();
            session.writer.interrupt();
            log("连接关闭：" + (session.nickname == null ? "未加入" : session.nickname)
                    + "，原因：" + session.closeReason);
        }
    }

    private Thread worker(String name, Runnable task) {
        return new Thread(() -> {
            try {
                task.run();
            } finally {
                workers.remove(Thread.currentThread());
            }
        }, name);
    }

    private void startWorker(String name, Runnable task) {
        Thread thread = worker(name, task);
        workers.add(thread);
        thread.start();
    }

    @Override
    public void close() {
        synchronized (startLock) {
            if (!closing.compareAndSet(false, true)) {
                return;
            }
            running = false;
        }
        try {
            ServerSocket socket = listener;
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException exception) {
                    log("关闭监听连接失败：" + exception.getClass().getSimpleName());
                }
            }
            List<Session> closingSessions = new ArrayList<>();
            synchronized (registryLock) {
                for (Session session : List.copyOf(sessions)) {
                    removeLocked(session, "服务端关闭", closingSessions);
                }
            }
            finishClosing(closingSessions);
            Thread current = Thread.currentThread();
            List<Thread> stopping = List.copyOf(workers);
            for (Thread thread : stopping) {
                if (thread != current) {
                    thread.interrupt();
                }
            }
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CLOSE_WAIT_MILLIS);
            boolean interrupted = false;
            for (Thread thread : stopping) {
                while (thread != current && thread.isAlive() && System.nanoTime() < deadline) {
                    try {
                        long remaining = Math.max(1,
                                TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
                        thread.join(remaining);
                    } catch (InterruptedException exception) {
                        interrupted = true;
                    }
                }
            }
            if (interrupted) {
                current.interrupt();
            }
            List<String> remaining = stopping.stream().filter(thread -> thread != current)
                    .filter(Thread::isAlive).map(Thread::getName).toList();
            if (!remaining.isEmpty()) {
                throw new IllegalStateException("服务端线程未及时退出：" + remaining);
            }
        } finally {
            terminated.countDown();
        }
    }

    private static void closeSocket(Socket socket) {
        try {
            socket.close();
        } catch (IOException exception) {
            log("关闭客户端连接失败：" + exception.getClass().getSimpleName());
        }
    }

    private static void log(String message) {
        System.out.println("[服务端] " + message);
    }

    private final class Session {
        private final Socket socket;
        private final InputStream input;
        private final OutputStream output;
        private final ArrayBlockingQueue<Frame> outgoing =
                new ArrayBlockingQueue<>(options.queueCapacity());
        private final long acceptedAt = System.nanoTime();
        private final Thread reader;
        private final Thread writer;
        private volatile long lastFrameAt = acceptedAt;
        private volatile boolean closed;
        private String nickname;
        private String closeReason;

        private Session(Socket socket) throws IOException {
            this.socket = socket;
            input = socket.getInputStream();
            output = socket.getOutputStream();
            long id = sessionSequence.incrementAndGet();
            reader = worker("chat-server-read-" + id, this::readLoop);
            writer = worker("chat-server-write-" + id, this::writeLoop);
        }

        private void start() {
            workers.add(reader);
            workers.add(writer);
            writer.start();
            reader.start();
        }

        private void readLoop() {
            String reason = "客户端结束连接";
            try {
                while (!closed) {
                    Frame request = ProtocolCodec.read(input);
                    if (request == null) {
                        break;
                    }
                    handle(this, request);
                }
            } catch (IOException exception) {
                reason = "协议或连接读取失败：" + exception.getClass().getSimpleName();
            } finally {
                disconnect(this, reason);
            }
        }

        private void writeLoop() {
            String reason = "发送线程结束";
            try {
                while (!closed) {
                    Frame frame = outgoing.take();
                    ProtocolCodec.write(output, frame);
                    output.flush();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (IOException exception) {
                reason = "连接写入失败：" + exception.getClass().getSimpleName();
            } finally {
                disconnect(this, reason);
            }
        }
    }
}
