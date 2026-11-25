package de.spraener.prjxp.tibed;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.common.transfer.TransferCrypto;
import de.spraener.prjxp.common.transfer.TransferPasswordResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.AbstractEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AstNeighboursLoaderTest {

    @TempDir
    Path tempDir;

    private PrjXPConfig cfg;
    private ProjectDefinition pd;
    private AstNeighboursLoader loader;
    private Path sidecar;

    @BeforeEach
    void setUp() {
        cfg = new PrjXPConfig();
        cfg.getTransfer().setPasswordEnv("PRJXP_TEST_NO_PASSWORD_XYZ");
        pd = new ProjectDefinition();
        pd.setName("p");
        pd.setRootDir(tempDir.toString());
        pd.setJsonlFile(tempDir.resolve("px-chunks.jsonl").toString());
        sidecar = tempDir.resolve("px-chunks.ast-neighbours.json");
        loader = new AstNeighboursLoader(new ObjectMapper(), resolverWith(null), cfg);
    }

    @Test
    void loadsPlaintextSidecar() throws Exception {
        Files.writeString(sidecar, "{\"chunk1\":[\"chunk2\",\"chunk3\"]}");

        Map<String, List<String>> loaded = loader.load(pd);

        assertThat(loaded).containsOnlyKeys("chunk1");
        assertThat(loaded.get("chunk1")).containsExactly("chunk2", "chunk3");
    }

    @Test
    void loadsEncryptedSidecar() throws Exception {
        loader = new AstNeighboursLoader(new ObjectMapper(), resolverWith("secret"), cfg);
        try (OutputStream out = TransferCrypto.openEncryptedOutputStream(
                Files.newOutputStream(sidecar), "secret".toCharArray())) {
            out.write("{\"a\":[\"b\"]}".getBytes(StandardCharsets.UTF_8));
        }
        assertThat(new String(Files.readAllBytes(sidecar), 0, 8, StandardCharsets.US_ASCII))
                .isEqualTo("PRJXPENC");

        assertThat(loader.load(pd)).containsEntry("a", List.of("b"));
    }

    @Test
    void encryptedSidecarWithoutPasswordReturnsEmpty() throws Exception {
        try (OutputStream out = TransferCrypto.openEncryptedOutputStream(
                Files.newOutputStream(sidecar), "secret".toCharArray())) {
            out.write("{\"a\":[\"b\"]}".getBytes(StandardCharsets.UTF_8));
        }

        assertThat(loader.load(pd)).isEmpty();
    }

    @Test
    void missingFileReturnsEmpty() {
        assertThat(Files.exists(sidecar)).isFalse();
        assertThat(loader.load(pd)).isEmpty();
    }

    @Test
    void corruptFileReturnsEmpty() throws Exception {
        Files.write(sidecar, new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9});

        assertThat(loader.load(pd)).isEmpty();
    }

    @Test
    void noGraphPathReturnsEmpty() {
        ProjectDefinition withoutFiles = new ProjectDefinition();
        withoutFiles.setName("p");

        assertThat(loader.load(withoutFiles)).isEmpty();
        assertThat(loader.load(null)).isEmpty();
    }

    @Test
    void customGraphFileNameUsesExtendedSidecarPath() throws Exception {
        pd.setGraphFile(tempDir.resolve("artifacts/graph.json").toString());
        Path customSidecar = tempDir.resolve("artifacts/graph.json.ast-neighbours.json");
        Files.createDirectories(customSidecar.getParent());
        Files.writeString(customSidecar, "{\"x\":[\"y\"]}");

        assertThat(loader.load(pd)).containsEntry("x", List.of("y"));
    }

    @Test
    void astNeighboursPathDerivesFromGraphSuffix() {
        assertThat(AstNeighboursLoader.astNeighboursPath("/p/px-chunks.graph.json"))
                .isEqualTo("/p/px-chunks.ast-neighbours.json");
        assertThat(AstNeighboursLoader.astNeighboursPath("/p/graph.json"))
                .isEqualTo("/p/graph.json.ast-neighbours.json");
    }

    private TransferPasswordResolver resolverWith(String password) {
        AbstractEnvironment env = new AbstractEnvironment() {
        };
        Map<String, Object> props = new HashMap<>();
        if (password != null) {
            props.put(cfg.getTransfer().getPasswordEnv(), password);
        }
        env.getPropertySources().addFirst(new MapPropertySource("test", props));
        return new TransferPasswordResolver(env);
    }
}
