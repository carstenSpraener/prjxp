package de.spraener.prjxp.common.chat;

import de.spraener.prjxp.common.chat.ChatModelSupplier;
import de.spraener.prjxp.common.chat.KIChat;
import de.spraener.prjxp.common.chat.OllamaSupplier;
import de.spraener.prjxp.common.chat.OpenAPISupplier;
import de.spraener.prjxp.common.chat.GeminiSupplier;
import de.spraener.prjxp.common.chat.LMStudioSupplier;
import de.spraener.prjxp.common.config.PrjXPChatModelReference;
import de.spraener.prjxp.common.chat.KIChatModelProvider;
import de.spraener.prjxp.common.chat.OllamaSupplier;
import de.spraener.prjxp.common.chat.OpenAPISupplier;
import de.spraener.prjxp.common.chat.GeminiSupplier;
import de.spraener.prjxp.common.chat.LMStudioSupplier;
import de.spraener.prjxp.common.config.PrjXPChatModelReference;
import de.spraener.prjxp.common.test.PrjXPTestComponentMother;
import de.spraener.prjxp.common.test.PrjXPTestObjectMother;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
class KIChatModelProviderTest {

    @Autowired
    private KIChatModelProvider uut;

    @Test
    void createKIChat_returnsKiChat() {
        PrjXPChatModelReference ref = PrjXPTestObjectMother.createPrjXPChatModelReference(m -> {
            m.setServerType("ollama");
            m.setModelName("test-model");
            m.setProviderUrl("http://localhost:11434");
        });

        KIChat result = uut.createKIChat(ref);

        assertThatNoException().isThrownBy(() -> {});
    }

    @Test
    void createKIChat_withUnknownServerType_throwsIllegalStateException() {
        PrjXPChatModelReference ref = PrjXPTestObjectMother.createPrjXPChatModelReference(m -> {
            m.setServerType("unknown");
            m.setModelName("test-model");
            m.setProviderUrl("http://localhost:9999");
        });

        assertThatThrownBy(() -> uut.createKIChat(ref))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("There is no supplier for server model stereotype unknown");
    }

    @Configuration
    static class TestConfig {
        @Bean
        List<ChatModelSupplier> chatModelSuppliers() {
            return List.of(
                    new OllamaSupplier(),
                    new OpenAPISupplier(),
                    new GeminiSupplier(),
                    new LMStudioSupplier()
            );
        }

        @Bean
        KIChatModelProvider uut(List<ChatModelSupplier> chatModelSuppliers) {
            return new KIChatModelProvider(chatModelSuppliers);
        }
    }
}
