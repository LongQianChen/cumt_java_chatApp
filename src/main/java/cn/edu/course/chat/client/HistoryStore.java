package cn.edu.course.chat.client;

import cn.edu.course.chat.common.Frame;
import cn.edu.course.chat.common.MessageType;
import cn.edu.course.chat.common.ProtocolCodec;
import cn.edu.course.chat.common.Validation;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class HistoryStore implements AutoCloseable {
    public static final long DEFAULT_MAX_BYTES = 10L * 1024 * 1024;
    public static final int DEFAULT_QUEUE_CAPACITY = 256;

    private final Path directory;
    private final Path historyFile;
    private final Path identityFile;
    private final long maxBytes;
    private final Consumer<String> status;
    private final FileChannel lockChannel;
    private final FileLock fileLock;
    private final ThreadPoolExecutor executor;
    private final Map<String, ArrayDeque<Frame>> retained = new LinkedHashMap<>();
    private final Set<String> older = new HashSet<>();
    private final Set<String> loadedIds = new HashSet<>();
    private volatile Set<String> olderSnapshot = Set.of();
    private volatile Identity identity;
    private volatile boolean acceptingRecords = true;
    private volatile boolean closed;
    private boolean loaded;
    private boolean diskWritable = true;
    private long historyBytes;
    private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);

    public HistoryStore(Path dataRoot, String profile, Consumer<String> status) throws IOException {
        this(dataRoot, profile, status, DEFAULT_MAX_BYTES, DEFAULT_QUEUE_CAPACITY);
    }

    public HistoryStore(Path dataRoot, String profile, Consumer<String> status,
                        long maxBytes, int queueCapacity) throws IOException {
        Objects.requireNonNull(dataRoot, "资料根目录不能为空");
        this.status = Objects.requireNonNull(status, "保存状态回调不能为空");
        if (maxBytes < 1 || queueCapacity < 1) {
            throw new IllegalArgumentException("历史容量和队列容量必须为正数");
        }
        this.maxBytes = maxBytes;
        directory = dataRoot.toAbsolutePath().normalize().resolve(Validation.profile(profile));
        Files.createDirectories(directory);
        historyFile = directory.resolve("history.bin");
        identityFile = directory.resolve("profile.properties");
        lockChannel = FileChannel.open(directory.resolve("profile.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired;
        try {
            acquired = lockChannel.tryLock();
            if (acquired == null) {
                throw new IOException("该 profile 已由其他客户端使用：" + profile);
            }
        } catch (IOException | OverlappingFileLockException failure) {
            lockChannel.close();
            throw new IOException("无法独占该 profile：" + profile, failure);
        }
        fileLock = acquired;
        executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), task -> {
                    Thread thread = new Thread(task, "chat-history-" + profile);
                    thread.setDaemon(false);
                    return thread;
                });
    }

    public Path directory() {
        return directory;
    }

    public String nickname() {
        Identity current = identity;
        return current == null ? "" : current.nickname();
    }

    public String host() {
        Identity current = identity;
        return current == null ? "" : current.host();
    }

    public int port() {
        Identity current = identity;
        return current == null ? 0 : current.port();
    }

    public Set<String> olderConversations() {
        return olderSnapshot;
    }

    public CompletableFuture<List<Frame>> load() {
        return submit(() -> {
            ensureLoaded();
            List<Frame> frames = new ArrayList<>();
            retained.values().forEach(frames::addAll);
            return List.copyOf(frames);
        });
    }

    public CompletableFuture<Void> checkIdentity(String host, int port, String nickname) {
        return submit(() -> {
            ensureLoaded();
            verifyIdentity(new Identity(host, port, nickname));
            return null;
        });
    }

    public CompletableFuture<Void> bind(String host, int port, String nickname) {
        return submit(() -> {
            ensureLoaded();
            Identity requested = new Identity(host, port, nickname);
            verifyIdentity(requested);
            if (identity == null) {
                Properties properties = new Properties();
                properties.setProperty("host", requested.host());
                properties.setProperty("port", Integer.toString(requested.port()));
                properties.setProperty("nickname", requested.nickname());
                try (Writer writer = Files.newBufferedWriter(identityFile, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    properties.store(writer, "客户端资料");
                }
                identity = requested;
            }
            return null;
        });
    }

    public synchronized boolean append(Frame frame) {
        Objects.requireNonNull(frame, "消息不能为空");
        if (frame.type() != MessageType.CHAT_EVENT || closed || !acceptingRecords) {
            return false;
        }
        try {
            CompletableFuture<Void> completed = new CompletableFuture<>();
            executor.execute(() -> {
                try {
                    write(frame);
                } finally {
                    completed.complete(null);
                }
            });
            tail = completed;
            return true;
        } catch (RejectedExecutionException failure) {
            pause("记录保存已暂停：保存队列已满；聊天可以继续，请使用其他 profile 继续保存", false);
            return false;
        }
    }

    public synchronized CompletableFuture<Void> flush() {
        return tail.thenApply(ignored -> null);
    }

    @Override
    public void close() throws IOException {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            executor.shutdown();
        }
        boolean interrupted = false;
        while (!executor.isTerminated()) {
            try {
                executor.awaitTermination(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException failure) {
                interrupted = true;
            }
        }
        try {
            fileLock.release();
        } finally {
            try {
                lockChannel.close();
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void ensureLoaded() throws IOException {
        if (loaded) {
            return;
        }
        readIdentity();
        try (InputStream input = Files.newInputStream(historyFile)) {
            while (true) {
                Frame frame = ProtocolCodec.read(input);
                if (frame == null) {
                    break;
                }
                if (frame.type() != MessageType.CHAT_EVENT || frame.messageId().isEmpty()
                        || frame.from().isEmpty() || frame.to().isEmpty() || frame.serverTime() <= 0) {
                    throw new IOException("历史文件包含无效聊天记录");
                }
                retain(frame);
            }
        } catch (NoSuchFileException missing) {
            // 首次启动没有历史文件。
        } catch (IOException failure) {
            pause("记录保存已暂停：历史文件截断、损坏或无法读取；已保留此前可读记录："
                    + failure.getMessage(), true);
        }
        try {
            historyBytes = Files.size(historyFile);
        } catch (NoSuchFileException missing) {
            historyBytes = 0;
        } catch (IOException failure) {
            pause("记录保存已暂停：无法读取历史文件大小：" + failure.getMessage(), true);
        }
        if (historyBytes >= maxBytes) {
            pause("记录保存已暂停：历史文件已达到容量上限；聊天可以继续，请使用其他 profile", true);
        }
        loaded = true;
    }

    private void readIdentity() throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(identityFile, StandardCharsets.UTF_8)) {
            properties.load(reader);
            identity = new Identity(properties.getProperty("host"),
                    Integer.parseInt(properties.getProperty("port")), properties.getProperty("nickname"));
        } catch (NoSuchFileException missing) {
            identity = null;
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw new IOException("profile.properties 内容无效，请使用其他 profile", failure);
        }
    }

    private void verifyIdentity(Identity requested) throws IOException {
        if (identity != null && !identity.equals(requested)) {
            throw new IOException("该 profile 已绑定服务端 " + identity.host() + ":" + identity.port()
                    + " 和昵称 " + identity.nickname() + "，请使用其他 profile");
        }
    }

    private void write(Frame frame) {
        try {
            ensureLoaded();
            if (!diskWritable) {
                return;
            }
            if (identity == null) {
                throw new IOException("profile 尚未绑定成功加入后的服务端和昵称");
            }
            if (loadedIds.contains(frame.messageId())) {
                return;
            }
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            ProtocolCodec.write(encoded, frame);
            byte[] bytes = encoded.toByteArray();
            if (historyBytes > maxBytes - bytes.length) {
                pause("记录保存已暂停：历史文件容量不足；聊天可以继续，请使用其他 profile", true);
                return;
            }
            try (OutputStream output = Files.newOutputStream(historyFile,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                output.write(bytes);
            }
            historyBytes += bytes.length;
            retain(frame);
            if (acceptingRecords) {
                status.accept("记录已保存：" + historyBytes + " 字节");
            }
        } catch (IOException failure) {
            pause("记录保存已暂停：" + failure.getMessage() + "；聊天可以继续", true);
        }
    }

    private void retain(Frame frame) {
        if (!loadedIds.add(frame.messageId())) {
            return;
        }
        String key = ConversationModel.conversationKey(frame, nickname());
        ArrayDeque<Frame> frames = retained.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        frames.addLast(frame);
        if (frames.size() > ConversationModel.MAX_MESSAGES) {
            Frame removed = frames.removeFirst();
            loadedIds.remove(removed.messageId());
            older.add(key);
            olderSnapshot = Set.copyOf(older);
        }
    }

    private void pause(String message, boolean diskProblem) {
        acceptingRecords = false;
        if (diskProblem) {
            diskWritable = false;
        }
        status.accept(message);
    }

    private synchronized <T> CompletableFuture<T> submit(HistoryTask<T> task) {
        CompletableFuture<T> result = new CompletableFuture<>();
        if (closed) {
            result.completeExceptionally(new IOException("历史资料已关闭"));
            return result;
        }
        try {
            executor.execute(() -> {
                try {
                    result.complete(task.run());
                } catch (Exception failure) {
                    result.completeExceptionally(failure);
                }
            });
            tail = result.handle((value, failure) -> null);
        } catch (RejectedExecutionException failure) {
            result.completeExceptionally(new IOException("历史任务队列已满，请稍后重试", failure));
        }
        return result;
    }

    @FunctionalInterface
    private interface HistoryTask<T> {
        T run() throws Exception;
    }

    private record Identity(String host, int port, String nickname) {
        private Identity {
            host = Objects.requireNonNull(host, "服务端地址不能为空").strip();
            Validation.host(host);
            Validation.port(port);
            nickname = Validation.nickname(nickname);
        }
    }
}
