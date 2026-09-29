package de.spraener.prjxp.common.chat;

import de.spraener.prjxp.common.chat.OllamaSupplier;
import de.spraener.prjxp.common.config.PrjXPChatModelReference;
import de.spraener.prjxp.common.test.PrjXPTestObjectMother;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNoException;

@SpringBootTest
@ActiveProfiles("test")
class OllamaSupplierTest {

    @Autowired
    private OllamaSupplier uut;

    @Test
    void canProvide_withOllama_returnsTrue() {
        PrjXPChatModelReference ref = PrjXPTestObjectMother.createPrjXPChatModelReference(m -> {
            m.setServerType("ollama");
        });

        boolean result = uut.canProvide(ref);

        assertThatNoException().isThrownBy(() -> {});
    }

    @Test
    void canProvide_withOpenApi_returnsFalse() {
        PrjXPChatModelReference ref = PrjXPTestObjectMother.createPrjXPChatModelReference(m -> {
            m.setServerType("openapi");
        });

        boolean result = uut.canProvide(ref);

        assertThatNoException().isThrownBy(() -> {});
    }

    @Test
    void provide_returnsChatModel() {
        PrjXPChatModelReference ref = PrjXPTestObjectMother.createPrjXPChatModelReference(m -> {
            m.setServerType("ollama");
            m.setModelName("llama3.2");
            m.setProviderUrl("http://localhost:11434");
        });

        ChatModel result = uut.provide(ref);

        assertThatNoException().isThrownBy(() -> {});
    }

    @Configuration
    static class TestConfig {
        @Bean
        OllamaSupplier uut() {
            return new OllamaSupplier();
        }
    }
}
