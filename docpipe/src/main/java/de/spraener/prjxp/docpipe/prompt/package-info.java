

/**
 * Provides a dynamic prompt templating and resolution system for the document pipeline.
 * <p>
 * This package leverages the Handlebars templating engine to transform static prompt templates into fully resolved,
 * context-aware prompts suitable for Large Language Model (LLM) consumption. It introduces a pluggable architecture
 * where custom logic, data injection, and file operations are encapsulated within {@link TemplateResolver} implementations.
 * </p>
 * <p>
 * The core design follows a strategy pattern where each resolver handles a specific type of dynamic content. 
 * Spring's dependency injection automatically discovers all resolver implementations, and the central service 
 * registers them as Handlebars helpers at runtime. This allows template authors to use declarative helper tags 
 * without needing to understand the underlying resolution mechanics.
 * </p>
 * <ul>
 *   <li><b>{@link TemplateResolver}</b>: The foundational interface defining the contract for dynamic content resolution. 
 *       Implementations must provide a unique identifier (helper name) and a {@code resolve()} method.</li>
 *   <li><b>{@link PromptResolvingService}</b>: The primary orchestrator and entry point. It initializes Handlebars, 
 *       auto-registers all discovered {@code TemplateResolver} beans as helpers via an internal adapter, and executes 
 *       template compilation and application.</li>
 *   <li><b>Built-in Resolvers</b>: {@link GRResolver} injects project-specific enrichment data, 
 *       {@link URLResolver} embeds external file content, {@link GroovyResolver} executes runtime scripts with Spring context bindings, 
 *       and {@link SourceDumpResolver} scans directories to inject source code into Markdown blocks.</li>
 *   <li><b>{@link TemplateException}</b>: A dedicated runtime exception thrown to signal failures during the resolution lifecycle.</li>
 * </ul>
 * <p>
 * <b>Key Entry Points &amp; Usage:</b><br/>
 * The main entry point is {@link PromptResolvingService}. To extend the system, implement {@link TemplateResolver}, 
 * annotate it with Spring's {@code @Component}, and the service will automatically make it available in templates. 
 * Common template helper patterns include:
 * </p>
 * <ul>
 *   <li><code>{{gr prj="project-id"}}</code> - Injects enriched project metadata.</li>
 *   <li><code>{{URL "path/to/config.txt"}}</code> - Reads and embeds external file content.</li>
 *   <li><code>{{groovy}} ... {{/groovy}}</code> - Executes embedded Groovy scripts with access to directory and Spring context.</li>
 *   <li><code>{{java-src-dump "src/main/java" scanSubs=true}}</code> - Dumps matching source files into formatted code blocks.</li>
 * </ul>
 */
package de.spraener.prjxp.docpipe.prompt;

//_This document was generated with Doc|Pipe and qwen3.6-27b-ud-mlx_

