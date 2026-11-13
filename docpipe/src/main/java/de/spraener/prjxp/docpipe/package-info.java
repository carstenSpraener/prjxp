/**
 * Documentation generation pipeline (DocPipe) for PrjXP projects.
 *
 * <p>This package implements the DocPipe module, a Spring Boot–based command-line
 * application that discovers documentation jobs defined in a project, generates the
 * corresponding content (e.g., via LLM-backed tasks), and writes a manifest of all
 * produced artifacts. It supports two execution modes:</p>
 *
 * <ul>
 *   <li><b>CLI mode</b> – the standalone entry point ({@link DocPipeCliApp}) parses
 *       arguments, runs the pipeline, and exits the JVM with a non-zero status on
 *       severe errors.</li>
 *   <li><b>In-process (hub) mode</b> – {@link DocPipeRunner#runForProject} executes
 *       the same pipeline without calling {@code System.exit}; failures are surfaced
 *       via {@link DocPipeRunException}, and a manifest is always written so the
 *       hosting hub can embed whatever was produced.</li>
 * </ul>
 *
 * <h2>Architecture and class interactions</h2>
 *
 * <p>The pipeline follows a clear orchestration flow:</p>
 *
 * <ol>
 *   <li><b>Entry point</b> – {@link DocPipeCliApp} bootstraps the Spring context,
 *       loads {@code .env} variables, and wires a {@code CommandLineRunner} bean
 *       (active only when {@code prjxp.hub.enabled=false}).</li>
 *   <li><b>Argument parsing</b> – {@link DocPipeArgsParser} extends the shared
 *       {@code PxDefaultArgsParser} to handle DocPipe-specific CLI flags and logs
 *       parsing errors through the project's {@code PxLogService}.</li>
 *   <li><b>Orchestration</b> – {@link DocPipeRunner} is the central coordinator.
 *       It delegates job discovery to {@code JobCreationService} (in the
 *       {@code .config} sub-package) and content generation to
 *       {@code ContentCreationService} (in the {@code .content} sub-package).
 *       Tasks are submitted to a fixed-size thread pool for parallel execution.</li>
 *   <li><b>Run state</b> – {@link DocPipeRunResult} is a thread-safe accumulator
 *       (using {@code CopyOnWriteArrayList}) that collects absolute output paths and
 *       per-task failure records during a run.</li>
 *   <li><b>Manifest output</b> – {@link DocPipeManifestWriter} serializes the
 *       collected outputs as a sorted JSON array of project-relative paths (forward
 *       slashes) to {@code .prjxp-data/docpipe/manifest.json}, which the hub's
 *       {@code DocumentSource} reads after the run completes.</li>
 *   <li><b>Configuration</b> – {@link DocPipeConfig} holds global settings such as
 *       the project root directory.</li>
 * </ol>
 *
 * <h2>Key entry points</h2>
 *
 * <ul>
 *   <li>{@link DocPipeCliApp#main(String[])} – launch the standalone CLI application.</li>
 *   <li>{@link DocPipeRunner#run(de.spraener.prjxp.common.config.PrjXPConfig)} –
 *       execute the pipeline in CLI mode (exits on error).</li>
 *   <li>{@link DocPipeRunner#runForProject(de.spraener.prjxp.common.config.ProjectDefinition)} –
 *       execute the pipeline in-process for the hub (throws on failure, always writes
 *       the manifest).</li>
 * </ul>
 *
 * <h2>Dependencies on sibling packages</h2>
 *
 * <ul>
 *   <li>{@code de.spraener.prjxp.docpipe.config} – job and file discovery services.</li>
 *   <li>{@code de.spraener.prjxp.docpipe.content} – content-creation task model and
 *       execution service.</li>
 *   <li>{@code de.spraener.prjxp.common} – shared configuration, CLI utilities, and
 *       error-logging infrastructure.</li>
 * </ul>
 *
 * @see DocPipeCliApp
 * @see DocPipeRunner
 * @see DocPipeManifestWriter
 * @see DocPipeRunResult
 */
package de.spraener.prjxp.docpipe;

//_This document was generated with Doc|Pipe and qwen3.6-27b-ud-mlx_

