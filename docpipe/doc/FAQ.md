## What is Doc|Pipe and what problem does it solve?

Doc|Pipe is a pipeline-driven documentation generation system. It automates the production of project documentation by combining three concerns into a single, repeatable execution:

1. **Project scanning** – discovers configuration directories (`.dp/`) and their `documents.json` task definitions.
2. **Prompt assembly** – resolves Handlebars templates with project-specific context (source code, file trees, environment variables) into a final LLM prompt.
3. **Content generation** – sends the prompt to a configured LLM, post-processes the response, and writes the result to a file on disk.

The primary use case is **legacy and brownfield systems** where documentation is missing, stale, or exists only in the heads of a few engineers. Instead of manually writing a README, an architecture assessment, or per-class API docs, you declare *what* to generate and *how* to frame the prompt; Doc|Pipe handles the mechanical work of gathering source context, calling the model, and persisting the output.

### Execution model

```
ProjectRoot/
├── .dp/
│   ├── documents.json          ← task definitions (glob, output, stereotype, prompt file)
│   ├── content-hashes.properties  ← SHA-256 cache to skip unchanged prompts
│   └── *.prompt.txt            ← Handlebars templates
├── src/
│   └── ...
└── docs/                       ← generated output
```

The `DocPipeRunner` walks the project tree, collects all `DPJob` instances, flattens them into `ContentCreationTask` objects, and submits them to a fixed thread pool (configurable via `prjxp.docpipe.maxthreads`, default 5). Each task is independent; a failure in one does not block the others.

### Incremental execution

The `ContentUpdateRequiredController` computes a SHA-256 hash of the *resolved prompt* before every LLM call. If the hash matches the one stored in `.dp/content-hashes.properties` for that output file, the task is skipped entirely—no API call, no token spend. This makes repeated runs idempotent and cheap.

```java
// Simplified logic inside ContentUpdateRequiredController
String currentHash = sha256(resolvedPrompt);
String storedHash  = readFromProperties(outputFile);
if (storedHash == null || !storedHash.equals(currentHash)) {
    updater.run();              // actually call the LLM and write the file
    writeEntry(outputFile, currentHash);
}
```

---

## How does the template resolution mechanism work?

The core of Doc|Pipe is the `PromptResolvingService`. It takes a raw Handlebars template (a `.txt` file inside `.dp/`) and produces a **final, data-driven prompt string** by injecting project context through registered `TemplateResolver` helpers.

### Resolution pipeline

```
documents.json  ──►  prompt filename  ──►  read .dp/<filename>
                                                        │
                                                        ▼
                                          Handlebars.compileInline(template)
                                                        │
                                                        ▼
                                          template.apply(dpcc.getArgs())
                                                        │
                                                        ▼
                                              final prompt string
```

Concretely, `PromptResolvingService.resolve(ContentCreationTask)` does the following:

1. Resolves the `.dp` directory for the task's job root.
2. Reads the template file named by `DPContentCreation.prompt` (e.g. `architecture-assessment.prompt.txt`).
3. Creates a `Handlebars` instance and registers **every** `TemplateResolver` bean as a helper (by `getID()` and each alias).
4. Compiles the template inline and applies it with `DPContentCreation.args` as the data context.
5. Optionally writes the resolved prompt to disk (`storePrompt` field) for debugging.

### Example: a `documents.json` entry

```json
[
  {
    "forEach": "src/main/java/**/*.java",
    "outputFile": ".md",
    "outputDir": "docs/api",
    "stereotype": "doc-writer",
    "prompt": "class-doc.prompt.txt",
    "filterList": "noSurroundingCodeBlock"
  },
  {
    "outputFile": "ARCHITECTURE.md",
    "stereotype": "arch-assessor",
    "prompt": "architecture.prompt.txt"
  }
]
```

The `forEach` glob is expanded at job-creation time by `JobCreationService`. For every matching file, a **clone** of the `DPContentCreation` is produced with:

- `args["currentFile"]` set to the relative path (e.g. `src/main/java/com/acme/Order.java`)
- `outputFile` rewritten to `<outputDir>/<baseName><outputFile>` (e.g. `docs/api/Order.md`)

