package de.spraener.prjxp.mcp.rest.dto;

import java.util.ArrayList;
import java.util.List;

/** Request DTO for sending a chat message. */
public class ChatRequest {
    private String message;
    private String chatModelName;
    /** Optional project name for RAG enrichment. */
    private String projectContext;
    /** Enable tool usage (default false). */
    private boolean useTools = false;
    /** Conversation history for multi-turn context (last N messages). */
    private List<ChatMessage> messages = new ArrayList<>();

    public ChatRequest() {}

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getChatModelName() { return chatModelName; }
    public void setChatModelName(String chatModelName) { this.chatModelName = chatModelName; }

    public String getProjectContext() { return projectContext; }
    public void setProjectContext(String projectContext) { this.projectContext = projectContext; }

    public boolean isUseTools() { return useTools; }
    public void setUseTools(boolean useTools) { this.useTools = useTools; }

    public List<ChatMessage> getMessages() { return messages; }
    public void setMessages(List<ChatMessage> messages) { this.messages = messages; }

    /** A single message in the conversation history. */
    public static class ChatMessage {
        private String role;     // "user" or "assistant"
        private String content;

        public ChatMessage() {}

        public ChatMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }

        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }

        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
    }
}
