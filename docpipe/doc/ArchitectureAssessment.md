# Software Architecture Assessment: DocPipe CLI Documentation Pipeline

## 1. Executive Summary

DocPipe is a Spring Boot based CLI tool that discovers `.dp` configuration directories inside a project, resolves prompt templates, invokes LLM chat providers, post-processes generated content, and writes documentation outputs. It is designed for build-pipeline usage and explicitly attempts to continue execution despite local configuration errors.

The overall architecture is pragmatic and service-oriented. The main flow is clearly separated into job discovery, prompt resolution, LLM invocation, content filtering, output writing, and run-result reporting. Spring dependency injection is used effectively to register extensible components such as template resolvers, content filters, output sinks, and source skeletonizers.

Key strengths include:

- Clear orchestration through `DocPipeRunner`.
- Extensible prompt helper mechanism via `TemplateResolver`.
- Pluggable content filtering via `ContentFilter`.
- Output abstraction through `OutputSink` and `OutputSinkFactory`.
- Thread-safe run-result collection for in-process execution.
- Explicit support for “continue on errors” behavior, which matches the CLI/build-pipeline requirement.
- Flexible Groovy-based prompt generation, which is architecturally powerful when used in trusted environments.

The most critical risks are:

- Groovy execution exposes the Spring `ApplicationContext`, creating a very broad execution and security surface.
- Parallel execution writes shared hash state without synchronization or atomicity.
- Some failures are logged but still mark content as up to date, which can lead to stale or missing generated files.
- Configuration validation is weak; many malformed configurations fail late at runtime.
- File path handling is inconsistent and can write files relative to the process working directory instead of the job root.
- The normal CLI runner can terminate the JVM through `System.exit`, which is acceptable for CLI mode but risky if accidentally reused in embedded contexts.
- Several injected or defined components are unused, indicating architectural drift.

Overall, the project has a workable modular structure, but it needs stronger boundaries, validation, concurrency control, and safer script/file handling to be reliable in CI/CD pipelines.

## 2. Architectural Style & Patterns

### 2.1 Primary Architectural Style

The project follows a Spring-Boot-idiomatic service architecture with a lightweight layered structure:

- **CLI/Application layer**
  - `DocPipeCliApp`
  - `DocPipeArgsParser`
  - `DocPipeRunner`

- **Configuration/discovery layer**
  - `JobCreationService`
  - `DotDPFilesService`
  - `ModelConfigLoader`
  - `DocPipeConfig`

- **Content generation layer**
  - `ContentCreationService`
  - `ContentCreationTask`
  - `ContentUpdateRequiredController`
  - `ContentFilter`

- **Prompt/template layer**
  - `PromptResolvingService`
  - `TemplateResolver`
  - Resolver implementations such as `GroovyResolver`, `SourceDumpResolver`, `GRResolver`, `McpProjectResolver`, `URLResolver`

- **LLM integration layer**
  - `LLMService`
  - External dependency: `KIChatProvider`
  - External MCP decoration: `McpClientManager`

- **I/O layer**
  - `OutputSink`
  - `FileOutputSink`
  - `OutputSinkFactory`
  - `DocPipeManifestWriter`

The architecture is not strictly hexagonal, but it contains some ports/adapters-like abstractions:

- `OutputSink` acts as an output port.
- `ContentFilter` acts as a plugin extension point.
- `TemplateResolver` acts as a prompt-generation extension point.
- `SourceSkeletonizer` acts as a language-specific source abstraction.
- `KIChatProvider` is an external abstraction for LLM access.

### 2.2 Orchestration Pattern

`DocPipeRunner` acts as the central orchestrator:

1. Reads jobs through `JobCreationService`.
2. Converts jobs into `ContentCreationTask` instances.
3. Executes tasks in parallel.
4. Delegates content generation to `ContentCreationService`.
5. In embedded mode, writes a manifest and throws `DocPipeRunException` on failures.
6. In CLI mode, checks global log severity and calls the exit handler.

This orchestration is clear and easy to follow.

However, `DocPipeRunner` currently contains two slightly different execution paths:

- `run(PrjXPConfig cfg)` for CLI mode.
- `runForProject(ProjectDefinition pd)` for in-process/hub mode.

The two paths share similar task-discovery and execution logic but differ in failure reporting, timeout handling, and manifest writing. This creates duplication and makes behavior drift likely.

### 2.3 Plugin/Extension Patterns

The project makes good use of Spring’s collection injection pattern:

```java
private final List<ContentFilter> contentFilterList;
private final List<TemplateResolver> templateResolvers;
private final List<SourceSkeletonizer> skeletonizers;
```

This allows new filters, prompt helpers, and skeletonizers to be added without changing the central services. This is one of the strongest architectural choices in the codebase.

### 2.4 Template Resolver Pattern

