package cn.edu.course.chat.common;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ProtocolCodec {
    public static final int MAX_FRAME_BYTES = 16_384;
    private static final int VERSION = 1;
    private static final int MAX_NAME_BYTES = 64;
    private static final int MAX_TEXT_BYTES = 8_000;
    private static final int MAX_DESCRIPTION_BYTES = 256;
    private static final int MAX_USERS = 10;

    private ProtocolCodec() {
    }

    public static void write(OutputStream output, Frame frame) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(frame, "frame");
        validateUsers(frame.type(), frame.users().size());
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream payload = new DataOutputStream(buffer);
        payload.writeByte(VERSION);
        payload.writeByte(frame.type().code());
        payload.writeLong(frame.requestId());
        payload.writeLong(frame.serverTime());
        writeString(payload, frame.messageId(), MAX_NAME_BYTES, "messageId");
        writeString(payload, frame.from(), MAX_NAME_BYTES, "from");
        writeString(payload, frame.to(), MAX_NAME_BYTES, "to");
        writeString(payload, frame.text(), textLimit(frame.type()), "text");
        payload.writeInt(frame.users().size());
        for (String user : frame.users()) {
            writeString(payload, user, MAX_NAME_BYTES, "users");
        }
        payload.flush();
        if (buffer.size() <= 0 || buffer.size() > MAX_FRAME_BYTES) {
            throw new ProtocolException("帧长度超出范围：" + buffer.size());
        }
        DataOutputStream target = new DataOutputStream(output);
        target.writeInt(buffer.size());
        buffer.writeTo(target);
    }

    public static Frame read(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        int first = input.read();
        if (first == -1) {
            return null;
        }
        byte[] header = new byte[Integer.BYTES];
        header[0] = (byte) first;
        DataInputStream source = new DataInputStream(input);
        source.readFully(header, 1, Integer.BYTES - 1);
        int length = ByteBuffer.wrap(header).getInt();
        if (length <= 0 || length > MAX_FRAME_BYTES) {
            throw new ProtocolException("帧长度超出范围：" + length);
        }
        byte[] bytes = new byte[length];
        source.readFully(bytes);
        DataInputStream payload = new DataInputStream(new ByteArrayInputStream(bytes));
        int version = payload.readUnsignedByte();
        if (version != VERSION) {
            throw new ProtocolException("未知协议版本：" + version);
        }
        MessageType type = MessageType.fromCode(payload.readUnsignedByte());
        long requestId = payload.readLong();
        long serverTime = payload.readLong();
        String messageId = readString(payload, MAX_NAME_BYTES, "messageId");
        String from = readString(payload, MAX_NAME_BYTES, "from");
        String to = readString(payload, MAX_NAME_BYTES, "to");
        String text = readString(payload, textLimit(type), "text");
        int userCount = payload.readInt();
        validateUsers(type, userCount);
        List<String> users = new ArrayList<>(userCount);
        for (int i = 0; i < userCount; i++) {
            users.add(readString(payload, MAX_NAME_BYTES, "users"));
        }
        if (payload.available() != 0) {
            throw new ProtocolException("帧包含多余字节");
        }
        return new Frame(type, requestId, serverTime, messageId, from, to, text, users);
    }

    private static void validateUsers(MessageType type, int userCount) throws ProtocolException {
        if (userCount < 0 || userCount > MAX_USERS) {
            throw new ProtocolException("在线昵称数量超出范围：" + userCount);
        }
        if (type != MessageType.USER_LIST && userCount != 0) {
            throw new ProtocolException("该消息类型不能携带在线昵称列表");
        }
    }

    private static int textLimit(MessageType type) {
        return switch (type) {
            case PUBLIC_SEND, PRIVATE_SEND, CHAT_EVENT -> MAX_TEXT_BYTES;
            default -> MAX_DESCRIPTION_BYTES;
        };
    }

    private static void writeString(DataOutputStream output, String text, int maxBytes,
                                    String field) throws IOException {
        if (text.length() > maxBytes) {
            throw new ProtocolException(field + " 字段过长");
        }
        ByteBuffer bytes;
        try {
            bytes = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(text));
        } catch (CharacterCodingException exception) {
            throw new ProtocolException(field + " 字段包含无效 Unicode", exception);
        }
        if (bytes.remaining() > maxBytes) {
            throw new ProtocolException(field + " 字段超过 UTF-8 字节上限");
        }
        output.writeInt(bytes.remaining());
        output.write(bytes.array(), bytes.arrayOffset() + bytes.position(), bytes.remaining());
    }

    private static String readString(DataInputStream input, int maxBytes,
                                     String field) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > maxBytes || length > input.available()) {
            throw new ProtocolException(field + " 字段长度无效：" + length);
        }
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new ProtocolException(field + " 字段包含无效 UTF-8", exception);
        }
    }
}
