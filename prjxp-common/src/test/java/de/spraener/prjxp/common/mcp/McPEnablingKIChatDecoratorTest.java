package de.spraener.prjxp.common.mcp;

import de.spraener.prjxp.common.chat.KIChat;
import de.spraener.prjxp.common.config.PrjXPChatModelReference;
import de.spraener.prjxp.common.toolregistry.GroovyToolExecutor;
import de.spraener.prjxp.common.toolregistry.ToolDefinition;
import de.spraener.prjxp.common.toolregistry.ToolRegistry;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.AiServices;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.List;
import java.util.Map;

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

        ToolRegistry toolRegistry = new ToolRegistry(new GroovyToolExecutor());
        McPEnablingKIChatDecorator decorator = new McPEnablingKIChatDecorator(delegate, toolRegistry, null);

        assertThat(decorator.getChatModelReference()).isSameAs(ref);
    }

    @Test
    void analyzeImage_delegates() {
        KIChat delegate = mock(KIChat.class);
        when(delegate.analyzeImage(any(BufferedImage.class))).thenReturn("image-result");

        ToolRegistry toolRegistry = new ToolRegistry(new GroovyToolExecutor());
        McPEnablingKIChatDecorator decorator = new McPEnablingKIChatDecorator(delegate, toolRegistry, null);
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
        ToolRegistry toolRegistry = new ToolRegistry(new GroovyToolExecutor());
        McPEnablingKIChatDecorator decorator = new McPEnablingKIChatDecorator(delegate, toolRegistry, null) {
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

    @Test
    void buildSystemPrompt_withoutDefaultProject_excludesProjectName() {
        KIChat delegate = mock(KIChat.class);

        ToolRegistry toolRegistry = new ToolRegistry(new GroovyToolExecutor());
        McPEnablingKIChatDecorator decorator = new McPEnablingKIChatDecorator(delegate, toolRegistry, null);
        String prompt = decorator.buildSystemPrompt();

        assertThat(prompt).contains("You have access to tools for searching embedded project code");
        assertThat(prompt).doesNotContain("The active project is:");
    }

    @Test
    void chat_withDefaultProject_includesProjectInSystemPrompt() {
        KIChat delegate = mock(KIChat.class);

        ToolRegistry toolRegistry = new ToolRegistry(new GroovyToolExecutor());
        McPEnablingKIChatDecorator decorator = new McPEnablingKIChatDecorator(delegate, toolRegistry, "my-project");
        String prompt = decorator.buildSystemPrompt();

        assertThat(prompt).contains("The active project is: 'my-project'");
    }

    @Test
    void buildSystemPrompt_withBlankProject_excludesProjectName() {
        KIChat delegate = mock(KIChat.class);

        ToolRegistry toolRegistry = new ToolRegistry(new GroovyToolExecutor());
        McPEnablingKIChatDecorator decorator = new McPEnablingKIChatDecorator(delegate, toolRegistry, "   ");
        String prompt = decorator.buildSystemPrompt();

        assertThat(prompt).doesNotContain("The active project is:");
    }

    @Test
    void buildSystemPrompt_withTools_includesToolDescriptions() {
        KIChat delegate = mock(KIChat.class);

        ToolRegistry toolRegistry = new ToolRegistry(new GroovyToolExecutor());
        toolRegistry.register(new ToolDefinition("test-tool", "A test tool for searching",
                Map.of("query", "Search query"), params -> "result"));

        McPEnablingKIChatDecorator decorator = new McPEnablingKIChatDecorator(delegate, toolRegistry, null);
        String prompt = decorator.buildSystemPrompt();

        assertThat(prompt).contains("Available tools:");
        assertThat(prompt).contains("- test-tool");
    }

    @Test
    void buildSystemPrompt_withoutTools_showsNoToolsMessage() {
        KIChat delegate = mock(KIChat.class);

        ToolRegistry toolRegistry = new ToolRegistry(new GroovyToolExecutor());
        McPEnablingKIChatDecorator decorator = new McPEnablingKIChatDecorator(delegate, toolRegistry, null);
        String prompt = decorator.buildSystemPrompt();

        assertThat(prompt).contains("No tools are currently available.");
    }
}
