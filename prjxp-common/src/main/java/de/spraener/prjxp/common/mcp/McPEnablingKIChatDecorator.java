package de.spraener.prjxp.common.mcp;

import de.spraener.prjxp.common.chat.KIChat;
import de.spraener.prjxp.common.config.PrjXPChatModelReference;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.UserMessage;
import lombok.RequiredArgsConstructor;
import okhttp3.Response;

import java.awt.image.BufferedImage;
import java.util.List;

public class McPEnablingKIChatDecorator implements KIChat {
    interface McpAgent {
        String chat(String prompt);
    }

    private final KIChat delegate;
    private final List<McpClient> mcpClients;

    public McPEnablingKIChatDecorator(KIChat delegate, List<McpClient> mcpClients) {
        this.delegate = delegate;
        this.mcpClients = mcpClients;
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

    private McpAgent createMcpAgent(String prompt) {
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
        // 2. Den neuen McpToolProvider über seinen Builder mit allen Clients konfigurieren
        McpToolProvider mcpToolProvider = McpToolProvider.builder()
                .mcpClients(mcpClients)
                .failIfOneServerFails(false) // Schützt die Anwendung, falls ein Server offline ist
                .build();

        // 3. AiServices-Builder für v1.13.0 konfigurieren
        McpAgent agent = AiServices.builder(McpAgent.class)
                .chatModel(bridgeModel)
                .toolProvider(mcpToolProvider) // Registriert den Provider direkt
                .build();
        return agent;
    }

    @Override
    public String analyzeImage(BufferedImage image) {
        return delegate.analyzeImage(image);
    }
}