`PromptResolvingService` registers each `TemplateResolver` as a Handlebars helper. This provides a clean extension model:

- Resolver ID becomes the helper name.
- Optional aliases are supported through `getAliases()`.
- Dynamic prompt functionality is isolated in resolver classes.

This is a good separation of concerns. The risk is that the resolvers have unrestricted access to the file system and, in the case of Groovy, potentially the whole Spring application.

### 2.5 Build-Pipeline Resilience Pattern

The project intentionally catches and logs many exceptions to avoid failing the entire run because of a single local misconfiguration. Examples:

- `JobCreationService#createDPJob()` returns `DPJob.EMPTY_JOB` on malformed `documents.json`.
- `ContentCreationService#createContent()` catches `TemplateException`, `IOException`, and `Throwable`.
- `DocPipeArgsParser#parseArgs()` catches runtime argument parsing errors.
- `ContentUpdateRequiredController#readEntry()` returns `null` on read errors, forcing regeneration.

This supports the stated requirement that the CLI tool should keep running even when local configurations are broken. However, this design must be paired with accurate failure reporting. In several areas, errors are swallowed or logged without reliably affecting run outcome.

## 3. Quality Attribute Evaluation

### 3.1 Maintainability & Readability

#### Positive observations

The codebase is generally readable. Most services have focused responsibilities and descriptive names:

- `JobCreationService`
- `ContentCreationService`
- `PromptResolvingService`
- `ContentUpdateRequiredController`
- `DocPipeManifestWriter`
- `OutputSinkFactory`

The main execution path is easy to understand:

```text
DocPipeRunner
  -> JobCreationService
  -> ContentCreationService
      -> PromptResolvingService
      -> ContentUpdateRequiredController
      -> LLMService
      -> ContentFilter
      -> OutputSinkFactory
```

Use of Lombok reduces boilerplate and keeps domain/configuration objects compact.

#### Maintainability issues

##### 1. Inconsistent documentation and naming

Several comments refer to `.docpipe`, while the actual constant is:

```java
public static final String DP_DIR = ".dp";
```

This creates confusion about the real configuration directory.

Examples:

- `JobCreationService` Javadocs mention `.docpipe`.
- `DotDPFilesService` and code use `.dp`.

Recommendation: Standardize terminology throughout code, documentation, and configuration examples.

##### 2. Unused or partially obsolete components

Some components appear unused or architecturally stale:

- `ModelConfigLoader` is injected into `DocPipeRunner` but not used.
- `DPJob#getModelForStereotype()` is not used by `LLMService`.
- `EnvResolver` is not used in the shown code.
- `DPGroovyTool` exists but is not wired into `GroovyResolver`.

This indicates architectural drift. It is not necessarily harmful, but it increases cognitive load and makes it unclear which model configuration source is authoritative.

##### 3. Mixed error-handling strategies

The code mixes:

- checked exceptions: `ConfigException`
- runtime exceptions: `TemplateException`, `DocPipeRunException`
- logging-and-continue behavior
- logging-and-exit behavior
- logging-and-swallow behavior
- throwing `IllegalStateException` from lower-level infrastructure

This makes failure semantics difficult to reason about.

##### 4. Some services contain too much operational policy

`ContentCreationService` currently does all of the following:

- Resolves prompts.
- Decides whether an update is required.
- Calls the LLM.
- Applies filters.
- Appends postscript text.
- Writes output.
- Records run results.
- Logs failures.

This is acceptable for a small CLI, but if the project grows, this service will become the main maintenance hotspot.

##### 5. Type safety issues in prompt resolvers

`CurrentFileResolver` and `GroovyResolver` cast template context directly:

```java
((Map<String,String>) context).get("currentFile")
```

This assumes the Handlebars context is always a `Map<String, String>`. A malformed template invocation or future context model change can cause `ClassCastException`.

##### 6. Javadocs are extensive but occasionally misleading

The code has many Javadocs, which is positive. However, some comments describe intended behavior rather than actual robust behavior. For example, several classes describe safe/resilient operation, but path validation, null validation, and concurrency safeguards are limited.

### 3.2 Extensibility

#### Strong extensibility points

The project is extensible in several important dimensions.

##### Template resolvers

Adding a new prompt helper is simple:

```java
@Component
public class MyResolver implements TemplateResolver {
    public String getID() { return "my-helper"; }

    public String resolve(File baseDir, Object context, Options options) {
        return "...";
    }
}
```

Spring auto-discovers the resolver and `PromptResolvingService` registers it with Handlebars.

##### Content filters

Adding post-processing is straightforward:

```java
@Component
public class MyFilter implements ContentFilter {
    public String name() { return "myFilter"; }

    public String filter(String content) {
        return content;
    }
}
```

This is a clean plugin model.

