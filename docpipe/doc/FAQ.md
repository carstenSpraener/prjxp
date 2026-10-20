## What is Doc|Pipe and what problem does it solve?

Doc|Pipe is a pipeline-driven documentation generation system. It automates the production of technical documentation by combining **project-structure awareness** with **LLM-based content synthesis**. The core problem it addresses: legacy and mid-sized codebases accumulate documentation debt. READMEs go stale, architecture overviews are never written, and per-module explanations simply don't exist. Manually writing or updating that documentation is expensive and rarely happens.

Doc|Pipe solves this by treating documentation as a **build artifact**. You declare *what* to generate, *where* to find the source material, and *which model* to use in a small JSON configuration. The system then walks the project tree, injects actual source code and structural data into a prompt template, calls an LLM, and writes the result to a file—reproducibly and incrementally.

### How the pipeline works at a glance

```
Project Root
├── .dp/
│   ├── documents.json          ← what to generate (glob patterns, output paths, stereotypes)
│   ├── models.json             ← which LLM to use per stereotype
│   ├── content-hashes.properties  ← SHA-256 cache to skip unchanged prompts
│   └── *.prompt.txt            ← Handlebars prompt templates
├── src/
│   └── ...
└── docs/                       ← generated output
```

The execution flow for a single run:

1. **Discovery** – `JobCreationService` walks the project root, finds every directory containing a `.dp` folder, and parses its `documents.json` into `DPJob` instances.
2. **Expansion** – Each `DPContentCreation` entry with a `forEach` glob is expanded into one task per matched file (e.g., `**/*.java` → one task per Java file).
3. **Prompt Resolution** – `PromptResolvingService` reads the Handlebars template, registers all `TemplateResolver` helpers, and renders the final prompt with project context.
4. **Change Detection** – `ContentUpdateRequiredController` hashes the resolved prompt (SHA-256) and compares it against the stored hash. If unchanged, the LLM call is skipped entirely.
5. **Generation** – `LLMService` routes the prompt to the correct model (selected by *stereotype*) and receives the response.
6. **Post-processing** – Configured `ContentFilter` implementations clean the output (e.g., stripping Markdown code fences).
7. **Output** – The result is written via an `OutputSink` to the declared output path.

### Why this matters for legacy systems

A legacy codebase with 200 source files and zero documentation can be assessed in a single run. The `forEach` glob pattern means you write **one** configuration entry, and the system generates **200** individual documents. The hash-based cache means subsequent runs only re-generate documents whose prompt actually changed—keeping LLM costs bounded.

---

## How does the template resolution mechanism work?

The prompt for every content-creation task is defined as a **Handlebars template** stored in the `.dp` directory. At runtime, `PromptResolvingService` performs three steps:

1. **Read** the raw template file (e.g., `.dp/architecture.prompt.txt`).
2. **Register** every `TemplateResolver` bean as a Handlebars helper (by its `getID()` and any `getAliases()`).
3. **Compile and apply** the template inline, passing the task's `args` map as the Handlebars context.

The result is a fully resolved, data-driven prompt string that is then sent to the LLM.

### The `TemplateResolver` contract

```java
public interface TemplateResolver {
    String getID();                          // helper name in Handlebars
    default List<String> getAliases() { ... } // optional alternate names
    String resolve(File baseDir, Object context, Options options) throws Exception;
}
```

- **`baseDir`** – the `.dp` configuration directory. All relative paths in a resolver are resolved against this.
- **`context`** – the Handlebars context object (the `args` map from `DPContentCreation`). For `forEach`-expanded tasks, this contains a `currentFile` key with the relative path of the matched file.
- **`options`** – the Handlebars `Options` object, giving access to positional parameters (`options.param(0)`) and named hash parameters (`options.hash("ending", "java")`), as well as block content (`options.fn.text()`).

### Built-in resolvers

| ID / Alias | Purpose |
|---|---|
| `src-dump` / `java-src-dump` | Dumps full source files (filtered by extension) into fenced code blocks |
| `src-skeleton` | Dumps source *skeletons* (signatures only, bodies stripped) via `SourceSkeletonizer` |
| `URL` | Reads and inlines the content of a file referenced by a `file:` URL |
| `groovy` | Executes an inline Groovy script with access to `dir`, `options`, `applicationContext`, `dpContext`, `currentFile` |
| `currentFile` | Reads the full content of the file identified by the `currentFile` context entry |
| `mcp-project` / `mcpProject` | Injects MCP tool descriptions and the active project name |
| `gr` | Delegates to `GRPromptEnrichment` for project-specific enrichment |

