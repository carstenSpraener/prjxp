/**
 * Documentation pipeline ("DocPipe") for the PrjXP project.
 *
 * <p>This package implements a pluggable, job-driven documentation generation pipeline.
 * It discovers documentation jobs defined in a project, flattens them into individual
 * content-creation tasks, and executes those tasks in parallel using a configurable
 * fixed thread pool. Generated artifacts are tracked per run and recorded in a JSON
 * manifest that downstream consumers (e.g. the PrjXP hub) can read.</p>
 *
 * <h2>Architecture and Key Components</h2>
 * <p>The package is organized around the following responsibilities:</p>
 * <ul>
 *   <li><b>Application entry point</b> – {@link DocPipeCliApp} bootstraps a Spring
 *       application, loads environment variables from a {@code .env} file, and wires
 *       the {@link CommandLineRunner} that drives the pipeline in CLI mode.</li>
 *   <li><b>Argument parsing</b> – {@link DocPipeArgsParser} extends the shared
 *       {@code PxDefaultArgsParser} to handle DocPipe-specific CLI arguments and
 *       reports parse errors through the project's logging service.</li>
 *   <li><b>Pipeline orchestration</b> – {@link DocPipeRunner} is the central
 *       coordinator. It reads jobs (via {@code JobCreationService}), maps them to
 *       {@code ContentCreationTask} instances, and submits them to a thread pool
 *       for parallel execution (via {@code ContentCreationService}).</li>
 *   <li><b>Run state</b> – {@link DocPipeRunResult} is a thread-safe accumulator
 *       that collects absolute output paths and failure records produced during a
 *       single pipeline run.</li>
 *   <li><b>Manifest output</b> – {@link DocPipeManifestWriter} serializes the
 *       collected outputs as a sorted JSON array of project-relative paths
 *       (forward-slash separated) into {@code .prjxp-data/docpipe/manifest.json}.</li>
 *   <li><b>Configuration</b> – {@link DocPipeConfig} holds global settings such as
 *       the project root directory.</li>
 *   <li><b>Error signaling</b> – {@link DocPipeRunException} is thrown (rather than
 *       calling {@code System.exit}) when the pipeline is invoked in-process by the
 *       hub and one or more tasks fail.</li>
 * </ul>
 *
 * <h2>Execution Modes</h2>
 * <p>DocPipe supports two invocation contexts:</p>
 * <ul>
 *   <li><b>CLI mode</b> (default): Launched via {@code DocPipeCliApp.main()}.
 *       The {@code CommandLineRunner} bean is active when the property
 *       {@code prjxp.hub.enabled} is {@code false} (or absent). On severe errors
 *       the process exits with status&nbsp;1.</li>
 *   <li><b>In-process / hub mode</b>: The hub calls
 *       {@link DocPipeRunner#runForProject(de.spraener.prjxp.common.config.ProjectDefinition)}
 *       directly. Failures are surfaced by throwing {@link DocPipeRunException};
 *       the manifest is written in all cases (including partial failure or timeout)
 *       so the hub can embed whatever documents were produced.</li>
 * </ul>
 *
 * <h2>Sub-packages</h2>
 * <ul>
 *   <li>{@code de.spraener.prjxp.docpipe.config} – Job discovery and file-service
 *       components ({@code JobCreationService}, {@code DotDPFilesService}).</li>
 *   <li>{@code de.spraener.prjxp.docpipe.content} – Content-creation logic
 *       ({@code ContentCreationService}, {@code ContentCreationTask}).</li>
 * </ul>
 *
 * <h2>Typical Flow (CLI)</h2>
 * <ol>
 *   <li>{@code DocPipeCliApp.main} loads {@code .env} and starts Spring.</li>
 *   <li>The {@code CommandLineRunner} bean invokes {@code DocPipeArgsParser.parseArgs}
 *       to populate the {@code PrjXPConfig}.</li>
 *   <li>{@code DocPipeRunner.run} reads jobs, creates tasks, and executes them
 *       concurrently.</li>
 *   <li>On completion, if any SEVERE log entries exist, a summary is printed and
 *       the JVM exits with code&nbsp;1.</li>
 * </ol>
 *
 * @see DocPipeCliApp
 * @see DocPipeRunner
 * @see DocPipeManifestWriter
 * @see DocPipeRunResult
 */
package de.spraener.prjxp.docpipe;

//_This document was generated with Doc|Pipe and qwen3.6-27b-ud-mlx_