##### Source skeletonizers

The `SourceSkeletonizer` abstraction supports adding language-specific skeletonization without changing `SourceSkeletonResolver`.

##### Output abstraction

`OutputSink` and `OutputSinkFactory` make output writing testable and replaceable.

#### Limitations in extensibility

##### 1. LLM provider extensibility is delegated externally

`LLMService` depends on `KIChatProvider` and asks it for a model by stereotype:

```java
chatProvider.getByStereotype(stereotype)
```

This is clean from DocPipe’s perspective, but the shown code does not clarify how `models.json` participates. `ModelConfigLoader` exists but is unused in `DocPipeRunner`, and `DPJob#getModelForStereotype()` is also unused.

As a result, model configuration responsibility is ambiguous:

- Is `models.json` loaded somewhere else?
- Is `PrjXPConfig.chatModels` already populated before DocPipe starts?
- Should job-local `.dp/models.json` override global models?
- Should `ModelConfigLoader` merge model configurations?

This should be clarified architecturally.

##### 2. Prompt resolver interface is powerful but very broad

The interface passes low-level Handlebars `Options`, raw `Object context`, and a filesystem `File baseDir`. This maximizes flexibility, but it also exposes implementation details and makes each resolver responsible for validation and type handling.

##### 3. No explicit configuration schema/versioning

`documents.json` maps directly to `List<DPContentCreation>`. This is convenient, but lacks:

- schema validation
- versioning
- deprecation support
- clear error messages
- required-field validation
- defaults handling

Extending configuration safely will become harder as features grow.

### 3.3 Robustness & Error Handling

#### Positive robustness mechanisms

The project explicitly supports continuing after errors:

- Malformed job configs produce empty jobs.
- Prompt/content failures are logged and do not crash all tasks.
- Embedded runs collect failures in `DocPipeRunResult`.
- In-process execution does not call `System.exit`.
- Manifest writing is best-effort and does not mask primary run failures.

These are aligned with the stated build-pipeline use case.

#### Critical robustness issues

##### 1. Hash state can be updated even when output writing fails

In `ContentCreationService`, the update lambda catches `IOException` while writing the output:

```java
try (OutputSink sink = outputSinkFactory.createSink(Path.of(outputFile))) {
    sink.println(content);
    if (result != null) { result.recordOutput(outputFile); }
} catch (IOException e) {
    logService.error(e, ...);
    if (result != null) { result.recordFailure(...); }
}
```

The exception is swallowed inside the lambda. Then `ContentUpdateRequiredController#onUpdateRequired()` continues and writes the prompt hash:

```java
updater.run();
writeEntry(cct, toHash(prompt), outputFile);
```

This means a failed output write can still mark the content as up to date. On the next run, DocPipe may skip generation even though the output file is missing or stale.

This is a high-priority correctness issue.

##### 2. Content hash database is not concurrency-safe

`ContentUpdateRequiredController` reads and writes a shared properties file:

```text
.dp/content-hashes.properties
```

With parallel task execution, multiple tasks in the same job can concurrently:

1. Read old properties.
2. Add their own entry.
3. Write the whole file back.

This creates race conditions and lost updates.

Example:

- Task A reads `{}`.
- Task B reads `{}`.
- Task A writes `{a=hashA}`.
- Task B writes `{b=hashB}`.
- Final file loses `a=hashA`.

This is a major risk because `DocPipeRunner` executes tasks in parallel by default.

##### 3. Failure reporting is inconsistent between CLI and embedded modes

Embedded mode records failures in `DocPipeRunResult` and throws `DocPipeRunException`.

CLI mode does not collect per-task failures and relies on `logService.maxLevel()`:

```java
if (logService.maxLevel().intValue() >= Level.SEVERE.intValue()) {
    exitHandler.accept(1);
}
```

This depends on all critical failures being logged as `SEVERE` through the shared log service. However, some code logs through `java.util.logging.Logger` directly, and some warnings/severe logs may not be reflected in `PxLogService`.

This can lead to CLI runs reporting success despite meaningful failures.

##### 4. `DocPipeRunner#run()` has no timeout

Embedded mode has a one-hour timeout:

```java
executor.awaitTermination(1, TimeUnit.HOURS)
```

CLI mode uses try-with-resources on `ExecutorService`. Depending on the Java version and runtime behavior, this waits for completion but provides no timeout. A hanging LLM request can hang the build pipeline indefinitely.

##### 5. `runForProject()` submits tasks but does not inspect `Future` objects

Because `ContentCreationService#createContent()` catches broad exceptions, this is mostly safe. However, if an exception escapes before reaching the internal catch block, the executor will capture it in the task `Future`, but the runner never checks it.

A safer design is to collect `Future<?>` objects and call `get()` after termination.

