package de.spraener.prjxp.gldrtrvr.code.java;

import de.spraener.prjxp.common.config.PrjXPEmbeddingStoreReference;
import de.spraener.prjxp.common.graph.GraphMetadata;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.retrieval.ContentLevel;
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

    // ---- Phase 01 (Graphify-Tool-V2): the ContentLevel knob ----

    private PxChunk plainMethodChunk() {
        return PxChunk.create(
                c -> c.setId("com.example.Foo.bar"),
                c -> c.setMimeType("text/x-java"),
                c -> c.setFile("src/com/example/Foo.java"),
                c -> c.setContent("public void bar(){\n    body\n}"),
                c -> c.getMetadata().put("java_code_section", "method"));
    }

    @Test
    void fullContentLevelReplacesMethodWithFullBody() {
        JavaPromptModifier full = new JavaPromptModifier(new SearchParams(20, 0.85));

        String result = full.modifyPrompt(dao, plainMethodChunk(), "call bar here");

        assertThat(result).contains("public void bar(){").contains("body");
    }

    @Test
    void skeletonContentLevelUsesSignatureOnly() {
        JavaPromptModifier skeleton = new JavaPromptModifier(new SearchParams(20, 0.85, true));

        String result = skeleton.modifyPrompt(dao, plainMethodChunk(), "call bar here");

        assertThat(result).contains("public void bar();").doesNotContain("body");
    }

    @Test
    void pointersContentLevelRendersLikeSkeletonInPhase01() {
        SearchParams params = new SearchParams(20, 0.85);
        params.setContentLevel(ContentLevel.POINTERS);
        JavaPromptModifier pointers = new JavaPromptModifier(params);

        String result = pointers.modifyPrompt(dao, plainMethodChunk(), "call bar here");

        assertThat(result).contains("public void bar();").doesNotContain("body");
    }

    // ---- branch coverage for the remaining section cases & helpers ----

    private PxChunkDao daoWithJavadoc(String javadocContent) {
        return new PxChunkDao() {
            @Override
            public PrjXPEmbeddingStoreReference getStoreReference() {
                return ref;
            }

            @Override
            public List<PxChunk> findById(String id) {
                if (id.endsWith(".javadoc")) {
                    return new java.util.ArrayList<>(List.of(PxChunk.create(
                            c -> c.setId(id),
                            c -> c.setContent(javadocContent))));
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

    @Test
    void fullContentLevelWithJavadocInsertsDocBeforeMethod() {
        JavaPromptModifier full = new JavaPromptModifier(new SearchParams(20, 0.85));

        String result = full.modifyPrompt(daoWithJavadoc("/** the javadoc */"), plainMethodChunk(), "call bar here");

        assertThat(result).contains("/** the javadoc */").contains("body");
    }

    @Test
    void methodDocInsertsBeforeMethodName() {
        PxChunk doc = PxChunk.create(
                c -> c.setId("com.example.Foo.bar"),
                c -> c.setContent("/** doc */"),
                c -> c.getMetadata().put("java_code_section", "methodDoc"));

        String result = modifier.modifyPrompt(dao, doc, "call bar here");

        assertThat(result).contains("/** doc */");
    }

    @Test
    void methodDocWithoutNameInPromptLeavesPromptUnchanged() {
        PxChunk doc = PxChunk.create(
                c -> c.setId("com.example.Foo.bar"),
                c -> c.setContent("/** doc */"),
                c -> c.getMetadata().put("java_code_section", "methodDoc"));

        String result = modifier.modifyPrompt(dao, doc, "no method name here");

        assertThat(result).isEqualTo("no method name here");
    }

    @Test
    void dependencyInfoAppendsContent() {
        PxChunk dep = PxChunk.create(
                c -> c.setId("com.example.Foo"),
                c -> c.setContent("DEP-CONTENT"),
                c -> c.getMetadata().put("java_code_section", "dependenciesInfo"));

        String result = modifier.modifyPrompt(dao, dep, "prompt");

        assertThat(result).contains("DEP-CONTENT");
    }

    @Test
    void varargsMethodNameIsResolved() {
        PxChunk varargs = PxChunk.create(
                c -> c.setId("com.example.Foo.bar(int... args)"),
                c -> c.setContent("public void bar(int... args){\n    body\n}"),
                c -> c.getMetadata().put("java_code_section", "method"));
        JavaPromptModifier skeleton = new JavaPromptModifier(new SearchParams(20, 0.85, true));

        String result = skeleton.modifyPrompt(dao, varargs, "call bar(int... args) here");

        assertThat(result).contains("public void bar(int... args);");
    }

    @Test
    void skeletonSignatureSkipsBlankLinesAndReturnsFirstNonBraceLine() {
        PxChunk single = PxChunk.create(
                c -> c.setId("com.example.Foo.baz"),
                c -> c.setContent("\npublic int baz()"),
                c -> c.getMetadata().put("java_code_section", "method"));
        JavaPromptModifier skeleton = new JavaPromptModifier(new SearchParams(20, 0.85, true));

        String result = skeleton.modifyPrompt(dao, single, "call baz here");

        assertThat(result).contains("public int baz()");
    }

    @Test
    void skeletonSignatureOfBlankContentIsNoOp() {
        PxChunk blank = PxChunk.create(
                c -> c.setId("com.example.Foo.qux"),
                c -> c.setContent("   "),
                c -> c.getMetadata().put("java_code_section", "method"));
        JavaPromptModifier skeleton = new JavaPromptModifier(new SearchParams(20, 0.85, true));

        String result = skeleton.modifyPrompt(dao, blank, "call qux here");

        assertThat(result).contains("call").contains("here");
    }

    // ---- Phase 02 (Graphify-Tool-V2): cluster id + pointer layer on class frames ----

    private PxChunk clazzFrameChunkWithFile() {
        return PxChunk.create(
                c -> c.setId("com.example.Foo"),
                c -> c.setFile("/abs/path/com/example/Foo.java"),
                c -> c.setContent("class Foo {}"),
                c -> c.getMetadata().put("java_code_section", "classFrame"));
    }

    @Test
    void fullLevelEmitsSkeletonAndPointerLayer() {
        JavaPromptModifier full = new JavaPromptModifier(new SearchParams(20, 0.85));

        String result = full.modifyPrompt(dao, clazzFrameChunkWithFile(), "prompt");

        assertThat(result).contains("class Foo {}");
        assertThat(result).contains("readFile(");
        assertThat(result).contains("readBySignature(");
        assertThat(result).contains("graphNode(");
    }

    @Test
    void skeletonLevelEmitsSkeletonAndPointerLayer() {
        JavaPromptModifier skeleton = new JavaPromptModifier(new SearchParams(20, 0.85, true));

        String result = skeleton.modifyPrompt(dao, clazzFrameChunkWithFile(), "prompt");

        assertThat(result).contains("class Foo {}");
        assertThat(result).contains("readFile(");
        assertThat(result).contains("readBySignature(");
        assertThat(result).contains("graphNode(");
    }

    @Test
    void clusterMetadataEmitsClusterPointer() {
        PxChunk clustered = PxChunk.create(
                c -> c.setId("com.example.Foo"),
                c -> c.setFile("/abs/path/com/example/Foo.java"),
                c -> c.setContent("class Foo {}"),
                c -> c.getMetadata().put("java_code_section", "classFrame"),
                c -> c.getMetadata().put(GraphMetadata.LEIDEN_CLUSTER_ID, "c6"));
        JavaPromptModifier full = new JavaPromptModifier(new SearchParams(20, 0.85));

        String result = full.modifyPrompt(dao, clustered, "prompt");

        assertThat(result).contains("[cluster:c6]");
    }

    @Test
    void missingClusterMetadataEmitsNoClusterPointer() {
        JavaPromptModifier full = new JavaPromptModifier(new SearchParams(20, 0.85));

        String result = full.modifyPrompt(dao, clazzFrameChunkWithFile(), "prompt");

        assertThat(result).doesNotContain("[cluster:");
    }

    @Test
    void chunkWithoutFileEmitsNoReadFilePointer() {
        PxChunk noFile = PxChunk.create(
                c -> c.setId("com.example.NoFile"),
                c -> c.setContent("class NoFile {}"),
                c -> c.getMetadata().put("java_code_section", "classFrame"));
        JavaPromptModifier full = new JavaPromptModifier(new SearchParams(20, 0.85));

        String result = full.modifyPrompt(dao, noFile, "prompt");

        assertThat(result).doesNotContain("readFile(\"null\")");
        assertThat(result).doesNotContain("readFile(");
        assertThat(result).contains("readBySignature(\"com.example.NoFile\")");
    }

    @Test
    void clusterPointerStartsOnItsOwnLineAfterDependencyMarker() {
        PxChunk clustered = PxChunk.create(
                c -> c.setId("com.example.Foo"),
                c -> c.setFile("/abs/path/com/example/Foo.java"),
                c -> c.setContent("class Foo {}"),
                c -> c.getMetadata().put("java_code_section", "classFrame"),
                c -> c.getMetadata().put(GraphMetadata.LEIDEN_CLUSTER_ID, "c7"));
        PxChunkDao depDao = daoWithDependencies(DependencyCompactorTest.dependencyContent(25, 15));
        JavaPromptModifier full = new JavaPromptModifier(new SearchParams(20, 0.85));

        String result = full.modifyPrompt(depDao, clustered, "prompt");

        assertThat(result).doesNotContain("angezeigt][cluster:");
        assertThat(result).contains("\n[cluster:c7]");
    }

    @Test
    void pointersHeaderAndClusterPointerAreAdjacentWithoutBlankLine() {
        PxChunk clustered = PxChunk.create(
                c -> c.setId("com.example.Foo"),
                c -> c.setContent("class Foo {}"),
                c -> c.getMetadata().put("java_code_section", "classFrame"),
                c -> c.getMetadata().put(GraphMetadata.LEIDEN_CLUSTER_ID, "c7"));
        SearchParams params = new SearchParams(20, 0.85);
        params.setContentLevel(ContentLevel.POINTERS);
        JavaPromptModifier pointers = new JavaPromptModifier(params);

        String result = pointers.modifyPrompt(dao, clustered, "prompt");

        assertThat(result).contains("## com.example.Foo\n[cluster:c7]\n");
    }

    @Test
    void pointersLevelEmitsOnlyPointerLayer() {
        SearchParams params = new SearchParams(20, 0.85);
        params.setContentLevel(ContentLevel.POINTERS);
        JavaPromptModifier pointers = new JavaPromptModifier(params);
        // dependency chunk exists in the store, but POINTERS must not render it
        PxChunkDao depDao = daoWithDependencies(DependencyCompactorTest.dependencyContent(25, 15));

        String result = pointers.modifyPrompt(depDao, clazzFrameChunkWithFile(), "prompt");

        assertThat(result).contains("## com.example.Foo");
        assertThat(result).contains("readFile(");
        assertThat(result).contains("readBySignature(");
        assertThat(result).contains("graphNode(");
        assertThat(result).doesNotContain("```java");
        assertThat(result).doesNotContain("### Hier noch Infos zu den Dependencies");
    }
}
