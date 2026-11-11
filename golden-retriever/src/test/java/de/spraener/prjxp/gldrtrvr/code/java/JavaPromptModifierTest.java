package de.spraener.prjxp.gldrtrvr.code.java;

import de.spraener.prjxp.common.config.PrjXPEmbeddingStoreReference;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.retrieval.SearchParams;
import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.gldrtrvr.DependencyCompactorTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 06: the LLM freshness line. A method chunk carrying a {@code generated_at} watermark gets an
 * {@code [Index-Stand ...]} line appended to the prompt; without the key the prompt is left untouched.
 */
class JavaPromptModifierTest {

    private final PrjXPEmbeddingStoreReference ref = new PrjXPEmbeddingStoreReference();
    private final PxChunkDao dao = new PxChunkDao() {
        @Override
        public PrjXPEmbeddingStoreReference getStoreReference() {
            return ref;
        }

        @Override
        public List<PxChunk> findById(String id) {
            return List.of();
        }

        @Override
        public List<PxChunk> findByMetaData(Map<String, String> metaData) {
            return List.of();
        }

        @Override
        public List<PxChunk> findRelevant(String question, int maxResults, double minScore) {
            return List.of();
        }

        @Override
        public Stream<PxChunk> findAll() {
            return Stream.empty();
        }
    };

    // skeletonsOnly=true keeps the METHOD branch to a single replace (no javadoc lookup),
    // so the only observable change is the freshness line.
    private final JavaPromptModifier modifier = new JavaPromptModifier(new SearchParams(20, 0.85, true));

    private PxChunk methodChunk(String generatedAt) {
        return PxChunk.create(
                c -> c.setId("com.example.Foo.bar"),
                c -> c.setMimeType("text/x-java"),
                c -> c.setFile("src/com/example/Foo.java"),
                c -> c.setContent("public void bar() {\n    body\n}"),
                c -> c.getMetadata().put("java_code_section", "method"),
                c -> { if (generatedAt != null) { c.getMetadata().put("generated_at", generatedAt); } });
    }

    @Test
    void methodChunkWithGeneratedAtAppendsFreshnessLine() {
        String result = modifier.modifyPrompt(dao, methodChunk("1700000000000"), "prompt");

        assertThat(result).contains("[Index-Stand");
    }

    @Test
    void methodChunkWithoutGeneratedAtIsUnchanged() {
        String result = modifier.modifyPrompt(dao, methodChunk(null), "prompt");

        assertThat(result).doesNotContain("Index-Stand");
    }

    // Phase 01: the CLAZZ_FRAME case must compact the dependency section to maxEntries.

    private PxChunkDao daoWithDependencies(String dependencyContent) {
        return new PxChunkDao() {
            @Override
            public PrjXPEmbeddingStoreReference getStoreReference() {
                return ref;
            }

            @Override
            public List<PxChunk> findById(String id) {
                if (id.endsWith(".dependencies")) {
                    // mutable: PxChunk.combine sorts the list
                    return new java.util.ArrayList<>(List.of(PxChunk.create(
                            c -> c.setId(id),
                            c -> c.setContent(dependencyContent))));
                }
                return List.of();
            }

            @Override
            public List<PxChunk> findByMetaData(Map<String, String> metaData) {
                return List.of();
            }

            @Override
            public List<PxChunk> findRelevant(String question, int maxResults, double minScore) {
                return List.of();
            }

            @Override
            public Stream<PxChunk> findAll() {
                return Stream.empty();
            }
        };
    }

    private PxChunk clazzFrameChunk() {
        return PxChunk.create(
                c -> c.setId("com.example.Foo"),
                c -> c.setContent("class Foo {}"),
                c -> c.getMetadata().put("java_code_section", "classFrame"));
    }

    @Test
    void clazzFrameChunkCompactsDependenciesToMaxEntries() {
        JavaPromptModifier compacting = new JavaPromptModifier(new SearchParams(20, 0.85, true), 10);
        PxChunkDao depDao = daoWithDependencies(DependencyCompactorTest.dependencyContent(25, 15)); // 40 entries

        String result = compacting.modifyPrompt(depDao, clazzFrameChunk(), "prompt");

        assertThat(result).contains("## Hier ein Rumpf der Klasse com.example.Foo");
        assertThat(result).contains("### Hier noch Infos zu den Dependencies innerhalb des Projekts");
        assertThat(DependencyCompactorTest.countEntries(result)).isEqualTo(10);
        assertThat(result).contains(DependencyCompactorTest.MARKER_30);
    }

    @Test
    void clazzFrameChunkWithoutDependencyChunkHasNoDependencySection() {
        String result = modifier.modifyPrompt(dao, clazzFrameChunk(), "prompt");

        assertThat(result).contains("## Hier ein Rumpf der Klasse com.example.Foo");
        assertThat(result).doesNotContain("### Hier noch Infos zu den Dependencies");
    }
}
