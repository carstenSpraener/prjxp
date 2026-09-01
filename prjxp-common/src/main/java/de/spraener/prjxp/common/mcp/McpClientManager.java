package de.spraener.prjxp.common.mcp;

import de.spraener.prjxp.common.chat.KIChat;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.McpServerReference;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Log
public class McpClientManager {
    private final PrjXPConfig cfg;
    private final List<McpClient> activeClients = new ArrayList<>();

    @PostConstruct
    public void init() {
        if (cfg.getMcpServers() == null) return;

        for (McpServerReference ref : cfg.getMcpServers()) {
            try {
                log.info("Initialisiere globalen MCP Server: " + ref.getName());

                if ("stdio".equalsIgnoreCase(ref.getType())) {
                    // 1. Baue die vollständige Command-Liste zusammen (Befehl + Argumente)
                    List<String> fullCommand = new ArrayList<>();
                    fullCommand.add(ref.getCommand()); // z.B. "npx"
                    if (ref.getArgs() != null) {
                        fullCommand.addAll(ref.getArgs()); // z.B. ["-y", "@modelcontextprotocol/server-filesystem", ...]
                    }

                    // 2. Übergib die gesamte Liste direkt an .command(...)
                    McpTransport transport = new StdioMcpTransport.Builder()
                            .command(fullCommand)
                            .logEvents(false)
                            .build();

                    // 3. MCP Client erzeugen
                    McpClient client = new DefaultMcpClient.Builder()
                            .clientName(ref.getName())
                            .transport(transport)
                            .toolExecutionTimeout(Duration.ofSeconds(60))
                            .build();

                    activeClients.add(client);
                }
            } catch (Exception e) {
                log.severe("Fehler beim Starten des globalen MCP-Servers " + ref.getName() + ": " + e.getMessage());
            }
        }
    }

    public List<McpClient> getActiveClients() {
        return activeClients;
    }

    public Optional<KIChat> decorate(Optional<KIChat> optionalChat) {
        // * Ist das optional gefüllt? Nein -> Optional direkt zurückgeben
        if (optionalChat.isEmpty()) {
            return optionalChat;
        }

        // * Haben wir konfigurierte MCP-Server? Nein -> Optional direkt zurückgeben
        if (activeClients.isEmpty()) {
            return optionalChat;
        }

        // * Ansonsten: Verpacke den originalen KIChat in den MCP-Decorator
        log.info("Verpacke KIChat in ein McPEnabledKIChatImpl mit " + activeClients.size() + " aktiven MCP-Servern.");
        KIChat originalChat = optionalChat.get();
        KIChat mcpEnabledChat = new McPEnablingKIChatDecorator(originalChat, activeClients);

        return Optional.of(mcpEnabledChat);
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