### Example: a prompt template

```handlebars
{{! .dp/architecture.prompt.txt }}
You are a senior software architect. Analyze the following codebase and produce
a concise architecture assessment covering: components, data flow, and key risks.

Here is the complete codebase:

{{src-dump "src/main/java" ending="java" scanSubs=true}}

{{#if currentFile}}
Focus particularly on: {{currentFile}}
{{/if}}
```

When the `forEach` glob `**/*.java` matches `src/main/java/com/acme/OrderService.java`, the `args` map contains `{"currentFile": "src/main/java/com/acme/OrderService.java"}`, and the `{{currentFile}}` helper reads that file's content. The `{{src-dump ...}}` helper walks the directory tree and inlines every `.java` file as a fenced block. The final resolved prompt is a single, self-contained string sent to the LLM.

### The `forEach` expansion mechanism

In `documents.json`, a `forEach` glob triggers per-file task expansion:

```json
[
  {
    "forEach": "**/*.java",
    "outputFile": ".md",
    "outputDir": "docs/api",
    "stereotype": "doc-writer",
    "prompt": "class-doc.prompt.txt",
    "storePrompt": ".prompt.txt"
  }
]
```

For each matched file, `JobCreationService` clones the `DPContentCreation`, sets `args["currentFile"]` to the relative path, and computes the output filename by stripping the source extension and appending the `outputFile` suffix. A file `src/main/java/com/acme/OrderService.java` produces output `docs/api/OrderService.md`.

---

## How can developers extend Doc|Pipe?

Doc|Pipe is designed for open extension. The primary extension point is the **`TemplateResolver`** interface. Secondary extension points include `ContentFilter`, `SourceSkeletonizer`, and `OutputSink`.

### Registering a custom `TemplateResolver`

Implement the interface and annotate with `@Component`. The `PromptResolvingService` injects `List<TemplateResolver>` via constructor, so Spring auto-discovers every implementation:

```java
@Component
public class DependencyGraphResolver implements TemplateResolver {

    @Override
    public String getID() {
        return "dep-graph";
    }

    @Override
    public List<String> getAliases() {
        return List.of("dependencyGraph");
    }

    @Override
    public String resolve(File baseDir, Object context, Options options) throws Exception {
        String srcPath = options.param(0).toString();
        String format  = options.hash("format", "mermaid");

        // Walk the source tree, extract class-level dependencies,
        // and render a Mermaid or PlantUML diagram.
        return renderDiagram(baseDir.toPath().resolve(srcPath), format);
    }
}
```

Use it in any prompt template:

```handlebars
{{dep-graph "src/main/java" format="mermaid"}}
```

No registration call, no configuration file—Spring's component scan picks it up, and `PromptResolvingService` registers it as a Handlebars helper automatically.

### Adding a `ContentFilter`

Filters post-process the LLM's raw response before it is written to disk. They are identified by name in the `filterList` field of `DPContentCreation`:

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

```json
{ "filterList": "noSurroundingCodeBlock,stripThinking" }
```

Filters are applied in the order listed. Unknown names are silently skipped.

### Adding a `SourceSkeletonizer`

The `src-skeleton` resolver delegates to a list of `SourceSkeletonizer` beans. Each one declares which file extensions it supports:

```java
@Component
public class TypeScriptSourceSkeletonizer implements SourceSkeletonizer {

    @Override
    public boolean supports(File sourceFile, String ending) {
        return "ts".equals(ending) || "tsx".equals(ending);
    }

    @Override
    public String skeletonize(File sourceFile) throws Exception {
        // Extract class/interface declarations and method signatures,
        // strip method bodies.
        return extractSignatures(sourceFile);
    }
}
```

If no skeletonizer matches a file, the resolver falls back to a full content dump with an HTML comment explaining why.

### Adding a custom `OutputSink`

The `OutputSink` interface (with `println`, `printf`, `close`) abstracts the write target. The default `FileOutputSink` writes to the filesystem. For testing or alternative targets (e.g., an in-memory buffer, a database, a remote store), provide a different implementation through the `OutputSinkFactory`:

