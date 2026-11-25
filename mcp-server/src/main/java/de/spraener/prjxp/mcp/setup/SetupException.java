package de.spraener.prjxp.mcp.setup;

public class SetupException extends RuntimeException {
    private final String code;
    private final String step;
    private final int status;

    public SetupException(String code, String step, int status, String message) {
        super(message);
        this.code = code;
        this.step = step;
        this.status = status;
    }

    public String code() { return code; }
    public String step() { return step; }
    public int status() { return status; }
}
