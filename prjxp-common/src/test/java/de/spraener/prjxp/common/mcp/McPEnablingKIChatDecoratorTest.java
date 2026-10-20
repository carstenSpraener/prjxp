package de.spraener.prjxp.common.mcp;

import de.spraener.prjxp.common.chat.KIChat;
import de.spraener.prjxp.common.config.PrjXPChatModelReference;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McPEnablingKIChatDecoratorTest {

    @Test
    void getChatModelReference_delegates() {
        KIChat delegate = mock(KIChat.class);
        PrjXPChatModelReference ref = mock(PrjXPChatModelReference.class);
        when(delegate.getChatModelReference()).thenReturn(ref);

        McPEnablingKIChatDecorator decorator = new McPEnablingKIChatDecorator(delegate, List.of(), null);

        assertThat(decorator.getChatModelReference()).isSameAs(ref);
    }

    @Test
    void analyzeImage_delegates() {
        KIChat delegate = mock(KIChat.class);
        when(delegate.analyzeImage(any(BufferedImage.class))).thenReturn("image-result");

        McPEnablingKIChatDecorator decorator = new McPEnablingKIChatDecorator(delegate, List.of(), null);
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);

        String result = decorator.analyzeImage(image);

        assertThat(result).isEqualTo("image-result");
        verify(delegate).analyzeImage(image);
    }

    @Test
    void supportsTools_delegates() {
        KIChat delegate = mock(KIChat.class);
        when(delegate.supportsTools()).thenReturn(true);

        McPEnablingKIChatDecorator decorator = new McPEnablingKIChatDecorator(delegate, List.of(), null);

        assertThat(decorator.supportsTools()).isTrue();
    }

    @Test
    void chat_nativeToolsSupported_forwardsMessagesAndToolsToDelegate() {
        KIChat delegate = mock(KIChat.class);
        when(delegate.supportsTools()).thenReturn(true);
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from("native answer"))
                .build();
        when(delegate.chat(anyList(), anyList())).thenReturn(response);

        McPEnablingKIChatDecorator decorator = new McPEnablingKIChatDecorator(delegate, List.of(), null);

        String result = decorator.chat("hello");

        assertThat(result).isEqualTo("native answer");
        verify(delegate).chat(anyList(), anyList());
    }

    @Test
    void chat_toolsNotSupported_fallsBackToPlainChat() {
        KIChat delegate = mock(KIChat.class);
        when(delegate.supportsTools()).thenReturn(false);
        when(delegate.chat("hello")).thenReturn("plain answer");

        McPEnablingKIChatDecorator decorator = new McPEnablingKIChatDecorator(delegate, List.of(), null);

        String result = decorator.chat("hello");

        assertThat(result).isEqualTo("plain answer");
        verify(delegate).chat("hello");
    }

    @Test
    void buildSystemPrompt_withoutDefaultProject_excludesProjectName() {
        KIChat delegate = mock(KIChat.class);

        McPEnablingKIChatDecorator decorator = new McPEnablingKIChatDecorator(delegate, List.of(), null);
        String prompt = decorator.buildSystemPrompt();

        assertThat(prompt).contains("You have access to MCP tools for searching embedded project code");
        assertThat(prompt).doesNotContain("The active project is:");
    }

    @Test
    void buildSystemPrompt_withDefaultProject_includesProjectName() {
        KIChat delegate = mock(KIChat.class);

        McPEnablingKIChatDecorator decorator = new McPEnablingKIChatDecorator(delegate, List.of(), "my-project");
        String prompt = decorator.buildSystemPrompt();

        assertThat(prompt).contains("The active project is: 'my-project'");
    }

    @Test
    void buildSystemPrompt_withBlankProject_excludesProjectName() {
        KIChat delegate = mock(KIChat.class);

        McPEnablingKIChatDecorator decorator = new McPEnablingKIChatDecorator(delegate, List.of(), "   ");
        String prompt = decorator.buildSystemPrompt();

        assertThat(prompt).doesNotContain("The active project is:");
    }
}
