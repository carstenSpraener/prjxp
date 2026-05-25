package de.spraener.prjxp.common.chat;

import de.spraener.prjxp.common.config.PrjXPChatModelReference;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

class EmptyKiChatTest {

    @Test
    void constructor_setsChatModelReference() {
        PrjXPChatModelReference ref = new PrjXPChatModelReference();
        ref.setServerType("ollama");
        ref.setModelName("test-model");

        EmptyKiChat emptyKiChat = new EmptyKiChat(ref);

        assertThat(emptyKiChat.getChatModelReference()).isSameAs(ref);
    }

    @Test
    void chat_returnsEmptyString() {
        EmptyKiChat emptyKiChat = new EmptyKiChat(new PrjXPChatModelReference());

        String result = emptyKiChat.chat("Was ist das?");

        assertThat(result).isEmpty();
    }

    @Test
    void chat_withNull_returnsEmptyString() {
        EmptyKiChat emptyKiChat = new EmptyKiChat(new PrjXPChatModelReference());

        String result = emptyKiChat.chat(null);

        assertThat(result).isEmpty();
    }

    @Test
    void chat_withEmptyString_returnsEmptyString() {
        EmptyKiChat emptyKiChat = new EmptyKiChat(new PrjXPChatModelReference());

        String result = emptyKiChat.chat("");

        assertThat(result).isEmpty();
    }

    @Test
    void analyzeImage_returnsEmptyString() {
        EmptyKiChat emptyKiChat = new EmptyKiChat(new PrjXPChatModelReference());
        BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);

        String result = emptyKiChat.analyzeImage(image);

        assertThat(result).isEmpty();
    }

    @Test
    void analyzeImage_withNull_returnsEmptyString() {
        EmptyKiChat emptyKiChat = new EmptyKiChat(new PrjXPChatModelReference());

        String result = emptyKiChat.analyzeImage(null);

        assertThat(result).isEmpty();
    }

    @Test
    void chatModelReference_isImmutable() {
        PrjXPChatModelReference ref = new PrjXPChatModelReference();
        EmptyKiChat emptyKiChat = new EmptyKiChat(ref);

        PrjXPChatModelReference result = emptyKiChat.getChatModelReference();

        assertThat(result).isSameAs(ref);
    }
}
