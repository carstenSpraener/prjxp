/**
 * Documentation generation pipeline (DocPipe) for PrjXP projects.
 *
 * <p>
 * This package implements the DocPipe module, a Spring Boot–based pipeline that discovers
 * documentation jobs defined in a project, creates individual content-creation tasks, and
 * executes them in parallel using a fixed thread pool. It supports two execution modes:
 * </p>
 *
 * <ul>
 *   <li><b>Standalone CLI</b> – launched via {@link DocPipeCliApp}, the application reads
 *       a {@code .env} file, parses command-line arguments with {@link DocPipeArgsParser},
 *       and delegates execution to {@link DocPipeRunner#run(de.spraener.prjxp.common.config.PrjXPConfig)}.
 *       On severe errors the process exits with status&nbsp;1.</li>
 *   <li><b>In-process (hub)</b> – invoked via {@link DocPipeRunner#runForProject(de.spraener.prjxp.common.config.ProjectDefinition)},
 *       the pipeline runs without calling {@code System.exit}. Failures are reported by throwing
 *       {@link DocPipeRunException}, and a manifest of all generated documents is written in every
 *       outcome (success, partial failure, or timeout) so the calling hub can embed whatever was produced.</li>
 * </ul>
 *
 * <h2>Architecture and class interactions</h2>
 *
 * <p>The pipeline follows a clear orchestration flow:</p>
 *
 * <ol>
 *   <li><b>Configuration</b> – {@link DocPipeConfig} holds the project root directory;
 *       the broader {@code PrjXPConfig} (from the common module) carries active-project metadata.</li>
 *   <li><b>Job discovery</b> – {@code JobCreationService} (in the {@code .config} sub-package)
 *       reads job definitions from the project. Each job contains a list of content-creation
 *       specifications that are flattened into {@code ContentCreationTask} instances.</li>
 *   <li><b>Parallel execution</b> – {@link DocPipeRunner} submits all tasks to a fixed-size
 *       {@link java.util.concurrent.ExecutorService} (size configurable via
 *       {@code prjxp.docpipe.maxthreads}, default&nbsp;5). Each task is handled by
 *       {@code ContentCreationService} (in the {@code .content} sub-package).</li>
 *   <li><b>Result aggregation</b> – {@link DocPipeRunResult} provides thread-safe
 *       ({@link java.util.concurrent.CopyOnWriteArrayList}-backed) collection of output paths
 *       and failure records produced during the run.</li>
 *   <li><b>Manifest output</b> – {@link DocPipeManifestWriter} serializes the collected output
 *       paths (relative to the project root, forward-slash separated) into a JSON file at
 *       {@code .prjxp-data/docpipe/manifest.json} for downstream consumption by the hub's
 *       {@code DocumentSource}.</li>
 * </ol>
 *
 * <h2>Key entry points</h2>
 *
 * <ul>
 *   <li>{@link DocPipeCliApp#main(String[])} – bootstrap for standalone CLI usage.</li>
 *   <li>{@link DocPipeRunner#run(de.spraener.prjxp.common.config.PrjXPConfig)} – CLI execution
 *       (exits the JVM on failure).</li>
 *   <li>{@link DocPipeRunner#runForProject(de.spraener.prjxp.common.config.ProjectDefinition)} –
 *       in-process execution for the hub (throws on failure, always writes manifest).</li>
 * </ul>
 *
 * <h2>Sub-packages</h2>
 *
 * <ul>
 *   <li>{@code de.spraener.prjxp.docpipe.config} – job discovery, model configuration loading,
 *       and file-service utilities.</li>
 *   <li>{@code de.spraener.prjxp.docpipe.content} – content-creation task model and the service
 *       that performs the actual document generation.</li>
 * </ul>
 *
 * <h2>Dependencies</h2>
 *
 * <p>The package relies on the {@code de.spraener.prjxp.common} module for shared configuration
 * ({@code PrjXPConfig}, {@code ProjectDefinition}), error logging ({@code PxLogService},
 * {@code PxLogMessage}), and the default argument parser ({@code PxDefaultArgsParser}).
 * External dependencies include Spring Boot, Jackson (JSON serialization), Lombok, and
 * dotenv-java (environment variable loading).</p>
 */
package de.spraener.prjxp.docpipe;

//_This document was generated with Doc|Pipe and qwen3.6-27b-ud-mlx_

