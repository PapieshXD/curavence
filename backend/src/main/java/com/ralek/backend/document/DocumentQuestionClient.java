package com.ralek.backend.document;

import java.util.List;

public interface DocumentQuestionClient {

    Answer answer(List<ChatMessage> messages);

    default Answer answerJson(List<ChatMessage> messages) {
        return answer(messages);
    }

    record ChatMessage(String role, String content, List<String> images) {
        public ChatMessage(String role, String content) { this(role, content, List.of()); }
    }

    record Answer(String content, String model) {
    }
}
