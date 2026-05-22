package de.spraener.prjxp.common.config;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
public class ProjectDefinition {
    private String name;
    private String rootDir;
    private String jsonlFile;
    private String chunoWhiteList = "java,ts";
    private int tibedBatchSize = 50;
    private boolean tibedResetStore = false;
}
