## What is Doc|Pipe and what problem does it solve?

Doc|Pipe is a pipeline-driven documentation generator for software projects. It scans project directories for Doc|Pipe configuration folders, resolves prompt templates against the actual project structure, sends the resulting prompts to configured LLMs, and writes the generated documentation back into the repository or a configured output location.

The main problem it solves is **repeatable documentation generation based on source-of-truth project artifacts**, especially for systems where documentation is missing, stale, or difficult to create manually.

Typical use cases include:

- Generating architecture assessments from existing source code
- Producing README files for modules or services
- Extracting source-code-based data model descriptions
- Creating per-file or per-module documentation
- Supporting legacy system analysis where design documentation no longer exists
- Re-running documentation generation only when the resolved prompt changes

At runtime, Doc|Pipe performs the following high-level steps:

1. Discover `.dp` directories below the active project root.
2. Read `.dp/documents.json`.
3. Expand configured content creation tasks.
4. Resolve prompt templates using Handlebars and registered template resolvers.
5. Select an LLM by configured `stereotype`.
6. Generate content.
7. Apply optional post-processing filters.
8. Write the output file.
9. Store prompt hashes to avoid unnecessary repeated LLM calls.

---

## How is a Doc|Pipe project configured?

Doc|Pipe expects a `.dp` directory inside a project or module directory.

A minimal structure looks like this:

```text
my-project/
  .dp/
    documents.json
    architecture-assessment.prompt.txt
  src/
    main/
      java/
        ...
```

The most important configuration file is:

```text
.dp/documents.json
```

It defines which documents should be generated and which prompt templates should be used.

Example:

```json
[
  {
    "outputFile": "ARCHITECTURE.md",
    "stereotype": "doc-writer",
    "prompt": "architecture-assessment.prompt.txt",
    "filterList": "noSurroundingCodeBlock"
  }
]
```

The key fields are:

| Field | Purpose |
|---|---|
| `outputFile` | Target file to write |
| `outputDir` | Optional output directory used with `forEach` expansion |
| `stereotype` | Logical model role used to select an LLM |
| `prompt` | Prompt template file inside `.dp` |
| `forEach` | Optional glob expression for task expansion |
| `args` | Template context values |
| `filterList` | Comma-separated list of output filters |
| `storePrompt` | Optional file path for writing the resolved prompt |
| `ps` | Optional suffix appended after LLM generation |

---

## How does Doc|Pipe discover documentation jobs?

Doc|Pipe walks the active project directory and looks for directories containing a `.dp` folder.

For every directory with a `.dp` folder, Doc|Pipe attempts to read:

```text
.dp/documents.json
```

Each such directory becomes a `DPJob`.

Conceptually:

```java
Files.walk(rootPath)
    .filter(Files::isDirectory)
    .filter(dpFilesService::hasDocPipeDir)
    .map(this::createDPJob);
```

This allows a repository to contain multiple independently configured documentation jobs:

```text
repo/
  .dp/
    documents.json
  module-a/
    .dp/
      documents.json
  module-b/
    .dp/
      documents.json
```

Each job uses its own root directory as the working context for prompt resolution and output generation.

---

## How does the template resolution mechanism work?

Doc|Pipe resolves prompt templates in a context-sensitive way.

A content creation task references a prompt template:

```json
{
  "outputFile": "ARCHITECTURE.md",
  "stereotype": "doc-writer",
  "prompt": "architecture.prompt.txt"
}
```

The template is loaded from the job’s `.dp` directory:

```text
project-root/.dp/architecture.prompt.txt
```

Doc|Pipe then processes it with Handlebars. During processing, registered `TemplateResolver` implementations are exposed as Handlebars helpers.

The result is a fully expanded, data-driven prompt that contains project-specific context such as source files, skeletonized code, current file content, tool descriptions, or dynamically generated Groovy output.

Simplified flow:

