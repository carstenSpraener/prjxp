package de.spraener.prjxp.docpipe.prompt;
import com.github.jknack.handlebars.HandlebarsException;
import com.github.jknack.handlebars.Options;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.docpipe.config.DotDPFilesService;
import de.spraener.prjxp.docpipe.content.ContentCreationTask;
import de.spraener.prjxp.docpipe.model.DPContentCreation;
import de.spraener.prjxp.docpipe.model.DPJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;
class PromptResolvingServiceTest {
    private PromptResolvingService service;
    @BeforeEach
    void setUp() {
        DotDPFilesService dpFilesService = new DotDPFilesService();
        List<TemplateResolver> resolvers = List.of(new SourceDumpResolver());
        service = new PromptResolvingService(resolvers, dpFilesService);
    }
    @Test
    void testResolveWithSourceDump() throws Exception {
        File testProjectDir = new File(
                Objects.requireNonNull(getClass().getClassLoader().getResource("test-project")).toURI()
        );
        DPJob job = new DPJob();
        job.setRootDir(testProjectDir);
        PrjXPConfig cfg = new PrjXPConfig();
        cfg.setChatModels(List.of());
        job.setPxCfg(cfg);
        job.setContentCreationList(List.of());
        DPContentCreation dpcc = new DPContentCreation();
        dpcc.setPrompt("test-prompt.txt");
        dpcc.setOutputFile("output.md");
        ContentCreationTask task = new ContentCreationTask(job, dpcc);
        String result = service.resolve(task);
        assertNotNull(result);
        assertTrue(result.contains("Hier ist der Source-Code:"), "Should contain the template text");
        assertTrue(result.contains("public class Hello"), "Should contain the dumped Java source");
        assertTrue(result.contains("Hello World"), "Should contain content from Hello.java");
    }

    @Test
    public void testKiGbauAAPromptCreation() throws Exception {
        // Portable stand-in for the original KiGbau project (which lived at a local
        // Windows path and silently skipped in CI): a mini fixture with the same shape —
        // an ArchitectureAssessment prompt that dumps the Java sources of package de.db.kigbau.
        File testProjectDir = new File(
                Objects.requireNonNull(getClass().getClassLoader().getResource("fixtures/kigbau-like")).toURI()
        );
        DPJob job = new DPJob();
        job.setRootDir(testProjectDir);
        PrjXPConfig cfg = new PrjXPConfig();
        cfg.setChatModels(List.of());
        job.setPxCfg(cfg);
        job.setContentCreationList(List.of());
        DPContentCreation dpcc = new DPContentCreation();
        dpcc.setPrompt("ArchitectureAssessment.prompt.txt");
        dpcc.setOutputFile("output.md");
        ContentCreationTask task = new ContentCreationTask(job, dpcc);
        String result = service.resolve(task);
        assertNotNull(result);
        assertTrue(result.contains("Here is the complete codebase of the project for your analysis:"), "Should contain the template text");
        assertTrue(result.contains("```java"), "Should contain dumped Java code blocks");
        assertTrue(result.contains("package de.db.kigbau"), "Should contain KiGbau Java source");

    }

    @TempDir
    Path tempProject;

    /**
     * A resolver whose resolve() always fails, used to trigger the TRHelper error path.
     */
    private static class ThrowingResolver implements TemplateResolver {
        @Override
        public String getID() {
            return "boom";
        }

        @Override
        public String resolve(File baseDir, Object context, Options options) {
            throw new IllegalStateException("kaboom");
        }
    }

    @Test
    void resolverFailureIsWrappedInTemplateException() throws Exception {
        PromptResolvingService svc = new PromptResolvingService(List.of(new ThrowingResolver()), null);

        assertThatThrownBy(() -> svc.resolve(new DPContentCreation(), "{{boom}}", tempProject.toFile()))
                .isInstanceOf(HandlebarsException.class)
                .hasMessageContaining("TemplateException")
                .hasCauseInstanceOf(TemplateException.class);
    }

    @Test
    void storesPromptFileWhenStorePromptIsConfigured() throws Exception {
        File root = tempProject.toFile();
        Files.createDirectories(root.toPath().resolve(".dp"));
        Files.writeString(root.toPath().resolve(".dp").resolve("my-prompt.txt"), "Hello {{name}}!");

        DPJob job = new DPJob();
        job.setRootDir(root);
        PrjXPConfig cfg = new PrjXPConfig();
        cfg.setChatModels(List.of());
        job.setPxCfg(cfg);
        job.setContentCreationList(List.of());

        DPContentCreation dpcc = new DPContentCreation();
        dpcc.setPrompt("my-prompt.txt");
        dpcc.setOutputFile("output.md");
        File storePrompt = tempProject.resolve("stored-prompt.txt").toFile();
        dpcc.setStorePrompt(storePrompt.getAbsolutePath());
        dpcc.setArgs(Map.of("name", "World"));

        ContentCreationTask task = new ContentCreationTask(job, dpcc);
        String result = service.resolve(task);

        assertThat(result).isEqualTo("Hello World!");
        assertThat(storePrompt).exists();
        assertThat(Files.readString(storePrompt.toPath())).isEqualTo("Hello World!");
    }

    @Test
    void doesNotStorePromptWhenStorePromptIsBlank() throws Exception {
        File root = tempProject.toFile();
        Files.createDirectories(root.toPath().resolve(".dp"));
        Files.writeString(root.toPath().resolve(".dp").resolve("my-prompt.txt"), "Static prompt");

        DPJob job = new DPJob();
        job.setRootDir(root);
        PrjXPConfig cfg = new PrjXPConfig();
        cfg.setChatModels(List.of());
        job.setPxCfg(cfg);
        job.setContentCreationList(List.of());

        DPContentCreation dpcc = new DPContentCreation();
        dpcc.setPrompt("my-prompt.txt");
        dpcc.setOutputFile("output.md");

        ContentCreationTask task = new ContentCreationTask(job, dpcc);
        String result = service.resolve(task);

        assertThat(result).isEqualTo("Static prompt");
    }
}
