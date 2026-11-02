package cn.edu.course.chat;

import cn.edu.course.chat.client.ConversationModel;
import cn.edu.course.chat.client.HistoryStore;
import cn.edu.course.chat.common.Frame;
import cn.edu.course.chat.common.MessageType;
import cn.edu.course.chat.common.ProtocolCodec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public final class HistoryTest {
    private HistoryTest() {
    }

    public static void main(String[] arguments) throws Exception {
        System.setOut(new java.io.PrintStream(System.out, true, StandardCharsets.UTF_8));
        run();
        System.out.println("HistoryTest 通过：资料隔离、历史保存、损坏处理、资源限制与会话未读");
    }

    public static void run() throws Exception {
        Path temporaryRoot = Path.of("tmp").toAbsolutePath().normalize();
        Files.createDirectories(temporaryRoot);
        Path root = Files.createTempDirectory(temporaryRoot, "history-test-");
        try {
            persistenceAndIdentity(root);
            damagedHistory(root, false);
            damagedHistory(root, true);
            quota(root);
            queueCapacity(root);
            closeDrainsAndUnlocks(root);
            unwritableHistory(root);
            retainedRange(root);
            conversations();
        } finally {
            if (!root.getParent().equals(temporaryRoot)
                    || !root.getFileName().toString().startsWith("history-test-")) {
                throw new IOException("测试清理目录超出范围");
            }
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    private static void persistenceAndIdentity(Path root) throws Exception {
        List<String> status = new CopyOnWriteArrayList<>();
        Frame publicMessage = event("甲", "PUBLIC", "中文😀\n第二行\t<>&");
        Frame privateOutgoing = event("甲", "乙", "私聊发送");
        Frame privateIncoming = event("乙", "甲", "私聊回复");
        Path identityPath;
        try (HistoryStore store = new HistoryStore(root, "persistence", status::add)) {
            check(await(store.load()).isEmpty(), "首次启动须没有历史");
            check(store.nickname().isEmpty() && store.host().isEmpty() && store.port() == 0,
                    "未绑定资料不得提供身份");
            expectIo(() -> new HistoryStore(root, "persistence", status::add), "重复 profile 须拒绝");
            try (HistoryStore separate = new HistoryStore(root, "separate", status::add)) {
                check(await(separate.load()).isEmpty(), "独立 profile 须隔离");
            }
            await(store.checkIdentity("127.0.0.1", 5000, "甲"));
            await(store.bind("127.0.0.1", 5000, "甲"));
            identityPath = store.directory().resolve("profile.properties");
            byte[] identity = Files.readAllBytes(identityPath);
            await(store.bind("127.0.0.1", 5000, "甲"));
            expectFutureIo(store.bind("127.0.0.1", 5000, "乙"), "换昵称须拒绝");
            expectFutureIo(store.checkIdentity("127.0.0.2", 5000, "甲"), "换地址须拒绝");
            expectFutureIo(store.checkIdentity("127.0.0.1", 5001, "甲"), "换端口须拒绝");
            check(Arrays.equals(identity, Files.readAllBytes(identityPath)), "身份不匹配不得覆盖资料");
            check(store.append(publicMessage), "公共消息须进入保存队列");
            check(store.append(privateOutgoing), "发送私聊须进入保存队列");
            check(store.append(privateIncoming), "接收私聊须进入保存队列");
            check(store.append(privateIncoming), "重复记录可进入队列并由 messageId 去重");
            check(!store.append(Frame.request(MessageType.PING, 1, "", "", "")),
                    "心跳不得保存");
            await(store.flush());
            check(status.stream().anyMatch(value -> value.contains("已保存")), "写入后须报告已保存");
        }
        check(Files.readString(identityPath, StandardCharsets.UTF_8).contains("甲"),
                "身份资料须使用 UTF-8");
        try (HistoryStore restarted = new HistoryStore(root, "persistence", status::add)) {
            List<Frame> restored = await(restarted.load());
            check(restored.equals(List.of(publicMessage, privateOutgoing, privateIncoming)),
                    "重启须恢复全部公共和私聊正文且没有重复");
            check(restarted.nickname().equals("甲") && restarted.host().equals("127.0.0.1")
                    && restarted.port() == 5000, "重启须提供已绑定资料");
            ConversationModel model = new ConversationModel();
            restored.forEach(frame -> model.receive(frame, restarted.nickname(), true));
            check(model.messages("PUBLIC").equals(List.of(publicMessage)), "公共历史须正确分组");
            check(model.messages("乙").equals(List.of(privateOutgoing, privateIncoming)),
                    "双向私聊历史须归入同一会话");
            check(model.unread("乙") == 0 && model.isHistorical(privateIncoming.messageId()),
                    "历史须标记并保持未读为零");
        }
    }

    private static void damagedHistory(Path root, boolean malformed) throws Exception {
        String profile = malformed ? "malformed" : "truncated";
        Frame good = event("甲", "PUBLIC", "可读记录");
        List<String> statuses = new CopyOnWriteArrayList<>();
        Path history;
        try (HistoryStore store = new HistoryStore(root, profile, statuses::add)) {
            await(store.bind("127.0.0.1", 5000, "甲"));
            check(store.append(good), "有效记录须入队");
            await(store.flush());
            history = store.directory().resolve("history.bin");
        }
        byte[] broken = malformed ? new byte[] {-1, -1, -1, -1} : new byte[] {0, 0};
        Files.write(history, broken, StandardOpenOption.APPEND);
        byte[] before = Files.readAllBytes(history);
        try (HistoryStore store = new HistoryStore(root, profile, statuses::add)) {
            check(await(store.load()).equals(List.of(good)), "损坏前的记录须保留");
            check(!store.append(event("甲", "PUBLIC", "不得覆盖")), "损坏后须停止追加");
            await(store.flush());
        }
        check(Arrays.equals(before, Files.readAllBytes(history)), "损坏文件须保持原样");
        check(statuses.stream().anyMatch(value -> value.contains("损坏") && value.contains("暂停")),
                "损坏须产生明确提示");
    }

    private static void quota(Path root) throws Exception {
        Frame good = event("甲", "PUBLIC", "容量边界");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ProtocolCodec.write(bytes, good);
        List<String> statuses = new CopyOnWriteArrayList<>();
        try (HistoryStore store = new HistoryStore(root, "quota", statuses::add, bytes.size(), 4)) {
            await(store.bind("127.0.0.1", 5000, "甲"));
            check(store.append(good), "恰好可容纳的消息须入队");
            await(store.flush());
            check(Files.size(store.directory().resolve("history.bin")) == bytes.size(),
                    "消息写入不得超过配额");
            check(store.append(event("甲", "PUBLIC", "超过容量")), "容量判断在后台执行");
            await(store.flush());
            check(!store.append(event("甲", "PUBLIC", "停止保存")), "容量不足后须暂停新记录");
            check(Files.size(store.directory().resolve("history.bin")) == bytes.size(),
                    "配额不足不得改写原有记录");
        }
        check(statuses.stream().anyMatch(value -> value.contains("容量") && value.contains("暂停")),
                "配额不足须明确提示");
    }

    private static void queueCapacity(Path root) throws Exception {
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<String> statuses = new CopyOnWriteArrayList<>();
        HistoryStore store = new HistoryStore(root, "queue", value -> {
            statuses.add(value);
            if (value.contains("已保存")) {
                writing.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("历史测试没有释放实际写入线程");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
            }
        }, 10_000, 1);
        try {
            await(store.bind("127.0.0.1", 5000, "甲"));
            check(store.append(event("甲", "PUBLIC", "第一条")), "第一条须入队");
            check(writing.await(5, TimeUnit.SECONDS), "第一条须完成真实文件写入");
            check(store.append(event("甲", "PUBLIC", "第二条")), "空余队列须接受一条");
            check(!store.append(event("甲", "PUBLIC", "超过队列")), "满队列须立即拒绝");
            CompletableFuture<Void> flushed = store.flush();
            check(!flushed.isDone(), "flush 须等待已经接受的保存任务");
            release.countDown();
            await(flushed);
            check(await(store.load()).size() == 2, "已接受的队列记录须写入完成");
            check(statuses.stream().anyMatch(value -> value.contains("队列已满")),
                    "队列满须明确提示");
        } finally {
            release.countDown();
            store.close();
        }
    }

    private static void unwritableHistory(Path root) throws Exception {
        List<String> statuses = new CopyOnWriteArrayList<>();
        try (HistoryStore store = new HistoryStore(root, "unwritable", statuses::add)) {
            await(store.bind("127.0.0.1", 5000, "甲"));
            check(store.append(event("甲", "PUBLIC", "原有记录")), "原有消息须写入");
            await(store.flush());
            Path history = store.directory().resolve("history.bin");
            long before = Files.size(history);
            DosFileAttributeView attributes = Files.getFileAttributeView(history, DosFileAttributeView.class);
            check(attributes != null, "Windows 验证需要 DOS 文件属性支持");
            attributes.setReadOnly(true);
            try {
                check(store.append(event("甲", "PUBLIC", "不可写")), "不可写状态由后台发现");
                await(store.flush());
                check(Files.size(history) == before, "不可写文件不得增加内容");
                check(!store.append(event("甲", "PUBLIC", "已暂停")), "写入失败后须暂停");
                check(statuses.stream().anyMatch(value -> value.contains("暂停")),
                        "真实不可写文件须产生保存提示");
            } finally {
                attributes.setReadOnly(false);
            }
        }
    }

    private static void closeDrainsAndUnlocks(Path root) throws Exception {
        HistoryStore store = new HistoryStore(root, "close", ignored -> { });
        try {
            await(store.bind("127.0.0.1", 5000, "甲"));
            for (int i = 0; i < 20; i++) {
                check(store.append(event("甲", "PUBLIC", "关闭等待" + i)), "关闭前须接受保存任务");
            }
        } finally {
            store.close();
        }
        check(!store.append(event("甲", "PUBLIC", "关闭后")), "关闭后不得接受任务");
        try (HistoryStore reopened = new HistoryStore(root, "close", ignored -> { })) {
            check(await(reopened.load()).size() == 20, "关闭须等待全部已接受记录并释放文件锁");
        }
    }

    private static void retainedRange(Path root) throws Exception {
        Path history;
        List<Frame> frames = new ArrayList<>();
        try (HistoryStore store = new HistoryStore(root, "range", ignored -> { })) {
            await(store.bind("127.0.0.1", 5000, "甲"));
            history = store.directory().resolve("history.bin");
        }
        try (var output = Files.newOutputStream(history)) {
            for (int i = 0; i < 503; i++) {
                Frame frame = event("甲", "PUBLIC", "历史范围" + i);
                frames.add(frame);
                ProtocolCodec.write(output, frame);
            }
            ProtocolCodec.write(output, event("乙", "甲", "另一会话"));
        }
        try (HistoryStore store = new HistoryStore(root, "range", ignored -> { })) {
            List<Frame> restored = await(store.load());
            check(restored.size() == 501, "每会话须分别保留最多 500 条");
            check(restored.subList(0, 500).equals(frames.subList(3, 503)), "须保留最新记录");
            check(store.olderConversations().equals(java.util.Set.of("PUBLIC")),
                    "须提供已截取的会话提示");
        }
    }

    private static void conversations() {
        ConversationModel model = new ConversationModel();
        check(model.active().equals("PUBLIC"), "初始会话须为公共聊天室");
        Frame incoming = event("乙", "甲", "私聊来信");
        model.receive(incoming, "甲", false);
        model.receive(incoming, "甲", false);
        check(model.unread("乙") == 1 && model.messages("乙").size() == 1,
                "重复事件不得增加正文和未读");
        model.receive(event("乙", "甲", "第二条来信"), "甲", false);
        check(model.unread("乙") == 2, "非当前会话来信须累计未读");
        model.receive(event("甲", "乙", "自己发送"), "甲", false);
        check(model.unread("乙") == 2, "自己发送不得增加未读");
        model.select("乙");
        check(model.unread("乙") == 0, "切换会话须清零未读");
        model.receive(event("乙", "甲", "当前会话来信"), "甲", false);
        check(model.unread("乙") == 0, "当前会话来信不得增加未读");
        model.select("PUBLIC");
        Frame historical = event("丙", "甲", "历史私聊");
        model.receive(historical, "甲", true);
        check(model.unread("丙") == 0 && model.isHistorical(historical.messageId()),
                "历史消息须标记并保持未读为零");
        for (int i = 0; i < 501; i++) {
            model.receive(event("甲", "PUBLIC", "公共范围" + i), "甲", false);
        }
        check(model.messages("PUBLIC").size() == 500 && model.hasOlder("PUBLIC"),
                "会话内存须有界并提示显示范围");
        model.markOlder("乙");
        check(model.hasOlder("乙"), "历史扫描截取提示须可传入模型");
    }

    private static Frame event(String from, String to, String text) {
        return new Frame(MessageType.CHAT_EVENT, 0, System.currentTimeMillis(),
                UUID.randomUUID().toString(), from, to, text, List.of());
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(5, TimeUnit.SECONDS);
    }

    private static void expectFutureIo(CompletableFuture<?> future, String message) throws Exception {
        try {
            await(future);
        } catch (ExecutionException failure) {
            check(failure.getCause() instanceof IOException, message);
            return;
        }
        throw new AssertionError(message);
    }

    private static void expectIo(IoAction action, String message) throws Exception {
        try {
            action.run();
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws Exception;
    }
}
