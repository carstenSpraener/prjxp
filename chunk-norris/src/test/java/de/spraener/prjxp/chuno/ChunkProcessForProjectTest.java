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

    /**
     * Phase 06 bug fix: {@code processedFiles} is a singleton-bean field — without clearing it at the
     * start of each run, a second {@code executeForProject} for the same files filters everything out
     * (zero chunks). Critical for hub reindex.
     */
    @Test
    void secondRunReChunksTheSameFiles() throws Exception {
        Path src = tempDir.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("Hello.java"),
                "public class Hello {\n    void hi() {\n        System.out.println(\"hi\");\n    }\n}\n");

        ProjectDefinition pd = new ProjectDefinition();
        pd.setName("demo");
        pd.setRootDir(tempDir.toString());
        Path jsonl = tempDir.resolve("out.jsonl");
        pd.setJsonlFile(jsonl.toString());

        chunkProcess.executeForProject(pd);   // first run
        assertThat(Files.readString(jsonl)).contains("src/Hello.java");

        chunkProcess.executeForProject(pd);   // second run (reindex) — must re-chunk, not filter out

        String content = Files.readString(jsonl);
        assertThat(content).contains("src/Hello.java");   // the file was chunked again, not skipped
    }
}