### Example: a Handlebars prompt template

`.dp/class-doc.prompt.txt`:

```handlebars
You are a technical writer. Generate a concise API reference for the following Java class.

## Source

{{currentFile}}

## Instructions

- Document the public API surface.
- Note any side effects or thread-safety concerns.
- Output in Markdown.
```

`.dp/architecture.prompt.txt`:

```handlebars
Analyze the following codebase and produce an architecture assessment.

## Codebase

{{src-dump "src/main/java" ending="java" scanSubs=true}}

## Focus

- Module boundaries and dependency direction
- Key design patterns in use
- Technical debt indicators

Output a structured Markdown document.
```

The `{{currentFile}}` helper is backed by `CurrentFileResolver`, which reads the file whose relative path is in `args["currentFile"]`. The `{{src-dump ...}}` helper is backed by `SourceDumpResolver`, which walks a directory tree and wraps each matching file in a fenced code block.

### Available built-in resolvers

| Helper ID | Aliases | Purpose |
|-----------|---------|---------|
| `src-dump` | `java-src-dump` | Dumps full source of files matching an extension |
| `src-skeleton` | – | Dumps source with method bodies stripped (via `SourceSkeletonizer`) |
| `currentFile` | – | Reads the file identified by `args["currentFile"]` |
| `URL` | – | Reads a file via a `file:` URL relative to `.dp/` |
| `groovy` | – | Executes an inline Groovy script with `dir`, `options`, `applicationContext`, `dpContext` bindings |
| `gr` | – | Delegates to `GRPromptEnrichment` for project-specific enrichment |
| `mcp-project` | `mcpProject` | Injects MCP tool descriptions and the active project name |

---

## How can developers extend Doc|Pipe?

The primary extension point is the **`TemplateResolver`** interface. Any Spring `@Component` that implements it is automatically discovered and registered as a Handlebars helper.

### The interface

```java
public interface TemplateResolver {

    /** Unique identifier used as the Handlebars helper name, e.g. "src-dump". */
    String getID();

    /** Optional alternative names (e.g. legacy aliases). */
    default List<String> getAliases() {
        return List.of();
    }

    /**
     * Resolves a value to inject into the template.
     *
     * @param baseDir   the `.dp` configuration directory (use as path base)
     * @param context   the current Handlebars data context (typically the `args` map)
     * @param options   Handlebars `Options` – access positional params via `options.param(n)`,
     *                  named params via `options.hash("key", default)`,
     *                  and block content via `options.fn.text()`
     * @return the string to splice into the template
     */
    String resolve(File baseDir, Object context, Options options) throws Exception;
}
```

### Registering a custom resolver

