package de.spraener.prjxp.common.config;

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

    // Standardwerte setzt du einfach direkt am Feld!
    private String embeddingOllamaUrl = "http://192.168.1.228:11434";
    private String embeddingModelName = "mxbai-embed-large";
    private int embeddingTimeoutSecs = 60;

    // Embedding model type: "ollama" or "onnx_local"
    private EmbeddingModelType embeddingModelType = EmbeddingModelType.OLLAMA;

    public enum EmbeddingModelType {
        OLLAMA, ONNX_LOCAL
    }

    // Embedding server auto-start config (ONNX_LOCAL mode)
    private String embeddingServerScriptPath = "prjxp-common/embedding-server/scripts/embedding-server.py";
    private String embeddingServerModelPath = "prjxp-common/embedding-server/models/model.onnx";
    private String embeddingServerModelsDir = "prjxp-common/embedding-server/models";
    private int embeddingServerPort = 11435;

    // Hierarchische Listen MÜSSEN vorinitialisiert sein
    private List<PrjXPEmbeddingStoreReference> embeddingStores = new ArrayList<>();
    private List<PrjXPChatModelReference> chatModels = new ArrayList<>();

    // Embedding store type: "lucene" or "chroma"
    private EmbeddingStoreType embeddingStoreType = EmbeddingStoreType.CHROMA;
    private LuceneEmbeddingStoreConfig embeddingStoreLucene = new LuceneEmbeddingStoreConfig();

    public enum EmbeddingStoreType {
        LUCENE, CHROMA
    }

    @lombok.Data
    public static class LuceneEmbeddingStoreConfig {
        private String indexPath = ".prjxp-data/lucene-index";
        private int vectorDimension = 1024;
    }

    private ProjectDefinition createCwdFallback() {
        ProjectDefinition cwd = new ProjectDefinition();
        cwd.setName("cwd");
        cwd.setRootDir(".");
        cwd.setJsonlFile("px-chunks.jsonl");
        cwd.setChunoWhiteList("java,ts");
        cwd.setTibedBatchSize(50);
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
}