##### 6. `ContentCreationService` catches `Throwable`

```java
} catch (Throwable t) {
```

Catching `Throwable` keeps the pipeline alive, but it also catches serious JVM-level problems such as `OutOfMemoryError`, `StackOverflowError`, or linkage errors. In a CLI tool this may be intentional, but it should be used very carefully.

A better approach is to catch `Exception` and only catch selected serious errors if there is a strong reason.

##### 7. Missing validation of required fields

The following fields can be null and cause runtime failures:

- `DPContentCreation.prompt`
- `DPContentCreation.outputFile`
- `DPContentCreation.stereotype`
- `DPContentCreation.args`
- `DPJob.contentCreationList`
- active project root
- source paths in resolvers

Invalid configuration is expected in local setups, so robust validation with actionable messages would improve the user experience significantly.

##### 8. File paths can escape intended directories

Several components resolve paths without normalization or boundary checks:

- `PromptResolvingService#readTemplate()`
- `SourceDumpResolver`
- `SourceSkeletonResolver`
- `URLResolver`
- `CurrentFileResolver`
- `FileOutputSink`
- output file resolution in `DotDPFilesService`

This allows configurations to read or write outside the intended project/job root using `../` paths.

In trusted build environments this may be acceptable, but with Groovy and source-dump helpers, the tool should clearly define its trust model.

##### 9. `storePrompt` handling is inconsistent

`JobCreationService#expandCreation()` sets `storePrompt` relative to an output directory:

```java
copy.setStorePrompt(outputDir + "/" + baseName + c.getStorePrompt());
```

But `PromptResolvingService` writes it as:

```java
new File(ccTask.getDpContentCreation().getStorePrompt())
```

This resolves relative to the current process working directory, not the job root or `.dp` directory. It also does not create parent directories.

This can lead to prompts being written in unexpected locations or failing when directories are missing.

##### 10. `EnvResolver` may return null

`EnvResolver.resolve()` returns `System.getenv().get(envVar)` for placeholders. If the environment variable is not set, it returns `null`, despite the method documentation saying null input returns empty string.

Also, `DocPipeCliApp#readDotEnv()` stores `.env` entries as system properties, but `EnvResolver` only checks environment variables, not system properties. Therefore `.env` values may not be resolved by `EnvResolver`.

### 3.4 Performance & Resource Efficiency

#### Positive observations

- Tasks are executed in parallel using a fixed thread pool.
- Expensive LLM calls are avoided when prompt hashes have not changed.
- Prompt templates can selectively dump source code or skeletonized code.
- `DocPipeRunResult` uses `CopyOnWriteArrayList`, which is simple and thread-safe for low-to-moderate write volumes.

#### Performance risks

##### 1. Full filesystem traversal is used in several places

Examples:

- `JobCreationService#readJobs()` uses `Files.walk(rootPath)`.
- `JobCreationService#expandCreation()` uses `Files.walk(rootPath)`.
- `SourceDumpResolver` uses `Files.walk(srcPath)`.
- `SourceSkeletonResolver` uses `Files.walk(srcPath)`.
- `DPGroovyTool#grep()` uses `Files.walk(rootDir)`.

For large repositories, repeated recursive scans can be expensive.

Potential improvements:

- Ignore common directories such as `.git`, `target`, `build`, `node_modules`, `.gradle`.
- Support include/exclude patterns.
- Cache discovered files per run.
- Limit source dump size.
- Add maximum prompt size controls.

##### 2. Prompt generation can create very large prompts

`SourceDumpResolver` can dump full source files recursively into a prompt. This risks:

- high memory usage
- large LLM token costs
- request failures due to context limits
- slow CI runs

The skeletonizer is a good mitigation, but it currently defaults to Visual Basic-like endings in `SourceSkeletonResolver`.

##### 3. `PromptResolvingService` creates a new `Handlebars` instance per prompt

For each task:

```java
Handlebars handlebars = new Handlebars();
```

This is simple and safe, but may be inefficient when many tasks are run. Template compilation and helper registration happen repeatedly.

A cache may be beneficial if prompt templates are reused.

##### 4. `CopyOnWriteArrayList` is safe but not optimal for many writes

`DocPipeRunResult` uses `CopyOnWriteArrayList`. This is fine for small task counts, but for many outputs/failures, a synchronized list or concurrent queue would be more write-efficient.

##### 5. File reading is fully in-memory

Most resolvers read complete files into strings:

- `Files.readString`
- `IOUtils.toString`
- `FileUtils.readFileToString`

For prompt generation this is often necessary, but safeguards should exist for very large files.

##### 6. LLM calls have no visible timeout/cancellation abstraction

The runner can timeout embedded execution after one hour, but individual LLM calls are delegated to `KIChatProvider`. There is no visible per-request timeout, retry policy, circuit breaker, or cancellation handling.

