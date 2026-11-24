package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.language.LanguagePack;
import de.spraener.prjxp.common.language.LanguagePackRegistry;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.ScoredChunk;
import de.spraener.prjxp.common.model.SearchHit;
import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import de.spraener.prjxp.common.util.ContentSplitter;
import de.spraener.prjxp.common.retrieval.ContentLevel;
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
import static org.mockito.Mockito.lenient;
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

    // ---- contentLevel: POINTERS emits hints instead of bodies ----

    @Test
    void pointersModeEmitsHintsInsteadOfBodies() {
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(provider.get("myproj")).thenReturn(Optional.of(dao));

        String bodyMarker = "return !vetoRegistry.shouldVeto(p);";
        PxChunk chunk = fileChunk("v1", "src/main/java/DirectoryVetos.java",
                "public boolean shouldVeto(PxChunk p) {\n    " + bodyMarker + "\n}");
        when(dao.searchFullText(anyString(), anyMap(), anyInt()))
                .thenReturn(List.of(new ScoredChunk(chunk, 1.0)));

        ContentRetriever fake = new ContentRetriever();
        when(retrieverRegistry.all()).thenReturn(List.of(fake));

        SearchHit pointers = service.search("@ChunkVeto", "myproj", "java", 10, true, ContentLevel.POINTERS).get(0);
        assertThat(pointers.snippet()).doesNotContain(bodyMarker);
        assertThat(pointers.snippet()).contains("readFile(");
        assertThat(fake.lastParams.effectiveContentLevel()).isEqualTo(ContentLevel.POINTERS);

        SearchHit full = service.search("@ChunkVeto", "myproj", "java", 10, true, ContentLevel.FULL).get(0);
        assertThat(full.snippet()).contains(bodyMarker);
        assertThat(fake.lastParams.effectiveContentLevel()).isEqualTo(ContentLevel.FULL);
    }

    // ---- Efficiency measure 1: NAMES returns filenames only ----

    @Test
    void namesModeReturnsFilenamesOnlyWithoutBodyOrPointers() {
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(provider.get("myproj")).thenReturn(Optional.of(dao));

        String bodyMarker = "return !vetoRegistry.shouldVeto(p);";
        PxChunk chunk = fileChunk("v1", "src/main/java/DirectoryVetos.java",
                "public boolean shouldVeto(PxChunk p) {\n    " + bodyMarker + "\n}");
        when(dao.searchFullText(anyString(), anyMap(), anyInt()))
                .thenReturn(List.of(new ScoredChunk(chunk, 1.0)));

        SearchHit hit = service.search("@ChunkVeto", "myproj", "java", 10, true, ContentLevel.NAMES).get(0);

        // grep -l: one line per file with the hit-count prefix, no body, no pointers
        assertThat(hit.snippet()).isEqualTo("1  src/main/java/DirectoryVetos.java");
        assertThat(hit.snippet()).doesNotContain(bodyMarker);
        assertThat(hit.snippet()).doesNotContain("readFile");
        assertThat(hit.file()).isEqualTo("src/main/java/DirectoryVetos.java");
    }

    @Test
    void namesModeListsEachMatchingFileOnce() {
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(provider.get("myproj")).thenReturn(Optional.of(dao));
        when(dao.searchFullText(anyString(), anyMap(), anyInt()))
                .thenReturn(List.of(
                        new ScoredChunk(fileChunk("m1", "src/main/java/A.java", "PxChunk a"), 1.0),
                        new ScoredChunk(fileChunk("m2", "src/main/java/B.java", "PxChunk b"), 1.0),
                        new ScoredChunk(fileChunk("m3", "src/main/java/A.java", "PxChunk a2"), 1.0)));

        List<SearchHit> hits = service.search("PxChunk", "myproj", null, 10, true, ContentLevel.NAMES);

        // A.java has 2 matching chunks, B.java has 1 — sorted by count descending
        assertThat(hits).extracting(SearchHit::snippet)
                .containsExactly("2  src/main/java/A.java", "1  src/main/java/B.java");
    }

    // ---- grep -l mode: NAMES renders "count  path" sorted by hit count ----

    @Test
    void namesModeRendersHitCountPrefix() {
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(provider.get("myproj")).thenReturn(Optional.of(dao));
        when(dao.searchFullText(anyString(), anyMap(), anyInt()))
                .thenReturn(List.of(
                        new ScoredChunk(fileChunk("m1", "src/main/java/A.java", "PxChunk a"), 1.0),
                        new ScoredChunk(fileChunk("m2", "src/main/java/A.java", "PxChunk a2"), 1.0),
                        new ScoredChunk(fileChunk("m3", "src/main/java/B.java", "PxChunk b"), 1.0)));

        List<SearchHit> hits = service.search("PxChunk", "myproj", null, 10, true, ContentLevel.NAMES);

        // A.java has 2 matching chunks, B.java has 1
        SearchHit a = hits.stream().filter(h -> h.file().equals("src/main/java/A.java")).findFirst().orElseThrow();
        SearchHit b = hits.stream().filter(h -> h.file().equals("src/main/java/B.java")).findFirst().orElseThrow();
        assertThat(a.snippet()).isEqualTo("2  src/main/java/A.java");
        assertThat(b.snippet()).isEqualTo("1  src/main/java/B.java");
    }

    @Test
    void namesModeSortsByHitCountDescending() {
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(provider.get("myproj")).thenReturn(Optional.of(dao));
        // B.java has 3 chunks (higher count) but a LOWER score; A.java has 1 chunk (higher score).
        // The grep -l sort must be by COUNT, not score, so B.java comes first.
        when(dao.searchFullText(anyString(), anyMap(), anyInt()))
                .thenReturn(List.of(
                        new ScoredChunk(fileChunk("a1", "src/main/java/A.java", "x"), 9.0),
                        new ScoredChunk(fileChunk("b1", "src/main/java/B.java", "x"), 1.0),
                        new ScoredChunk(fileChunk("b2", "src/main/java/B.java", "x"), 1.0),
                        new ScoredChunk(fileChunk("b3", "src/main/java/B.java", "x"), 1.0)));

        List<SearchHit> hits = service.search("x", "myproj", null, 10, true, ContentLevel.NAMES);

        assertThat(hits).extracting(SearchHit::file)
                .containsExactly("src/main/java/B.java", "src/main/java/A.java");
    }

    @Test
    void fullModeStillSortsByScoreNotCount() {
        // Regression guard: the count-based sort must NOT leak into FULL/SKELETON/POINTERS.
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(provider.get("myproj")).thenReturn(Optional.of(dao));
        when(retrieverRegistry.all()).thenReturn(List.of(new ContentRetriever()));
        when(dao.searchFullText(anyString(), anyMap(), anyInt()))
                .thenReturn(List.of(
                        new ScoredChunk(fileChunk("a1", "src/main/java/A.java", "x"), 9.0),
                        new ScoredChunk(fileChunk("b1", "src/main/java/B.java", "x"), 1.0),
                        new ScoredChunk(fileChunk("b2", "src/main/java/B.java", "x"), 1.0)));

        List<SearchHit> hits = service.search("x", "myproj", null, 10, true, ContentLevel.FULL);

        // A.java has the higher total score (9.0 > 2.0) and must come first, despite B.java having more chunks.
        assertThat(hits).extracting(SearchHit::file)
                .containsExactly("src/main/java/A.java", "src/main/java/B.java");
    }

    // ---- Efficiency measure 5: AGGREGATED is an alias for NAMES in search tools ----

    @Test
    void aggregatedModeBehavesLikeNames() {
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(provider.get("myproj")).thenReturn(Optional.of(dao));

        String bodyMarker = "return !vetoRegistry.shouldVeto(p);";
        PxChunk chunk = fileChunk("v1", "src/main/java/DirectoryVetos.java",
                "public boolean shouldVeto(PxChunk p) {\n    " + bodyMarker + "\n}");
        when(dao.searchFullText(anyString(), anyMap(), anyInt()))
                .thenReturn(List.of(new ScoredChunk(chunk, 1.0)));

        SearchHit hit = service.search("@ChunkVeto", "myproj", "java", 10, true, ContentLevel.AGGREGATED).get(0);

        // AGGREGATED is an alias for NAMES in search tools: one line per file with the count prefix
        assertThat(hit.snippet()).isEqualTo("1  src/main/java/DirectoryVetos.java");
        assertThat(hit.snippet()).doesNotContain(bodyMarker);
    }

    // ---- Truncation note: the caller must know when files were cut off ----

    @Test
    void detailedResultCarriesNoteWhenFilesAreTruncated() {
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(provider.get("myproj")).thenReturn(Optional.of(dao));
        when(dao.searchFullText(anyString(), anyMap(), anyInt()))
                .thenReturn(List.of(
                        new ScoredChunk(fileChunk("a1", "src/main/java/A.java", "x"), 3.0),
                        new ScoredChunk(fileChunk("b1", "src/main/java/B.java", "x"), 2.0),
                        new ScoredChunk(fileChunk("c1", "src/main/java/C.java", "x"), 1.0)));

        GrepResult result = service.searchDetailed("x", "myproj", null, 2, true, ContentLevel.NAMES);

        assertThat(result.hits()).hasSize(2);
        assertThat(result.totalFiles()).isEqualTo(3);
        assertThat(result.truncated()).isTrue();
        assertThat(result.note()).contains("showing 2 of 3").contains("1 more");
    }

    @Test
    void detailedResultHasNoNoteWhenEverythingFits() {
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(provider.get("myproj")).thenReturn(Optional.of(dao));
        when(dao.searchFullText(anyString(), anyMap(), anyInt()))
                .thenReturn(List.of(
                        new ScoredChunk(fileChunk("a1", "src/main/java/A.java", "x"), 2.0),
                        new ScoredChunk(fileChunk("b1", "src/main/java/B.java", "x"), 1.0)));

        GrepResult result = service.searchDetailed("x", "myproj", null, 10, true, ContentLevel.NAMES);

        assertThat(result.hits()).hasSize(2);
        assertThat(result.truncated()).isFalse();
        assertThat(result.note()).isNull();
    }

    private static class ContentRetriever implements GoldenRetriever {
        /** Last SearchParams handed in — captured so tests can assert on the content level. */
        SearchParams lastParams;

        @Override
        public StringBuilder buildPromptForFindings(String projectName, List<ScoredChunk> chunks, SearchParams params, Function<String, Boolean>... contextValidators) {
            lastParams = params;
            boolean pointers = params != null && params.effectiveContentLevel() == ContentLevel.POINTERS;
            StringBuilder sb = new StringBuilder();
            for (ScoredChunk sc : chunks) {
                if (pointers) {
                    // pointer layer: tool hints only, no body
                    String file = sc.chunk().getFile();
                    if (file != null) {
                        sb.append("→ readFile(\"").append(file).append("\")\n");
                    }
                } else if (sc.chunk().getContent() != null) {
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

    // ---------------------------------------------------------------- P2 line search

    private static PxChunk lineChunk(String id, String file, String fromLine, String content) {
        return PxChunk.create(
                c -> c.setId(id),
                c -> c.setFile(file),
                c -> c.setFromLine(fromLine),
                c -> c.setPart(0),
                c -> c.setTotal(1),
                c -> c.setContent(content)
        );
    }

    /**
     * Both candidate sources: {@code findAll} for {@code REGEX} (still a scan) and
     * {@code searchCandidates} for {@code SUBSTRING}/{@code EXACT} (analyzed index).
     */
    private void stubAllChunks(PxChunk... chunks) {
        when(projectRegistry.resolve("p")).thenReturn("p");
        when(provider.get("p")).thenReturn(Optional.of(dao));
        lenient().when(dao.findAll()).thenAnswer(invocation -> java.util.stream.Stream.of(chunks));
        lenient().when(dao.searchCandidates(anyString(), anyMap(), anyInt())).thenAnswer(invocation -> {
            List<ScoredChunk> scored = new java.util.ArrayList<>();
            for (PxChunk chunk : chunks) {
                scored.add(new ScoredChunk(chunk, 1.0));
            }
            return scored;
        });
    }

    /** A file-name hit for the name prior; the fusion ranks it before unconfirmed candidates. */
    private void stubFileNames(String... files) {
        when(dao.findFilesContaining(anyString(), anyInt())).thenReturn(List.of(files));
    }

    @Test
    void grepReportsAbsoluteLineInMultiChunkFile() {
        StringBuilder source = new StringBuilder();
        for (int i = 1; i <= 40; i++) {
            source.append("line ").append(i).append('\n');
        }
        List<PxChunk> raw = new ContentSplitter(80, 20)
                .splitContent(source.toString(), 0, 40, PxChunk::create);
        assertThat(raw.size()).isGreaterThan(2);
        PxChunk[] chunks = raw.stream().map(part -> {
            part.setFile("src/A.java");
            return part;
        }).toArray(PxChunk[]::new);
        stubAllChunks(chunks);

        GrepResult result = service.searchMatches("line 37", "p", null, 20, true, GrepMode.SUBSTRING);

        // overlap may return the token from two chunks, but every anchor must be the real line
        assertThat(result.hits()).isNotEmpty();
        assertThat(result.hits()).allMatch(h -> h.lineFrom() == 37);
    }

    @Test
    void syntheticTailOfPartlyVerbatimChunkIsNotAnchored() {
        PxChunk frame = PxChunk.create(c -> {
            c.setId("de.ex.A");
            c.setFile("src/A.java");
            c.setFromLine("1");
            c.setContent("package de.ex;\npublic class A {\n    void syntheticSignature();");
            c.getMetadata().put(PxChunk.PXCHUNK_VERBATIM_LINES, "1-2");
        });
        stubAllChunks(frame);

        GrepResult classHit = service.searchMatches("class A", "p", null, 20, true, GrepMode.SUBSTRING);
        assertThat(classHit.hits()).hasSize(1);
        assertThat(classHit.hits().get(0).lineFrom()).isEqualTo(2);

        // the synthetic signature line would map to line 3, which is not a real source anchor
        GrepResult syntheticHit = service.searchMatches("syntheticSignature", "p", null, 20, true, GrepMode.SUBSTRING);
        assertThat(syntheticHit.hits()).isEmpty();
    }

    @Test
    void overlappingChunksReportOneHitPerLineWithTheLongerSnippet() {
        stubAllChunks(
                lineChunk("c1", "A.java", "10", "    private int CHUNKER_NAME;"),
                lineChunk("c2", "A.java", "10", "int CHUNKER_NAME;"));   // overlap fragment

        GrepResult result = service.searchMatches("CHUNKER_NAME", "p", null, 20, true, GrepMode.SUBSTRING);

        assertThat(result.hits()).hasSize(1);
        assertThat(result.hits().get(0).lineFrom()).isEqualTo(10);
        assertThat(result.hits().get(0).snippet()).isEqualTo("private int CHUNKER_NAME;");
    }

    @Test
    void substringSearchReturnsFileLineAndMatchingLine() {
        stubAllChunks(lineChunk("c1", "src/A.java", "10", "class A {\n  void run() {}\n}"));

        GrepResult result = service.searchMatches("run", "p", null, 20, true, GrepMode.SUBSTRING);

        assertThat(result.hits()).hasSize(1);
        assertThat(result.hits().get(0).file()).isEqualTo("src/A.java");
        assertThat(result.hits().get(0).lineFrom()).isEqualTo(11);
        assertThat(result.hits().get(0).snippet()).isEqualTo("void run() {}");
    }

    @Test
    void exactModeMatchesWholeWordsOnly() {
        stubAllChunks(lineChunk("c1", "A.java", "1", "void run() {}\nvoid running() {}"));

        GrepResult result = service.searchMatches("run", "p", null, 20, true, GrepMode.EXACT);

        assertThat(result.hits()).hasSize(1);
        assertThat(result.hits().get(0).lineFrom()).isEqualTo(1);
    }

    @Test
    void regexModeUsesAJavaPattern() {
        stubAllChunks(lineChunk("c1", "A.java", "1", "int a = 41;\nint b = 42;"));

        GrepResult result = service.searchMatches("4[12]", "p", null, 20, true, GrepMode.REGEX);

        assertThat(result.hits()).hasSize(2);
    }

    @Test
    void invalidRegexIsReportedInsteadOfThrowing() {
        when(projectRegistry.resolve("p")).thenReturn("p");
        when(provider.get("p")).thenReturn(Optional.of(dao));

        GrepResult result = service.searchMatches("[", "p", null, 20, true, GrepMode.REGEX);

        assertThat(result.hits()).isEmpty();
        assertThat(result.note()).contains("invalid regular expression");
    }

    @Test
    void fulltextCopiesAndTestsAreNotScanned() {
        PxChunk fulltext = lineChunk("ft", "A.java", "1", "run");
        fulltext.getMetadata().put(PxChunk.PXCHUNK_TYPE, PxChunk.FULLTEXT_MARKER);
        stubAllChunks(fulltext, lineChunk("t", "src/test/java/A.java", "1", "run"));

        GrepResult result = service.searchMatches("run", "p", null, 20, false, GrepMode.SUBSTRING);

        assertThat(result.hits()).isEmpty();
    }

    @Test
    void searchFilesReturnsOneHitPerFile() {
        stubAllChunks(
                lineChunk("c1", "A.java", "1", "run\nrun\nrun"),
                lineChunk("c2", "B.java", "1", "run"));

        GrepResult result = service.searchFiles("run", "p", null, 10, true, GrepMode.SUBSTRING);

        assertThat(result.hits()).extracting(SearchHit::file).containsExactly("A.java", "B.java");
        assertThat(result.totalFiles()).isEqualTo(2);
        assertThat(result.note()).isNull();
    }

    @Test
    void searchFilesLimitsByFileCountNotByHits() {
        stubAllChunks(
                lineChunk("c1", "A.java", "1", "run\nrun\nrun"),
                lineChunk("c2", "B.java", "1", "run"),
                lineChunk("c3", "C.java", "1", "run"));

        GrepResult result = service.searchFiles("run", "p", null, 2, true, GrepMode.SUBSTRING);

        assertThat(result.hits()).hasSize(2);
        assertThat(result.totalFiles()).isEqualTo(3);
        assertThat(result.note()).contains("2 of 3 files");
    }

    @Test
    void legacyFlagRestoresCountRanking() {
        stubAllChunks(
                lineChunk("c1", "ZMinor.java", "1", "run"),
                lineChunk("c2", "AMajor.java", "1", "run\nrun\nrun"));
        service.rankingMode = GrepSearchService.RANKING_LEGACY;

        GrepResult result = service.searchFiles("run", "p", null, 10, true, GrepMode.SUBSTRING);

        assertThat(result.hits()).extracting(SearchHit::file).containsExactly("AMajor.java", "ZMinor.java");
        assertThat(result.hits()).allMatch(h -> h.lineFrom() == null && h.snippet() == null);
    }

    @Test
    void limitTruncatesHitsAndExplainsHowToSeeMore() {
        stubAllChunks(lineChunk("c1", "A.java", "1", "run\nrun\nrun"));

        GrepResult result = service.searchMatches("run", "p", null, 2, true, GrepMode.SUBSTRING);

        assertThat(result.hits()).hasSize(2);
        assertThat(result.note()).contains("2 of 3");
    }

    @Test
    void legacyRankingStillPrefersFilesWithMoreHits() {
        stubAllChunks(
                lineChunk("c1", "ZMinor.java", "1", "run"),
                lineChunk("c2", "AMajor.java", "1", "run\nrun\nrun"));
        service.rankingMode = GrepSearchService.RANKING_LEGACY;

        GrepResult result = service.searchMatches("run", "p", null, 3, true, GrepMode.SUBSTRING);

        assertThat(result.hits()).hasSize(3);
        assertThat(result.hits()).allMatch(h -> h.file().equals("AMajor.java"));
        assertThat(result.note()).contains("3 of 4");
    }

    @Test
    void nameMatchRanksFirst() {
        stubAllChunks(
                lineChunk("c1", "BaumassnahmeViewAction.java", "1", "new PreloadEventListener()"),
                lineChunk("c2", "PreloadEventListener.java", "1", "class PreloadEventListener"));
        stubFileNames("PreloadEventListener.java");

        GrepResult result = service.searchFiles("Preload", "p", null, 10, true, GrepMode.SUBSTRING);

        assertThat(result.hits()).extracting(SearchHit::file)
                .containsExactly("PreloadEventListener.java", "BaumassnahmeViewAction.java");
    }

    @Test
    void nameMatchWithoutMatchingLineIsNotReported() {
        stubAllChunks(lineChunk("c1", "A.java", "1", "Preload setting"));
        stubFileNames("PreloadOnly.java", "A.java");

        GrepResult result = service.searchFiles("Preload", "p", null, 10, true, GrepMode.SUBSTRING);

        assertThat(result.hits()).extracting(SearchHit::file).containsExactly("A.java");
        assertThat(result.totalFiles()).isEqualTo(1);
    }

    @Test
    void prefixCandidateWithoutLineMatchIsRejected() {
        stubAllChunks(lineChunk("c1", "A.java", "1", "PreloadXyz();"));

        GrepResult result = service.searchMatches("Preload", "p", null, 20, true, GrepMode.EXACT);

        assertThat(result.hits()).isEmpty();
    }

    @Test
    void regexModeStillRanksByName() {
        stubAllChunks(
                lineChunk("c1", "Other.java", "1", "PreloadSetting setting;"),
                lineChunk("c2", "PreloadSetting.java", "1", "class PreloadSetting"));
        stubFileNames("PreloadSetting.java");

        GrepResult result = service.searchFiles("Preload", "p", null, 10, true, GrepMode.REGEX);

        assertThat(result.hits()).extracting(SearchHit::file)
                .containsExactly("PreloadSetting.java", "Other.java");
    }

    @Test
    void missingStructureServiceStillRanks() {
        stubAllChunks(
                lineChunk("c1", "Other.java", "1", "run"),
                lineChunk("c2", "Runner.java", "1", "run"));
        stubFileNames("Runner.java");
        service.chunkRankingService = null;

        GrepResult result = service.searchFiles("run", "p", null, 10, true, GrepMode.SUBSTRING);

        assertThat(result.hits()).extracting(SearchHit::file).containsExactly("Runner.java", "Other.java");
    }

    @Test
    void blankQueryIsRejectedForBothSurfaces() {
        assertThat(service.searchMatches(" ", "p", null, 10, true, GrepMode.SUBSTRING).note())
                .contains("'query' is required");
        assertThat(service.searchFiles(" ", "p", null, 10, true, GrepMode.SUBSTRING).note())
                .contains("'query' is required");
    }

    @Test
    void grepAsksForEveryCandidateNotForAWindow() {
        stubAllChunks(lineChunk("c1", "A.java", "1", "run"));

        service.searchFiles("run", "p", null, 10, true, GrepMode.SUBSTRING);

        // limit 0 == all matches; a window would answer "the top N" instead of "which files".
        verify(dao).searchCandidates(eq("run"), anyMap(), eq(0));
    }

    @Test
    void heavilyMatchingFileRanksAboveASingleHitDoc() {
        stubAllChunks(
                lineChunk("m1", "impl/Minor.java", "1", "ZvF"),
                lineChunk("c1", "impl/ImportZvfXmlAction.java", "1",
                        "ZvF = 1;\nuse(ZvF);\nread(ZvF);\nwrite(ZvF);"));

        GrepResult result = service.searchFiles("ZvF", "p", null, 10, true, GrepMode.SUBSTRING);

        // Frequency is a signal of its own: a token search is not only about where it occurs.
        assertThat(result.hits()).extracting(SearchHit::file)
                .containsExactly("impl/ImportZvfXmlAction.java", "impl/Minor.java");
    }

    @Test
    void nameMatchIsTheStrongestSingleSignal() {
        stubAllChunks(
                lineChunk("d1", "docs/ZvF.md", "1", "ZvF"),
                lineChunk("c1", "impl/ImportZvfXmlAction.java", "1",
                        "ZvF = 1;\nuse(ZvF);\nread(ZvF);\nwrite(ZvF);"));
        stubFileNames("docs/ZvF.md");

        GrepResult result = service.searchFiles("ZvF", "p", null, 10, true, GrepMode.SUBSTRING);

        // Documented trade-off: a file about the token outranks a file that merely mentions it.
        assertThat(result.hits()).extracting(SearchHit::file)
                .containsExactly("docs/ZvF.md", "impl/ImportZvfXmlAction.java");
    }

    @Test
    void twoCallsReturnIdenticalOrder() {
        stubAllChunks(
                lineChunk("c1", "A.java", "1", "run"),
                lineChunk("c2", "B.java", "1", "run"));

        GrepResult first = service.searchFiles("run", "p", null, 10, true, GrepMode.SUBSTRING);
        GrepResult second = service.searchFiles("run", "p", null, 10, true, GrepMode.SUBSTRING);

        assertThat(first.hits()).isEqualTo(second.hits());
    }

    @Test
    void totalCountsAllMatchesEvenWhenOnlyAPrefixIsRendered() {
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            many.append("run\n");
        }
        stubAllChunks(lineChunk("c1", "A.java", "1", many.toString()),
                lineChunk("c2", "B.java", "1", "run"));

        GrepResult result = service.searchMatches("run", "p", null, 5, true, GrepMode.SUBSTRING);

        assertThat(result.hits()).hasSize(5);
        assertThat(result.totalFiles()).isEqualTo(501);
    }
}
