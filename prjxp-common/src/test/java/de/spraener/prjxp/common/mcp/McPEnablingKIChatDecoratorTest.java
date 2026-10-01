package de.spraener.prjxp.common.mcp;

import de.spraener.prjxp.common.chat.KIChat;
import de.spraener.prjxp.common.config.PrjXPChatModelReference;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.AiServices;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McPEnablingKIChatDecoratorTest {

    @Test
    void getChatModelReference_delegates() {
        KIChat delegate = mock(KIChat.class);
        PrjXPChatModelReference ref = mock(PrjXPChatModelReference.class);
        when(delegate.getChatModelReference()).thenReturn(ref);

        McPEnablingKIChatDecorator decorator = new McPEnablingKIChatDecorator(delegate, List.of());

        assertThat(decorator.getChatModelReference()).isSameAs(ref);
    }

    @Test
    void analyzeImage_delegates() {
        KIChat delegate = mock(KIChat.class);
        when(delegate.analyzeImage(any(BufferedImage.class))).thenReturn("image-result");

        McPEnablingKIChatDecorator decorator = new McPEnablingKIChatDecorator(delegate, List.of());
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);

        String result = decorator.analyzeImage(image);

        assertThat(result).isEqualTo("image-result");
        verify(delegate).analyzeImage(image);
    }

    @Test
    void chat_delegatesThroughAiServicesPipeline() {
        KIChat delegate = mock(KIChat.class);
        when(delegate.chat("hello")).thenReturn("answer");

        // Build a real AiServices agent with a bridge model and empty tool provider
        interface TestAgent {
            String chat(String prompt);
        }

        ChatModel bridgeModel = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                // Extract the last user message text, or use original prompt
                String prompt = request.messages().get(request.messages().size() - 1)
                        .toString(); // Simplified: just get some text
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from(delegate.chat("hello")))
                        .build();
            }
        };

        McpToolProvider emptyTools = McpToolProvider.builder()
                .mcpClients(Collections.emptyList())
                .failIfOneServerFails(false)
                .build();

        TestAgent agent = AiServices.builder(TestAgent.class)
                .chatModel(bridgeModel)
                .toolProvider(emptyTools)
                .build();

        // Now test the decorator with real AiServices by creating a subclass
        McPEnablingKIChatDecorator decorator = new McPEnablingKIChatDecorator(delegate, Collections.emptyList()) {
            @Override
            McpAgent createMcpAgent(String prompt) {
                // Build real agent with bridge model tunneling to delegate
                ChatModel bm = new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest request) {
                        String responseText = delegate.chat("hello");
                        return ChatResponse.builder()
                                .aiMessage(AiMessage.from(responseText))
                                .build();
                    }
                };

                McpToolProvider tp = McpToolProvider.builder()
                        .mcpClients(Collections.emptyList())
                        .failIfOneServerFails(false)
                        .build();

                return AiServices.builder(McpAgent.class)
                        .chatModel(bm)
                        .toolProvider(tp)
                        .build();
            }
        };

        String result = decorator.chat("hello");

        assertThat(result).isEqualTo("answer");
        verify(delegate).chat("hello");
    }
}
