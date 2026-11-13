package de.spraener.prjxp.gldrtrvr.code.typescript;

import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.ScoredChunk;
import de.spraener.prjxp.common.retrieval.ContentLevel;
import de.spraener.prjxp.common.retrieval.SearchParams;
import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import de.spraener.prjxp.gldrtrvr.DependencyCompactorTest;
import de.spraener.prjxp.gldrtrvr.chunks.ChunkRankingService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 01: the CLASS_FRAME case must compact the dependency section to maxEntries.
 */
class TypeScriptRetrieverTest {

    private final SearchParams params = new SearchParams(20, 0.85);

    private PxChunk classFrameChunk() {
        return PxChunk.create(
                c -> c.setId("com/example/Foo.ts"),
                c -> c.setContent("export class Foo {}"),
                c -> c.setTotal(1),
                c -> c.getMetadata().put("typescript_code_section", "classFrame"));
    }

    private PxChunkDao mockDao(PxChunk classFrame, String dependencyContent) {
        PxChunkDao dao = mock(PxChunkDao.class);
        // mutable lists: PxChunk.combine sorts them
        when(dao.findById(classFrame.getId())).thenReturn(new java.util.ArrayList<>(List.of(classFrame)));
        if (dependencyContent != null) {
            PxChunk depChunk = PxChunk.create(
                    c -> c.setId(classFrame.getId() + ".dependencies"),
                    c -> c.setContent(dependencyContent));
            when(dao.findById(classFrame.getId() + ".dependencies")).thenReturn(new java.util.ArrayList<>(List.of(depChunk)));
        } else {
            when(dao.findById(classFrame.getId() + ".dependencies")).thenReturn(List.of());
        }
        return dao;
    }

    private TypeScriptRetriever retrieverWith(PxChunkDao dao) {
        PxChunkDaoProvider provider = mock(PxChunkDaoProvider.class);
        when(provider.get("proj")).thenReturn(Optional.of(dao));
        ChunkRankingService rankingService = mock(ChunkRankingService.class);
        when(rankingService.rank(any(PxChunk.class))).thenReturn(1.0);
        // maxDependencyEntries defaults to 10 (Spring injects the @Value in production)
        return new TypeScriptRetriever(provider, rankingService);
    }

    @Test
    void classFrameChunkCompactsDependenciesToMaxEntries() {
        PxChunk classFrame = classFrameChunk();
        TypeScriptRetriever retriever = retrieverWith(mockDao(classFrame, DependencyCompactorTest.dependencyContent(25, 15))); // 40 entries

        String prompt = retriever.buildPromptForFindings("proj", List.of(new ScoredChunk(classFrame, 1.0)), params).toString();

        assertThat(prompt).contains("## Hier ein Rumpf der Klasse com/example/Foo.ts");
        assertThat(prompt).contains("### Hier noch Infos zu den Dependencies innerhalb des Projekts");
        assertThat(DependencyCompactorTest.countEntries(prompt)).isEqualTo(10);
        assertThat(prompt).contains(DependencyCompactorTest.MARKER_30);
    }

    @Test
    void classFrameChunkWithoutDependencyChunkHasNoDependencySection() {
        PxChunk classFrame = classFrameChunk();
        TypeScriptRetriever retriever = retrieverWith(mockDao(classFrame, null));

        String prompt = retriever.buildPromptForFindings("proj", List.of(new ScoredChunk(classFrame, 1.0)), params).toString();

        assertThat(prompt).contains("## Hier ein Rumpf der Klasse com/example/Foo.ts");
        assertThat(prompt).doesNotContain("### Hier noch Infos zu den Dependencies");
    }

