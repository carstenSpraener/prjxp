package de.spraener.prjxp.common.config;

import lombok.Data;

@Data
public class PrjXPEmbeddingModelReference {
    private String serverType = "ollama";       // ollama, openai, lm-studio, gemini, custom
    private String providerUrl;                   // e.g. http://192.168.188.92:11434
    private String modelName;                     // e.g. mxbai-embed-large
    private String apiKey;                        // optional, for openai/gemini
    private Integer timeoutSecs = 60;
}