In CI/CD, external LLM calls should have explicit timeout policies.

## 4. Strengths & Best Practices

### 4.1 Clear service decomposition

The main responsibilities are separated into clear Spring services:

- Discovery: `JobCreationService`
- Prompt resolution: `PromptResolvingService`
- LLM access: `LLMService`
- Content orchestration: `ContentCreationService`
- Update detection: `ContentUpdateRequiredController`
- Output writing: `OutputSinkFactory`
- Manifest writing: `DocPipeManifestWriter`

This makes the codebase approachable.

### 4.2 Good use of Spring extension points

Using injected lists for polymorphic components is a strong design choice:

- `List<TemplateResolver>`
- `List<ContentFilter>`
- `List<SourceSkeletonizer>`

This supports open/closed principle behavior for several feature areas.

### 4.3 Testability through output abstraction

`OutputSink` and `OutputSinkFactory` decouple output writing from content generation. This makes content generation easier to test without touching the real filesystem.

### 4.4 Separate embedded runner behavior

`runForProject(ProjectDefinition pd)` avoids `System.exit` and reports failures through `DocPipeRunException`. This is a good design distinction for in-process use.

### 4.5 Thread-safe result collection

`DocPipeRunResult` is simple and thread-safe. It supports concurrent task execution without external locking.

### 4.6 Manifest writing is robustly isolated

`DocPipeManifestWriter` writes generated output paths relative to the project root and falls back to absolute paths for external outputs. Manifest writing is also handled as non-fatal in `runForProject()`, which prevents secondary failures from masking the main run result.

### 4.7 Prompt templating architecture is flexible

The Handlebars-based resolver mechanism enables powerful prompt composition:

- Include current file contents.
- Dump source directories.
- Run Groovy scripts.
- Inject graph retrieval enrichment.
- Inject MCP tool instructions.
- Include URL-like file references.

This provides significant flexibility for documentation automation.

### 4.8 Hash-based update avoidance

The prompt-hash mechanism is a useful optimization to avoid unnecessary LLM calls. This is particularly valuable because LLM calls can be slow and expensive.

### 4.9 Explicit local misconfiguration tolerance

Returning `DPJob.EMPTY_JOB` on broken `documents.json` is consistent with the requirement that local misconfigurations should not stop the whole pipeline.

### 4.10 Visual Basic skeletonization is a useful token-reduction feature

`VisualBasicSourceSkeletonizer` demonstrates a valuable pattern: extract structure instead of dumping full source code. This reduces prompt size and improves LLM relevance.

## 5. Identified Risks & Technical Debt

### 5.1 Critical: Groovy execution has excessive privileges

`GroovyResolver` exposes:

```java
b.put("applicationContext", applicationContext);
b.put("dir", baseDir);
b.put("options", options);
b.put("dpContext", context);
```

Giving scripts access to `ApplicationContext` effectively gives them access to all Spring beans and potentially all application capabilities. Since Groovy can execute arbitrary code, this means prompt templates can:

- read arbitrary files
- write files
- access secrets exposed through beans or environment
- invoke network-capable services
- alter application behavior
- call Spring-managed services directly

The integration of Groovy is an intentional architectural decision and provides maximum flexibility, but it must be treated as trusted-code execution.

Risk level: **High**

### 5.2 Critical: Parallel hash-file writes are unsafe

The `.dp/content-hashes.properties` file is modified through read-modify-write cycles without locking. Parallel tasks can overwrite each other’s hash entries.

Risk level: **High**

### 5.3 Critical: Failed writes can still update prompt hashes

If output sink creation or writing fails, the error is logged and recorded, but `ContentUpdateRequiredController` still writes the prompt hash because the updater lambda swallows the exception.

Risk level: **High**

### 5.4 High: Weak configuration validation

The project currently relies on runtime failures to identify malformed configuration. Missing or invalid fields are not validated upfront.

Examples:

- missing `prompt`
- missing `outputFile`
- missing `stereotype`
- invalid `filterList`
- invalid `forEach` glob
- invalid source paths
- missing prompt template files
- output path escaping root

Risk level: **High**

### 5.5 High: Path handling is inconsistent and insufficiently constrained

Several operations can read or write outside the intended directory structure. This is especially important because prompt templates may be authored locally and executed in CI.

Risk level: **High**

### 5.6 High: CLI execution can hang indefinitely

`DocPipeRunner#run()` has no explicit timeout. If an LLM provider, MCP tool, prompt resolver, or Groovy script hangs, the build pipeline can hang.

Risk level: **High**

### 5.7 Medium: Duplicate runner logic

`run()` and `runForProject()` perform similar task creation and executor submission but differ in behavior. This creates a risk of inconsistent behavior and future bugs.

Risk level: **Medium**