```java
@Component
public class DependencyGraphResolver implements TemplateResolver {

    @Override
    public String getID() {
        return "dep-graph";
    }

    @Override
    public String resolve(File baseDir, Object context, Options options) throws Exception {
        String pkg = options.hash("package", "");
        boolean includeTests = options.hash("includeTests", false);

        // Walk the source tree, build a Mermaid dependency graph, return it
        StringBuilder mermaid = new StringBuilder("```mermaid\ngraph TD\n");
        // ... your logic here ...
        mermaid.append("```\n");
        return mermaid.toString();
    }
}
```

Usage in a template:

```handlebars
{{dep-graph package="com.acme.order" includeTests=true}}
```

Because the resolver is a Spring bean, it can inject any other service (file system access, a code parser, a database, etc.) via constructor injection. The `baseDir` parameter always points to the `.dp/` directory of the job, so relative paths in your resolver are resolved against the project root via `baseDir.getParentFile()`.

### Other extension points

| Interface / Class | Purpose |
|---|---|
| `SourceSkeletonizer` | Pluggable logic to reduce source files to signatures (used by `src-skeleton`). Implement `supports(File, String ending)` and `skeletonize(File)`. |
| `ContentFilter` | Post-processing of LLM output. Implement `name()` and `filter(String)`. Reference by name in `filterList` (comma-separated). |
| `OutputSink` | Abstraction over the write target. The default `FileOutputSink` writes to disk; swap for an in-memory or S3 sink in tests or custom deployments. |
| `DPGroovyTool` | Utility class available inside `{{#groovy}}` blocks for `grep()` and `printFile()` operations. |

### Example: a content filter

```java
@Component
public class StripThinkingBlock implements ContentFilter {

    @Override
    public String name() {
        return "stripThinking";
    }

    @Override
    public String filter(String content) {
        // Remove any <thinking>...</thinking> blocks some models emit
        return content.replaceAll("(?s)<thinking>.*?</thinking>", "").trim();
    }
}
```

Reference it in `documents.json`:

```json
{ "filterList": "noSurroundingCodeBlock,stripThinking" }
```

Filters are applied in the order listed. Unknown names are silently skipped.

---

## What are the key benefits for team onboarding and system assessments?

Doc|Pipe turns a one-time manual effort into a **repeatable, version-controlled pipeline**. The concrete benefits:

### Zero-time architecture assessments

A single `documents.json` entry with a `src-dump` or `src-skeleton` resolver can produce a full architecture document from the actual source tree. No manual code reading, no stale diagrams.

```json
{
  "outputFile": "docs/ARCHITECTURE.md",
  "stereotype": "arch-assessor",
  "prompt": "architecture.prompt.txt"
}
```

The LLM receives the real code (or its skeleton) and produces a structured assessment. Re-run the pipeline after a refactor and the document updates—provided the prompt hash changed.

### Per-file API documentation at scale

The `forEach` glob pattern eliminates the need to enumerate files:

```json
{
  "forEach": "src/main/java/**/*.java",
  "outputFile": ".md",
  "outputDir": "docs/api",
  "stereotype": "doc-writer",
  "prompt": "class-doc.prompt.txt"
}
```

For a project with 200 Java classes, this generates 200 individual Markdown files in parallel (bounded by `maxThreads`). New files added to the source tree are picked up on the next run; unchanged files are skipped via the hash cache.

### Reproducible onboarding material

Because the prompt templates live in `.dp/` and are checked into version control, every team member (and every CI run) produces identical documentation from the same code state. A new engineer cloning the repo and running `docpipe` gets the same architecture overview, data-model summary, and README that the team has been using for months.

### Parallelism and fault isolation

```java
// DocPipeRunner – simplified
try (ExecutorService executor = Executors.newFixedThreadPool(maxThreads)) {
    for (ContentCreationTask task : allTasks) {
        executor.submit(() -> contentCreationService.createContent(task));
    }
}
```

- Tasks run concurrently (default 5 threads).
- A failure in one task is logged and recorded in `DocPipeRunResult`; it does not abort the run.
- In CLI mode, the process exits with status 1 if any `SEVERE` errors occurred.
- In in-process (hub) mode, a `DocPipeRunException` is thrown after the run, and a manifest of successfully produced files is always written to `.prjxp-data/docpipe/manifest.json`.

### Environment-aware prompts

The `EnvResolver` and the `groovy` resolver allow prompts to reference environment variables or perform arbitrary logic at resolution time:

```handlebars
{{#groovy}}
def branch = System.getenv("CI_BRANCH") ?: "main"
return "Documenting branch: ${branch}"
{{/groovy}}
```

This lets a single template produce branch-specific or environment-specific documentation without maintaining multiple template files.

### Summary of the data flow

```
documents.json
      │
      ▼
JobCreationService  ──►  DPJob (rootDir, pxCfg, List<DPContentCreation>)
      │
      ▼
DocPipeRunner  ──►  ContentCreationTask (job + creation)  [parallel]
      │
      ▼
PromptResolvingService  ──►  resolved prompt (Handlebars + TemplateResolvers)
      │
      ▼
ContentUpdateRequiredController  ──►  hash check (skip if unchanged)
      │
      ▼
LLMService  ──►  model response
      │
      ▼
ContentFilter chain  ──►  cleaned content
      │
      ▼
OutputSink  ──►  file on disk
```

Each stage is independently testable and replaceable, which is the architectural intent: swap the LLM provider, add a new resolver, change the output target—without touching the pipeline orchestration.

_This document was generated with .dp and gemini-3-flash-preview_

