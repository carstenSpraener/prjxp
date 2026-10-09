package de.spraener.prjxp.docpipe.content;

import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.docpipe.config.DotDPFilesService;
import de.spraener.prjxp.docpipe.model.DPContentCreation;
import de.spraener.prjxp.docpipe.model.DPJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link ContentUpdateRequiredController} using a real {@link DotDPFilesService}
 * and a temporary project root. The updater {@link Runnable} acts as the test seam:
 * an {@link AtomicInteger} records whether it was invoked.
 */
class ContentUpdateRequiredControllerTest {

    @TempDir
    Path tempDir;

    private DotDPFilesService dpFilesService;
    private ContentUpdateRequiredController controller;

    @BeforeEach
    void setUp() {
        dpFilesService = new DotDPFilesService();
        controller = new ContentUpdateRequiredController(dpFilesService);
    }

    private ContentCreationTask task(String outputFile) {
        DPJob job = new DPJob();
        job.setRootDir(tempDir.toFile());
        job.setPxCfg(mock(PrjXPConfig.class));

        DPContentCreation creation = new DPContentCreation();
        creation.setOutputFile(outputFile);

        return new ContentCreationTask(job, creation);
    }

    private Path hashFile() {
        return tempDir.resolve(DotDPFilesService.DP_DIR).resolve("content-hashes.properties");
    }

    private String sha256(String input) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    /** Writes the hash file with all given key/hash pairs in a single store call. */
    private void writeHashFile(String... keyHashPairs) throws Exception {
        Files.createDirectories(hashFile().getParent());
        Properties props = new Properties();
        for (int i = 0; i < keyHashPairs.length; i += 2) {
            props.setProperty(keyHashPairs[i], keyHashPairs[i + 1]);
        }
        try (FileOutputStream fos = new FileOutputStream(hashFile().toFile())) {
            props.store(fos, "test fixture");
        }
    }

    private Properties readHashFile() throws Exception {
        Properties props = new Properties();
        props.load(Files.newInputStream(hashFile()));
        return props;
    }

    @Test
    void onUpdateRequired_noHashFileYet_runsUpdaterAndStoresNewHash() throws Exception {
        AtomicInteger runs = new AtomicInteger();

        controller.onUpdateRequired("prompt v1", task("out.md"), runs::incrementAndGet);

        assertThat(runs).hasValue(1);
        assertThat(hashFile()).exists();
        assertThat(readHashFile().getProperty("out.md")).isEqualTo(sha256("prompt v1"));
    }

    @Test
    void onUpdateRequired_storedHashMatchesPrompt_skipsUpdater() throws Exception {
        writeHashFile("out.md", sha256("prompt v1"));
        AtomicInteger runs = new AtomicInteger();

        controller.onUpdateRequired("prompt v1", task("out.md"), runs::incrementAndGet);

        assertThat(runs).hasValue(0);
    }

    @Test
    void onUpdateRequired_promptChanged_runsUpdaterAndOverwritesHashKeepingOtherEntries() throws Exception {
        writeHashFile("out.md", sha256("old prompt"), "other.md", "unchanged-entry");
        AtomicInteger runs = new AtomicInteger();

        controller.onUpdateRequired("new prompt", task("out.md"), runs::incrementAndGet);

        assertThat(runs).hasValue(1);
        Properties stored = readHashFile();
        assertThat(stored.getProperty("out.md")).isEqualTo(sha256("new prompt"));
        assertThat(stored.getProperty("other.md")).isEqualTo("unchanged-entry");
    }

    @Test
    void onUpdateRequired_hashFileExistsButNoEntryForThisOutput_runsUpdater() throws Exception {
        writeHashFile("other.md", "some-stored-hash");
        AtomicInteger runs = new AtomicInteger();

        controller.onUpdateRequired("prompt", task("out.md"), runs::incrementAndGet);

        assertThat(runs).hasValue(1);
    }

    @Test
    void onUpdateRequired_hashFileIsADirectory_throwsIllegalStateException() throws Exception {
        // Make the hash file path a directory: loading it fails with an IOException,
        // so readEntry reports "no stored hash" and writeEntry must fail hard.
        Files.createDirectories(hashFile());
        AtomicInteger runs = new AtomicInteger();

        assertThatThrownBy(() -> controller.onUpdateRequired("prompt", task("out.md"), runs::incrementAndGet))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("out.md");

        assertThat(runs).hasValue(1);
    }
}
