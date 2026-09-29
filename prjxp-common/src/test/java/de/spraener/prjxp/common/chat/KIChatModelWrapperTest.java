package de.spraener.prjxp.common.chat;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import de.spraener.prjxp.common.config.PrjXPChatModelReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KIChatModelWrapperTest {

    private ChatModel chatModel;
    private PrjXPChatModelReference chatModelReference;
    private KIChatModelWrapper uut;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        chatModelReference = new PrjXPChatModelReference();
        chatModelReference.setServerType("ollama");
        chatModelReference.setModelName("test-model");
        chatModelReference.setProviderUrl("http://localhost:11434");
        uut = new KIChatModelWrapper(chatModel, chatModelReference);
    }

    @Test
    void constructor_setsFields() {
        assertThat(uut.getChatModelReference()).isSameAs(chatModelReference);
    }

    @Test
    void chat_delegatesToChatModel() {
        String question = "Was ist Java?";
        String answer = "Eine Programmiersprache.";
        when(chatModel.chat(question)).thenReturn(answer);

        String result = uut.chat(question);

        assertThat(result).isEqualTo(answer);
        verify(chatModel).chat(question);
    }

    @Test
    void chat_withEmptyQuestion_delegatesToChatModel() {
        when(chatModel.chat("")).thenReturn("");

        String result = uut.chat("");

        assertThat(result).isEmpty();
        verify(chatModel).chat("");
    }

    @Test
    void chat_withLongQuestion_delegatesToChatModel() {
        String longQuestion = "x".repeat(10000);
        when(chatModel.chat(longQuestion)).thenReturn("response");

        String result = uut.chat(longQuestion);

        assertThat(result).isEqualTo("response");
        verify(chatModel).chat(longQuestion);
    }

    @Test
    void analyzeImage_success_returnsTextFromResponse() {
        BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        ChatResponse chatResponse = mock(ChatResponse.class);
        AiMessage aiMessage = mock(AiMessage.class);

        when(chatModel.chat(any(UserMessage.class))).thenReturn(chatResponse);
        when(chatResponse.aiMessage()).thenReturn(aiMessage);
        when(aiMessage.text()).thenReturn("Der Text im Bild ist: Hallo Welt");

        String result = uut.analyzeImage(image);

        assertThat(result).isEqualTo("Der Text im Bild ist: Hallo Welt");
        verify(chatModel).chat(any(UserMessage.class));
    }

    @Test
    void analyzeImage_withNullImage_returnsErrorMessage() {
        String result = uut.analyzeImage(null);

        assertThat(result).startsWith("Fehler bei der Bild-Analyse:");
    }

    @Test
    void analyzeImage_chatModelThrowsException_returnsErrorMessage() {
        BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        when(chatModel.chat(any(UserMessage.class))).thenThrow(new RuntimeException("API error"));

        String result = uut.analyzeImage(image);

        assertThat(result).startsWith("Fehler bei der Bild-Analyse:");
        assertThat(result).contains("API error");
    }

    @Test
    void analyzeImage_aiMessageTextIsNull_returnsNull() {
        BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        ChatResponse chatResponse = mock(ChatResponse.class);
        AiMessage aiMessage = mock(AiMessage.class);

        when(chatModel.chat(any(UserMessage.class))).thenReturn(chatResponse);
        when(chatResponse.aiMessage()).thenReturn(aiMessage);
        when(aiMessage.text()).thenReturn(null);

        String result = uut.analyzeImage(image);

        assertThat(result).isNull();
    }

    @Test
    void analyzeImage_multipleCalls_cachesNothing() {
        BufferedImage image1 = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        BufferedImage image2 = new BufferedImage(50, 50, BufferedImage.TYPE_INT_RGB);
        ChatResponse chatResponse = mock(ChatResponse.class);
        AiMessage aiMessage = mock(AiMessage.class);

        when(chatModel.chat(any(UserMessage.class))).thenReturn(chatResponse);
        when(chatResponse.aiMessage()).thenReturn(aiMessage);
        when(aiMessage.text()).thenReturn("result");

        uut.analyzeImage(image1);
        uut.analyzeImage(image2);

        verify(chatModel, times(2)).chat(any(UserMessage.class));
    }
}
