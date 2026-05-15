package de.spraener.prjxp.docpipe.model;

import lombok.Data;

@Data
public class DPModelConfig {
    private String stereotype;
    private String modelName;
    private String modelProviderURL;
    private String serverType = "ollama";
}
