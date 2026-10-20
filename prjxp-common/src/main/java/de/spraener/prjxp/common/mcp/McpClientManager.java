package de.spraener.prjxp.common.mcp;

import de.spraener.prjxp.common.chat.KIChat;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.McpServerReference;
import de.spraener.prjxp.common.toolregistry.ToolRegistry;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
@Log
public class McpClientManager {
    private final PrjXPConfig cfg;
    private final ToolRegistry toolRegistry;
    private final List<McpClient> activeClients = new ArrayList<>();

    @PostConstruct
    public void init() {
        if (cfg.getMcpServers() == null) return;

        for (McpServerReference ref : cfg.getMcpServers()) {
            try {
                log.info("Initialisiere MCP Server: " + ref.getName() + " (type=" + ref.getType() + ")");

                if ("groovy".equalsIgnoreCase(ref.getType())) {
                    // Groovy script tools: load from scriptDir into ToolRegistry
                    String scriptDir = ref.getScriptDir();
                    if (scriptDir == null || scriptDir.isBlank()) {
                        scriptDir = "./groovyTools"; // default relative to project root
                    }
                    Path dirPath = Path.of(System.getProperty("user.dir")).resolve(scriptDir);
                    toolRegistry.loadGroovyTools(dirPath);
                } else if ("stdio".equalsIgnoreCase(ref.getType())) {
                    // 1. Baue die vollständige Command-Liste zusammen (Befehl + Argumente)
                    List<String> fullCommand = new ArrayList<>();
                    fullCommand.add(ref.getCommand()); // z.B. "npx"
                    if (ref.getArgs() != null) {
                        fullCommand.addAll(ref.getArgs()); // z.B. ["-y", "@modelcontextprotocol/server-filesystem", ...]
                    }

                    // 2. Übergib die gesamte Liste direkt an .command(...)
                    McpTransport transport = StdioMcpTransport.builder()
                            .command(fullCommand)
                            .logEvents(false)
                            .build();

                    // 3. MCP Client erzeugen
                    McpClient client = DefaultMcpClient.builder()
                            .key(ref.getName())
                            .transport(transport)
                            .toolExecutionTimeout(Duration.ofSeconds(60))
                            .build();

                    activeClients.add(client);
                    toolRegistry.registerMcpClient(client); // Register in ToolRegistry
                } else if ("http".equalsIgnoreCase(ref.getType())) {
                    String url = ref.getUrl();
                    if (url == null || url.isBlank()) {
                        log.severe("HTTP MCP Server '" + ref.getName() + "' has no URL configured. Skipping.");
                        continue;
                    }

                    McpTransport httpTransport = StreamableHttpMcpTransport.builder()
                            .url(url)
                            .logRequests(false)
                            .logResponses(false)
                            .build();

                    McpClient httpClient = DefaultMcpClient.builder()
                            .key(ref.getName())
                            .transport(httpTransport)
                            .toolExecutionTimeout(Duration.ofSeconds(60))
                            .build();

                    activeClients.add(httpClient);
                    toolRegistry.registerMcpClient(httpClient); // Register in ToolRegistry
                } else {
                    log.warning("Unknown MCP server type '" + ref.getType() + "' for server '" + ref.getName() + "'. Skipping.");
                }
            } catch (Exception e) {
                log.severe("Fehler beim Starten des MCP-Servers " + ref.getName() + ": " + e.getMessage());
            }
        }
    }

    public List<McpClient> getActiveClients() {
        return activeClients;
    }

    /**
     * Wraps the given chat in a {@link McPEnablingKIChatDecorator} so the LLM can call the
     * configured MCP tools. Returns the input unchanged when it is {@code null} or no MCP
     * client is active, so callers can apply this unconditionally.
     */
    public KIChat decorate(KIChat chat) {
        if (chat == null) {
            return null;
        }
        if (activeClients.isEmpty()) {
            return chat;
        }

        String project = cfg.getMcpServers().stream()
                .filter(ref -> ref.getDefaultProject() != null && !ref.getDefaultProject().isBlank())
                .map(McpServerReference::getDefaultProject)
                .findFirst()
                .orElse(null);

        log.info("Verpacke KIChat in McPEnablingKIChatDecorator mit " + activeClients.size() + " aktiven MCP-Servern.");
        return new McPEnablingKIChatDecorator(chat, activeClients, project);
    }

    @PreDestroy
    public void shutdown() {
        log.info("Fahre MCP-Server herunter...");
        for (McpClient client : activeClients) {
            try {
                client.close();
            } catch (Exception ignored) {}
        }
    }
}
