package de.spraener.prjxp.common.chat;

import de.spraener.prjxp.common.chat.LMStudioSupplier;
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
class LMStudioSupplierTest {

    @Autowired
    private LMStudioSupplier uut;

    @Test
    void canProvide_withLmStudio_returnsTrue() {
        PrjXPChatModelReference ref = PrjXPTestObjectMother.createPrjXPChatModelReference(m -> {
            m.setServerType("lm-studio");
        });

        boolean result = uut.canProvide(ref);

        assertThatNoException().isThrownBy(() -> {});
    }

    @Test
    void canProvide_withOllama_returnsFalse() {
        PrjXPChatModelReference ref = PrjXPTestObjectMother.createPrjXPChatModelReference(m -> {
            m.setServerType("ollama");
        });

        boolean result = uut.canProvide(ref);

        assertThatNoException().isThrownBy(() -> {});
    }

    @Test
    void provide_returnsChatModel() {
        PrjXPChatModelReference ref = PrjXPTestObjectMother.createPrjXPChatModelReference(m -> {
            m.setServerType("lm-studio");
            m.setModelName("llama3.2");
            m.setProviderUrl("http://localhost:1234");
        });

        ChatModel result = uut.provide(ref);

        assertThatNoException().isThrownBy(() -> {});
    }

    @Configuration
    static class TestConfig {
        @Bean
        LMStudioSupplier uut() {
            return new LMStudioSupplier();
        }
    }
}
