package de.spraener.prjxp.common.chat;

import de.spraener.prjxp.common.config.PrjXPChatModelReference;
import de.spraener.prjxp.common.errorlog.PxLogService;
import de.spraener.prjxp.common.test.PrjXPTestComponentMother;
import de.spraener.prjxp.common.test.PrjXPTestObjectMother;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
class CustomChatModelSupplierTest {

    @Autowired
    private CustomChatModelSupplier uut;

    @Test
    void canProvide_withCustomServerType_returnsTrue() {
        PrjXPChatModelReference ref = PrjXPTestObjectMother.createPrjXPChatModelReference(m -> {
            m.setServerType("custom");
        });

        boolean result = uut.canProvide(ref);

        assertThat(result).isTrue();
    }

    @Test
    void canProvide_withOllama_returnsFalse() {
        PrjXPChatModelReference ref = PrjXPTestObjectMother.createPrjXPChatModelReference(m -> {
            m.setServerType("ollama");
        });

        boolean result = uut.canProvide(ref);

        assertThat(result).isFalse();
    }

    @Test
    void provide_withMatchingCustomChatModel_returnsChatModel() {
        PrjXPChatModelReference ref = PrjXPTestObjectMother.createPrjXPChatModelReference(m -> {
            m.setServerType("custom");
        });

        ChatModel result = uut.provide(ref);

        assertThat(result).isNotNull();
        assertThat(result).isInstanceOf(ChatModel.class);
    }

    @Configuration
    static class TestConfig {
        @Bean
        List<CustomChatModel> customChatModels() {
            CustomChatModel customChatModel = mock(CustomChatModel.class);
            when(customChatModel.canHandle(any())).thenReturn(true);
            return List.of(customChatModel);
        }

        @Bean
        PxLogService pxLogService() {
            return PrjXPTestComponentMother.createPxLogService();
        }

        @Bean
        CustomChatModelSupplier uut(List<CustomChatModel> customChatModels, PxLogService pxLogService) {
            return new CustomChatModelSupplier(customChatModels, pxLogService);
        }
    }
}
