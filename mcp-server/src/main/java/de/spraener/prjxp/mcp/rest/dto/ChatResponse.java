package de.spraener.prjxp.mcp.rest.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Response DTO for chat messages. */
public class ChatResponse {
    private String id;
    private String role;
    private String content;
    private List<RagContext> ragContext = new ArrayList<>();
    private List<ToolCall> toolCalls = new ArrayList<>();

    public ChatResponse() {
        this.id = UUID.randomUUID().toString();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public List<RagContext> getRagContext() { return ragContext; }
    public void setRagContext(List<RagContext> ragContext) { this.ragContext = ragContext; }

    public List<ToolCall> getToolCalls() { return toolCalls; }
    public void setToolCalls(List<ToolCall> toolCalls) { this.toolCalls = toolCalls; }
}
