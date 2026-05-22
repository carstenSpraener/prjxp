package de.spraener.prjxp.common.config;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

@Component
@ConfigurationProperties(prefix = "prjxp") // <--- Das magische Prefix
@Data
public class PrjXPConfig {
    // For tools operating on a designated project (like TiBed)
    private String projectName = "default";

    // Spring matcht YAML-Keys im "Kebab-Case" (chuno-root-dir)
    // automatisch auf CamelCase-Felder (chunoRootDir).
    private String chunoRootDir;
    private String chunoOutput;
    private String chunoWhiteList;

    private String tibedInput;
    private int tibedBatchSize = 50;
    private boolean tibedResetStore = false;

    private String grInputSource;
    private String grQuestion;
    private String grProjectSourceDir;

    // --- Embedding Sektion ---
    // Standardwerte setzt du einfach direkt am Feld!
    private String embeddingOllamaUrl = "http://192.168.1.228:11434";
    private String embeddingModelName = "mxbai-embed-large";
    private int embeddingTimeoutSecs = 60;

    // Hierarchische Listen MÜSSEN vorinitialisiert sein
    private List<PrjXPEmbeddingStoreReference> embeddingStores = new ArrayList<>();
    private List<PrjXPChatModelReference> chatModels = new ArrayList<>();

    {
        PrjXPEmbeddingStoreReference local = new PrjXPEmbeddingStoreReference();
        local.setProjectName("default");
        local.setStoreURL("http://localhost:8000");
        local.setStoreDBName("prjxp");
        local.setStoreTenant("prjxp");
        local.setStoreCollectionName("prjxp");
        embeddingStores.add(local);

        PrjXPChatModelReference chatModelReference = new PrjXPChatModelReference();
        chatModelReference.setName("default");
        chatModelReference.setApiUrl("http://192.168.1.224:1234");
        chatModelReference.setModelName("gemma-4-31b");
        chatModelReference.setProviderType("openAI");
        chatModelReference.setApiKey("lm-studio");

        chatModels.add(chatModelReference);
    }
}