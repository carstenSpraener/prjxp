package de.spraener.prjxp.mcp.rest;

import de.spraener.prjxp.common.chat.KIChatModelProvider;
import de.spraener.prjxp.common.model.ModelLister;
import de.spraener.prjxp.mcp.setup.GlobalsTestFixture;
import de.spraener.prjxp.mcp.setup.SetupModelTestService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GlobalsFreshSnapshotRedTest {
    @TempDir Path directory;

    @Test void globalsReadsEmbeddingWrittenAfterStartupRatherThanLegacyBean() throws Exception {
        var fixture = new GlobalsTestFixture(directory);
        var service = new GlobalsService(fixture.globals, mock(SetupModelTestService.class),
                mock(KIChatModelProvider.class), mock(ModelLister.class));
        Files.writeString(fixture.yaml, Files.readString(fixture.yaml).replace("original", "after-setup-save"));
        assertThat(service.getEmbeddingModel().value().getModelName()).isEqualTo("after-setup-save");
    }
}