```java
File cfgDir = dpFilesService.dotPipeDir(ccTask);

String promptTemplate = Files.readString(
    cfgDir.toPath().resolve(dpcc.getPrompt()),
    StandardCharsets.UTF_8
);

String prompt = resolve(dpcc, promptTemplate, cfgDir);
```

The resolved prompt is then sent to the configured LLM.

---

## What does “context-sensitive prompt resolution” mean?

Template resolution is context-sensitive because each resolver receives:

```java
String resolve(File baseDir, Object context, Options options) throws Exception;
```

Where:

| Parameter | Meaning |
|---|---|
| `baseDir` | The current job’s `.dp` directory |
| `context` | The current Handlebars context, usually `DPContentCreation.args` |
| `options` | Handlebars helper parameters, block content, and hash arguments |

This means the same template can resolve differently depending on:

- Which `.dp` directory owns the job
- Which source file is currently processed
- Which `args` are passed in `documents.json`
- Which helper parameters are used
- Which project root is active

Example template:

```handlebars
You are analyzing this Java project.

Here is the relevant source code:

{{src-dump "src/main/java" ending="java" scanSubs=true}}
```

If the same template is used in different modules, `src/main/java` is resolved relative to that module’s `.dp` directory.

---

## What does a resolved prompt look like?

A template such as:

```handlebars
Create an architecture assessment for this module.

Use the following source code as input:

{{src-dump "src/main/java" ending="java"}}
```

May resolve into a final prompt like:

```text
Create an architecture assessment for this module.

Use the following source code as input:

```java
package com.example;

public class OrderService {
    ...
}
```

```java
package com.example;

public class CustomerRepository {
    ...
}
```
```

The LLM does not receive the template. It receives the resolved prompt containing the actual project data.

---

## Which built-in template resolvers are available?

Doc|Pipe registers all Spring beans implementing `TemplateResolver` as Handlebars helpers.

Common built-in resolvers include:

| Resolver ID | Purpose |
|---|---|
| `src-dump` | Dumps source files into the prompt |
| `java-src-dump` | Legacy alias for `src-dump` |
| `src-skeleton` | Dumps skeletonized source code |
| `currentFile` | Injects the content of the currently processed file |
| `groovy` | Executes Groovy code to generate prompt content |
| `gr` | Enriches prompt content through `GRPromptEnrichment` |
| `mcp-project` | Injects MCP/tool usage context |
| `mcpProject` | Alias for `mcp-project` |
| `URL` | Includes file content through a URL-like reference |

Example using `src-dump`:

```handlebars
Analyze the following Java source files:

{{src-dump "src/main/java" ending="java" scanSubs=true}}
```

Example using `src-skeleton` for Visual Basic:

```handlebars
Create a high-level system overview from these VB source skeletons:

{{src-skeleton "src" ending="vb" scanSubs=true}}
```

Example using `currentFile`:

```handlebars
Document the following file:

{{currentFile}}
```

---

## How does `forEach` task expansion work?

The `forEach` field allows one content creation definition to expand into multiple tasks based on a glob pattern.

Example:

```json
[
  {
    "forEach": "src/**/*.java",
    "outputFile": ".md",
    "outputDir": "docs",
    "storePrompt": ".prompt.txt",
    "stereotype": "doc-writer",
    "prompt": "class-doc.prompt.txt"
  }
]
```

For every matching file, Doc|Pipe creates a copy of the content creation task and sets:

```java
copy.getArgs().put("currentFile", fqName);
```

For a file:

```text
src/main/java/com/example/OrderService.java
```

Doc|Pipe may produce:

```text
./docs/OrderService.md
./docs/OrderService.prompt.txt
```

The prompt can then access the file content:

```handlebars
Create technical documentation for this source file:

File path: {{currentFile}}

Source:

{{currentFile}}
```

Internally, the `currentFile` resolver reads the file relative to the project root:

```java
File currentFileContent =
    new File(baseDir.getParentFile(), ((Map<String,String>) context).get("currentFile"));
```

---

