package cn.edu.course.chat.client;

import cn.edu.course.chat.common.Frame;
import cn.edu.course.chat.common.MessageType;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ConversationModel {
    public static final String PUBLIC = "PUBLIC";
    public static final int MAX_MESSAGES = 500;

    private final Map<String, Conversation> conversations = new LinkedHashMap<>();
    private final Set<String> messageIds = new HashSet<>();
    private final Set<String> historicalIds = new HashSet<>();
    private String active = PUBLIC;

    public ConversationModel() {
        conversations.put(PUBLIC, new Conversation());
    }

    public String active() {
        return active;
    }

    public void select(String key) {
        Objects.requireNonNull(key, "会话不能为空");
        if (key.isEmpty()) {
            throw new IllegalArgumentException("会话不能为空");
        }
        active = key;
        conversations.computeIfAbsent(key, ignored -> new Conversation()).unread = 0;
    }

    public void receive(Frame frame, String ownNickname, boolean historical) {
        Objects.requireNonNull(frame, "消息不能为空");
        Objects.requireNonNull(ownNickname, "昵称不能为空");
        if (frame.type() != MessageType.CHAT_EVENT || !messageIds.add(frame.messageId())) {
            return;
        }
        String key = conversationKey(frame, ownNickname);
        Conversation conversation = conversations.computeIfAbsent(key, ignored -> new Conversation());
        conversation.messages.addLast(frame);
        if (historical) {
            historicalIds.add(frame.messageId());
        }
        if (!historical && !frame.from().equals(ownNickname) && !key.equals(active)) {
            conversation.unread = Math.min(Integer.MAX_VALUE - 1, conversation.unread) + 1;
        }
        if (conversation.messages.size() > MAX_MESSAGES) {
            Frame removed = conversation.messages.removeFirst();
            messageIds.remove(removed.messageId());
            historicalIds.remove(removed.messageId());
            conversation.hasOlder = true;
        }
    }

    public List<Frame> messages(String key) {
        Conversation conversation = conversations.get(key);
        return conversation == null ? List.of() : List.copyOf(conversation.messages);
    }

    public List<String> conversations() {
        return List.copyOf(conversations.keySet());
    }

    public int unread(String key) {
        Conversation conversation = conversations.get(key);
        return conversation == null ? 0 : conversation.unread;
    }

    public boolean isHistorical(String messageId) {
        return historicalIds.contains(messageId);
    }

    public boolean hasOlder(String key) {
        Conversation conversation = conversations.get(key);
        return conversation != null && conversation.hasOlder;
    }

    public void markOlder(String key) {
        conversations.computeIfAbsent(key, ignored -> new Conversation()).hasOlder = true;
    }

    static String conversationKey(Frame frame, String ownNickname) {
        return PUBLIC.equals(frame.to()) ? PUBLIC
                : frame.from().equals(ownNickname) ? frame.to() : frame.from();
    }

    private static final class Conversation {
        private final ArrayDeque<Frame> messages = new ArrayDeque<>();
        private int unread;
        private boolean hasOlder;
    }
}