    @Test
    void dependencyInfoUnderPointersEmitsOnlyGraphNodePointer() {
        // parent frame resolvable in the dao, so the session tree keeps the dep chunk's parent
        PxChunk otherFrame = PxChunk.create(
                c -> c.setId("com/example/Other.ts"),
                c -> c.setContent("export class Other {}"),
                c -> c.setTotal(1),
                c -> c.getMetadata().put("typescript_code_section", "classFrame"));
        // id deliberately NOT parent + ".dependencies": the parent frame's CLASS_FRAME branch
        // would otherwise re-render the dep content via the compactor and leak the marker
        PxChunk dep = PxChunk.create(
                c -> c.setId("com/example/Other.ts.deps"),
                c -> c.setParent("com/example/Other.ts"),
                c -> c.setContent("  * de.example.HiddenNeighbour"),
                c -> c.setTotal(1),
                c -> c.getMetadata().put("typescript_code_section", "dependenciesInfo"));
        PxChunkDao dao = mock(PxChunkDao.class);
        when(dao.findById(dep.getId())).thenReturn(new java.util.ArrayList<>(List.of(dep)));
        when(dao.findById(otherFrame.getId())).thenReturn(new java.util.ArrayList<>(List.of(otherFrame)));
        TypeScriptRetriever retriever = retrieverWith(dao);

        SearchParams pointers = new SearchParams(20, 0.85);
        pointers.setContentLevel(ContentLevel.POINTERS);
        String pointerPrompt = retriever.buildPromptForFindings("proj", List.of(new ScoredChunk(dep, 1.0)), pointers).toString();

        assertThat(pointerPrompt).doesNotContain("de.example.HiddenNeighbour");
        assertThat(pointerPrompt).contains("→ graphNode(\"com/example/Other.ts\")");

        String fullPrompt = retriever.buildPromptForFindings("proj", List.of(new ScoredChunk(dep, 1.0)), params).toString();
        assertThat(fullPrompt).contains("de.example.HiddenNeighbour");

        String skeletonPrompt = retriever.buildPromptForFindings("proj", List.of(new ScoredChunk(dep, 1.0)), new SearchParams(20, 0.85, true)).toString();
        assertThat(skeletonPrompt).contains("de.example.HiddenNeighbour");
    }

    @Test
    void pointersLevelRespectsBudget() {
        PxChunk chunk = classFrameChunk();
        TypeScriptRetriever retriever = retrieverWith(mock(PxChunkDao.class));
        SearchParams pointers = new SearchParams(20, 0.85);
        pointers.setContentLevel(ContentLevel.POINTERS);
        pointers.setMaxContentLength(10);

        String prompt = retriever.buildPromptForFindings("proj", List.of(new ScoredChunk(chunk, 1.0)), pointers).toString();

        assertThat(prompt).contains("[weitere 1 Klassen wegen Groessenlimit nicht enthalten]");
    }

