package de.spraener.prjxp.common.mcp;

import de.spraener.prjxp.common.chat.KIChat;
import de.spraener.prjxp.common.config.PrjXPChatModelReference;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.AiServices;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Decorates a {@link KIChat} so the underlying LLM can autonomously call MCP tools
 * (e.g. the prjxp search tools) through a LangChain4j {@link AiServices} agent.
 * <p>
 * The decorator bridges the agent to the delegate chat: the full message history and the
 * tool specifications are forwarded to the delegate, so the model can emit tool calls that
 * the agent then executes through the {@link McpToolProvider} (which talks to the configured
 * MCP servers). For models without native function calling it falls back to a plain
 * single-shot chat.
 * <p>
 * DocPipe applies this decoration in its {@code LLMService}; the mcp-server's live chat
 * keeps its own tool loop and is intentionally left undecorated.
 */
public class McPEnablingKIChatDecorator implements KIChat {
    interface McpAgent {
        String chat(String prompt);
    }

    private final KIChat delegate;
    private final List<McpClient> mcpClients;
    private final String defaultProject; // may be null

    public McPEnablingKIChatDecorator(KIChat delegate, List<McpClient> mcpClients, String defaultProject) {
        this.delegate = delegate;
        this.mcpClients = mcpClients;
        this.defaultProject = defaultProject;
    }

    @Override
    public PrjXPChatModelReference getChatModelReference() {
        return delegate.getChatModelReference();
    }

    @Override
    public boolean supportsTools() {
        return delegate.supportsTools();
    }

    @Override
    public String chat(String prompt) {
        McpAgent agent = createMcpAgent(prompt);
        return agent.chat(prompt);
    }

    /* package-private for testability (overridable in test subclasses) */
    McpAgent createMcpAgent(String prompt) {
        ChatModel bridgeModel = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest chatRequest) {
                // Forward the full history + tool specs so the model can return tool calls
                // that the AiServices agent executes via the McpToolProvider.
                if (delegate.supportsTools()) {
                    return delegate.chat(chatRequest.messages(), chatRequest.toolSpecifications());
                }
                // Fallback for models without native function calling: plain single-shot chat.
                String activePrompt = extractLastUserMessage(chatRequest.messages(), prompt);
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from(delegate.chat(activePrompt)))
                        .build();
            }

            private String extractLastUserMessage(List<ChatMessage> messages, String fallback) {
                if (messages == null || messages.isEmpty()) {
                    return fallback;
                }
                for (int i = messages.size() - 1; i >= 0; i--) {
                    if (messages.get(i) instanceof UserMessage um && um.hasSingleText()) {
                        return um.singleText();
                    }
                }
                return fallback;
            }
        };

        McpToolProvider mcpToolProvider = McpToolProvider.builder()
                .mcpClients(mcpClients)
                .failIfOneServerFails(false) // keep going even if a server is offline
                .build();

        McpAgent agent = AiServices.builder(McpAgent.class)
                .chatModel(bridgeModel)
                .systemMessageProvider(context -> buildSystemPrompt())
                .toolProvider(mcpToolProvider)
                .build();
        return agent;
    }

    /* package-private for testability */
    String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("You have access to MCP tools for searching embedded project code.");

        if (defaultProject != null && !defaultProject.isBlank()) {
            sb.append(" The active project is: '").append(defaultProject).append("'");
        }

        sb.append(". Available tools include: vectorSearch (semantic search over code chunks), ");
        sb.append("grep (exact text search), readFile (full file content), ");
        sb.append("readBySignature (lookup method by name).");
        sb.append(" Use these tools to gather information before answering the user's question.");

        return sb.toString();
    }

    @Override
    public String analyzeImage(BufferedImage image) {
        return delegate.analyzeImage(image);
    }
}
