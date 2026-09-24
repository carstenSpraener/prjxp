package de.spraener.prjxp.chuno;

import de.spraener.prjxp.common.config.ProjectDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 01 (DockerHub): {@link ChunkProcess#executeForProject(ProjectDefinition)}
 * must run the chunking pipeline for an explicitly given project definition,
 * independent of the configured active project (hub in-process use).
 */
@SpringBootTest(properties = {
        "prjxp.cli.enabled=false"
})
class ChunkProcessForProjectTest {

    @Autowired
    ChunkProcess chunkProcess;

    @TempDir
    Path tempDir;

    @Test
    void executeForProjectWritesJsonlWithFileRelativeToRootDir() throws Exception {
        Path src = tempDir.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("Hello.java"),
                "public class Hello {\n    void hi() {\n        System.out.println(\"hi\");\n    }\n}\n");

        ProjectDefinition pd = new ProjectDefinition();
        pd.setName("demo");
        pd.setRootDir(tempDir.toString());
        Path jsonl = tempDir.resolve("out.jsonl");
        pd.setJsonlFile(jsonl.toString());

        chunkProcess.executeForProject(pd);

        assertThat(Files.exists(jsonl)).isTrue();
        String content = Files.readString(jsonl);
        assertThat(content).contains("Hello.java");
        // the chunk's file must be relative to rootDir, not an absolute path
        assertThat(content).contains("src/Hello.java");
        assertThat(content).doesNotContain(tempDir.toString());
    }
}