### 5.8 Medium: Model configuration path is unclear

`ModelConfigLoader` is present but unused by the runner. `DPJob#getModelForStereotype()` is also unused. `LLMService` uses `KIChatProvider` directly.

This creates uncertainty around:

- whether `.dp/models.json` is supported
- whether global models are loaded
- whether project-local models override global ones
- how stereotypes are registered

Risk level: **Medium**

### 5.9 Medium: Logging strategy is inconsistent

The project uses both:

- `PxLogService`
- Lombok `@Log` / `java.util.logging.Logger`

Only errors logged through `PxLogService` appear to contribute to CLI failure detection via `logService.maxLevel()`.

Risk level: **Medium**

### 5.10 Medium: Store-prompt output uses wrong base directory

`storePrompt` writes relative to process working directory. This can create files in unexpected locations and fail in CI.

Risk level: **Medium**

### 5.11 Medium: Source dump helpers can generate excessive prompts

Recursive source dumping without size limits can create very large prompts, leading to:

- high costs
- slow execution
- failed LLM calls
- memory pressure

Risk level: **Medium**

### 5.12 Medium: Unknown filters are silently ignored

`ContentCreationService#applyContentFilters()` ignores unrecognized filters:

```java
if (filter != null) {
    content = filter.filter(content);
}
```

This can hide configuration typos.

Risk level: **Medium**

### 5.13 Medium: No clear retry or timeout policy for external services

LLM calls and enrichment/MCP integrations depend on external services. The visible code does not define:

- request timeout
- retry behavior
- rate-limit behavior
- fallback behavior
- cancellation behavior

Risk level: **Medium**

### 5.14 Low: `EnvResolver` behavior is incomplete

`EnvResolver` only resolves values that start with `${`, strips all braces, and reads from `System.getenv()`. It does not support embedded placeholders or system properties loaded from `.env`.

Risk level: **Low**

### 5.15 Low: Resource handling issue in `DPGroovyTool#grep()`

`Files.walk(rootDir)` is not used in a try-with-resources block. This can leak filesystem resources.

Risk level: **Low**

### 5.16 Low: `FileOutputSink` assumes parent path exists

`FileOutputSink` calls:

```java
Files.createDirectories(path.getParent());
```

If `path.getParent()` is null, this can throw a `NullPointerException`. In normal use, output paths appear absolute and parented, but the class itself is not defensive.

Risk level: **Low**

### 5.17 Low: `URLResolver` is misleadingly named

`URLResolver` does not really fetch URL content. It parses a URL and reads a local file based on `new URL(fileParam).getFile()` relative to the base directory.

This may surprise users and maintainers.

Risk level: **Low**

## 6. Actionable Recommendations

### Priority 1: Fix correctness and CI reliability issues

#### 1.1 Prevent hash updates when output generation fails

Change `ContentUpdateRequiredController#onUpdateRequired()` to update the hash only when the updater reports success.

Example direction:

```java
public void onUpdateRequired(String prompt, ContentCreationTask cct, CheckedRunnable updater) {
    String outputFile = cct.getDpContentCreation().getOutputFile();
    if (updateRequired(cct, prompt, outputFile)) {
        updater.run();
        writeEntry(cct, toHash(prompt), outputFile);
    }
}
```

Then ensure output write failures propagate out of the updater. Alternatively, return a boolean:

```java
public void onUpdateRequired(String prompt, ContentCreationTask cct, Supplier<Boolean> updater) {
    if (updateRequired(cct, prompt, outputFile)) {
        boolean success = updater.get();
        if (success) {
            writeEntry(cct, toHash(prompt), outputFile);
        }
    }
}
```

#### 1.2 Make content hash storage concurrency-safe

At minimum, synchronize per hash file path:

```java
private final ConcurrentMap<Path, Object> locks = new ConcurrentHashMap<>();
```

Use the lock for read-modify-write operations.

Better options:

- Use one hash file per output.
- Use atomic file replacement.
- Use a small embedded database.
- Use Java file locks if multiple DocPipe processes can run concurrently.
- Write to a temporary file and atomically move it into place.

#### 1.3 Add a timeout to CLI execution

Make CLI mode use the same timeout concept as embedded mode.

Recommended properties:

```properties
prjxp.docpipe.maxthreads=5
prjxp.docpipe.timeout=PT1H
prjxp.docpipe.task-timeout=PT10M
```

Avoid indefinite build-pipeline hangs.

#### 1.4 Collect and inspect submitted futures

Update runner execution to retain `Future<?>` objects and call `get()` after executor termination. This prevents silently lost executor task exceptions.

#### 1.5 Standardize failure reporting

Ensure all meaningful task failures are recorded consistently.

Recommended approach:

