package de.spraener.prjxp.common.chat;

import de.spraener.prjxp.common.chat.LMStudioSupplier;
import de.spraener.prjxp.common.config.PrjXPChatModelReference;
import de.spraener.prjxp.common.test.PrjXPTestObjectMother;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Test
    void provide_withBodyProperties_setsCustomParameters() {
        PrjXPChatModelReference ref = PrjXPTestObjectMother.createPrjXPChatModelReference(m -> {
            m.setServerType("lm-studio");
            m.setModelName("qwen3.8-27b-splash");
            m.setProviderUrl("http://localhost:1234/v1");
            m.setBodyProperties(Map.of("enable_thinking", false));
        });

        ChatModel result = uut.provide(ref);

        assertThat(result).isInstanceOf(OpenAiChatModel.class);
        Map<String, Object> custom = ((OpenAiChatModel) result).defaultRequestParameters().customParameters();
        assertThat(custom).containsEntry("enable_thinking", false);
    }

    @Test
    void provide_withStringBodyProperty_coercesToBoolean() {
        PrjXPChatModelReference ref = PrjXPTestObjectMother.createPrjXPChatModelReference(m -> {
            m.setServerType("lm-studio");
            m.setModelName("qwen3.8-27b-splash");
            m.setProviderUrl("http://localhost:1234/v1");
            m.setBodyProperties(Map.of("enable_thinking", "false"));
        });

        ChatModel result = uut.provide(ref);

        Map<String, Object> custom = ((OpenAiChatModel) result).defaultRequestParameters().customParameters();
        assertThat(custom.get("enable_thinking")).isEqualTo(false);
        assertThat(custom.get("enable_thinking")).isInstanceOf(Boolean.class);
    }

    @Test
    void provide_withoutBodyProperties_noCustomParameters() {
        PrjXPChatModelReference ref = PrjXPTestObjectMother.createPrjXPChatModelReference(m -> {
            m.setServerType("lm-studio");
            m.setModelName("llama3.2");
            m.setProviderUrl("http://localhost:1234");
        });

        ChatModel result = uut.provide(ref);

        Map<String, Object> custom = ((OpenAiChatModel) result).defaultRequestParameters().customParameters();
        assertThat(custom).isEmpty();
    }

    @Configuration
    static class TestConfig {
        @Bean
        LMStudioSupplier uut() {
            return new LMStudioSupplier();
        }
    }
}
