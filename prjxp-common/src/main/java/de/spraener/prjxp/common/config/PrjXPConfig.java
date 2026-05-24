package de.spraener.prjxp.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
@ConfigurationProperties(prefix = "prjxp") // <--- Das magische Prefix
@Data
public class PrjXPConfig {
    private String activeProject = "cwd";
    private List<ProjectDefinition> projects = new ArrayList<>();

    // --- Embedding Sektion ---

    // Standardwerte setzt du einfach direkt am Feld!
    private String embeddingOllamaUrl = "http://192.168.1.228:11434";
    private String embeddingModelName = "mxbai-embed-large";
    private int embeddingTimeoutSecs = 60;

    // Hierarchische Listen MÜSSEN vorinitialisiert sein
    private List<PrjXPEmbeddingStoreReference> embeddingStores = new ArrayList<>();
    private List<PrjXPChatModelReference> chatModels = new ArrayList<>();

    {
        ProjectDefinition cwd = new ProjectDefinition();
        cwd.setName("cwd");
        cwd.setRootDir(".");
        cwd.setJsonlFile("px-chunks.jsonl");
        cwd.setChunoWhiteList("java,ts");
        cwd.setTibedBatchSize(50);
        cwd.setTibedResetStore(true);
        projects.add(cwd);

        PrjXPEmbeddingStoreReference local = new PrjXPEmbeddingStoreReference();
        local.setProjectName("default");
        local.setProviderUrl("http://localhost:8000");
        local.setDbName("prjxp");
        local.setTenant("prjxp");
        local.setCollectionName("prjxp");
        embeddingStores.add(local);

        PrjXPChatModelReference chatModelReference = new PrjXPChatModelReference();
        chatModelReference.setStereoType("default");
        chatModelReference.setProviderUrl("http://192.168.1.224:1234");
        chatModelReference.setModelName("gemma-4-31b");
        chatModelReference.setServerType("openAI");
        chatModelReference.setApiKey("lm-studio");

        chatModels.add(chatModelReference);
    }

    public Optional<ProjectDefinition> getProjectDefinition(String name) {
        return projects.stream()
                .filter(
                pd -> pd.getName().equals(name)
                ).findFirst();
    }

    public Optional<ProjectDefinition> getActiveProject() {
        return getProjectDefinition(activeProject);
    }
}