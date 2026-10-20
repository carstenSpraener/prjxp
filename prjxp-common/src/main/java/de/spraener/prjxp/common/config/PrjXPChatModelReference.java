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
    /**
     * Client/connection-level parameters (supplier-specific setup, e.g. Azure endpoint, api-version,
     * trust-all-certs). Consumed by the individual {@code ChatModelSupplier} implementations.
     */
    private Map<String,String> args = new HashMap<>();
    /**
     * Request-level properties merged into every chat-completion request body (via the provider's
     * "custom parameters", serialized as top-level JSON body fields). Use for provider-specific knobs
     * that have no dedicated config field, e.g. {@code enable_thinking: false} for Qwen3 (its thinking
     * mode breaks tool-calling). Values may be booleans, numbers, strings, or nested objects; string
     * values that look like booleans/numbers are coerced to their native JSON type by the supplier.
     */
    private Map<String,Object> bodyProperties = new HashMap<>();
}
