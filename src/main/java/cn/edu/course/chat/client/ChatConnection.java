package cn.edu.course.chat.client;

import cn.edu.course.chat.common.Frame;
import cn.edu.course.chat.common.MessageType;
import cn.edu.course.chat.common.ProtocolCodec;
import cn.edu.course.chat.common.Validation;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class ChatConnection implements AutoCloseable {
    public enum State { DISCONNECTED, CONNECTING, CONNECTED, CLOSING }

    public interface Listener {
        void onState(State state, String detail);
        void onFrame(Frame frame);
    }

    public record Options(int connectMillis, long handshakeMillis, long idleMillis,
                          long pingMillis, int queueCapacity, long checkMillis) {
        public Options {
            if (connectMillis < 1 || handshakeMillis < 1 || idleMillis < 1
                    || pingMillis < 1 || queueCapacity < 1 || queueCapacity > 128 || checkMillis < 1) {
                throw new IllegalArgumentException("连接容量与期限必须为正数");
            }
        }

        public static Options defaults() {
            return new Options(5000, 10000, 45000, 15000, 128, 1000);
        }
    }

    private final Object lifecycle = new Object();
    private final Listener listener;
    private final Options options;
    private final AtomicLong requestIds = new AtomicLong();
    private final Set<Thread> workers = ConcurrentHashMap.newKeySet();
    private final String threadPrefix = "chat-client-" + Integer.toHexString(System.identityHashCode(this)) + "-";
    private final ThreadPoolExecutor callbacks;
    private volatile State state = State.DISCONNECTED;
    private volatile String endpoint = "";
    private long generation;
    private boolean closed;
    private Session current;

    public ChatConnection(Listener listener) {
        this(listener, Options.defaults());
    }

    public ChatConnection(Listener listener, Options options) {
        this.listener = Objects.requireNonNull(listener, "listener");
        this.options = Objects.requireNonNull(options, "options");
        callbacks = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(128), task -> worker("callbacks", task));
    }

    public void connect(String host, int port, String nickname) {
        Validation.host(host);
        Validation.port(port);
        String acceptedNickname = Validation.nickname(nickname);
        synchronized (lifecycle) {
            if (closed) {
                throw new IllegalStateException("客户端连接组件已经关闭");
            }
            if (state != State.DISCONNECTED) {
                throw new IllegalStateException("当前连接尚未结束");
            }
            Session session = new Session(++generation, host, port, acceptedNickname);
            current = session;
            endpoint = "";
            state = State.CONNECTING;
            session.timer.scheduleWithFixedDelay(() -> check(session), options.checkMillis(),
                    options.checkMillis(), TimeUnit.MILLISECONDS);
            startWorker("reader-" + session.generation, () -> read(session));
            dispatchState(session, State.CONNECTING, "正在连接 " + host + ":" + port);
        }
    }

    public long send(String recipient, String text) {
        if (recipient == null) {
            throw new IllegalArgumentException("接收对象不能为空");
        }
        String target = "PUBLIC".equals(recipient) ? recipient : Validation.nickname(recipient);
        Validation.text(text);
        synchronized (lifecycle) {
            Session session = current;
            if (closed || state != State.CONNECTED || session == null || session.stopped.get()) {
                throw new IllegalStateException("当前未连接，无法发送消息");
            }
            if (target.equals(session.nickname)) {
                throw new IllegalArgumentException("不能向自己发送私聊消息");
            }
            long id = nextRequestId();
            MessageType type = "PUBLIC".equals(target) ? MessageType.PUBLIC_SEND : MessageType.PRIVATE_SEND;
            if (!session.outgoing.offer(Frame.request(type, id, "", target, text))) {
                throw new IllegalStateException("发送队列已满，请稍后重试");
            }
            return id;
        }
    }

    public void disconnect() {
        synchronized (lifecycle) {
            Session session = current;
            if (session == null || state == State.DISCONNECTED || state == State.CLOSING) {
                return;
            }
            boolean joined = state == State.CONNECTED;
            session.requestedStop = "已主动断开连接";
            state = State.CLOSING;
            dispatchState(session, State.CLOSING, "正在断开连接");
            try {
                if (joined) {
                    session.outgoing.clear();
                    session.outgoing.add(Frame.request(MessageType.LEAVE, nextRequestId(), "", "", ""));
                    session.timer.schedule(() -> finish(session, session.requestedStop),
                            options.connectMillis(), TimeUnit.MILLISECONDS);
                } else {
                    session.timer.execute(() -> finish(session, session.requestedStop));
                }
            } catch (RejectedExecutionException exception) {
                if (!session.stopped.get()) {
                    throw exception;
                }
            }
        }
    }

    public State state() {
        return state;
    }

    public String endpoint() {
        return endpoint;
    }

    @Override
    public void close() {
        Session session;
        synchronized (lifecycle) {
            if (closed) {
                return;
            }
            closed = true;
            session = current;
            if (session != null) {
                session.requestedStop = "客户端已经关闭";
            }
        }
        if (session != null) {
            finish(session, session.requestedStop);
        }
        callbacks.shutdown();
        try {
            for (Thread thread : Set.copyOf(workers)) {
                if (thread != Thread.currentThread()) {
                    thread.join(5000);
                    if (thread.isAlive()) {
                        throw new IllegalStateException("客户端后台线程未能结束：" + thread.getName());
                    }
                }
            }
            if (!Thread.currentThread().getName().equals(threadPrefix + "callbacks")
                    && !callbacks.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("客户端回调线程未能结束");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待客户端关闭时被中断", exception);
        }
    }

    private void read(Session session) {
        try {
            session.socket.connect(new InetSocketAddress(session.host, session.port), options.connectMillis());
            session.socket.setTcpNoDelay(true);
            synchronized (lifecycle) {
                if (!active(session)) {
                    return;
                }
                session.connectedAt = System.nanoTime();
                session.lastReceived = session.connectedAt;
                endpoint = session.socket.getLocalSocketAddress() + " → " + session.socket.getRemoteSocketAddress();
                session.outgoing.add(Frame.request(MessageType.JOIN, session.joinId, session.nickname, "", ""));
                startWorker("writer-" + session.generation, () -> write(session));
            }
            while (!session.stopped.get()) {
                Frame frame = ProtocolCodec.read(session.socket.getInputStream());
                if (frame == null) {
                    throw new EOFException("服务端已经关闭连接");
                }
                receive(session, frame);
            }
        } catch (IOException | IllegalArgumentException exception) {
            finish(session, "连接已断开：" + exception.getMessage());
        } finally {
            finish(session, "连接已结束");
        }
    }

    private void write(Session session) {
        try {
            while (!session.stopped.get()) {
                Frame frame = session.outgoing.take();
                ProtocolCodec.write(session.socket.getOutputStream(), frame);
                if (frame.type() == MessageType.LEAVE) {
                    finish(session, session.requestedStop);
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (IOException exception) {
            finish(session, "发送失败：" + exception.getMessage());
        } finally {
            finish(session, "发送线程已经结束");
        }
    }

    private void receive(Session session, Frame frame) throws IOException {
        boolean rejected = false;
        synchronized (lifecycle) {
            if (!active(session)) {
                return;
            }
            if (state == State.CONNECTING) {
                if (frame.type() == MessageType.ERROR && frame.requestId() == session.joinId) {
                    dispatchFrame(session, frame);
                    session.requestedStop = "加入被拒绝：" + frame.text();
                    rejected = true;
                } else if (frame.type() == MessageType.WELCOME && frame.requestId() == session.joinId
                        && frame.from().equals(session.nickname)) {
                    state = State.CONNECTED;
                    session.lastPing = System.nanoTime();
                    dispatchState(session, State.CONNECTED, "已连接 " + endpoint);
                    dispatchFrame(session, frame);
                } else {
                    throw new IOException("服务端握手消息无效");
                }
            } else if (state == State.CONNECTED) {
                switch (frame.type()) {
                    case USER_LIST, CHAT_EVENT, NOTICE, ERROR, PONG -> dispatchFrame(session, frame);
                    default -> throw new IOException("服务端消息方向或连接状态无效");
                }
            }
            session.lastReceived = System.nanoTime();
        }
        if (rejected) {
            finish(session, session.requestedStop);
        }
    }

    private void check(Session session) {
        String reason = null;
        long now = System.nanoTime();
        synchronized (lifecycle) {
            if (!active(session)) {
                return;
            }
            if (state == State.CONNECTING) {
                if (session.connectedAt == 0 && elapsed(now, session.startedAt) >= options.connectMillis()) {
                    reason = "建立连接超时";
                } else if (session.connectedAt != 0
                        && elapsed(now, session.connectedAt) >= options.handshakeMillis()) {
                    reason = "等待服务端 WELCOME 超时";
                }
            } else if (state == State.CONNECTED) {
                if (elapsed(now, session.lastReceived) >= options.idleMillis()) {
                    reason = "服务端心跳超时，连接已断开";
                } else if (elapsed(now, session.lastPing) >= options.pingMillis()) {
                    if (!session.outgoing.offer(Frame.request(MessageType.PING, nextRequestId(), "", "", ""))) {
                        reason = "发送队列已满，连接已断开";
                    }
                    session.lastPing = now;
                }
            }
        }
        if (reason != null) {
            finish(session, reason);
        }
    }

    private void finish(Session session, String detail) {
        if (!session.stopped.compareAndSet(false, true)) {
            return;
        }
        session.timer.shutdownNow();
        try {
            session.socket.close();
        } catch (IOException exception) {
            detail += "；关闭 Socket 失败：" + exception.getMessage();
        }
        session.outgoing.clear();
        Thread writer = session.writer;
        if (writer != null) {
            writer.interrupt();
        }
        synchronized (lifecycle) {
            if (current == session) {
                current = null;
                state = State.DISCONNECTED;
                endpoint = "";
                dispatchState(session, State.DISCONNECTED,
                        session.requestedStop == null ? detail : session.requestedStop);
            }
        }
    }

    private boolean active(Session session) {
        return !closed && current == session && !session.stopped.get();
    }

    private void dispatchState(Session session, State next, String detail) {
        dispatch(session, () -> listener.onState(next, detail), next == State.DISCONNECTED);
    }

    private void dispatchFrame(Session session, Frame frame) {
        dispatch(session, () -> listener.onFrame(frame), false);
    }

    private void dispatch(Session session, Runnable action, boolean terminal) {
        if (closed || (!terminal && session.callbackOverflow.get())) {
            return;
        }
        Runnable delivery = () -> {
            synchronized (lifecycle) {
                if (closed || generation != session.generation) {
                    return;
                }
            }
            action.run();
        };
        try {
            callbacks.execute(delivery);
        } catch (RejectedExecutionException exception) {
            if (closed || callbacks.isShutdown()) {
                return;
            }
            callbacks.getQueue().clear();
            if (terminal) {
                callbacks.execute(delivery);
            } else if (session.callbackOverflow.compareAndSet(false, true)) {
                startWorker("callback-overflow-" + session.generation, () -> {
                    session.requestedStop = "界面事件队列已满，连接已断开";
                    finish(session, session.requestedStop);
                });
            }
        }
    }

    private Thread worker(String label, Runnable action) {
        Thread thread = new Thread(() -> {
            try {
                action.run();
            } finally {
                workers.remove(Thread.currentThread());
            }
        }, threadPrefix + label);
        workers.add(thread);
        return thread;
    }

    private void startWorker(String label, Runnable action) {
        Thread thread = worker(label, action);
        if (label.startsWith("writer-") && current != null) {
            current.writer = thread;
        }
        thread.start();
    }

    private long nextRequestId() {
        return requestIds.updateAndGet(value -> value == Long.MAX_VALUE ? 1 : value + 1);
    }

    private static long elapsed(long now, long then) {
        return TimeUnit.NANOSECONDS.toMillis(now - then);
    }

    private final class Session {
        final long generation;
        final String host;
        final int port;
        final String nickname;
        final long joinId = nextRequestId();
        final Socket socket = new Socket();
        final ArrayBlockingQueue<Frame> outgoing = new ArrayBlockingQueue<>(options.queueCapacity());
        final AtomicBoolean stopped = new AtomicBoolean();
        final AtomicBoolean callbackOverflow = new AtomicBoolean();
        final long startedAt = System.nanoTime();
        final ScheduledExecutorService timer;
        volatile long connectedAt;
        volatile long lastReceived;
        volatile long lastPing;
        volatile Thread writer;
        volatile String requestedStop;

        Session(long generation, String host, int port, String nickname) {
            this.generation = generation;
            this.host = host;
            this.port = port;
            this.nickname = nickname;
            timer = Executors.newSingleThreadScheduledExecutor(task -> worker("timer-" + generation, task));
        }
    }
}
