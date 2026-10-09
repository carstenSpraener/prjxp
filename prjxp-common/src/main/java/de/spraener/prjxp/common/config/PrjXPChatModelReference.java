package de.spraener.prjxp.common.config;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class PrjXPChatModelReference {
    private String stereoType;
    private String serverType;
    private String modelName;
    private String apiKey;
    private String providerUrl;
    private int timeoutSecs = 60;
    private double temperature = 0.;
    /** Optional standing instructions for this model, prepended to every conversation. Supports ${ENV} placeholders. */
    private String prompt;
    private Map<String,String> args = new HashMap<>();
}
