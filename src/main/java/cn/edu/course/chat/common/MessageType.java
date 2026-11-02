package cn.edu.course.chat.common;

public enum MessageType {
    JOIN(1),
    WELCOME(2),
    USER_LIST(3),
    PUBLIC_SEND(4),
    PRIVATE_SEND(5),
    CHAT_EVENT(6),
    NOTICE(7),
    ERROR(8),
    LEAVE(9),
    PING(10),
    PONG(11);

    private final int code;

    MessageType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static MessageType fromCode(int code) throws ProtocolException {
        for (MessageType value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new ProtocolException("未知消息类型：" + code);
    }
}