## How does Doc|Pipe avoid unnecessary LLM calls?

Doc|Pipe hashes the fully resolved prompt and stores the hash per output file.

The hash file is stored at:

```text
.dp/content-hashes.properties
```

If the prompt hash has not changed since the last run, Doc|Pipe skips the LLM call for that output.

The relevant behavior is:

```java
String currentHash = toHash(prompt);
String storedHash = readEntry(cct, outputFile);

return storedHash == null || !storedHash.equals(currentHash);
```

This means output regeneration is triggered when:

- The prompt template changes
- Included source files change
- Template arguments change
- Resolver output changes
- No previous hash exists

The comparison is based on the resolved prompt, not only the template file.

---

## How are LLM models selected?

Content creation tasks reference a logical model role through `stereotype`.

Example:

```json
{
  "outputFile": "README.md",
  "stereotype": "doc-writer",
  "prompt": "readme.prompt.txt"
}
```

At runtime, `LLMService` resolves the matching chat model by stereotype:

```java
chatProvider.getByStereotype(stereotype)
    .map(mcpClientManager::decorate)
    .map(chat -> chat.chat(prompt))
    .orElseThrow(() ->
        new IllegalArgumentException("No Model found for Stereotype " + stereotype)
    );
```

This decouples documentation tasks from concrete model names or providers.

A model configuration may look like this:

```json
[
  {
    "stereoType": "doc-writer",
    "serverType": "lm-studio",
    "modelName": "qwen2.5-7b-instruct",
    "apiKey": "${LM_STUDIO_API_KEY}",
    "providerUrl": "http://localhost:1234/v1",
    "timeoutSecs": 180
  }
]
```

Environment placeholders such as `${LM_STUDIO_API_KEY}` can be resolved through `EnvResolver`.

---

## How can developers extend Doc|Pipe with custom template types?

Developers extend prompt generation by implementing the `TemplateResolver` interface.

```java
public interface TemplateResolver {
    String getID();

    default List<String> getAliases() {
        return List.of();
    }

    String resolve(File baseDir, Object context, Options options) throws Exception;
}
```

Any Spring bean implementing this interface is automatically picked up by `PromptResolvingService` and registered as a Handlebars helper.

Example custom resolver:

```java
package com.example.docpipe;

import com.github.jknack.handlebars.Options;
import de.spraener.prjxp.docpipe.prompt.TemplateResolver;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;

@Component
public class PackageTreeResolver implements TemplateResolver {

    @Override
    public String getID() {
        return "package-tree";
    }

    @Override
    public String resolve(File baseDir, Object context, Options options) throws Exception {
        String relativePath = options.param(0).toString();

        return Files.walk(baseDir.toPath().resolve(relativePath))
                .filter(Files::isDirectory)
                .map(baseDir.toPath()::relativize)
                .map(Object::toString)
                .sorted()
                .reduce("", (a, b) -> a + b + "\n");
    }
}
```

Usage in a prompt template:

```handlebars
Describe the package structure below:

{{package-tree "src/main/java"}}
```

---

## How are custom resolver aliases registered?

A resolver can expose multiple helper names by overriding `getAliases()`.

```java
@Component
public class DiagramResolver implements TemplateResolver {

    @Override
    public String getID() {
        return "diagram";
    }

    @Override
    public List<String> getAliases() {
        return List.of("uml", "plantuml");
    }

    @Override
    public String resolve(File baseDir, Object context, Options options) {
        return "@startuml\nclass Example\n@enduml";
    }
}
```

All of the following template calls would resolve through the same implementation:

```handlebars
{{diagram}}
{{uml}}
{{plantuml}}
```

Internally, Doc|Pipe registers the primary ID and aliases:

```java
handlebars.registerHelper(tr.getID(), trh);

for (String alias : tr.getAliases()) {
    handlebars.registerHelper(alias, trh);
}
```

---

## How can a resolver access helper parameters?

Template resolvers receive Handlebars `Options`.

