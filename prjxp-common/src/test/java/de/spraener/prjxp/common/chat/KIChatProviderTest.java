package de.spraener.prjxp.common.chat;

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
import de.spraener.prjxp.common.chat.KIChatProvider;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.test.PrjXPTestComponentMother;
import de.spraener.prjxp.common.test.PrjXPTestObjectMother;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
class KIChatProviderTest {

    @Autowired
    private KIChatProvider uut;

    @Autowired
    private PrjXPConfig prjXPConfig;

    @Autowired
    private KIChatModelProvider modelProvider;

    @BeforeEach
    void setUp() {
        lenient().when(modelProvider.createKIChat(any(PrjXPChatModelReference.class)))
                .thenAnswer(inv -> {
                    PrjXPChatModelReference ref = inv.getArgument(0);
                    KIChat chat = mock(KIChat.class);
                    when(chat.getChatModelReference()).thenReturn(ref);
                    return chat;
                });
    }

    @Test
    void getByName_returnsOptional() {
        PrjXPChatModelReference ref = PrjXPTestObjectMother.createPrjXPChatModelReference(m -> {
            m.setModelName("test-model");
        });

        Optional<KIChat> result = uut.getByName("test-model");

        assertThatNoException().isThrownBy(() -> {});
    }

    @Test
    void getByStereotype_returnsOptional() {
        PrjXPChatModelReference ref = PrjXPTestObjectMother.createPrjXPChatModelReference(m -> {
            m.setStereoType("test-stereotype");
        });

        Optional<KIChat> result = uut.getByStereotype("test-stereotype");

        assertThatNoException().isThrownBy(() -> {});
    }

    @Test
    void apply_withPredicate_returnsOptional() {
        Predicate<PrjXPChatModelReference> predicate = m -> true;

        Optional<KIChat> result = uut.apply(predicate);

        assertThatNoException().isThrownBy(() -> {});
    }

    @Test
    void getByStereotype_resolvesPromptPlaceholderWithDefault() {
        PrjXPChatModelReference ref = new PrjXPChatModelReference();
        ref.setStereoType("prompt-test-1");
        ref.setServerType("ollama");
        ref.setProviderUrl("http://localhost:11434");
        ref.setPrompt("${UNSET_PRJXP_TEST_VAR:fallback-text}");
        prjXPConfig.getChatModels().add(ref);

        var result = uut.getByStereotype("prompt-test-1");

        assertThat(result).isPresent();
        assertThat(result.get().getChatModelReference().getPrompt()).isEqualTo("fallback-text");
    }

    @Test
    void getByStereotype_plainPromptPassesThrough() {
        PrjXPChatModelReference ref = new PrjXPChatModelReference();
        ref.setStereoType("prompt-test-2");
        ref.setServerType("ollama");
        ref.setProviderUrl("http://localhost:11434");
        ref.setPrompt("Always answer in German.");
        prjXPConfig.getChatModels().add(ref);

        var result = uut.getByStereotype("prompt-test-2");

        assertThat(result).isPresent();
        assertThat(result.get().getChatModelReference().getPrompt()).isEqualTo("Always answer in German.");
    }

    @Test
    void getByStereotype_nullPromptStaysNull() {
        PrjXPChatModelReference ref = new PrjXPChatModelReference();
        ref.setStereoType("prompt-test-3");
        ref.setServerType("ollama");
        ref.setProviderUrl("http://localhost:11434");
        prjXPConfig.getChatModels().add(ref);

        var result = uut.getByStereotype("prompt-test-3");

        assertThat(result).isPresent();
        assertThat(result.get().getChatModelReference().getPrompt()).isNull();
    }

    @Configuration
    static class TestConfig {
        @Bean
        PrjXPConfig prjXPConfig() {
            return PrjXPTestObjectMother.createPrjXPConfig();
        }

        @Bean
        KIChatModelProvider modelProvider() {
            return PrjXPTestComponentMother.createKIChatModelProvider();
        }

        @Bean
        de.spraener.prjxp.common.toolregistry.ToolRegistry toolRegistry() {
            return new de.spraener.prjxp.common.toolregistry.ToolRegistry(
                    new de.spraener.prjxp.common.toolregistry.GroovyToolExecutor());
        }

        @Bean
        de.spraener.prjxp.common.mcp.McpClientManager mcpClientManager(PrjXPConfig config,
                de.spraener.prjxp.common.toolregistry.ToolRegistry toolRegistry) {
            return new de.spraener.prjxp.common.mcp.McpClientManager(config, toolRegistry);
        }

        @Bean
        KIChatProvider uut(PrjXPConfig config, KIChatModelProvider modelProvider,
                           de.spraener.prjxp.common.mcp.McpClientManager mcpClientManager) {
            return new KIChatProvider(config, modelProvider, mcpClientManager);
        }
    }
}
