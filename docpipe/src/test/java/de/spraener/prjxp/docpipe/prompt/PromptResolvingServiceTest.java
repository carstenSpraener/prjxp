package de.spraener.prjxp.docpipe.prompt;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.docpipe.config.DotDPFilesService;
import de.spraener.prjxp.docpipe.content.ContentCreationTask;
import de.spraener.prjxp.docpipe.model.DPContentCreation;
import de.spraener.prjxp.docpipe.model.DPJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.io.File;
import java.util.List;
import java.util.Objects;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
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
        File testProjectDir = new File("D:\\Projekte\\kigbau\\impl\\kigbau-jar\\doc");
        assumeTrue(testProjectDir.exists(), "Local KiGbau checkout is not available");
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
}
