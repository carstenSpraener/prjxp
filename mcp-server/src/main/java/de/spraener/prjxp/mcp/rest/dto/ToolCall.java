package de.spraener.prjxp.mcp.rest.dto;

import java.util.HashMap;
import java.util.Map;

/** Record of a tool call executed during chat. */
public class ToolCall {
    private String toolName;
    private Map<String, Object> arguments = new HashMap<>();
    private String result;

    public ToolCall() {}

    public ToolCall(String toolName, Map<String, Object> arguments, String result) {
        this.toolName = toolName;
        this.arguments = arguments;
        this.result = result;
    }

    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }

    public Map<String, Object> getArguments() { return arguments; }
    public void setArguments(Map<String, Object> arguments) { this.arguments = arguments; }

    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
}
