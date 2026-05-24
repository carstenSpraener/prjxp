

/**
 * The {@code de.spraener.prjxp.docpipe} package provides the core command-line interface and execution engine for the DocPipe documentation generation pipeline.
 * <p>
 * This module is designed to automate the discovery, processing, and generation of documentation content for software projects. It operates as a standalone Spring Boot CLI application that reads project configurations, parses command-line arguments, and orchestrates parallel content creation tasks.
 * </p>
 * <p><b>Architecture and Class Interactions:</b></p>
 * <p>
 * The package follows a clear separation of concerns between application bootstrap, configuration management, argument parsing, and pipeline execution:
 * </p>
 * <ul>
 *   <li>{@link DocPipeCliApp} serves as the primary bootstrap class. It initializes the Spring context, loads environment variables from a {@code .env} file, and configures the application lifecycle via a {@link org.springframework.boot.CommandLineRunner}.</li>
 *   <li>{@link DocPipeArgsParser} handles command-line argument parsing. It extends a default parser to integrate application-specific logic and routes parsing errors to the shared logging service.</li>
 *   <li>{@link DocPipeRunner} acts as the central orchestrator. Upon execution, it discovers documentation jobs, maps them to individual content creation tasks, and submits them to a fixed thread pool for parallel processing.</li>
 *   <li>{@link DocPipeConfig} provides lightweight configuration storage, primarily holding the root directory path of the target project.</li>
 * </ul>
 * <p>
 * These components interact sequentially during startup: the CLI app parses arguments, updates the project configuration, and delegates control to the runner. The runner coordinates with external services (such as job creation and content generation services) to execute the pipeline, while aggregating logs and handling severe errors by terminating the process with a non-zero exit code.
 * </p>
 * <p><b>Key Entry Points and Use Cases:</b></p>
 * <ul>
 *   <li><b>Application Startup:</b> Invoke {@link DocPipeCliApp#main(String[])} to launch the documentation pipeline. The application automatically reads environment variables and CLI arguments before execution.</li>
 *   <li><b>Pipeline Execution:</b> The {@link DocPipeRunner#run(de.spraener.prjxp.common.config.PrjXPConfig)} method is the core execution entry point, triggered automatically by the Spring {@code CommandLineRunner} bean.</li>
 *   <li><b>Configuration & Customization:</b> Developers can adjust thread pool limits via the {@code prjxp.docpipe.maxthreads} property or modify project paths through CLI arguments and environment variables.</li>
 * </ul>
 */
package de.spraener.prjxp.docpipe;

//_This document was generated with Doc|Pipe and qwen3.6-27b-ud-mlx_