- Introduce a `RunMode` or `RunContext`.
- Always collect a `DocPipeRunResult`.
- CLI mode can still use `exitHandler.accept(1)` after evaluating the result.
- Embedded mode throws `DocPipeRunException`.

This removes the dependency on global log severity as the primary failure signal.

### Priority 2: Introduce configuration validation

#### 2.1 Add a configuration validator

Create a `DPContentCreationValidator` or `DocPipeConfigValidator`.

Validate:

- `prompt` is present.
- prompt template exists.
- `outputFile` or generated output pattern is valid.
- `stereotype` is present.
- `filterList` entries exist.
- `forEach` pattern is valid.
- `outputDir` and `storePrompt` are safe.
- no path escapes allowed base directory unless explicitly enabled.

Validation should produce structured, actionable diagnostics and allow the runner to skip invalid tasks rather than fail the whole run.

#### 2.2 Consider JSON Schema or versioned config

Introduce a config shape like:

```json
{
  "version": 1,
  "documents": [
    {
      "outputFile": "README.md",
      "stereotype": "documentation",
      "prompt": "readme.hbs"
    }
  ]
}
```

This supports evolution and better validation messages.

#### 2.3 Normalize local and global model configuration

Clarify and document the model-loading architecture.

Options:

1. Remove `ModelConfigLoader` if model configuration is handled elsewhere.
2. Use `ModelConfigLoader` explicitly in `JobCreationService` or `DocPipeRunner`.
3. Define precedence:
   - global config
   - project config
   - job-local `.dp/models.json`

### Priority 3: Harden Groovy integration while preserving flexibility

Groovy is an intentional architectural decision and can remain. The recommendation is not to remove it, but to define and control its risk.

#### 3.1 Define a trust model

Document clearly:

- Groovy scripts are trusted code.
- They can access local files and application services.
- They must not be used with untrusted repositories or untrusted pull requests unless sandboxing is enabled.

#### 3.2 Reduce default exposed bindings

Avoid exposing the full `ApplicationContext` by default.

Instead expose a curated API, for example:

```java
b.put("tools", new DPGroovyTool());
b.put("dir", baseDir);
b.put("currentFile", currentFile);
b.put("args", context);
```

If `ApplicationContext` is still needed, make it opt-in:

```properties
prjxp.docpipe.groovy.expose-application-context=false
```

#### 3.3 Add execution timeout for Groovy scripts

Prevent infinite loops in scripts from hanging the pipeline.

#### 3.4 Add script caching carefully

If scripts are repeated, compiled script caching can improve performance. The cache should be keyed by script content and relevant engine configuration.

### Priority 4: Improve file/path safety and consistency

#### 4.1 Centralize path resolution

Expand `DotDPFilesService` or create a `DocPipePathService` that handles:

- job root
- `.dp` directory
- prompt templates
- output files
- stored prompts
- manifest paths
- path normalization
- base-directory boundary checks

#### 4.2 Fix `storePrompt` path resolution

Store prompts relative to the job root or `.dp` directory, not the process working directory.

Example:

```java
Path storePromptPath = ccTask.getDpJob()
    .getRootDir()
    .toPath()
    .resolve(ccTask.getDpContentCreation().getStorePrompt())
    .normalize();

Files.createDirectories(storePromptPath.getParent());
Files.writeString(storePromptPath, prompt, StandardCharsets.UTF_8);
```

#### 4.3 Add boundary checks for file inclusion helpers

For `SourceDumpResolver`, `SourceSkeletonResolver`, `CurrentFileResolver`, and `URLResolver`, normalize paths and verify they remain within the intended project/job root unless explicitly configured otherwise.

#### 4.4 Rename or clarify `URLResolver`

If it only reads local files, rename it to `FileIncludeResolver` or update behavior/documentation to match actual URL semantics.

### Priority 5: Refactor runner execution flow

#### 5.1 Extract common task execution logic

Create a reusable internal method:

```java
private DocPipeRunResult executeTasks(List<ContentCreationTask> tasks, boolean collectManifest)
```

Both `run()` and `runForProject()` should use it.

#### 5.2 Use structured run options

Introduce:

```java
public record DocPipeRunOptions(
    boolean exitOnFailure,
    boolean writeManifest,
    Duration timeout,
    boolean continueOnTaskFailure
) {}
```

This makes behavior explicit and avoids mode-specific branching scattered across the runner.

#### 5.3 Avoid direct `System.exit` coupling

The existing `exitHandler` seam is good. Keep it, but prefer returning a status object from core logic:

```java
DocPipeRunSummary summary = runner.runInternal(...);
```

Then the CLI adapter decides whether to exit.

### Priority 6: Improve observability

#### 6.1 Use a single logging abstraction for failure decisions

If `PxLogService` determines CLI failure, all serious errors should go through it. Direct `log.severe()` calls should either be mirrored to `PxLogService` or failure should be based on `DocPipeRunResult`.

