package de.spraener.prjxp.common.mcp;

import de.spraener.prjxp.common.chat.KIChat;
import de.spraener.prjxp.common.config.PrjXPChatModelReference;
import de.spraener.prjxp.common.toolregistry.ToolRegistry;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.UserMessage;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

public class McPEnablingKIChatDecorator implements KIChat {
    interface McpAgent {
        String chat(String prompt);
    }

    private final KIChat delegate;
    private final ToolRegistry toolRegistry;
    private final String defaultProject;

    public McPEnablingKIChatDecorator(KIChat delegate, ToolRegistry toolRegistry, String defaultProject) {
        this.delegate = delegate;
        this.toolRegistry = toolRegistry;
        this.defaultProject = defaultProject; // may be null
    }

    @Override
    public PrjXPChatModelReference getChatModelReference() {
        return delegate.getChatModelReference();
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
                List<ChatMessage> messages = chatRequest.messages();
                String activePrompt = prompt; // Initialer Fallback

                if (messages != null && !messages.isEmpty()) {
                    ChatMessage lastMessage = messages.get(messages.size() - 1);

                    // Prüfen, ob die letzte Nachricht eine UserMessage ist
                    if (lastMessage instanceof UserMessage userMessage) {
                        activePrompt = extractTextFromUserMessage(userMessage, prompt);
                    } else {
                        // Falls LangChain4j den Tool-Loop aufbaut und eine andere Nachricht am Ende steht,
                        // suchen wir rückwärts nach der letzten User-Frage
                        for (int i = messages.size() - 1; i >= 0; i--) {
                            if (messages.get(i) instanceof UserMessage um) {
                                activePrompt = extractTextFromUserMessage(um, prompt);
                                break;
                            }
                        }
                    }
                }

                // Aufruf an deine tatsächliche KIChat-Implementierung tunneln
                String responseText = delegate.chat(activePrompt);

                // Als standardkonforme ChatResponse zurückgeben
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from(responseText))
                        .build();
            }

            // Hilfsmethode, um das String-Array aus .value() sicher zu verarbeiten
            private String extractTextFromUserMessage(UserMessage userMessage, String fallback) {
                String[] values = userMessage.value();
                if (values != null && values.length > 0) {
                    // Wenn mehrere Text-Inhalte vorliegen, fügen wir sie zusammen
                    return String.join("\n", values);
                }
                return fallback;
            }
        };

        // Collect MCP clients from ToolRegistry for McpToolProvider (McpToolProvider needs raw McpClients)
        // ToolRegistry wraps MCP clients, but McpToolProvider still needs the raw list for tool execution
        // For now we keep backward compatibility: McpToolProvider still uses raw MCP clients for execution
        // The ToolRegistry is used for prompt injection (system message) and direct tool execution

        McpToolProvider mcpToolProvider = McpToolProvider.builder()
                .mcpClients(new ArrayList<>()) // Empty: tools are handled via ToolRegistry in system prompt
                .failIfOneServerFails(false)
                .build();

        // AiServices-Builder für v1.13.0 konfigurieren
        McpAgent agent = AiServices.builder(McpAgent.class)
                .chatModel(bridgeModel)
                .systemMessageProvider(context -> buildSystemPrompt())
                .toolProvider(mcpToolProvider) // Registriert den Provider direkt
                .build();
        return agent;
    }

    /* package-private for testability */
    String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("You have access to tools for searching embedded project code.");

        if (defaultProject != null && !defaultProject.isBlank()) {
            sb.append(" The active project is: '").append(defaultProject).append("'");
        }

        // Dynamically add tool descriptions from ToolRegistry
        List<String> toolDescriptions = toolRegistry.getToolDescriptions();
        if (!toolDescriptions.isEmpty()) {
            sb.append(". Available tools:\n");
            for (String desc : toolDescriptions) {
                sb.append("- ").append(desc).append("\n");
            }
        } else {
            sb.append(". No tools are currently available.");
        }

        sb.append(" Use these tools to gather information before answering the user's question.");

        return sb.toString();
    }

    @Override
    public String analyzeImage(BufferedImage image) {
        return delegate.analyzeImage(image);
    }
}
