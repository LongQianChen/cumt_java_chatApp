package cn.edu.course.chat.common;

import java.util.List;
import java.util.Objects;

public record Frame(MessageType type, long requestId, long serverTime,
                    String messageId, String from, String to, String text,
                    List<String> users) {
    public Frame {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(text, "text");
        users = List.copyOf(Objects.requireNonNull(users, "users"));
    }

    public static Frame request(MessageType type, long requestId, String from,
                                String to, String text) {
        return new Frame(type, requestId, 0, "", from, to, text, List.of());
    }
}
