package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.language.LanguagePack;
import de.spraener.prjxp.common.language.LanguagePackRegistry;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.ScoredChunk;
import de.spraener.prjxp.common.model.SearchHit;
import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import de.spraener.prjxp.common.retrieval.GoldenRetriever;
import de.spraener.prjxp.common.retrieval.SearchParams;
import de.spraener.prjxp.gldrtrvr.RetrieverRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GrepSearchServiceTest {

    @Mock
    PxChunkDaoProvider provider;

    @Mock
    PxChunkDao dao;

    @Mock
    ProjectRegistry projectRegistry;

    @Mock
    RetrieverRegistry retrieverRegistry;

    @Mock
    GoldenRetriever retriever;

    GrepSearchService service;

    LanguagePack javaPack = new LanguagePack() {
        @Override public String language() { return "java"; }
        @Override public String mimeType() { return "text/x-java-code"; }
    };

    LanguagePack tsPack = new LanguagePack() {
        @Override public String language() { return "typescript"; }
        @Override public String mimeType() { return "text/x-typescript-code"; }
    };

    @BeforeEach void setUp() {
        // No stubbing for retrieverRegistry.all(): Mockito's default answer is an empty List,
        // and most tests never reach the retriever loop (strict stubs would reject an unused stub).
        service = new GrepSearchService(provider, retrieverRegistry,
                new LanguagePackRegistry(List.of(javaPack), List.of()), projectRegistry);
    }

    /** The FULLTEXT exclusion filter that every grep search carries (raw Lucene field name). */
    private static final Map<String, String> FULLTEXT_FILTER =
            Map.of(PxChunk.PXCHUNK_METADATA + "." + PxChunk.PXCHUNK_TYPE, "!" + PxChunk.FULLTEXT_MARKER);

    @Test
    void passesQueryProjectLanguageAndLimitToDao() {
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(provider.get("myproj")).thenReturn(Optional.of(dao));

        service.search("ChunkProcess", "myproj", "java", 10);

        // limit=10 -> overscan window min(max(10*4, 40), 500) = 40 raw chunks
        verify(dao).searchFullText(
                eq("ChunkProcess"),
                eq(Map.of(PxChunk.PXCHUNK_MIME_TYPE, "text/x-java-code",
                        PxChunk.PXCHUNK_METADATA + "." + PxChunk.PXCHUNK_TYPE, "!" + PxChunk.FULLTEXT_MARKER)),
                eq(40));
    }

    @Test
    void fulltextExclusionFilterPresentEvenWithoutLanguage() {
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(provider.get("myproj")).thenReturn(Optional.of(dao));

        service.search("q", "myproj", null, 10);

        verify(dao).searchFullText(eq("q"), eq(FULLTEXT_FILTER), eq(40));
    }

    @Test
    void defaultProjectUsesDefaultStore() {
        when(projectRegistry.resolve("default")).thenReturn("default");
        when(provider.get("default")).thenReturn(Optional.of(dao));
        when(dao.searchFullText(anyString(), anyMap(), anyInt())).thenReturn(List.of());

        service.search("q", "default", null, 10);

        verify(provider).get("default");
    }

    @Test
    void blankProjectResolvesToActiveProject() {
        when(projectRegistry.resolve("   ")).thenReturn("cwd");
        when(provider.get("cwd")).thenReturn(Optional.of(dao));
        when(dao.searchFullText(anyString(), anyMap(), anyInt())).thenReturn(List.of());

        service.search("q", "   ", null, 10);

        verify(provider).get("cwd");
    }

    @Test
    void unknownProjectThrowsUnknownProjectException() {
        doThrow(new UnknownProjectException("nope", List.of("alpha"))).when(projectRegistry).ensureSearchable("nope");

        assertThatThrownBy(() -> service.search("q", "nope", null, 10))
                .isInstanceOf(UnknownProjectException.class);

        verifyNoInteractions(provider);
        verifyNoInteractions(dao);
    }

    @Test
    void knownProjectWithoutStoreReturnsEmptyList() {
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(provider.get("myproj")).thenReturn(Optional.empty());

        List<SearchHit> hits = service.search("q", "myproj", null, 10);

        assertThat(hits).isEmpty();
    }

    @Test
    void resultsMappedToSearchHitWithGrepSource() {
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(provider.get("myproj")).thenReturn(Optional.of(dao));

        PxChunk chunk = PxChunk.create(c -> {
            c.setId("chunk-1");
            c.setFile("src/A.java");
            c.setFromLine("10");
            c.setToLine("25");
        });
        chunk.getMetadata().put("java_code_section", "method");

        when(dao.searchFullText(anyString(), anyMap(), anyInt()))
                .thenReturn(List.of(new ScoredChunk(chunk, 3.25)));

        List<SearchHit> hits = service.search("ChunkProcess", "myproj", null, 10);

        assertThat(hits).hasSize(1);
        SearchHit hit = hits.get(0);
        assertThat(hit.chunkId()).isEqualTo("src/A.java");
        assertThat(hit.score()).isEqualTo(3.25);
        assertThat(hit.file()).isEqualTo("src/A.java");
        assertThat(hit.lineFrom()).isNull();
        assertThat(hit.lineTo()).isNull();
        assertThat(hit.snippet()).isEmpty();
        assertThat(hit.source()).isEqualTo("grep");
        assertThat(hit.metadata()).isEmpty();
    }

    @Test
    void unsupportedStoreReturnsEmptyList() {
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(provider.get("myproj")).thenReturn(Optional.of(dao));
        when(dao.searchFullText(anyString(), anyMap(), anyInt()))
                .thenThrow(new UnsupportedOperationException("no lucene"));

        List<SearchHit> hits = service.search("q", "myproj", null, 10);

        assertThat(hits).isEmpty();
    }

    @Test
    void unmappedLanguageUsedAsMimeType() {
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(provider.get("myproj")).thenReturn(Optional.of(dao));

        service.search("q", "myproj", "text/x-cobol", 10);

        verify(dao).searchFullText(
                eq("q"),
                eq(Map.of(PxChunk.PXCHUNK_MIME_TYPE, "text/x-cobol",
                        PxChunk.PXCHUNK_METADATA + "." + PxChunk.PXCHUNK_TYPE, "!" + PxChunk.FULLTEXT_MARKER)),
                eq(40));
    }

    @Test
    void snippetContainsMatchedQueryPart() {
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(provider.get("myproj")).thenReturn(Optional.of(dao));

        String content = "x".repeat(300) + "ChunkProcess" + "y".repeat(300);
        PxChunk chunk = PxChunk.create(c -> {
            c.setId("c1");
            c.setFile("src/A.java");
            c.setContent(content);
        });

        when(dao.searchFullText(anyString(), anyMap(), anyInt()))
                .thenReturn(List.of(new ScoredChunk(chunk, 1.0)));

        when(retrieverRegistry.all()).thenReturn(List.of(retriever));
        when(retriever.buildPromptForFindings(anyString(), anyList(), any(SearchParams.class)))
                .thenReturn(new StringBuilder("...ChunkProcess..."));

        SearchHit hit = service.search("ChunkProcess", "myproj", null, 10).get(0);

        assertThat(hit.snippet()).contains("ChunkProcess");
        assertThat(hit.snippet().length()).isLessThan(content.length());
    }

    // ---- new tests for Phase 04 ----

    @Test
    void tsAliasResolvesToTypescriptMimeType() {
        service = new GrepSearchService(provider, retrieverRegistry,
                new LanguagePackRegistry(List.of(javaPack, tsPack), List.of()), projectRegistry);

        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(provider.get("myproj")).thenReturn(Optional.of(dao));

        service.search("q", "myproj", "ts", 10);

        verify(dao).searchFullText(
                eq("q"),
                eq(Map.of(PxChunk.PXCHUNK_MIME_TYPE, "text/x-typescript-code",
                        PxChunk.PXCHUNK_METADATA + "." + PxChunk.PXCHUNK_TYPE, "!" + PxChunk.FULLTEXT_MARKER)),
                eq(40));
    }

    @Test
    void cobolLanguageResolvesFromRegistry() {
        // TestLanguagePack (cobol) is part of the registry — the old ServiceLoader mechanism is gone
        service = new GrepSearchService(provider, retrieverRegistry,
                new LanguagePackRegistry(List.of(new TestLanguagePack()), List.of()), projectRegistry);

        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(provider.get("myproj")).thenReturn(Optional.of(dao));

        service.search("q", "myproj", "cobol", 10);

        verify(dao).searchFullText(
                eq("q"),
                eq(Map.of(PxChunk.PXCHUNK_MIME_TYPE, "text/x-cobol",
                        PxChunk.PXCHUNK_METADATA + "." + PxChunk.PXCHUNK_TYPE, "!" + PxChunk.FULLTEXT_MARKER)),
                eq(40));
    }

    @Test
    void pluginPackMimeResolvesFromRegistry() {
        LanguagePack pluginPack = new LanguagePack() {
            @Override public String language() { return "goodlang"; }
            @Override public String mimeType() { return "text/x-goodlang-code"; }
        };
        service = new GrepSearchService(provider, retrieverRegistry,
                new LanguagePackRegistry(List.of(javaPack), List.of(pluginPack)), projectRegistry);

        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(provider.get("myproj")).thenReturn(Optional.of(dao));

        service.search("q", "myproj", "goodlang", 10);

        verify(dao).searchFullText(
                eq("q"),
                eq(Map.of(PxChunk.PXCHUNK_MIME_TYPE, "text/x-goodlang-code",
                        PxChunk.PXCHUNK_METADATA + "." + PxChunk.PXCHUNK_TYPE, "!" + PxChunk.FULLTEXT_MARKER)),
                eq(40));
    }

    @Test
    void pluginOverrideWinsMime() {
        LanguagePack pluginJava = new LanguagePack() {
            @Override public String language() { return "java"; }
            @Override public String mimeType() { return "text/x-java-plugin"; }
        };
        service = new GrepSearchService(provider, retrieverRegistry,
                new LanguagePackRegistry(List.of(javaPack), List.of(pluginJava)), projectRegistry);

        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(provider.get("myproj")).thenReturn(Optional.of(dao));

        service.search("q", "myproj", "java", 10);

        verify(dao).searchFullText(
                eq("q"),
                eq(Map.of(PxChunk.PXCHUNK_MIME_TYPE, "text/x-java-plugin",
                        PxChunk.PXCHUNK_METADATA + "." + PxChunk.PXCHUNK_TYPE, "!" + PxChunk.FULLTEXT_MARKER)),
                eq(40));
    }

    // ---- Phase 02: test-class filter ----

    private static PxChunk fileChunk(String id, String file, String content) {
        return PxChunk.create(c -> {
            c.setId(id);
            c.setFile(file);
            c.setContent(content);
        });
    }

    @Test
    void defaultSearchIncludesTestFiles() {
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(provider.get("myproj")).thenReturn(Optional.of(dao));
        when(dao.searchFullText(anyString(), anyMap(), anyInt()))
                .thenReturn(List.of(
                        new ScoredChunk(fileChunk("m1", "src/main/java/A.java", "main content"), 1.0),
                        new ScoredChunk(fileChunk("t1", "src/test/java/ATest.java", "test content"), 1.0)));

        List<SearchHit> hits = service.search("q", "myproj", null, 10);

        assertThat(hits).extracting(SearchHit::file)
                .containsExactlyInAnyOrder("src/main/java/A.java", "src/test/java/ATest.java");
    }

    @Test
    void includeTestsFalseRemovesTestFiles() {
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(provider.get("myproj")).thenReturn(Optional.of(dao));
        when(dao.searchFullText(anyString(), anyMap(), anyInt()))
                .thenReturn(List.of(
                        new ScoredChunk(fileChunk("m1", "src/main/java/A.java", "main content"), 1.0),
                        new ScoredChunk(fileChunk("t1", "src/test/java/ATest.java", "test content"), 1.0)));

        List<SearchHit> hits = service.search("q", "myproj", null, 10, false);

        assertThat(hits).extracting(SearchHit::file)
                .containsExactly("src/main/java/A.java");
    }

    @Test
    void includeTestsFalseKeepsOutputWithinMainHitBudget() {
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(provider.get("myproj")).thenReturn(Optional.of(dao));

        List<ScoredChunk> chunks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            chunks.add(new ScoredChunk(fileChunk("m" + i, "src/main/java/M" + i + ".java",
                    "MAIN" + i + "-".repeat(100)), 1.0));
        }
        for (int i = 0; i < 6; i++) {
            chunks.add(new ScoredChunk(fileChunk("t" + i, "src/test/java/T" + i + "Test.java",
                    "TEST" + i + "-".repeat(100)), 1.0));
        }
        when(dao.searchFullText(anyString(), anyMap(), anyInt())).thenReturn(chunks);
        when(retrieverRegistry.all()).thenReturn(List.of(new ContentRetriever()));

        List<SearchHit> all = service.search("q", "myproj", null, 10, true);
        List<SearchHit> mainOnly = service.search("q", "myproj", null, 10, false);

        assertThat(all).hasSize(10);
        assertThat(mainOnly).hasSize(4);

        int mainSize = mainOnly.stream().mapToInt(h -> h.snippet().length()).sum();
        int allSize = all.stream().mapToInt(h -> h.snippet().length()).sum();
        assertThat(mainSize).isLessThan(allSize);

        // No test content may leak into the output string
        String output = String.join("", mainOnly.stream().map(SearchHit::snippet).toList());
        assertThat(output).doesNotContain("TEST");
        assertThat(output).contains("MAIN0");
    }

    // ---- Phase 03: doc-chunk windowing ----

    @Test
    void docChunkSnippetIsWindowed() {
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(provider.get("myproj")).thenReturn(Optional.of(dao));

        String filler = "lorem ipsum dolor\n".repeat(250);
        String content = filler + "UNIQUE_TOKEN" + filler;   // ~9 KB markdown
        PxChunk chunk = PxChunk.create(c -> {
            c.setId("c1");
            c.setFile("docs/readme.md");
            c.setMimeType("text/markdown");
            c.setContent(content);
        });
        when(dao.searchFullText(anyString(), anyMap(), anyInt()))
                .thenReturn(List.of(new ScoredChunk(chunk, 1.0)));
        when(retrieverRegistry.all()).thenReturn(List.of(new ContentRetriever()));

        SearchHit hit = service.search("UNIQUE_TOKEN", "myproj", null, 10).get(0);

        assertThat(hit.snippet()).contains("UNIQUE_TOKEN");
        assertThat(hit.snippet().length()).isLessThan(content.length());
        assertThat(hit.snippet()).contains("readFile(\"docs/readme.md\")");
    }

    @Test
    void codeChunkSnippetIsNotWindowed() {
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(provider.get("myproj")).thenReturn(Optional.of(dao));

        String content = "int x = 1;\n".repeat(2000);   // 8 KB code
        PxChunk chunk = PxChunk.create(c -> {
            c.setId("c1");
            c.setFile("src/A.java");
            c.setMimeType("text/x-java-code");
            c.setContent(content);
        });
        when(dao.searchFullText(anyString(), anyMap(), anyInt()))
                .thenReturn(List.of(new ScoredChunk(chunk, 1.0)));
        when(retrieverRegistry.all()).thenReturn(List.of(new ContentRetriever()));

        SearchHit hit = service.search("x", "myproj", null, 10).get(0);

        // Full text is a feature for code: snippet equals the full content, no marker
        assertThat(hit.snippet()).isEqualTo(content);
        assertThat(hit.snippet()).doesNotContain("readFile");
    }

    // ---- Phase 05: limit = max returned files, applied AFTER grouping + test filter ----

    /** 4 test-file chunks (score 2.0 each) first, then 2 main-file chunks (score 1.0 each). */
    private static List<ScoredChunk> testFirstChunks() {
        List<ScoredChunk> chunks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            chunks.add(new ScoredChunk(fileChunk("t" + i, "src/test/java/FooTest.java", "test content " + i), 2.0));
        }
        for (int i = 0; i < 2; i++) {
            chunks.add(new ScoredChunk(fileChunk("m" + i, "src/main/java/Foo.java", "main content " + i), 1.0));
        }
        return chunks;
    }

    @Test
    void smallLimitStillFindsNonTestFileWhenTestMatchesRankFirst() {
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(provider.get("myproj")).thenReturn(Optional.of(dao));
        when(dao.searchFullText(anyString(), anyMap(), anyInt())).thenReturn(testFirstChunks());
        when(retrieverRegistry.all()).thenReturn(List.of(new ContentRetriever()));

        List<SearchHit> hits = service.search("q", "myproj", null, 4, false);

        assertThat(hits).isNotEmpty();
        assertThat(hits).extracting(SearchHit::file).containsExactly("src/main/java/Foo.java");
    }

    @Test
    void includeTestsTrueReturnsAtMostLimitEntriesSortedByScore() {
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(provider.get("myproj")).thenReturn(Optional.of(dao));
        when(dao.searchFullText(anyString(), anyMap(), anyInt())).thenReturn(testFirstChunks());
        when(retrieverRegistry.all()).thenReturn(List.of(new ContentRetriever()));

        List<SearchHit> hits = service.search("q", "myproj", null, 4, true);

        assertThat(hits).hasSizeLessThanOrEqualTo(4);
        // the test file has the higher total score (4*2.0 > 2*1.0) and must come first
        assertThat(hits).extracting(SearchHit::file)
                .containsExactly("src/test/java/FooTest.java", "src/main/java/Foo.java");
    }

    @Test
    void limitOneStillFindsNonTestFileAndOverscans() {
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(provider.get("myproj")).thenReturn(Optional.of(dao));
        when(dao.searchFullText(anyString(), anyMap(), anyInt())).thenReturn(testFirstChunks());
        when(retrieverRegistry.all()).thenReturn(List.of(new ContentRetriever()));

        List<SearchHit> hits = service.search("q", "myproj", null, 1, false);

        assertThat(hits).extracting(SearchHit::file).containsExactly("src/main/java/Foo.java");
        // raw window must be >= 4x the requested limit (here 40) so filtering cannot starve the result
        verify(dao).searchFullText(eq("q"), anyMap(), eq(40));
    }

    @Test
    void growingWindowFindsNonTestFileWhenFirstWindowIsAllTestChunks() {
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(provider.get("myproj")).thenReturn(Optional.of(dao));

        when(dao.searchFullText(anyString(), anyMap(), anyInt())).thenAnswer(invocation -> {
            int window = invocation.getArgument(2);
            if (window == 40) {
                // Exactly 40 chunks (== window) so the service considers the window exhausted
                List<ScoredChunk> testChunks = new ArrayList<>();
                for (int i = 0; i < 40; i++) {
                    testChunks.add(new ScoredChunk(
                            fileChunk("t" + i, "src/test/java/FooTest.java", "test content " + i), 2.0));
                }
                return testChunks;
            }
            return List.of(new ScoredChunk(fileChunk("m1", "src/main/java/Foo.java", "main content"), 1.0));
        });

        List<SearchHit> hits = service.search("q", "myproj", null, 1, false);

        // the first 40-chunk window contained no usable file, so a second, larger window was needed
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).file()).isEqualTo("src/main/java/Foo.java");

        ArgumentCaptor<Integer> windowCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(dao, atLeast(2)).searchFullText(eq("q"), anyMap(), windowCaptor.capture());
        assertThat(windowCaptor.getAllValues().get(0)).isEqualTo(40);
        assertThat(windowCaptor.getAllValues().get(1)).isGreaterThan(40);
    }

    private static class ContentRetriever implements GoldenRetriever {
        @Override
        public StringBuilder buildPromptForFindings(String projectName, List<ScoredChunk> chunks, SearchParams params, Function<String, Boolean>... contextValidators) {
            StringBuilder sb = new StringBuilder();
            for (ScoredChunk sc : chunks) {
                if (sc.chunk().getContent() != null) {
                    sb.append(sc.chunk().getContent());
                }
            }
            return sb;
        }

        @Override
        public List<SearchHit> retrieveSearchHits(String projectName, List<ScoredChunk> chunks, Function<String, Boolean>... contextValidators) {
            return List.of();
        }
    }
}
