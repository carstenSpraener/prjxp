## What is Doc|Pipe and what problem does it solve?

Doc|Pipe is a pipeline-driven documentation generation system. It scans a project's directory tree for `.dp` configuration folders, resolves Handlebars prompt templates against the actual project structure, sends the resulting prompts to LLMs, and writes the generated content to output files.

The primary problem it addresses: **legacy and complex codebases lack up-to-date documentation**. Manually writing or maintaining architecture assessments, data-model descriptions, per-class documentation, and READMEs is expensive and drifts out of sync. Doc|Pipe automates this by:

- **Discovering** documentation jobs from `.dp/documents.json` files anywhere in the project tree.
- **Resolving** prompt templates context-sensitively (injecting real source code, file listings, or script-computed data).
- **Caching** via SHA-256 prompt hashes so unchanged prompts never trigger a redundant LLM call.
- **Executing** tasks in a configurable thread pool for parallel generation.

A minimal project integration looks like this:

```
my-project/
├── .dp/
│   ├── documents.json
│   ├── content-hashes.properties   (generated at runtime)
│   └── prompts/
│       └── architecture.prompt.txt
├── src/
│   └── main/java/...
└── docs/                           (generated output)
```

The `documents.json` file declares what to generate:

```json
[
  {
    "stereotype": "architect",
    "prompt": "prompts/architecture.prompt.txt",
    "outputFile": "docs/architecture.md",
    "filterList": "noSurroundingCodeBlock"
  },
  {
    "forEach": "src/main/java/**/*.java",
    "stereotype": "doc-writer",
    "prompt": "prompts/class-doc.prompt.txt",
    "outputDir": "docs/api",
    "outputFile": ".md"
  }
]
```

The `forEach` glob expands a single entry into one task per matched file, injecting a `currentFile` argument into the template context.

---

## How does the template resolution mechanism work?

The `PromptResolvingService` uses the **Handlebars** templating engine. The resolution pipeline for a single `ContentCreationTask` is:

1. **Read** the raw template file from `.dp/<prompt>` (e.g., `.dp/prompts/architecture.prompt.txt`).
2. **Register** every `TemplateResolver` bean as a Handlebars helper (by its `getID()` and any `getAliases()`).
3. **Compile** the template inline and **apply** it with the task's `args` map as the data context.
4. **Return** the fully resolved prompt string.

The `baseDir` passed to each resolver is the `.dp` configuration directory. Resolvers use it as the root for relative path resolution.

### Example: a prompt template

```handlebars
{{! .dp/prompts/architecture.prompt.txt }}
You are a senior software architect. Analyze the following codebase and produce
a concise architecture assessment covering: modules, key abstractions, data flow,
and external dependencies.

Here is the complete source code:

{{src-dump "src/main/java" ending="java" scanSubs=true}}

{{#if currentFile}}
Focus specifically on: {{currentFile}}
{{/if}}

Output in Markdown. Do not wrap the result in a code fence.
```

When resolved, `{{src-dump ...}}` is replaced with the actual concatenated source of every `.java` file under `src/main/java`, each wrapped in a fenced code block. The `{{currentFile}}` helper (populated by the `forEach` expansion) injects the relative path of the file being documented.

### Available built-in resolvers

| Helper ID | Aliases | Purpose |
|-----------|---------|---------|
| `src-dump` | `java-src-dump` | Dumps full source of matching files into fenced blocks |
| `src-skeleton` | — | Dumps source with method bodies stripped (signature-only) |
| `URL` | — | Reads and inlines the content of a file via a `file:` URL |
| `groovy` | — | Executes an inline Groovy script; bindings: `dir`, `options`, `applicationContext`, `dpContext`, `currentFile` |
| `gr` | — | Delegates block content to `GRPromptEnrichment` (golden-retriever) |
| `mcp-project` | `mcpProject` | Injects MCP tool descriptions and active project name |
| `currentFile` | — | Reads the file identified by the `currentFile` context entry |

### Hash-based update gating

Before any LLM call, `ContentUpdateRequiredController` computes a SHA-256 hash of the resolved prompt and compares it against the value stored in `.dp/content-hashes.properties` (keyed by output file path). If the hash matches, the task is **skipped entirely**—no LLM call, no file write. This makes repeated runs idempotent and cheap.

```java
// Simplified logic inside ContentUpdateRequiredController
String currentHash = sha256(resolvedPrompt);
String storedHash  = readFromProperties(outputFile);
if (storedHash == null || !storedHash.equals(currentHash)) {
    updater.run();          // LLM call + file write
    writeEntry(outputFile, currentHash);
}
```

---

## How can developers extend Doc|Pipe?

The primary extension point is the **`TemplateResolver`** interface. Implement it, annotate the class with `@Component`, and Spring will inject it into `PromptResolvingService`, which registers it as a Handlebars helper automatically.

### The interface

