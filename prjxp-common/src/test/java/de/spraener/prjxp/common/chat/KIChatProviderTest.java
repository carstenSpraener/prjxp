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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;
import java.util.function.Predicate;

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
        KIChatProvider uut(PrjXPConfig config, KIChatModelProvider modelProvider) {
            return new KIChatProvider(config, modelProvider);
        }
    }
}