Example template:

```handlebars
{{src-dump "src/main/java" ending="java" scanSubs=true}}
```

Resolver-side access:

```java
String path = options.param(0).toString();
String ending = options.hash("ending", "java");
boolean scanSubs = options.hash("scanSubs", true);
```

Block helpers can access block content through:

```java
String content = options.fn.text();
```

Example block helper usage:

```handlebars
{{#gr prj="my-project"}}
Explain this bounded context using repository terminology.
{{/gr}}
```

---

## How does the Groovy resolver work?

The `groovy` resolver executes Groovy code embedded in a template block.

Example:

```handlebars
{{#groovy}}
return "Generated at runtime"
{{/groovy}}
```

The resolver binds several objects into the script context:

| Binding | Description |
|---|---|
| `dir` | The `.dp` configuration directory |
| `options` | Handlebars options |
| `applicationContext` | Spring application context |
| `dpContext` | Current template context |
| `currentFile` | Current file path when available |

Example:

```handlebars
{{#groovy name="OrderService"}}
return "Document component: ${options.hash("name")}"
{{/groovy}}
```

The result of the script is inserted into the prompt. If the script returns `null`, Doc|Pipe inserts an empty string.

---

## How can developers add post-processing filters?

Output filters implement the `ContentFilter` interface.

```java
public interface ContentFilter {
    String name();

    String filter(String content);
}
```

Filters are Spring beans and are selected by name from `filterList`.

Example custom filter:

```java
import de.spraener.prjxp.docpipe.content.ContentFilter;
import org.springframework.stereotype.Component;

@Component
public class TrimOutputFilter implements ContentFilter {

    @Override
    public String name() {
        return "trim";
    }

    @Override
    public String filter(String content) {
        return content.trim();
    }
}
```

Usage:

```json
{
  "outputFile": "README.md",
  "stereotype": "doc-writer",
  "prompt": "readme.prompt.txt",
  "filterList": "trim"
}
```

The built-in filter `noSurroundingCodeBlock` removes surrounding Markdown code fences from LLM output:

```text
```java
code
```
```

becomes:

```text
code
```

---

## How does Doc|Pipe write generated output?

Generated content is written through the `OutputSink` abstraction.

```java
public interface OutputSink extends AutoCloseable {
    void println(String line);

    void printf(String format, Object... args);

    void close();
}
```

The default implementation writes UTF-8 files and creates missing parent directories:

```java
public FileOutputSink(Path path) throws IOException {
    Files.createDirectories(path.getParent());
    this.writer = new PrintWriter(
        Files.newBufferedWriter(path, StandardCharsets.UTF_8)
    );
}
```

This abstraction makes output handling testable and replaceable.

---

## How does Doc|Pipe support team onboarding?

Doc|Pipe helps onboarding by generating documentation from the current project structure instead of relying on manually maintained documents.

Typical generated artifacts include:

- Project README files
- Module summaries
- Architecture overviews
- Source-code-based class documentation
- Data model descriptions
- Legacy system assessment reports

For a new developer, this means the first view of the system can be generated from:

- Actual source files
- Actual package/module boundaries
- Existing configuration
- Current naming conventions
- Current implementation structure

This is especially useful when a system has evolved faster than its documentation.

---

## How does Doc|Pipe help with system assessments?

Doc|Pipe can generate architecture and codebase assessments by embedding real project data into prompts.

Example architecture prompt:

```handlebars
You are performing a technical architecture assessment.

Analyze the following source code and identify:
- major components
- architectural boundaries
- coupling hotspots
- persistence mechanisms
- integration points
- technical risks

Source code:

{{src-dump "src/main/java" ending="java" scanSubs=true}}
```

For large or legacy systems, `src-skeleton` can reduce prompt size by including signatures and structure without full implementation bodies:

```handlebars
Create a system assessment from these Visual Basic skeletons:

{{src-skeleton "src" ending="vb" scanSubs=true}}
```