```java
public interface TemplateResolver {

    /** Unique helper name used in Handlebars templates, e.g. "src-dump". */
    String getID();

    /** Optional alternative names, e.g. List.of("java-src-dump"). */
    default List<String> getAliases() {
        return List.of();
    }

    /**
     * Resolves a value for injection into the template.
     *
     * @param baseDir   the .dp configuration directory (use as path root)
     * @param context   the Handlebars data context (the DPContentCreation.args map)
     * @param options   Handlebars Options: positional params via options.param(i),
     *                  named params via options.hash("key", default),
     *                  block content via options.fn.text()
     * @return the string to splice into the template
     */
    String resolve(File baseDir, Object context, Options options) throws Exception;
}
```

### Example: a custom resolver that lists directory entries

```java
@Component
public class DirListingResolver implements TemplateResolver {

    @Override
    public String getID() {
        return "dir-listing";
    }

    @Override
    public String resolve(File baseDir, Object context, Options options) throws Exception {
        String relPath = options.param(0).toString();
        Path target = baseDir.toPath().getParent().resolve(relPath);

        StringBuilder sb = new StringBuilder();
        try (Stream<Path> walk = Files.walk(target)) {
            walk.filter(Files::isRegularFile)
                .forEach(p -> sb.append(target.relativize(p)).append('\n'));
        }
        return sb.toString();
    }
}
```

Usage in a template:

```handlebars
Project file inventory:
{{dir-listing "src"}}
```

### Other extension points

| Interface / Class | Purpose |
|---|---|
| `SourceSkeletonizer` | Pluggable logic to reduce source to signatures (used by `src-skeleton`). Implement `supports(File, String ending)` and `skeletonize(File)`. |
| `ContentFilter` | Post-processing of LLM output. Implement `name()` and `filter(String)`. Reference by name in `filterList` (comma-separated). |
| `OutputSink` | Abstraction over file writing. Swap for in-memory or network sinks in tests or custom deployments. |
| `DPGroovyTool` | Utility object available inside `{{#groovy}}` blocks: `grep(rootDir, regex, consumer)` and `printFile(file)`. |

### Registering a new stereotype

The `stereotype` field in `documents.json` maps to an LLM model via `KIChatProvider.getByStereotype(...)`. To use a new model, add a `PrjXPChatModelReference` entry to the project's `PrjXPConfig` (typically in `application.yml` or the `.env` file). No code change is required—only configuration.

---

## What are the key benefits for team onboarding and system assessments?

Doc|Pipe turns a project's **actual file structure and source code** into generated documentation artifacts in a single run. Concretely:

### Zero-time architecture assessments

A single entry in `documents.json` with a `src-dump` or `src-skeleton` helper produces a complete, code-grounded architecture document. Because the prompt is built from the real source tree, the output reflects the code as it exists *now*—not a stale wiki page.

```json
{
  "stereotype": "architect",
  "prompt": "prompts/arch-assessment.prompt.txt",
  "outputFile": "docs/architecture-assessment.md",
  "filterList": "noSurroundingCodeBlock"
}
```

### Per-file documentation at scale

The `forEach` glob mechanism eliminates the need to enumerate files manually. Adding a new class to the source tree automatically generates (or regenerates) its documentation on the next run:

```json
{
  "forEach": "src/main/java/**/*.java",
  "stereotype": "doc-writer",
  "prompt": "prompts/class-doc.prompt.txt",
  "outputDir": "docs/api",
  "outputFile": ".md",
  "storePrompt": ".prompt.txt"
}
```

The `storePrompt` field writes the fully resolved prompt alongside the output, giving reviewers full transparency into what context the LLM received.

### Idempotent, incremental runs

The SHA-256 hash cache means that a second run with no code or template changes performs **zero LLM calls**. Only files whose resolved prompt has changed trigger regeneration. This makes Doc|Pipe safe to wire into CI/CD pipelines or pre-commit hooks without incurring token costs for unchanged content.

### Parallel execution

`DocPipeRunner` submits all tasks to a fixed thread pool (configurable via `prjxp.docpipe.maxthreads`, default 5). For a project with 200 source files, the wall-clock time is bounded by the slowest LLM response, not the sum of all responses.

### In-process integration (hub mode)

When running inside the PrjXP hub (`prjxp.hub.enabled=true`), `DocPipeRunner.runForProject(ProjectDefinition)` executes the pipeline in-process, records outputs and failures in a `DocPipeRunResult`, and writes a JSON manifest (`.prjxp-data/docpipe/manifest.json`) that the hub consumes to embed generated documents. Failures throw `DocPipeRunException` rather than calling `System.exit`, making the pipeline composable within a larger application.

### Onboarding checklist for a new team member

1. Clone the repository.
2. Ensure a `.dp/documents.json` exists (or create one).
3. Run `java -jar docpipe.jar --project <name>`.
4. Open `docs/`—architecture, data-model, and per-class docs are generated from the current source.
5. Re-run after code changes; only affected documents are regenerated.

No manual writing, no stale content, no coordination overhead.

_This document was generated with .dp and gemini-3-flash-preview_

