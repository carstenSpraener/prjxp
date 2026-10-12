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
    /**
     * Explicitly enables/disables native function calling for this model.
     * {@code null} (default) means auto-detect: tools are attempted and the chat falls back to the
     * text protocol if the provider rejects them.
     */
    private Boolean supportsTools;
    private Map<String,String> args = new HashMap<>();
}
