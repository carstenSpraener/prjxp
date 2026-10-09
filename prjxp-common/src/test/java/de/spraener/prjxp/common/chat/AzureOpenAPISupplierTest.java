package de.spraener.prjxp.common.chat;

import de.spraener.prjxp.common.config.PrjXPChatModelReference;
import de.spraener.prjxp.common.errorlog.PxLogService;
import de.spraener.prjxp.common.test.PrjXPTestObjectMother;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class AzureOpenAPISupplierTest {

    @Autowired
    private AzureOpenAPISupplier uut;

    @Test
    void canProvide_customWithIsAzure_returnsTrue() {
        PrjXPChatModelReference ref = PrjXPTestObjectMother.createPrjXPChatModelReference(m -> {
            m.setServerType("custom");
            m.setArgs(Map.of("isAzure", "true"));
        });

        assertThat(uut.canProvide(ref)).isTrue();
    }

    @Test
    void canProvide_customWithoutIsAzure_returnsFalse() {
        PrjXPChatModelReference ref = PrjXPTestObjectMother.createPrjXPChatModelReference(m -> {
            m.setServerType("custom");
            m.setArgs(Map.of());
        });

        assertThat(uut.canProvide(ref)).isFalse();
    }

    @Test
    void canProvide_ollamaWithIsAzure_returnsFalse() {
        PrjXPChatModelReference ref = PrjXPTestObjectMother.createPrjXPChatModelReference(m -> {
            m.setServerType("ollama");
            m.setArgs(Map.of("isAzure", "true"));
        });

        assertThat(uut.canProvide(ref)).isFalse();
    }

    @Test
    void provide_buildsModelWithDeploymentBaseUrl() {
        PrjXPChatModelReference ref = PrjXPTestObjectMother.createPrjXPChatModelReference(m -> {
            m.setServerType("custom");
            m.setProviderUrl("http://host/deployments");
            m.setModelName("gpt");
            m.setArgs(Map.of(
                    "isAzure", "true",
                    "api-key", "sk-test",
                    "api-version", "2024-1"));
        });

        ChatModel cm = uut.provide(ref);

        assertThat(cm).isInstanceOf(OpenAiChatModel.class);
        String baseUrl = ReflectionTestUtils.getField(
                ReflectionTestUtils.getField(cm, "client"), "baseUrl").toString();
        assertThat(baseUrl).isEqualTo("http://host/deployments/gpt/");
    }

    @Test
    void provide_resolvesSystemPropertyPlaceholder() {
        System.setProperty("AZURE_TEST_KEY", "sk-resolved");
        try {
            PrjXPChatModelReference ref = PrjXPTestObjectMother.createPrjXPChatModelReference(m -> {
                m.setServerType("custom");
                m.setProviderUrl("http://host/deployments");
                m.setModelName("gpt");
                m.setArgs(Map.of(
                        "isAzure", "true",
                        "api-key", "${AZURE_TEST_KEY}",
                        "api-version", "2024-1"));
            });

            ChatModel cm = uut.provide(ref);

            assertThat(cm).isNotNull().isInstanceOf(OpenAiChatModel.class);
        } finally {
            System.clearProperty("AZURE_TEST_KEY");
        }
    }

    @Configuration
    static class TestConfig {
        @Bean
        AzureOpenAPISupplier uut() {
            return new AzureOpenAPISupplier(new PxLogService());
        }
    }
}
