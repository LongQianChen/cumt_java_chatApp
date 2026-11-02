package cn.edu.course.chat;

import cn.edu.course.chat.common.Frame;
import cn.edu.course.chat.common.MessageType;
import cn.edu.course.chat.common.ProtocolCodec;
import cn.edu.course.chat.common.ProtocolException;
import cn.edu.course.chat.common.Validation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class ProtocolTest {
    private ProtocolTest() {
    }

    public static void main(String[] args) throws Exception {
        run();
        new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true)
                .println("ProtocolTest: PASS A07 A08 A17 A18");
    }

    public static void run() throws Exception {
        unicodeRoundTrip();
        validationBoundaries();
        streamBoundaries();
        malformedFrames();
        immutableFrame();
    }

    private static void unicodeRoundTrip() throws Exception {
        Frame frame = new Frame(MessageType.CHAT_EVENT, 41, 1_700_000_000_000L,
                "550e8400-e29b-41d4-a716-446655440000", "甲", "PUBLIC",
                "中文 English 😀\n下一行\t<> & \\\"", List.of());
        check(frame.equals(decode(encode(frame))), "A07 Unicode 内容往返失败");
        Frame users = new Frame(MessageType.USER_LIST, 0, 0, "", "", "", "",
                List.of("甲", "Beta_2", "用户-3"));
        check(users.equals(decode(encode(users))), "在线列表往返失败");
        for (MessageType type : MessageType.values()) {
            check(MessageType.fromCode(type.code()) == type, "消息类型编码不一致");
        }
    }

    private static void validationBoundaries() throws Exception {
        check(Validation.nickname("  甲_9-  ").equals("甲_9-"), "昵称 strip 失败");
        check(Validation.nickname("甲".repeat(16)).length() == 16, "16 字符昵称被拒绝");
        String supplementaryLetter = new String(Character.toChars(0x10400));
        check(Validation.nickname(supplementaryLetter.repeat(16)).codePointCount(0, 32) == 16,
                "补充平面字母码点计数错误");
        for (String value : List.of("", " ", "甲".repeat(17), "a b", "PUBLIC", "SYSTEM", "😀")) {
            expect(IllegalArgumentException.class, () -> Validation.nickname(value));
        }
        expect(IllegalArgumentException.class, () -> Validation.nickname(null));
        Validation.text("😀".repeat(2_000));
        Validation.text("a\n\t甲");
        expect(IllegalArgumentException.class, () -> Validation.text("😀".repeat(2_001)));
        for (String value : List.of("", " \t\r\n", "\u00A0", "\uD800")) {
            expect(IllegalArgumentException.class, () -> Validation.text(value));
        }
        expect(IllegalArgumentException.class, () -> Validation.text(null));
        Frame largestText = Frame.request(MessageType.PUBLIC_SEND, 1, "", "PUBLIC", "😀".repeat(2_000));
        check(largestText.equals(decode(encode(largestText))), "8000 字节正文往返失败");
        expect(ProtocolException.class, () -> encode(Frame.request(MessageType.PUBLIC_SEND, 1,
                "", "PUBLIC", "a".repeat(8_001))));
        expect(ProtocolException.class, () -> encode(Frame.request(MessageType.ERROR, 1,
                "", "", "a".repeat(257))));
        expect(ProtocolException.class, () -> encode(Frame.request(MessageType.JOIN, 1,
                "甲".repeat(22), "", "")));
        expect(ProtocolException.class, () -> encode(Frame.request(MessageType.PUBLIC_SEND, 1,
                "", "PUBLIC", "\uD800")));
        check(Validation.profile("demo-a_1").equals("demo-a_1"), "profile 校验失败");
        Validation.profile("a".repeat(32));
        for (String value : List.of("", "a".repeat(33), "../demo", "甲", "a b")) {
            expect(IllegalArgumentException.class, () -> Validation.profile(value));
        }
        Validation.host("127.0.0.1");
        Validation.host("172.16.20.254");
        Validation.port(1);
        Validation.port(65_535);
        for (String value : List.of("0.0.0.0", "localhost", "127.1", "256.0.0.1", "1.2.3.4.5", " 127.0.0.1")) {
            expect(IllegalArgumentException.class, () -> Validation.host(value));
        }
        expect(IllegalArgumentException.class, () -> Validation.port(0));
        expect(IllegalArgumentException.class, () -> Validation.port(65_536));
    }

    private static void streamBoundaries() throws Exception {
        Frame first = Frame.request(MessageType.PUBLIC_SEND, 1, "", "PUBLIC", "分片😀\n消息");
        Frame second = Frame.request(MessageType.PING, 2, "", "", "");
        byte[] firstBytes = encode(first);
        InputStream fragmented = new FilterInputStream(new ByteArrayInputStream(firstBytes)) {
            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                return super.read(bytes, offset, Math.min(length, 1));
            }
        };
        check(first.equals(ProtocolCodec.read(fragmented)), "A17 分片帧解析失败");
        check(ProtocolCodec.read(fragmented) == null, "帧结束 EOF 未返回 null");
        ByteArrayOutputStream combined = new ByteArrayOutputStream();
        ProtocolCodec.write(combined, first);
        ProtocolCodec.write(combined, second);
        ByteArrayInputStream source = new ByteArrayInputStream(combined.toByteArray());
        check(first.equals(ProtocolCodec.read(source)), "A17 第一帧解析失败");
        check(second.equals(ProtocolCodec.read(source)), "A17 第二帧解析失败");
        check(ProtocolCodec.read(source) == null, "多帧后 EOF 未返回 null");
        for (int length = 1; length < firstBytes.length; length++) {
            byte[] truncated = Arrays.copyOf(firstBytes, length);
            expect(EOFException.class, () -> decode(truncated));
        }
    }

    private static void malformedFrames() throws Exception {
        for (int length : new int[] {Integer.MIN_VALUE, -1, 0, ProtocolCodec.MAX_FRAME_BYTES + 1, Integer.MAX_VALUE}) {
            expect(ProtocolException.class, () -> decode(ByteBuffer.allocate(4).putInt(length).array()));
        }
        byte[] valid = encode(Frame.request(MessageType.PING, 1, "", "", ""));
        byte[] badVersion = valid.clone();
        badVersion[4] = 2;
        expect(ProtocolException.class, () -> decode(badVersion));
        byte[] badType = valid.clone();
        badType[5] = 99;
        expect(ProtocolException.class, () -> decode(badType));
        byte[] extra = Arrays.copyOf(valid, valid.length + 1);
        ByteBuffer.wrap(extra).putInt(valid.length - 3);
        expect(ProtocolException.class, () -> decode(extra));
        for (int length : new int[] {-1, 65, 10_000}) {
            byte[] invalidString = valid.clone();
            ByteBuffer.wrap(invalidString).putInt(22, length);
            expect(ProtocolException.class, () -> decode(invalidString));
        }
        for (int userCount : new int[] {-1, 1, 11, Integer.MAX_VALUE}) {
            byte[] invalidUsers = valid.clone();
            ByteBuffer.wrap(invalidUsers).putInt(invalidUsers.length - 4, userCount);
            expect(ProtocolException.class, () -> decode(invalidUsers));
        }
        expect(ProtocolException.class, () -> decode(rawText(new byte[] {(byte) 0xC3, 0x28})));
        expect(ProtocolException.class, () -> decode(rawText(new byte[] {(byte) 0xED, (byte) 0xA0, (byte) 0x80})));
        expect(ProtocolException.class, () -> decode(rawText(new byte[] {(byte) 0xC0, (byte) 0xAF})));
        expect(ProtocolException.class, () -> encode(new Frame(MessageType.PING, 0, 0,
                "", "", "", "", List.of("甲"))));
        expect(ProtocolException.class, () -> encode(new Frame(MessageType.USER_LIST, 0, 0,
                "", "", "", "", java.util.Collections.nCopies(11, "甲"))));
    }

    private static void immutableFrame() throws Exception {
        List<String> users = new ArrayList<>(List.of("甲"));
        Frame frame = new Frame(MessageType.USER_LIST, 0, 0, "", "", "", "", users);
        users.add("乙");
        check(frame.users().equals(List.of("甲")), "Frame 未复制 users");
        expect(UnsupportedOperationException.class, () -> frame.users().add("乙"));
        expect(NullPointerException.class, () -> Frame.request(MessageType.JOIN, 1, null, "", ""));
    }

    private static byte[] rawText(byte[] text) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(body);
        data.writeByte(1);
        data.writeByte(MessageType.PUBLIC_SEND.code());
        data.writeLong(1);
        data.writeLong(0);
        data.writeInt(0);
        data.writeInt(0);
        data.writeInt(0);
        data.writeInt(text.length);
        data.write(text);
        data.writeInt(0);
        ByteArrayOutputStream framed = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(framed);
        output.writeInt(body.size());
        body.writeTo(output);
        return framed.toByteArray();
    }

    private static byte[] encode(Frame frame) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ProtocolCodec.write(output, frame);
        return output.toByteArray();
    }

    private static Frame decode(byte[] bytes) throws IOException {
        return ProtocolCodec.read(new ByteArrayInputStream(bytes));
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void expect(Class<? extends Throwable> expected, Action action) throws Exception {
        try {
            action.run();
        } catch (Throwable failure) {
            if (expected.isInstance(failure)) {
                return;
            }
            throw new AssertionError("预期异常 " + expected.getName() + "，实际为 " + failure, failure);
        }
        throw new AssertionError("未出现预期异常：" + expected.getName());
    }

    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }
}
