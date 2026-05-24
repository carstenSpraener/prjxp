package de.spraener.prjxp.docpipe.llm;

/**
 * Enumeration of supported LLM server types.
 * <p>
 * This enum defines the different providers that DocPipe can interact with, 
 * such as Ollama, Gemini, LM Studio, and OpenAI-compatible servers.
 * </p>
 */
public enum ServerTypes {
    UNKNOWN("unknown"),
    OLLAMA("ollama"),
    GEMINI("gemini"),
    LM_STUDIO("lm-studio"),
    OPEN_API("openapi"),
    // This value is for other server types not directly supported
    // by PrjXP. It enables new implementation without changing this enum.
    CUSTOM("custom")
    ;

    private final String serverType;
    /**
     * Constructs a ServerTypes enum constant with the specified string representation.
     *
     * @param serverType the string identifier for the server type
     */
    ServerTypes(String serverType) {
        this.serverType = serverType;
    }

    /**
     * Returns the string representation of the server type.
     *
     * @return the server type identifier
     */
    public String serverType() {
        return serverType;
    }

    /**
     * Resolves a {@code ServerTypes} enum constant from its string representation.
     *
     * @param serverType the string identifier of the server type
     * @return the matching {@code ServerTypes} constant, or {@link #UNKNOWN} if no match is found
     */
    public static ServerTypes from(String serverType) {
        for( ServerTypes server : ServerTypes.values() ) {
            if( server.serverType.equals(serverType) ) {
                return server;
            }
        }
        return UNKNOWN;
    }
}
