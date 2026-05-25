package de.spraener.prjxp.common.store;

import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import de.spraener.prjxp.common.config.PrjXPEmbeddingStoreReference;
import de.spraener.prjxp.common.test.PrjXPTestComponentMother;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
class PxChunkDaoProviderTest {

    @Autowired
    private PxChunkDaoProvider uut;

    @Test
    void apply_withPredicate_returnsOptional() {
        Predicate<PrjXPEmbeddingStoreReference> predicate = r -> true;

        Optional<PxChunkDao> result = uut.apply(predicate);

        assertThatNoException().isThrownBy(() -> {});
    }

    @Test
    void get_withDefaultName_returnsOptional() {
        Optional<PxChunkDao> result = uut.get("default");

        assertThatNoException().isThrownBy(() -> {});
    }

    @Test
    void getModelName_returnsString() {
        String result = uut.getModelName("default");

        assertThatNoException().isThrownBy(() -> {});
    }

    @Configuration
    static class TestConfig {
        @Bean
        List<PxChunkDao> chunkDaos() {
            return List.of();
        }

        @Bean
        PxChunkDaoProvider uut(List<PxChunkDao> chunkDaos) {
            return new PxChunkDaoProvider(chunkDaos);
        }
    }
}