```java
@Service
@Primary
public class CustomOutputSinkFactory implements OutputSinkFactory {
    @Override
    public OutputSink createSink(Path path) throws IOException {
        return new InMemoryOutputSink(path); // your implementation
    }
}
```

### Configuration files at a glance

All configuration lives in the `.dp` directory next to the source it documents:

```
my-module/
├── .dp/
│   ├── documents.json
│   ├── models.json
│   ├── content-hashes.properties   ← auto-generated, do not edit
│   ├── architecture.prompt.txt
│   └── class-doc.prompt.txt
├── src/
└── docs/                           ← generated output
```

**`models.json`** maps stereotypes to LLM configurations:

```json
[
  {
    "stereotype": "doc-writer",
    "serverType": "lm-studio",
    "modelName": "qwen2.5-7b-instruct",
    "apiKey": "${LM_STUDIO_API_KEY}",
    "providerUrl": "http://localhost:1234/v1",
    "timeoutSecs": 180
  },
  {
    "stereotype": "reader",
    "modelName": "gpt-4o-mini"
  }
]
```

The `${VARIABLE}` syntax is resolved from environment variables at runtime via `EnvResolver`.

---

## What are the key benefits for team onboarding and system assessments?

### Zero-time architecture assessments

A new engineer joining a team with a 500-file legacy codebase can run Doc|Pipe once and receive a structured architecture assessment—components, data flows, dependency boundaries, and risk areas—generated from the **actual source code**, not from a stale wiki page. The `src-dump` and `src-skeleton` resolvers feed real code into the prompt; the LLM synthesizes the narrative.

```json
[
  {
    "outputFile": "docs/ARCHITECTURE.md",
    "stereotype": "reader",
    "prompt": "architecture.prompt.txt"
  }
]
```

No manual curation. No "the docs are in Confluence, but that page was last updated in 2019."

### Per-file API documentation at scale

The `forEach` glob pattern turns a single configuration entry into N documentation tasks:

```json
[
  {
    "forEach": "src/main/java/**/*.java",
    "outputFile": ".md",
    "outputDir": "docs/api",
    "stereotype": "doc-writer",
    "prompt": "class-doc.prompt.txt"
  }
]
```

200 Java files → 200 Markdown documents, each generated from the actual class source. The `currentFile` resolver ensures each prompt references the correct file.

### Incremental regeneration via prompt hashing

`ContentUpdateRequiredController` stores a SHA-256 hash of every resolved prompt in `.dp/content-hashes.properties`. On subsequent runs:

- **Prompt unchanged** → the LLM call is skipped. The existing output file is left as-is.
- **Prompt changed** (source file was modified, template was edited) → the LLM is called again and the output is overwritten.

This means a full re-run of a 200-file project after a single-file change costs **one** LLM call, not 200. The hash file is a flat Java `Properties` file—inspectable, diffable, and safe to commit to version control.

### Parallel execution

`DocPipeRunner` submits all tasks to a fixed thread pool (configurable via `prjxp.docpipe.maxthreads`, default 5). Independent documents are generated concurrently, reducing wall-clock time for large projects.

### Reproducibility and auditability

- **`storePrompt`** – optionally persist the fully resolved prompt to a file. This gives you an exact, replayable record of what was sent to the LLM for a given output.
- **Manifest** – after each run, `DocPipeManifestWriter` writes `.prjxp-data/docpipe/manifest.json`, a sorted JSON array of all generated file paths. Downstream tooling (e.g., a documentation hub) can read this to know exactly what was produced.
- **Deterministic output paths** – output filenames are derived from source filenames, so the mapping from source to documentation is stable and predictable.

### Integration into CI/CD

Because Doc|Pipe is a Spring Boot CLI application (`DocPipeCliApp`), it can be invoked as a build step:

```bash
java -jar docpipe-cli.jar -p my-project
```

It reads a `.env` file for API keys, discovers all `.dp` directories, generates or updates documentation, and exits with status `1` if any task produced a `SEVERE`-level error. This makes it straightforward to gate merges on documentation freshness or to regenerate docs on every release.

_This document was generated with .dp and gemini-3-flash-preview_

