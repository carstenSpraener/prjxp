package de.spraener.prjxp.docpipe.llm;

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
    ServerTypes(String serverType) {
        this.serverType = serverType;
    }

    public String serverType() {
        return serverType;
    }

    public static ServerTypes from(String serverType) {
        for( ServerTypes server : ServerTypes.values() ) {
            if( server.serverType.equals(serverType) ) {
                return server;
            }
        }
        return UNKNOWN;
    }
}
