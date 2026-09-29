package de.spraener.prjxp.docpipe.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.common.config.PrjXPChatModelReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelConfigLoaderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ModelConfigLoader loader;

    @BeforeEach
    void setUp() {
        loader = new ModelConfigLoader(objectMapper);
    }

    private File fixtureFile(String name) throws URISyntaxException {
        return new File(
                java.util.Objects.requireNonNull(getClass().getClassLoader().getResource("fixtures/dp-project/.dp/" + name)).toURI()
        );
    }

    @Test
    void listFrom_validFixture_parsesAllReferences() throws Exception {
        List<PrjXPChatModelReference> models = loader.listFrom(fixtureFile("models.json").getAbsolutePath());

        assertThat(models).hasSize(2);
        PrjXPChatModelReference writer = models.get(0);
        assertThat(writer.getStereoType()).isEqualTo("doc-writer");
        assertThat(writer.getServerType()).isEqualTo("lm-studio");
        assertThat(writer.getModelName()).isEqualTo("qwen2.5-7b-instruct");
        assertThat(writer.getApiKey()).isEqualTo("${LM_STUDIO_API_KEY}");
        assertThat(writer.getProviderUrl()).isEqualTo("http://localhost:1234/v1");
        assertThat(writer.getTimeoutSecs()).isEqualTo(180);

        PrjXPChatModelReference reader = models.get(1);
        assertThat(reader.getStereoType()).isEqualTo("reader");
        assertThat(reader.getModelName()).isEqualTo("gpt-4o-mini");
    }

    @Test
    void listFrom_missingFile_returnsEmptyList() throws Exception {
        List<PrjXPChatModelReference> models = loader.listFrom("/does/not/exist/models.json");

        assertThat(models).isEmpty();
    }

    @Test
    void listFrom_invalidJson_throwsConfigException(@TempDir Path tempDir) throws Exception {
        File broken = tempDir.resolve("models.json").toFile();
        Files.writeString(broken.toPath(), "{ this is not valid json !!!");

        assertThatThrownBy(() -> loader.listFrom(broken.getAbsolutePath()))
                .isInstanceOf(ConfigException.class)
                .hasCauseInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }

    @Test
    void listFrom_emptyJsonArray_returnsEmptyList(@TempDir Path tempDir) throws Exception {
        File empty = tempDir.resolve("models.json").toFile();
        Files.writeString(empty.toPath(), "[]");

        assertThat(loader.listFrom(empty.getAbsolutePath())).isEmpty();
    }
}