    @Test
    void pointerBlockIsRenderedForFileChunks() {
        PxChunk chunk = PxChunk.create(
                c -> c.setId("com/example/Method.ts"),
                c -> c.setFile("src/com/example/Foo.ts"),
                c -> c.setContent("export function method() {}"),
                c -> c.setTotal(1),
                c -> c.getMetadata().put("typescript_code_section", "method"),
                c -> c.getMetadata().put("graph_node", "com/example/Foo#method()"),
                c -> c.getMetadata().put("keywords", "method, export"));
        TypeScriptRetriever retriever = retrieverWith(mock(PxChunkDao.class));
        SearchParams pointers = new SearchParams(20, 0.85);
        pointers.setContentLevel(ContentLevel.POINTERS);

        String prompt = retriever.buildPromptForFindings("proj", List.of(new ScoredChunk(chunk, 0.9)), pointers).toString();

        assertThat(prompt).contains("file: \"src/com/example/Foo.ts\"")
                .contains("→ readFile(\"src/com/example/Foo.ts\")")
                .contains("→ readBySignature(\"method\", \"com/example/Foo\")")
                .contains("keywords: [\"method\", \"export\"]");
        assertThat(prompt).doesNotContain("export function method()")
                .doesNotContain("Hier ein Rumpf");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    @Test
    void dependencyPointerIsSkippedWhenParentPointerAlreadyPresent() {
        // parent frame resolvable in the dao, so both dep chunks share the same tree
        PxChunk otherFrame = PxChunk.create(
                c -> c.setId("com/example/Other.ts"),
                c -> c.setContent("export class Other {}"),
                c -> c.setTotal(1),
                c -> c.getMetadata().put("typescript_code_section", "classFrame"));
        // two dep chunks with the same parent: the first emits the pointer, the second must be skipped
        PxChunk dep1 = PxChunk.create(
                c -> c.setId("com/example/Other.ts.deps1"),
                c -> c.setParent("com/example/Other.ts"),
                c -> c.setContent("  * de.example.HiddenNeighbour1"),
                c -> c.setTotal(1),
                c -> c.getMetadata().put("typescript_code_section", "dependenciesInfo"));
        PxChunk dep2 = PxChunk.create(
                c -> c.setId("com/example/Other.ts.deps2"),
                c -> c.setParent("com/example/Other.ts"),
                c -> c.setContent("  * de.example.HiddenNeighbour2"),
                c -> c.setTotal(1),
                c -> c.getMetadata().put("typescript_code_section", "dependenciesInfo"));
        PxChunkDao dao = mock(PxChunkDao.class);
        when(dao.findById(otherFrame.getId())).thenReturn(new java.util.ArrayList<>(List.of(otherFrame)));
        when(dao.findById(dep1.getId())).thenReturn(new java.util.ArrayList<>(List.of(dep1)));
        when(dao.findById(dep2.getId())).thenReturn(new java.util.ArrayList<>(List.of(dep2)));
        TypeScriptRetriever retriever = retrieverWith(dao);

        SearchParams pointers = new SearchParams(20, 0.85);
        pointers.setContentLevel(ContentLevel.POINTERS);
        String prompt = retriever.buildPromptForFindings("proj",
                List.of(new ScoredChunk(dep1, 1.0), new ScoredChunk(dep2, 1.0)), pointers).toString();

        // the pointer is emitted exactly once (by the first dep chunk); the second is skipped
        assertThat(countOccurrences(prompt, "chunk_id:")).isEqualTo(1);
        assertThat(countOccurrences(prompt, "→ graphNode(\"com/example/Other.ts\")")).isEqualTo(1);
    }

    @Test
    void dependencyPointerIsNotSuppressedBySimilarFqn() {
        PxChunk otherFrame = PxChunk.create(
                c -> c.setId("com/example/Other.ts"),
                c -> c.setContent("export class Other {}"),
                c -> c.setTotal(1),
                c -> c.getMetadata().put("typescript_code_section", "classFrame"));
        // a superstring FQN frame, nested under Other.ts so it shares the same tree
        PxChunk factoryFrame = PxChunk.create(
                c -> c.setId("com/example/Other.tsFactory"),
                c -> c.setParent("com/example/Other.ts"),
                c -> c.setContent("export class OtherFactory {}"),
                c -> c.setTotal(1),
                c -> c.getMetadata().put("typescript_code_section", "classFrame"));
        // emits → graphNode("com/example/Other.tsFactory") — a superstring of the dep's parent
        PxChunk similarDep = PxChunk.create(
                c -> c.setId("com/example/Other.tsFactory.deps"),
                c -> c.setParent("com/example/Other.tsFactory"),
                c -> c.setContent("  * de.example.HiddenNeighbour"),
                c -> c.setTotal(1),
                c -> c.getMetadata().put("typescript_code_section", "dependenciesInfo"));
        // the dep under test; its id sorts after the factory frame so the similar pointer is emitted first
        PxChunk dep = PxChunk.create(
                c -> c.setId("com/example/Other.tsZZZ"),
                c -> c.setParent("com/example/Other.ts"),
                c -> c.setContent("  * de.example.OtherNeighbour"),
                c -> c.setTotal(1),
                c -> c.getMetadata().put("typescript_code_section", "dependenciesInfo"));
        PxChunkDao dao = mock(PxChunkDao.class);
        when(dao.findById(otherFrame.getId())).thenReturn(new java.util.ArrayList<>(List.of(otherFrame)));
        when(dao.findById(factoryFrame.getId())).thenReturn(new java.util.ArrayList<>(List.of(factoryFrame)));
        when(dao.findById(similarDep.getId())).thenReturn(new java.util.ArrayList<>(List.of(similarDep)));
        when(dao.findById(dep.getId())).thenReturn(new java.util.ArrayList<>(List.of(dep)));
        TypeScriptRetriever retriever = retrieverWith(dao);

        SearchParams pointers = new SearchParams(20, 0.85);
        pointers.setContentLevel(ContentLevel.POINTERS);
        String prompt = retriever.buildPromptForFindings("proj",
                List.of(new ScoredChunk(similarDep, 1.0), new ScoredChunk(dep, 1.0)), pointers).toString();

        // the similar (superstring) pointer must not suppress the dep's own pointer.
        // Assert the pointer (not the header): the factory header "## Dependencies of
        // com/example/Other.tsFactory" contains the dep header as a prefix, but the factory
        // pointer does NOT contain the dep pointer (a bare contains(parent) would have suppressed it).
        assertThat(prompt).contains("→ graphNode(\"com/example/Other.ts\")");
    }
}
