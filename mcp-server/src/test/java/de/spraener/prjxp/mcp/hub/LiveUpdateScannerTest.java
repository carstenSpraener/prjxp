package de.spraener.prjxp.mcp.hub;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.chuno.veto.VetoRegistry;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.lucene.LuceneEmbeddingStore;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Scanner behavior against a REAL Lucene index (8-dim) and a real temp source tree;
 * only the {@link VetoRegistry} is mocked (default: nothing is vetoed).
 */
class LiveUpdateScannerTest {

    @TempDir
    Path tempRoot;

    private Path projectRoot;
    private Path liveJsonlDir;
    private Path indexDir;
    private HubProperties props;
    private LuceneEmbeddingStore luceneStore;
    private LiveUpdateScanner scanner;

    @BeforeEach
    void setUp() throws Exception {
        projectRoot = Files.createDirectories(tempRoot.resolve("project"));
        liveJsonlDir = Files.createDirectories(tempRoot.resolve("chunks"));
        indexDir = Files.createDirectories(tempRoot.resolve("index"));

        props = new HubProperties();
        props.setLiveJsonlDir(liveJsonlDir.toString());

        luceneStore = new LuceneEmbeddingStore(indexDir, 8);
        VetoRegistry vetoRegistry = mock(VetoRegistry.class);   // shouldVeto -> false (everything passes)
        scanner = new LiveUpdateScanner(props, vetoRegistry, luceneStore, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        luceneStore.close();   // release the Lucene write lock so the temp dir can be cleaned up
    }

    private ProjectEntry entry(String name) {
        ProjectDefinition def = new ProjectDefinition();
        def.setName(name);
        def.setRootDir(projectRoot.toString());
        return new ProjectEntry(name, projectRoot, ProjectEntry.Kind.LIVE, "live", def, null);
    }

    private Path writeDiskFile(String relPath, long mtimeMillis) throws Exception {
        Path p = projectRoot.resolve(relPath);
        Files.createDirectories(p.getParent());
        Files.writeString(p, "content of " + relPath);
        Files.setLastModifiedTime(p, FileTime.fromMillis(mtimeMillis));
        return p;
    }

    private void addChunk(String project, String file, String id, Map<String, String> extraMeta) {
        Metadata m = new Metadata();
        m.put("pxchunk_id", id);
        m.put("pxchunk_project", project);
        m.put("pxchunk_file", file);
        if (extraMeta != null) {
            extraMeta.forEach(m::put);
        }
        luceneStore.add(Embedding.from(new float[]{1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f}),
                TextSegment.from("content-" + id, m));
    }

    private Path liveJsonlPath(String name) {
        return liveJsonlDir.resolve(name + ".live-embedded.jsonl");
    }

    private void writeJsonlWatermark(String name, long generatedAt) throws Exception {
        Files.writeString(liveJsonlPath(name),
                "{\"id\":\"c1\",\"file\":\"a.java\",\"project\":\"" + name + "\",\"generatedAt\":" + generatedAt + "}\n");
    }

    @Test
    void scan_liveUpdateDisabled_returnsEmpty() {
        ProjectEntry entry = entry("p");
        entry.getDefinition().setLiveUpdate(false);

        LiveUpdateScanner.ScanResult result = scanner.scan(entry);

        assertThat(result.isEmpty()).isTrue();
        assertThat(result.generatedAt()).isZero();
    }

    @Test
    void scan_noJsonl_fullUpdate() throws Exception {
        writeDiskFile("a.java", 1_000);
        writeDiskFile("b.java", 2_000);
        addChunk("p", "a.java", "c1", null);
        addChunk("p", "c.java", "c2", null);   // in index, gone from disk

        LiveUpdateScanner.ScanResult result = scanner.scan(entry("p"));

        assertThat(result.generatedAt()).isZero();
        assertThat(result.toEmbed()).containsExactlyInAnyOrder("a.java", "b.java");
        assertThat(result.toDelete()).containsExactly("c.java");
    }

    @Test
    void scan_changedFile_embedded() throws Exception {
        writeJsonlWatermark("p", 1_000);
        writeDiskFile("a.java", 2_000);   // mtime > watermark
        addChunk("p", "a.java", "c1", null);

        LiveUpdateScanner.ScanResult result = scanner.scan(entry("p"));

        assertThat(result.generatedAt()).isEqualTo(1_000);
        assertThat(result.toEmbed()).containsExactly("a.java");
        assertThat(result.toDelete()).isEmpty();
    }

    @Test
    void scan_unchangedFile_skipped() throws Exception {
        writeJsonlWatermark("p", 2_000);
        writeDiskFile("a.java", 1_000);   // mtime < watermark
        addChunk("p", "a.java", "c1", null);

        LiveUpdateScanner.ScanResult result = scanner.scan(entry("p"));

        assertThat(result.toEmbed()).isEmpty();
        assertThat(result.toDelete()).isEmpty();
    }

    @Test
    void scan_newFile_embedded() throws Exception {
        writeJsonlWatermark("p", 2_000);
        writeDiskFile("a.java", 1_000);   // mtime < watermark, but not in the index

        LiveUpdateScanner.ScanResult result = scanner.scan(entry("p"));

        assertThat(result.toEmbed()).containsExactly("a.java");
        assertThat(result.toDelete()).isEmpty();
    }

    @Test
    void scan_futureMtimeAlreadyCaptured_isNotRepeated() throws Exception {
        writeDiskFile("a.java", System.currentTimeMillis() + 86_400_000); // one day ahead (clock skew)
        addChunk("p", "a.java", "c1", null);
        ProjectEntry entry = entry("p");
        scanner.publishSourceState(entry, scanner.captureSourceState(entry));

        LiveUpdateScanner.ScanResult result = scanner.scan(entry);

        assertThat(result.toEmbed()).isEmpty();
        assertThat(result.toDelete()).isEmpty();
    }

    @Test
    void scan_publishedEmptyState_doesNotBecomeFullUpdate() throws Exception {
        ProjectEntry entry = entry("p");
        scanner.publishSourceState(entry, Map.of());

        LiveUpdateScanner.ScanResult result = scanner.scan(entry);

        assertThat(result.generatedAt()).isZero();
        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void scan_deletedFile_deleted() throws Exception {
        writeJsonlWatermark("p", 1_000);
        addChunk("p", "a.java", "c1", null);   // in index, not on disk

        LiveUpdateScanner.ScanResult result = scanner.scan(entry("p"));

        assertThat(result.toEmbed()).isEmpty();
        assertThat(result.toDelete()).containsExactly("a.java");
    }

    @Test
    void scan_docPipeFile_notDeleted() throws Exception {
        writeJsonlWatermark("p", 1_000);
        addChunk("p", "a.java", "c1",
                Map.of(PxChunk.metadataFieldKey(DocumentSource.DOCPIPE_GENERATED), "true"));

        LiveUpdateScanner.ScanResult result = scanner.scan(entry("p"));

        assertThat(result.toDelete()).isEmpty();
    }

    @Test
    void readWatermark_readsFirstLine() throws Exception {
        Files.writeString(liveJsonlPath("p"),
                "{\"id\":\"c1\",\"generatedAt\":1234}\n"
                        + "{\"id\":\"c2\",\"generatedAt\":9999}\n");

        LiveUpdateScanner.ScanResult result = scanner.scan(entry("p"));

        assertThat(result.generatedAt()).isEqualTo(1234);
    }
}