This allows teams to quickly obtain first-pass documentation such as:

- Architecture assessment
- Module inventory
- Data model overview
- Risk report
- Migration assessment
- Modernization candidate list

The generated result is based on the current filesystem and source code, not a separate manually curated model.

---

## How does Doc|Pipe handle legacy systems?

Doc|Pipe is designed to work well with legacy systems because prompt templates can include raw source code, skeletonized source code, or dynamically extracted information.

For example, Visual Basic source skeletonization is supported through `SourceSkeletonizer` implementations.

The built-in Visual Basic skeletonizer supports endings such as:

```text
vb
bas
cls
frm
```

It keeps structural elements such as classes, fields, method signatures, and `End Sub` / `End Function` markers, while removing implementation details.

Example prompt:

```handlebars
Generate a technical inventory for this legacy VB application.

Use the source skeletons below:

{{src-skeleton "src" ending="vb" scanSubs=true}}
```

This is useful when full source dumps would exceed context limits or include too much low-level implementation detail.

---

## Can Doc|Pipe process multiple files into multiple documents?

Yes. Use `forEach`.

Example:

```json
[
  {
    "forEach": "src/**/*.java",
    "outputDir": "docs/classes",
    "outputFile": ".md",
    "stereotype": "doc-writer",
    "prompt": "class-doc.prompt.txt",
    "filterList": "noSurroundingCodeBlock"
  }
]
```

For each matching source file, Doc|Pipe creates one task.

If the project contains:

```text
src/Hello.java
src/sub/World.java
```

Doc|Pipe generates output paths similar to:

```text
./docs/classes/Hello.md
./docs/classes/World.md
```

The current file path is available in template arguments as:

```handlebars
{{currentFile}}
```

---

## Can the resolved prompt be stored for debugging?

Yes. Set `storePrompt`.

Example:

```json
[
  {
    "outputFile": "ARCHITECTURE.md",
    "storePrompt": "ARCHITECTURE.prompt.txt",
    "stereotype": "doc-writer",
    "prompt": "architecture.prompt.txt"
  }
]
```

Doc|Pipe writes the fully resolved prompt before calling the LLM.

This is useful for:

- Debugging template resolution
- Inspecting source dumps
- Reproducing LLM requests
- Auditing prompt input
- Understanding why a document changed

With `forEach`, `storePrompt` is expanded per matched file.

---

## How does Doc|Pipe run tasks?

`DocPipeRunner` discovers all jobs, flattens them into `ContentCreationTask` instances, and executes them using a fixed thread pool.

The default maximum thread count is configurable:

```properties
prjxp.docpipe.maxthreads=5
```

Simplified execution model:

```java
try (ExecutorService executor = Executors.newFixedThreadPool(maxThreads)) {
    for (ContentCreationTask task : allTasks) {
        executor.submit(() -> {
            contentCreationService.createContent(task);
        });
    }
}
```

Each task independently resolves a prompt, calls an LLM, filters the result, and writes one output file.

---

## What is the difference between CLI mode and in-process project mode?

Doc|Pipe supports two runner entry points:

| Method | Intended use | Failure behavior |
|---|---|---|
| `run(PrjXPConfig cfg)` | CLI execution | May call configured exit handler on severe errors |
| `runForProject(ProjectDefinition pd)` | In-process hub integration | Never calls `System.exit`; throws `DocPipeRunException` |

The in-process mode records generated outputs and failures in `DocPipeRunResult`.

It also writes a manifest when `DocPipeManifestWriter` is configured:

```text
.prjxp-data/docpipe/manifest.json
```

The manifest contains generated document paths relative to the project root.

Example:

```json
[
  "docs/a.md",
  "docs/sub/b.md"
]
```

This is useful for downstream systems that need to index, embed, or publish only the documents generated during a run.

---

## How are errors handled?

Doc|Pipe generally logs task-level failures and continues processing other tasks.

Examples:

