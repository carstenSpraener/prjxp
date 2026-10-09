# Phase 03: Integration

> **STRICT EXECUTION RULES FOR THE AGENT:**
> 1. DO NOT run repository scans (`find_files`, `grep`, `search_code`, `list_dir`).
> 2. Read ONLY the files explicitly listed in the "File Scope" section below.
> 3. All required interfaces/DTOs are provided inline in this document. Do not fetch them from the codebase.
> 4. **MANDATORY FINAL STEP:** As soon as the implementation and tests (>= 80% coverage) are green, execute `/compact` IMMEDIATELY to clean up the context window before returning control.

## 1. Target & Scope
* **Objective:** Integrate `ToolRegistry` into `McpClientManager` and `McPEnablingKIChatDecorator`. The registry becomes the single source of truth for all tools.
* **Target Files to Create/Modify:**
    * Modify: `prjxp-common/src/main/java/de/spraener/prjxp/common/mcp/McpClientManager.java`
    * Modify: `prjxp-common/src/main/java/de/spraener/prjxp/common/mcp/McPEnablingKIChatDecorator.java`
    * Create: `prjxp-common/src/test/java/de/spraener/prjxp/common/mcp/McpClientManagerTest.java`
    * Create: `prjxp-common/src/test/java/de/spraener/prjxp/common/mcp/McPEnablingKIChatDecoratorTest.java`

## 2. Inline Required Context (Contracts & Signatures)

### `McpClientManager.java` (MODIFY)
**Changes:**
1. Add `ToolRegistry` as a dependency
2. In `init()`: After creating MCP clients, register them in the ToolRegistry
3. In `init()`: For Groovy-type servers, call `toolRegistry.loadGroovyTools(scriptDir)`
4. In `decorate()`: Pass `ToolRegistry` to the decorator instead of raw `List<McpClient>`

```java
@Component
@RequiredArgsConstructor
@Log
public class McpClientManager {
    private final PrjXPConfig cfg;
    private final ToolRegistry toolRegistry;   // NEU: dependency injection

    @PostConstruct
    public void init() {
        if (cfg.getMcpServers() == null) return;

        for (McpServerReference ref : cfg.getMcpServers()) {
            try {
                log.info("Initialisiere MCP Server: " + ref.getName());

                if ("stdio".equalsIgnoreCase(ref.getType())) {
                    // ... existing stdio logic (unchanged) ...
                    McpClient client = DefaultMcpClient.builder()...build();
                    toolRegistry.registerMcpClient(client);   // NEU: register in ToolRegistry

                } else if ("http".equalsIgnoreCase(ref.getType())) {
                    // ... existing http logic (unchanged) ...
                    McpClient client = DefaultMcpClient.builder()...build();
                    toolRegistry.registerMcpClient(client);   // NEU: register in ToolRegistry

                } else if ("groovy".equalsIgnoreCase(ref.getType())) {   // NEU
                    String scriptDir = ref.getScriptDir();
                    if (scriptDir == null || scriptDir.isBlank()) {
                        scriptDir = "./groovyTools";   // default
                    }
                    Path dir = Path.of(scriptDir).isAbsolute()
                            ? Path.of(scriptDir)
                            : Path.of(System.getProperty("user.dir")).resolve(scriptDir);
                    toolRegistry.loadGroovyTools(dir);
                }
            } catch (Exception e) {
                log.severe("Fehler beim Starten des MCP-Servers " + ref.getName() + ": " + e.getMessage());
            }
        }
    }

    public Optional<KIChat> decorate(Optional<KIChat> optionalChat) {
        if (optionalChat.isEmpty() || toolRegistry.getTools().isEmpty()) {
            return optionalChat;
        }

        String project = cfg.getMcpServers().stream()
                .filter(ref -> ref.getDefaultProject() != null && !ref.getDefaultProject().isBlank())
                .map(McpServerReference::getDefaultProject)
                .findFirst()
                .orElse(null);

        log.info("Verpacke KIChat in McPEnablingKIChatDecorator mit " + toolRegistry.getTools().size() + " Tools.");
        KIChat mcpEnabledChat = new McPEnablingKIChatDecorator(
                optionalChat.get(), toolRegistry, project);   // NEU: pass ToolRegistry

        return Optional.of(mcpEnabledChat);
    }
}
```

### `McPEnablingKIChatDecorator.java` (MODIFY)
**Changes:**
1. Replace `List<McpClient>` with `ToolRegistry` as constructor parameter
2. In `createMcpAgent()`: Use `ToolRegistry` to build the tool provider
3. In `buildSystemPrompt()`: Use `toolRegistry.getToolDescriptions()` for the system message

```java
public class McPEnablingKIChatDecorator implements KIChat {
    private final KIChat delegate;
    private final ToolRegistry toolRegistry;   // NEU: replaces List<McpClient>
    private final String defaultProject;

    public McPEnablingKIChatDecorator(KIChat delegate, ToolRegistry toolRegistry, String defaultProject) {
        this.delegate = delegate;
        this.toolRegistry = toolRegistry;
        this.defaultProject = defaultProject;
    }

    @Override
    public String chat(String prompt) {
        McpAgent agent = createMcpAgent();
        return agent.chat(prompt);
    }

    private McpAgent createMcpAgent() {
        // Bridge ChatModel that tunnels to the delegate
        ChatModel bridgeModel = new ChatModel() {
            public ChatResponse doChat(ChatRequest request) {
                String responseText = delegate.chat(extractActivePrompt(request));
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from(responseText))
                        .build();
            }
        };

        // Tool provider that wraps the ToolRegistry
        ToolSpecification[] toolSpecs = buildToolSpecifications();

        return AiServices.builder(McpAgent.class)
                .chatModel(bridgeModel)
                .systemMessageProvider(ctx -> buildSystemPrompt())
                .tools(buildToolExecutor())   // NEU: uses ToolRegistry.execute()
                .build();
    }

    private String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("You have access to the following tools:\n\n");
        for (String desc : toolRegistry.getToolDescriptions()) {
            sb.append(desc).append("\n\n");
        }
        if (defaultProject != null) {
            sb.append("The active project is: '").append(defaultProject).append("'.\n");
        }
        sb.append("Use these tools to gather information before answering the user's question.");
        return sb.toString();
    }

    // ... existing methods adapted to use ToolRegistry ...
}
```

## 3. Test Requirements (TDD)

### `McpClientManagerTest.java`
- **init with Groovy server**: Verify that a Groovy-type server loads tools from scriptDir into ToolRegistry
- **init with stdio/http servers**: Verify that MCP clients are registered in ToolRegistry
- **decorate with empty registry**: Verify that no decoration happens when registry is empty

### `McPEnablingKIChatDecoratorTest.java`
- **chat uses tool descriptions**: Verify that the system prompt includes tool descriptions from ToolRegistry
- **chat executes tools**: Verify that tool calls are delegated to ToolRegistry.execute()

## 4. Dependencies
* `ToolRegistry`, `GroovyToolExecutor` (from Phase 01)
* Existing: `McpClientManager`, `McPEnablingKIChatDecorator`, `PrjXPConfig`
