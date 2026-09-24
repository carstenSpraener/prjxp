package de.spraener.prjxp.common.store;

import de.spraener.prjxp.common.config.PrjXPEmbeddingStoreReference;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 03: the provider keeps its startup-injected DAOs but also supports runtime
 * registration/unregistration (hub: dynamically imported projects).
 */
class PxChunkDaoProviderTest {

    private static PrjXPEmbeddingStoreReference ref(String projectName, boolean isDefault) {
        PrjXPEmbeddingStoreReference r = new PrjXPEmbeddingStoreReference();
        r.setProjectName(projectName);
        r.setDefault(isDefault);
        return r;
    }

    private static PxChunkDao dao(PrjXPEmbeddingStoreReference ref) {
        PxChunkDao d = mock(PxChunkDao.class);
        when(d.getStoreReference()).thenReturn(ref);
        return d;
    }

    @Test
    void constructorKeepsInitialDaos() {
        PxChunkDao alpha = dao(ref("alpha", true));

        PxChunkDaoProvider provider = new PxChunkDaoProvider(List.of(alpha));

        assertThat(provider.get("alpha")).containsSame(alpha);
    }

    @Test
    void constructorAcceptsEmptyList() {
        PxChunkDaoProvider provider = new PxChunkDaoProvider(List.of());

        assertThat(provider.get("alpha")).isEmpty();
    }

    @Test
    void registerAddsDaoFoundByGet() {
        PxChunkDaoProvider provider = new PxChunkDaoProvider(List.of());

        PxChunkDao beta = dao(ref("beta", false));
        provider.register(beta);

        assertThat(provider.get("beta")).containsSame(beta);
    }

    @Test
    void unregisterByProjectRemovesOnlyThatProjectsDaos() {
        PxChunkDao alpha = dao(ref("alpha", true));
        PxChunkDao beta1 = dao(ref("beta", false));
        PxChunkDao beta2 = dao(ref("beta", false));   // a project may have several DAOs
        PxChunkDaoProvider provider = new PxChunkDaoProvider(List.of(alpha, beta1, beta2));

        provider.unregisterByProject("beta");

        assertThat(provider.get("alpha")).containsSame(alpha);
        assertThat(provider.apply(r -> "beta".equals(r.getProjectName()))).isEmpty();
    }

    @Test
    void defaultLookupStillWorksAfterRuntimeChanges() {
        PxChunkDaoProvider provider = new PxChunkDaoProvider(List.of(dao(ref("alpha", false))));

        assertThat(provider.get("default")).isEmpty();   // no default store yet

        PxChunkDao beta = dao(ref("beta", true));
        provider.register(beta);

        assertThat(provider.get("default")).containsSame(beta);
    }

    @Test
    void applyReturnsFirstMatchingDaoOrEmpty() {
        PxChunkDao alpha = dao(ref("alpha", true));
        PxChunkDao beta = dao(ref("beta", false));
        PxChunkDaoProvider provider = new PxChunkDaoProvider(List.of(alpha, beta));

        assertThat(provider.apply(r -> "beta".equals(r.getProjectName()))).containsSame(beta);
        assertThat(provider.apply(r -> false)).isEmpty();
    }

    @Test
    void getModelNameReturnsDbNameOfMatchingDao() {
        PrjXPEmbeddingStoreReference ref = ref("alpha", false);
        ref.setDbName("alpha-db");

        PxChunkDaoProvider provider = new PxChunkDaoProvider(List.of(dao(ref)));

        assertThat(provider.getModelName("alpha")).isEqualTo("alpha-db");
    }

    @Test
    void getModelNameFallsBackToDefault() {
        PxChunkDaoProvider provider = new PxChunkDaoProvider(List.of(dao(ref("alpha", false))));

        assertThat(provider.getModelName("nope")).isEqualTo("default");
    }
}
