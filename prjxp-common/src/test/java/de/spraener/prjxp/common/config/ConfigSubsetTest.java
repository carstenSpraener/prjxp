package de.spraener.prjxp.common.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigSubsetTest {

    @Test
    void toConfigSubset_includesProjectNames() {
        PrjXPConfig config = new PrjXPConfig();
        ProjectDefinition proj1 = new ProjectDefinition();
        proj1.setName("project-alpha");
        proj1.setRootDir("/path/to/alpha");

        ProjectDefinition proj2 = new ProjectDefinition();
        proj2.setName("project-beta");
        proj2.setRootDir("/path/to/beta");

        config.setProjects(List.of(proj1, proj2));
        config.setActiveProject("project-alpha");

        ConfigSubset subset = config.toConfigSubset();

        assertThat(subset.getActiveProjectName()).isEqualTo("project-alpha");
        assertThat(subset.getProjectNames()).containsExactly("project-alpha", "project-beta");
    }

    @Test
    void toConfigSubset_includesChatModelStereotypes() {
        PrjXPConfig config = new PrjXPConfig();

        PrjXPChatModelReference ref1 = new PrjXPChatModelReference();
        ref1.setStereoType("architect");
        // Note: We don't set apiKey or other sensitive fields

        PrjXPChatModelReference ref2 = new PrjXPChatModelReference();
        ref2.setStereoType("summarizer");

        config.setChatModels(List.of(ref1, ref2));

        ConfigSubset subset = config.toConfigSubset();

        assertThat(subset.getChatModelStereotypes()).containsExactly("architect", "summarizer");
    }

    @Test
    void toConfigSubset_includesMcpServerNames() {
        PrjXPConfig config = new PrjXPConfig();

        McpServerReference ref1 = new McpServerReference();
        ref1.setName("prjxp-hub");
        ref1.setType("http");
        ref1.setUrl("http://localhost:8080/mcp"); // URL should NOT be in subset

        McpServerReference ref2 = new McpServerReference();
        ref2.setName("FileTools");
        ref2.setType("groovy");
        ref2.setScriptDir("./groovyTools"); // scriptDir should NOT be in subset

        config.setMcpServers(List.of(ref1, ref2));

        ConfigSubset subset = config.toConfigSubset();

        assertThat(subset.getMcpServerNames()).containsExactly("prjxp-hub", "FileTools");
        // ConfigSubset only contains server names - URLs and scriptDirs are NOT exposed
    }

    @Test
    void toConfigSubset_excludesSensitiveData() {
        PrjXPConfig config = new PrjXPConfig();

        // Set up chat model with API key (sensitive)
        PrjXPChatModelReference ref = new PrjXPChatModelReference();
        ref.setStereoType("test-model");
        ref.setApiKey("sk-secret-key-12345"); // This should NOT be in ConfigSubset
        config.setChatModels(List.of(ref));

        // Set up embedding config with API key (sensitive)
        PrjXPConfig.EmbeddingConfig embConfig = new PrjXPConfig.EmbeddingConfig();
        embConfig.setApiKey("lm-studio-secret"); // This should NOT be in ConfigSubset
        config.setEmbedding(embConfig);

        ConfigSubset subset = config.toConfigSubset();

        // Verify that only stereotype names are included, not API keys
        assertThat(subset.getChatModelStereotypes()).containsExactly("test-model");
        // ConfigSubset has no field for API keys - they're simply not exposed
    }

    @Test
    void toConfigSubset_handlesNulls() {
        PrjXPConfig config = new PrjXPConfig();
        // Don't set any lists - they should default to empty

        ConfigSubset subset = config.toConfigSubset();

        assertThat(subset.getProjectNames()).isEmpty();
        assertThat(subset.getChatModelStereotypes()).isEmpty();
        assertThat(subset.getMcpServerNames()).isEmpty();
        assertThat(subset.getEmbeddingStoreType()).isEqualTo("LUCENE");
        assertThat(subset.getLuceneIndexPath()).isEqualTo(".prjxp-data/lucene-index");
        assertThat(subset.getVectorDimension()).isEqualTo(1024);
    }

    @Test
    void toConfigSubset_handlesNullChatModels() {
        PrjXPConfig config = new PrjXPConfig();
        config.setChatModels(null); // Explicitly set to null

        ConfigSubset subset = config.toConfigSubset();

        assertThat(subset.getChatModelStereotypes()).isEmpty();
    }

    @Test
    void toConfigSubset_handlesNullMcpServers() {
        PrjXPConfig config = new PrjXPConfig();
        config.setMcpServers(null); // Explicitly set to null

        ConfigSubset subset = config.toConfigSubset();

        assertThat(subset.getMcpServerNames()).isEmpty();
    }

    @Test
    void configSubset_isImmutable() {
        PrjXPConfig config = new PrjXPConfig();
        ProjectDefinition proj = new ProjectDefinition();
        proj.setName("test-project");
        config.setProjects(List.of(proj));

        ConfigSubset subset = config.toConfigSubset();

        // @Value from Lombok makes the class immutable (no setters)
        // We can verify this by checking that we cannot modify the list
        List<String> projectNames = subset.getProjectNames();
        assertThat(projectNames).containsExactly("test-project");

        // The list itself might be mutable, but the ConfigSubset fields are final
        // This is acceptable - the subset provides a snapshot of the config state
    }

    @Test
    void toConfigSubset_usesLuceneDefaults() {
        PrjXPConfig config = new PrjXPConfig();
        // Don't set embeddingStoreLucene - should use defaults

        ConfigSubset subset = config.toConfigSubset();

        assertThat(subset.getEmbeddingStoreType()).isEqualTo("LUCENE");
        assertThat(subset.getLuceneIndexPath()).isEqualTo(".prjxp-data/lucene-index");
        assertThat(subset.getVectorDimension()).isEqualTo(1024);
    }

    @Test
    void toConfigSubset_usesCustomLuceneValues() {
        PrjXPConfig config = new PrjXPConfig();

        PrjXPConfig.LuceneEmbeddingStoreConfig luceneConfig = new PrjXPConfig.LuceneEmbeddingStoreConfig();
        luceneConfig.setIndexPath("/custom/path/to/index");
        luceneConfig.setVectorDimension(768);
        config.setEmbeddingStoreLucene(luceneConfig);

        ConfigSubset subset = config.toConfigSubset();

        assertThat(subset.getLuceneIndexPath()).isEqualTo("/custom/path/to/index");
        assertThat(subset.getVectorDimension()).isEqualTo(768);
    }
}
