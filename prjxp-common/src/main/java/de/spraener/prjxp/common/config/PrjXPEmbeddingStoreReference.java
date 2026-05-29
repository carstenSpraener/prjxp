package de.spraener.prjxp.common.config;

import lombok.Data;

@Data
public class PrjXPEmbeddingStoreReference {
    private String projectName;
    private String providerUrl;
    private String tenant;
    private String dbName;
    private String collectionName;
    private boolean isDefault;
    private int timeoutSecs = 60;
}
