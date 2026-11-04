/**
 * Command-line and in-process orchestration package for the DocPipe documentation
 * generation pipeline.
 *
 * <p>
 * This package provides the Spring Boot entry point, argument parsing, run
 * coordination, per-run result tracking, and manifest writing needed to execute
 * DocPipe against a configured project. DocPipe discovers documentation jobs,
 * converts them into content-creation tasks, executes those tasks concurrently,
 * records produced output files, and reports failures in a form suitable for both
 * CLI execution and embedding by other application modules.
 * </p>
 *
 * <p>
 * The main architectural flow is:
 * </p>
 * <ul>
 *   <li>{@link DocPipeCliApp} starts the Spring Boot application, loads optional
 *       {@code .env} properties, parses command-line arguments, and invokes the
 *       runner when CLI execution is enabled.</li>
 *   <li>{@link DocPipeArgsParser} extends the common project argument parser and
 *       logs parsing errors without terminating the application immediately.</li>
 *   <li>{@link DocPipeRunner} is the central coordinator. It uses services from
 *       the configuration and content subpackages to read DocPipe jobs, create
 *       content-creation tasks, and execute them with a fixed-size thread pool.</li>
 *   <li>{@link DocPipeRunResult} collects generated output paths and task failures
 *       in a thread-safe structure during in-process runs.</li>
 *   <li>{@link DocPipeManifestWriter} writes a JSON manifest of generated files
 *       below {@code .prjxp-data/docpipe/manifest.json}, allowing downstream
 *       components to discover and consume generated documentation.</li>
 *   <li>{@link DocPipeRunException} reports in-process run failures without
 *       calling {@code System.exit}.</li>
 * </ul>
 *
 * <p>
 * Primary use cases are:
 * </p>
 * <ul>
 *   <li>Running DocPipe as a command-line Spring Boot application via
 *       {@link DocPipeCliApp#main(String[])}.</li>
 *   <li>Executing the pipeline for the active configured project through
 *       {@link DocPipeRunner#run(de.spraener.prjxp.common.config.PrjXPConfig)}.</li>
 *   <li>Embedding DocPipe in another module by invoking
 *       {@link DocPipeRunner#runForProject(de.spraener.prjxp.common.config.ProjectDefinition)},
 *       which avoids process termination and writes a manifest even when partial
 *       failures occur.</li>
 * </ul>
 *
 * <p>
 * Configuration is supplied primarily through the shared
 * {@link de.spraener.prjxp.common.config.PrjXPConfig} and
 * {@link de.spraener.prjxp.common.config.ProjectDefinition} model, Spring
 * properties such as {@code prjxp.docpipe.maxthreads}, and optional values loaded
 * from a local {@code .env} file before the Spring context is initialized.
 * </p>
 */
package de.spraener.prjxp.docpipe;

//_This document was generated with Doc|Pipe and qwen3.6-27b-ud-mlx_

