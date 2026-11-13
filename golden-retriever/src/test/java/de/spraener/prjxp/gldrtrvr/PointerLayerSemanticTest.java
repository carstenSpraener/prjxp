package de.spraener.prjxp.gldrtrvr;

import de.spraener.prjxp.common.config.GraphRerankConfig;
import de.spraener.prjxp.common.config.PrjXPEmbeddingStoreReference;
import de.spraener.prjxp.common.graph.GraphMetadata;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.ScoredChunk;
import de.spraener.prjxp.common.retrieval.ContentLevel;
import de.spraener.prjxp.common.retrieval.SearchParams;
import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import de.spraener.prjxp.gldrtrvr.chunks.ChunkRankingService;
import de.spraener.prjxp.gldrtrvr.chunks.ChunkRankingStrategy;
import de.spraener.prjxp.gldrtrvr.code.java.JavaPromptModifier;
import de.spraener.prjxp.gldrtrvr.md.MarkdownRetriever;
import de.spraener.prjxp.gldrtrvr.text.TextRetriever;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class PointerLayerSemanticTest {

    @Test
    void markdownRetrieverEmitsSemanticAndExistingFilePointers() {
        PxChunk chunk = chunk("c1", "text/markdown", "a.md", null,
                Map.of(GraphMetadata.SEMANTIC_CLUSTER_ID, "s3",
                        GraphMetadata.SEMANTIC_NODE_ID, "n1"));
        String prompt = markdownRetriever(chunk).buildPromptForFindings(
                "p", List.of(new ScoredChunk(chunk, 0.9)), pointerParams()).toString();

        assertThat(prompt).contains("→ readFile(\"a.md\")")
                .contains("cluster_id: \"s3\"")
                .contains("→ semanticGraphNode(\"n1\")");
    }

    @Test
    void markdownPointerOmitsClusterLineWithoutClusterMetadata() {
        PxChunk chunk = chunk("c1", "text/markdown", "a.md", null,
                Map.of(GraphMetadata.SEMANTIC_NODE_ID, "n1"));
        String prompt = markdownRetriever(chunk).buildPromptForFindings(
                "p", List.of(new ScoredChunk(chunk, 0.9)), pointerParams()).toString();

        // The renderer emits each field independently: no cluster id, but the node pointer stays.
        assertThat(prompt).contains("→ readFile(\"a.md\")")
                .doesNotContain("cluster_id:")
                .contains("→ semanticGraphNode(\"n1\")");
    }

    @Test
    void textFilePointerIncludesSemanticNode() {
        PxChunk chunk = chunk("c2", "text/plain", "notes.txt", null,
                Map.of(GraphMetadata.SEMANTIC_CLUSTER_ID, "s2",
                        GraphMetadata.SEMANTIC_NODE_ID, "node-2"));
        String prompt = textRetriever(chunk).buildPromptForFindings(
                "p", List.of(new ScoredChunk(chunk, 0.9)), pointerParams()).toString();

        assertThat(prompt).contains("→ readFile(\"notes.txt\")")
                .contains("cluster_id: \"s2\"")
                .contains("→ semanticGraphNode(\"node-2\")");
    }

    @Test
    void textDependencyPointerIncludesSemanticNodeAndCodeGraphPointer() {
        PxChunk chunk = chunk("de.ex.Foo.dependencies", "text/plain", null, "de.ex.Foo",
                Map.of("java_code_section", "dependenciesInfo",
                        GraphMetadata.SEMANTIC_CLUSTER_ID, "s1",
                        GraphMetadata.SEMANTIC_NODE_ID, "node-3"));
        String prompt = textRetriever(chunk).buildPromptForFindings(
                "p", List.of(new ScoredChunk(chunk, 0.9)), pointerParams()).toString();

        assertThat(prompt).contains("→ graphNode(\"de.ex.Foo\")")
                .contains("cluster_id: \"s1\"")
                .contains("→ semanticGraphNode(\"node-3\")");
    }

    @Test
    void javaClassFrameEmitsBothClusterPointers() {
        PxChunk chunk = chunk("de.ex.Foo", "text/x-java-code", "Foo.java", null,
                Map.of("java_code_section", "classFrame",
                        GraphMetadata.LEIDEN_CLUSTER_ID, "c2",
                        GraphMetadata.SEMANTIC_CLUSTER_ID, "s4",
                        GraphMetadata.SEMANTIC_NODE_ID, "node-4"));

        String prompt = new JavaPromptModifier(pointerParams())
                .modifyPrompt(dao(chunk), chunk, "prompt");

        assertThat(prompt).contains("[cluster:c2]")
                .contains("[semantic-cluster:s4]")
                .contains("→ semanticGraphNode(\"node-4\")")
                .contains("→ readBySignature(\"de.ex.Foo\")");
    }

    @Test
    void javaClassFrameWithoutSemanticMetadataKeepsOnlyCodeClusterPointer() {
        PxChunk chunk = chunk("de.ex.Foo", "text/x-java-code", "Foo.java", null,
                Map.of("java_code_section", "classFrame",
                        GraphMetadata.LEIDEN_CLUSTER_ID, "c2"));

        String prompt = new JavaPromptModifier(pointerParams())
                .modifyPrompt(dao(chunk), chunk, "prompt");

        assertThat(prompt).contains("[cluster:c2]")
                .doesNotContain("[semantic-cluster:")
                .doesNotContain("semanticGraphNode");
    }

    private MarkdownRetriever markdownRetriever(PxChunk chunk) {
        ChunkRankingStrategy ranking = new ChunkRankingStrategy() {
            @Override
            public boolean supports(PxChunk candidate) {
                return true;
            }

            @Override
            public double rank(PxChunk candidate) {
                return 1.0;
            }
        };
        return new MarkdownRetriever(
                new PxChunkDaoProvider(List.of(dao(chunk))),
                new ChunkRankingService(List.of(ranking), new GraphRerankConfig()));
    }

    private TextRetriever textRetriever(PxChunk chunk) {
        return new TextRetriever(new PxChunkDaoProvider(List.of(dao(chunk))));
    }

    private PxChunkDao dao(PxChunk chunk) {
        PrjXPEmbeddingStoreReference reference = new PrjXPEmbeddingStoreReference();
        reference.setProjectName("p");
        reference.setDefault(true);
        Map<String, List<PxChunk>> byId = new HashMap<>();
        byId.put(chunk.getId(), List.of(chunk));
        return new PxChunkDao() {
            @Override
            public PrjXPEmbeddingStoreReference getStoreReference() {
                return reference;
            }

            @Override
            public List<PxChunk> findById(String id) {
                return new java.util.ArrayList<>(byId.getOrDefault(id, List.of()));
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
                return Stream.of(chunk);
            }
        };
    }

    private PxChunk chunk(String id, String mime, String file, String parent, Map<String, String> metadata) {
        return PxChunk.create(
                chunk -> chunk.setId(id),
                chunk -> chunk.setMimeType(mime),
                chunk -> chunk.setFile(file),
                chunk -> chunk.setParent(parent),
                chunk -> chunk.setPart(0),
                chunk -> chunk.setTotal(1),
                chunk -> chunk.setContent("content"),
                chunk -> chunk.getMetadata().putAll(metadata));
    }

    private SearchParams pointerParams() {
        SearchParams params = new SearchParams(20, 0.85);
        params.setContentLevel(ContentLevel.POINTERS);
        return params;
    }
}
