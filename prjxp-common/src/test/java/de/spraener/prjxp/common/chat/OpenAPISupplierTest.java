package de.spraener.prjxp.common.chat;

import de.spraener.prjxp.common.chat.OpenAPISupplier;
import de.spraener.prjxp.common.chat.OllamaSupplier;
import de.spraener.prjxp.common.chat.OpenAPISupplier;
import de.spraener.prjxp.common.chat.GeminiSupplier;
import de.spraener.prjxp.common.chat.LMStudioSupplier;
import de.spraener.prjxp.common.config.PrjXPChatModelReference;
import de.spraener.prjxp.common.test.PrjXPTestObjectMother;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.internal.OpenAiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class OpenAPISupplierTest {

    @Autowired
    private OpenAPISupplier uut;

    @Test
    void canProvide_withOpenApi_returnsTrue() {
        PrjXPChatModelReference ref = PrjXPTestObjectMother.createPrjXPChatModelReference(m -> {
            m.setServerType("openapi");
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
    void appendsV1ToBaseUrl() {
        PrjXPChatModelReference ref = PrjXPTestObjectMother.createPrjXPChatModelReference(m -> {
            m.setServerType("openapi");
            m.setProviderUrl("http://localhost:1234");
        });
        ChatModel cm = uut.provide(ref);

        assertThat(cm)
                .isNotNull()
                .isInstanceOf(OpenAiChatModel.class)
                .matches( c -> {
                            String baseUrl = ReflectionTestUtils.getField(
                                    ReflectionTestUtils.getField(c, "client"), "baseUrl").toString();
                            return baseUrl.endsWith("/v1");
                        }
                )
        ;

    }

    @Test
    void provide_returnsChatModel() {
        PrjXPChatModelReference ref = PrjXPTestObjectMother.createPrjXPChatModelReference(m -> {
            m.setServerType("openapi");
            m.setModelName("gpt-4");
        });

        ChatModel result = uut.provide(ref);

        assertThatNoException().isThrownBy(() -> {});
    }

    @Test
    void provide_withBodyProperties_setsCustomParameters() {
        PrjXPChatModelReference ref = PrjXPTestObjectMother.createPrjXPChatModelReference(m -> {
            m.setServerType("openapi");
            m.setModelName("qwen3.8-27b-splash");
            m.setProviderUrl("http://localhost:1234");
            m.setBodyProperties(Map.of("enable_thinking", false));
        });

        ChatModel result = uut.provide(ref);

        assertThat(result).isInstanceOf(OpenAiChatModel.class);
        Map<String, Object> custom = ((OpenAiChatModel) result).defaultRequestParameters().customParameters();
        assertThat(custom).containsEntry("enable_thinking", false);
    }

    @Configuration
    static class TestConfig {
        @Bean
        OpenAPISupplier uut() {
            return new OpenAPISupplier();
        }
    }
}
