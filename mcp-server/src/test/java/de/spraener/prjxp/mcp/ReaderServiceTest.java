package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.model.FileView;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.ScoredChunk;
import de.spraener.prjxp.common.reader.FileViewProvider;
import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReaderServiceTest {

    private static final String JAVA_MIME = "text/x-java-code";

    @Mock
    private PxChunkDao dao;
    @Mock
    private PxChunkDaoProvider daoProvider;
    @Mock
    private ProjectRegistry projectRegistry;

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private static PxChunk part(String id, int part, int total, String file, String mime,
                                String fromLine, String toLine, String content) {
        return PxChunk.create(c -> {
            c.setId(id);
            c.setPart(part);
            c.setTotal(total);
            c.setFile(file);
            c.setMimeType(mime);
            c.setFromLine(fromLine);
            c.setToLine(toLine);
            c.setOverlap(0);
            c.setSize(content.length());
            c.setContent(content);
        });
    }

    private static ScoredChunk scored(PxChunk chunk) {
        return new ScoredChunk(chunk, 1.0);
    }

    /** Deterministic stand-in for the Java reader: joins the combined unit contents with newlines. */
    private static FileViewProvider javaProvider() {
        return new FileViewProvider() {
            @Override
            public String mimeType() {
                return JAVA_MIME;
            }

            @Override
            public String language() {
                return "java";
            }

            @Override
            public String render(List<PxChunk> units) {
                return units.stream().map(PxChunk::getContent).collect(Collectors.joining("\n"));
            }
        };
    }

    private ReaderService service() {
        PrjXPConfig cfg = new PrjXPConfig();
        return new ReaderService(daoProvider, cfg, new FileViewRegistry(List.of(javaProvider())), projectRegistry);
    }

    private ReaderService service(int maxOutputChars) {
        PrjXPConfig cfg = new PrjXPConfig();
        cfg.setReaderMaxOutputChars(maxOutputChars);
        return new ReaderService(daoProvider, cfg, new FileViewRegistry(List.of(javaProvider())), projectRegistry);
    }

    /** project=null -> registry resolves to active project "cwd" (PrjXPConfig default) -> resolveDao asks the provider for "cwd". */
    private void routeToDao() {
        when(projectRegistry.resolve(null)).thenReturn("cwd");
        when(daoProvider.get("cwd")).thenReturn(Optional.of(dao));
    }

    /** Index behavior: exact term queries answer per their file value, the empty (MatchAll) query answers matchAll. */
    private void stubIndex(Map<String, String> exactTermFile, List<ScoredChunk> exactHits, List<ScoredChunk> matchAll) {
        when(dao.searchByIndex(anyMap(), anyInt())).thenAnswer(inv -> {
            Map<String, String> filters = inv.getArgument(0);
            if (filters.isEmpty()) {
                return matchAll;
            }
            if (exactTermFile != null && exactTermFile.equals(filters)) {
                return exactHits;
            }
            return List.of();
        });
    }

    // ------------------------------------------------------------------
    // tests
    // ------------------------------------------------------------------

    @Test
    void exactMatchWithLeadingSlashInIndex() {
        routeToDao();
        // the index stores rootDir-relative paths WITH leading slash; the two parts belong to ONE unit
        PxChunk p0 = part("u1", 0, 2, "/src/Foo.java", JAVA_MIME, "1", "3", "class Foo {");
        PxChunk p1 = part("u1", 1, 2, "/src/Foo.java", JAVA_MIME, "3", "5", "\n}");
        stubIndex(Map.of(PxChunk.PXCHUNK_FILE, "/src/Foo.java"),
                List.of(scored(p0), scored(p1)), List.of());

        FileView view = service().read("src/Foo.java", null, null, null);

        assertThat(view.file()).isEqualTo("src/Foo.java");
        assertThat(view.language()).isEqualTo("java");
        assertThat(view.content()).isEqualTo("class Foo {\n}");
        assertThat(view.chunkCount()).isEqualTo(2);
        assertThat(view.truncated()).isFalse();
        assertThat(view.totalLines()).isEqualTo(2);
        assertThat(view.returnedLines()).isEqualTo(2);
        assertThat(view.error()).isNull();
    }

    @Test
    void basenameMatchViaMatchAllFallback() {
        routeToDao();
        stubIndex(null,
                List.of(),
                List.of(
                        scored(part("u1", 0, 1, "/a/b/Foo.java", JAVA_MIME, "1", "2", "FOO")),
                        scored(part("u2", 0, 1, "/c/Foo2.java", JAVA_MIME, "1", "2", "FOO2"))));

        FileView view = service().read("Foo.java", null, null, null);

        // exactly /a/b/Foo.java is selected (content proves it; Foo2 is not part of the view)
        assertThat(view.error()).isNull();
        assertThat(view.language()).isEqualTo("java");
        assertThat(view.content()).isEqualTo("FOO");
        assertThat(view.candidates()).isNull(); // success view carries no candidates
    }

    @Test
    void suffixMatchViaMatchAllFallback() {
        routeToDao();
        stubIndex(null,
                List.of(),
                List.of(scored(part("u1", 0, 1, "/a/b/Foo.java", JAVA_MIME, "1", "2", "SUFFIX"))));

        FileView view = service().read("b/Foo.java", null, null, null);

        assertThat(view.error()).isNull();
        assertThat(view.content()).isEqualTo("SUFFIX");
    }

    @Test
    void ambiguousPathReturnsCandidates() {
        routeToDao();
        stubIndex(null,
                List.of(),
                List.of(
                        scored(part("u1", 0, 1, "/a/Foo.java", JAVA_MIME, "1", "2", "A")),
                        scored(part("u2", 0, 1, "/b/Foo.java", JAVA_MIME, "1", "2", "B"))));

        FileView view = service().read("Foo.java", null, null, null);

        assertThat(view.content()).isNull();
        assertThat(view.candidates()).containsExactly("a/Foo.java", "b/Foo.java");
        assertThat(view.error()).contains("candidates");
    }

    @Test
    void fileNotFoundReturnsError() {
        routeToDao();
        stubIndex(null,
                List.of(),
                List.of(scored(part("u1", 0, 1, "/a/Other.java", JAVA_MIME, "1", "2", "OTHER"))));

        FileView view = service().read("Nope.java", null, null, null);

        assertThat(view.content()).isNull();
        assertThat(view.candidates()).isEmpty();
        assertThat(view.error()).contains("Nope.java");
    }

    @Test
    void noDaoReturnsError() {
        when(projectRegistry.resolve(null)).thenReturn("cwd");
        when(daoProvider.get(anyString())).thenReturn(Optional.empty());

        FileView view = service().read("src/Foo.java", null, null, null);

        assertThat(view.content()).isNull();
        assertThat(view.error()).contains("No embedding store");
    }

    @Test
    void unknownProjectThrowsUnknownProjectException() {
        doThrow(new UnknownProjectException("nope", List.of("alpha"))).when(projectRegistry).ensureSearchable("nope");

        assertThatThrownBy(() -> service().read("src/Foo.java", "nope", null, null))
                .isInstanceOf(UnknownProjectException.class);

        verify(daoProvider, never()).get(anyString());
    }

    @Test
    void unsupportedStoreReturnsError() {
        routeToDao();
        when(dao.searchByIndex(anyMap(), anyInt()))
                .thenThrow(new UnsupportedOperationException("index search not available"));

        FileView view = service().read("src/Foo.java", null, null, null);

        assertThat(view.content()).isNull();
        assertThat(view.error()).contains("Lucene");
    }

    @Test
    void offsetAndLimitSliceLines() {
        routeToDao();
        String tenLines = "L1\nL2\nL3\nL4\nL5\nL6\nL7\nL8\nL9\nL10";
        PxChunk unit = part("u1", 0, 1, "/src/Foo.java", JAVA_MIME, "1", "10", tenLines);
        stubIndex(Map.of(PxChunk.PXCHUNK_FILE, "/src/Foo.java"),
                List.of(scored(unit)), List.of());

        FileView view = service().read("src/Foo.java", null, 3, 4);

        assertThat(view.totalLines()).isEqualTo(10);
        assertThat(view.returnedLines()).isEqualTo(4);
        assertThat(view.content()).isEqualTo("L3\nL4\nL5\nL6");
        assertThat(view.truncated()).isFalse();
    }

    @Test
    void capTruncatesContent() {
        routeToDao();
        String tenLines = "L1\nL2\nL3\nL4\nL5\nL6\nL7\nL8\nL9\nL10"; // 30 chars
        PxChunk unit = part("u1", 0, 1, "/src/Foo.java", JAVA_MIME, "1", "10", tenLines);
        stubIndex(Map.of(PxChunk.PXCHUNK_FILE, "/src/Foo.java"),
                List.of(scored(unit)), List.of());

        FileView view = service(10).read("src/Foo.java", null, null, null);

        // 10 chars of "L1\nL2\nL3\nL4\nL5..." = "L1\nL2\nL3\nL"
        assertThat(view.content()).hasSizeLessThanOrEqualTo(10);
        assertThat(view.content()).isEqualTo("L1\nL2\nL3\nL");
        assertThat(view.truncated()).isTrue();
    }
}
