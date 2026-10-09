package de.spraener.prjxp.docpipe.model;

import de.spraener.prjxp.common.config.PrjXPChatModelReference;
import de.spraener.prjxp.common.config.PrjXPConfig;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DPJobTest {

    private PrjXPChatModelReference ref(String stereoType, String modelName) {
        PrjXPChatModelReference r = new PrjXPChatModelReference();
        r.setStereoType(stereoType);
        r.setModelName(modelName);
        return r;
    }

    @Test
    void emptyJob_hasEmptyCreationListDummyRootAndDummyConfig() {
        DPJob job = DPJob.EMPTY_JOB;

        assertThat(job).isNotNull();
        assertThat(job.getContentCreationList()).isEmpty();
        assertThat(job.getRootDir()).isEqualTo(new File(""));
        assertThat(job.getPxCfg()).isNotNull();
        assertThat(job.getPxCfg().getChatModels()).isEmpty();
    }

    @Test
    void gettersAndSetters_roundTrip() {
        DPJob job = new DPJob();
        File rootDir = new File("/some/root");
        PrjXPConfig cfg = mock(PrjXPConfig.class);
        DPContentCreation creation = new DPContentCreation();

        job.setRootDir(rootDir);
        job.setPxCfg(cfg);
        job.setContentCreationList(List.of(creation));

        assertThat(job.getRootDir()).isSameAs(rootDir);
        assertThat(job.getPxCfg()).isSameAs(cfg);
        assertThat(job.getContentCreationList()).containsExactly(creation);
    }

    @Test
    void equalsAndHashCode_basedOnAllFields() {
        PrjXPConfig cfg = mock(PrjXPConfig.class);
        File rootDir = new File("/some/root");

        DPJob a = new DPJob();
        a.setRootDir(rootDir);
        a.setPxCfg(cfg);
        a.setContentCreationList(List.of());

        DPJob b = new DPJob();
        b.setRootDir(new File("/some/root"));
        b.setPxCfg(cfg); // same mock instance -> equal
        b.setContentCreationList(List.of());

        DPJob otherRoot = new DPJob();
        otherRoot.setRootDir(new File("/other/root"));
        otherRoot.setPxCfg(cfg);
        otherRoot.setContentCreationList(List.of());

        assertThat(a).isEqualTo(b)
                .hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(otherRoot);
    }

    @Test
    void toString_containsFieldNames() {
        DPJob job = new DPJob();
        job.setRootDir(new File("/some/root"));

        assertThat(job.toString()).contains("rootDir");
    }

    @Test
    void getModelForStereotype_returnsMatchingReference() {
        PrjXPConfig cfg = mock(PrjXPConfig.class);
        when(cfg.getChatModels()).thenReturn(List.of(ref("reader", "gpt-4o-mini"), ref("doc-writer", "qwen2.5")));

        DPJob job = new DPJob();
        job.setPxCfg(cfg);

        Optional<PrjXPChatModelReference> found = job.getModelForStereotype("doc-writer");

        assertThat(found).isPresent();
        assertThat(found.get().getModelName()).isEqualTo("qwen2.5");
    }

    @Test
    void getModelForStereotype_returnsFirstMatch() {
        PrjXPConfig cfg = mock(PrjXPConfig.class);
        when(cfg.getChatModels()).thenReturn(List.of(ref("reader", "first"), ref("reader", "second")));

        DPJob job = new DPJob();
        job.setPxCfg(cfg);

        assertThat(job.getModelForStereotype("reader").get().getModelName()).isEqualTo("first");
    }

    @Test
    void getModelForStereotype_noMatch_returnsEmpty() {
        PrjXPConfig cfg = mock(PrjXPConfig.class);
        when(cfg.getChatModels()).thenReturn(List.of(ref("reader", "gpt-4o-mini")));

        DPJob job = new DPJob();
        job.setPxCfg(cfg);

        assertThat(job.getModelForStereotype("unknown")).isEmpty();
    }
}
