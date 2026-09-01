package de.spraener.prjxp.common.config;

import lombok.Data;
import java.util.List;

@Data
public class McpServerReference {
    private String name;
    private String type; // "stdio" oder "sse"
    private String command;
    private List<String> args;
    private String url;
}