- Invalid `documents.json` results in an empty fallback job and a logged error.
- Template resolution failures are wrapped in `TemplateException`.
- Output write failures are logged.
- Missing LLM stereotypes result in an exception during content creation.
- In CLI mode, severe logged errors can cause process exit.
- In in-process mode, failures are collected and reported through `DocPipeRunException`.

Template resolver errors are wrapped when called through Handlebars:

```java
try {
    return new Handlebars.SafeString(
        templateResolver.resolve(baseDir, context, options)
    );
} catch (Exception e) {
    throw new TemplateException(e);
}
```

---

## What are the main extension points?

Doc|Pipe has several explicit extension points:

| Extension point | Interface/Class | Purpose |
|---|---|---|
| Prompt helper | `TemplateResolver` | Add custom Handlebars helpers |
| Output filter | `ContentFilter` | Post-process LLM output |
| Source skeletonizer | `SourceSkeletonizer` | Add language-specific skeleton extraction |
| Output destination | `OutputSinkFactory` / `OutputSink` | Replace or mock output writing |
| LLM provider integration | `KIChatProvider` | Resolve chat models by stereotype |
| MCP/tool integration | `McpClientManager`, `ToolRegistry` | Enrich model/tool interactions |

The most common extension is a custom `TemplateResolver`.

---

## When should I implement a `TemplateResolver` instead of using Groovy?

Use a `TemplateResolver` when the logic should be:

- Reusable across many templates
- Type-safe
- Unit-tested
- Integrated with Spring dependencies
- Distributed as part of the application
- Exposed under a stable helper name

Use `groovy` when the logic is:

- Project-specific
- Experimental
- Small enough to live directly in a template
- Not worth compiling into the application

Example reusable resolver:

```java
@Component
public class BuildFileResolver implements TemplateResolver {

    @Override
    public String getID() {
        return "build-file";
    }

    @Override
    public String resolve(File baseDir, Object context, Options options) throws Exception {
        File root = baseDir.getParentFile();
        File pom = new File(root, "pom.xml");
        File gradle = new File(root, "build.gradle");

        if (pom.exists()) {
            return Files.readString(pom.toPath());
        }
        if (gradle.exists()) {
            return Files.readString(gradle.toPath());
        }
        return "";
    }
}
```

Template usage:

```handlebars
Analyze the build configuration:

{{build-file}}
```

---

## What are good prompt template patterns for architecture documentation?

A useful architecture prompt usually combines instructions with concrete project context.

Example:

```handlebars
You are a software architect.

Create an architecture assessment with the following sections:

1. System overview
2. Main components
3. Dependencies
4. Data access
5. Integration points
6. Risks
7. Refactoring recommendations

Use only evidence from the source code below.

{{src-dump "src/main/java" ending="java" scanSubs=true}}
```

For large projects, prefer skeletons or targeted dumps:

```handlebars
Create a component inventory.

Use these source skeletons:

{{src-skeleton "src" ending="vb" scanSubs=true}}
```

For MCP-enabled analysis:

```handlebars
{{mcp-project project="my-project"}}

Create an architecture overview.
Use available tools to inspect structure before answering.
```

---

## What are the key engineering benefits?

Doc|Pipe provides the following engineering benefits:

- **Repeatability**: Documentation generation is defined as versioned configuration.
- **Project awareness**: Prompts are resolved against real source files and directories.
- **Incrementality**: Prompt hashes prevent unnecessary LLM calls.
- **Extensibility**: Custom resolvers, filters, and skeletonizers can be registered as Spring beans.
- **Parallel execution**: Multiple documentation tasks can run concurrently.
- **Onboarding support**: READMEs, architecture summaries, and module descriptions can be generated from current code.
- **Assessment support**: Architecture and legacy-system reports can be produced quickly from actual implementation artifacts.
- **Debuggability**: Resolved prompts can be stored for inspection.
- **Integration support**: In-process execution can produce a manifest of generated documents.

_This document was generated with .dp and gemini-3-flash-preview_