#### 6.2 Add structured task context to logs

Include:

- project name
- job root
- output file
- prompt template
- stereotype
- current file for `forEach` tasks

This will improve CI debugging.

#### 6.3 Log unknown filters as warnings or errors

Unknown filters should not be silently ignored.

Example:

```java
logService.warning("Unknown content filter '%s' for output '%s'", filterName, outputFile);
```

Depending on strictness settings, this could become a task failure.

### Priority 7: Improve performance safeguards

#### 7.1 Add source dump limits

Add configurable limits:

```properties
prjxp.docpipe.source-dump.max-files=200
prjxp.docpipe.source-dump.max-bytes-per-file=200000
prjxp.docpipe.source-dump.max-total-bytes=2000000
```

#### 7.2 Add ignore patterns

Default ignores should include:

- `.git`
- `.gradle`
- `target`
- `build`
- `node_modules`
- `.idea`
- `.prjxp-data`

#### 7.3 Cache prompt templates

Cache template file contents and/or compiled Handlebars templates per run, especially if `forEach` creates many tasks using the same prompt.

#### 7.4 Avoid repeated full repository walks

For `forEach` expansion and source scanning, consider a per-run file index.

### Priority 8: Clean up technical debt

#### 8.1 Remove or wire unused components

Decide the fate of:

- `ModelConfigLoader`
- `DPJob#getModelForStereotype()`
- `EnvResolver`
- `DPGroovyTool`

Unused components should either be integrated, tested, and documented, or removed.

#### 8.2 Fix `.docpipe` vs `.dp` terminology

Use `.dp` consistently in:

- Javadocs
- user documentation
- config examples
- method names if needed

#### 8.3 Improve `EnvResolver`

If kept, make it support:

- system properties
- environment variables
- embedded placeholders
- defaults
- unresolved-variable reporting

Example syntax:

```text
${OPENAI_API_KEY}
${MODEL:gpt-4o-mini}
```

#### 8.4 Make `FileOutputSink` defensive

Handle null parent paths:

```java
Path parent = path.getParent();
if (parent != null) {
    Files.createDirectories(parent);
}
```

#### 8.5 Close `Files.walk()` streams in `DPGroovyTool`

Use try-with-resources:

```java
try (Stream<Path> walk = Files.walk(rootDir)) {
    walk.filter(...)
        .forEach(...);
}
```

#### 8.6 Trim filter names

Support configuration like:

```json
"filterList": "noSurroundingCodeBlock, anotherFilter"
```

by trimming names:

```java
for (String filterName : filterList.split(",")) {
    String normalized = filterName.trim();
}
```

#### 8.7 Make context handling type-safe

Introduce a prompt context object instead of raw `Map<String, String>` casts.

Example:

```java
public record PromptContext(
    Map<String, String> args,
    String currentFile,
    File jobRoot,
    File dpDir
) {}
```

## Recommended Target Architecture

A slightly refined architecture would look like this:

```text
CLI Adapter
  -> DocPipeApplicationService
      -> JobDiscoveryService
      -> ConfigurationValidationService
      -> TaskExecutionService
          -> PromptService
              -> TemplateResolver plugins
          -> UpdateStateService
          -> LLMService
          -> ContentPostProcessor
              -> ContentFilter plugins
          -> OutputService
      -> ManifestService
      -> RunResult / Diagnostics
```

Key changes:

- `DocPipeRunner` becomes thinner.
- Execution policy is centralized.
- Hash/update state is concurrency-safe.
- Configuration is validated before task execution.
- Failures are recorded structurally.
- Groovy remains available but runs under an explicit trust model.
- File/path access goes through a central service.

## Prioritized Roadmap

### Immediate fixes

1. Prevent hash updates on failed output writes.
2. Synchronize or redesign hash-file writes.
3. Add CLI timeout.
4. Fix `storePrompt` path resolution.
5. Standardize failure reporting using `DocPipeRunResult`.

### Short-term improvements

1. Add configuration validation.
2. Add path normalization and boundary checks.
3. Clarify model configuration loading.
4. Collect executor futures and inspect task failures.
5. Log unknown filters.

### Medium-term improvements

1. Refactor duplicated runner logic.
2. Introduce run options and run summary objects.
3. Harden Groovy bindings and add script timeout.
4. Add source-dump limits and ignore patterns.
5. Cache prompt templates or file indexes per run.

### Long-term improvements

1. Define a versioned configuration schema.
2. Add comprehensive integration tests for malformed local configs.
3. Add concurrency tests for parallel hash updates.
4. Add security documentation for trusted/untrusted repository usage.
5. Introduce richer diagnostics output for CI/CD systems.

_This document was generated with .dp and gemini-3-flash-preview_

