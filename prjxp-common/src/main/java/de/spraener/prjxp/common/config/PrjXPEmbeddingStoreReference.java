package de.spraener.prjxp.common.config;

import lombok.Data;

@Data
public class PrjXPEmbeddingStoreReference {
    private String projectName;
    private String storeURL;
    private String storeTenant;
    private String storeDBName;
    private String storeCollectionName;
    private boolean isDefault;
}
