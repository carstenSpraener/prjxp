package de.spraener.prjxp.common.config;

import de.spraener.prjxp.common.transfer.TransferEncryptMode;
import de.spraener.prjxp.common.transfer.TransferMode;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
@ConfigurationProperties(prefix = "prjxp") // <--- Das magische Prefix
@Data
public class PrjXPConfig {
    private String activeProject = "cwd";
    private List<ProjectDefinition> projects = new ArrayList<>();
    // In PrjXPConfig.java ergänzen:
    private List<McpServerReference> mcpServers = new ArrayList<>();
    // --- Embedding Sektion ---

    public enum EmbeddingModelType {
        OLLAMA, OPEN_AI
    }

    // Hierarchische Listen MÜSSEN vorinitialisiert sein
    private List<PrjXPEmbeddingStoreReference> embeddingStores = new ArrayList<>();
    private List<PrjXPChatModelReference> chatModels = new ArrayList<>();

    private LuceneEmbeddingStoreConfig embeddingStoreLucene = new LuceneEmbeddingStoreConfig();
    /** Maximum characters returned by the readFile MCP tool (0 = uncapped). Property: prjxp.reader-max-output-chars */
    private int readerMaxOutputChars = 100_000;
    private EmbeddingConfig embedding = new EmbeddingConfig();

    @lombok.Data
    public static class LuceneEmbeddingStoreConfig {
        private String indexPath = ".prjxp-data/lucene-index";
        private int vectorDimension = 1024;
        private String name = "prjxp";
    }

    @lombok.Data
    public static class EmbeddingConfig {
        private EmbeddingModelType type = EmbeddingModelType.OPEN_AI;
        private String apiBaseURL = "http://host.docker.internal:1234";
        private String apiKey = "lm-studio";
        private String modelName = "mxbai-embed-large-v1";
        private int timeout = 20;
    }

    // --- Transfer Sektion (externes Embedding) ---
    private TransferConfig transfer = new TransferConfig();

    @lombok.Data
    public static class TransferConfig {
        private String passwordEnv = "PRJXP_TRANSFER_PASSWORD";
        private TransferEncryptMode encrypt = TransferEncryptMode.AUTO;
        private String input;
        private String output;
        private TransferMode mode = TransferMode.STORE;
    }

    private ProjectDefinition createCwdFallback() {
        ProjectDefinition cwd = new ProjectDefinition();
        cwd.setName("cwd");
        cwd.setRootDir(".");
        cwd.setJsonlFile("px-chunks.jsonl");
        cwd.setChunoWhiteList("java,ts");
        cwd.setTibedBatchSize(32);
        cwd.setTibedResetStore(true);
        return cwd;
    }

    public Optional<ProjectDefinition> getProjectDefinition(String name) {
        if (projects.isEmpty() && "cwd".equals(name)) {
            return Optional.of(createCwdFallback());
        }
        return projects.stream()
                .filter(pd -> pd.getName().equals(name))
                .findFirst();
    }

    public Optional<ProjectDefinition> getActiveProject() {
        return getProjectDefinition(activeProject);
    }

    /** Raw configured active-project name (may not match any entry in projects[]). */
    public String getActiveProjectName() { return activeProject; }

    /** Like getProjectDefinition but fails fast with the list of available projects. */
    public ProjectDefinition requireProject(String name) {
        return getProjectDefinition(name).orElseThrow(() -> new IllegalStateException(
                "Unknown project '" + name + "'. Available projects: "
                        + projects.stream().map(ProjectDefinition::getName).toList()));
    }

    /** Create a safe, read-only subset of this config for sandboxed Groovy execution. */
    public ConfigSubset toConfigSubset() {
        return new ConfigSubset(
                getActiveProjectName(),
                getProjects().stream().map(ProjectDefinition::getName).toList(),
                getChatModels() != null ? getChatModels().stream()
                        .map(PrjXPChatModelReference::getStereoType).toList() : List.of(),
                getMcpServers() != null ? getMcpServers().stream()
                        .map(McpServerReference::getName).toList() : List.of(),
                "LUCENE", // Only Lucene is supported in this project
                getEmbeddingStoreLucene() != null ? getEmbeddingStoreLucene().getIndexPath() : ".prjxp-data/lucene-index",
                getEmbeddingStoreLucene() != null ? getEmbeddingStoreLucene().getVectorDimension() : 1024
        );
    }

}