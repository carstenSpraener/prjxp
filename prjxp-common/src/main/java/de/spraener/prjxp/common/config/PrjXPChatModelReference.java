package de.spraener.prjxp.common.config;

import lombok.Data;

@Data
public class PrjXPChatModelReference {
    private String name;
    private String providerType;
    private String modelName;
    private String apiKey;
    private String apiUrl;
}
