/**
 * Configuration management for the DocPipe documentation pipeline.
 *
 * <p>This package provides the services and utilities required to discover,
 * configure, and resolve documentation pipeline (DocPipe) jobs within a project.
 * Its core responsibilities include:</p>
 *
 * <ul>
 *   <li><b>Job discovery and creation</b> – scanning the project directory tree
 *       for {@code .dp} configuration folders and parsing their {@code documents.json}
 *       files into {@link de.spraener.prjxp.docpipe.model.DPJob} instances.</li>
 *   <li><b>Path resolution</b> – locating the {@code .dp} directory, the
 *       {@code documents.json} configuration file, the content-hashes properties
 *       file, and output file paths relative to a given root or task.</li>
 *   <li><b>Environment variable resolution</b> – substituting {@code ${VAR}}
 *       placeholders in configuration strings with values from the system
 *       environment.</li>
 *   <li><b>Error handling</b> – a dedicated checked exception for configuration
 *       loading and parsing failures.</li>
 * </ul>
 *
 * <h2>Architecture</h2>
 *
 * <p>The package is organized around a small set of collaborating Spring-managed
 * components and plain utilities:</p>
 *
 * <ul>
 *   <li>{@link JobCreationService} – the primary entry point. As a Spring
 *       {@code @Service}, it walks the project root directory, identifies
 *       sub-directories that contain a {@code .dp} folder, and constructs
 *       fully-populated {@code DPJob} objects (including expanded
 *       {@code forEach} glob patterns) from the parsed {@code documents.json}
 *       content. It delegates all path-related lookups to
 *       {@link DotDPFilesService}.</li>
 *   <li>{@link DotDPFilesService} – a Spring {@code @Component} acting as a
 *       path-resolution facade. It encapsulates the well-known file and
 *       directory names (e.g., {@code .dp}, {@code documents.json},
 *       {@code content-hashes.properties}) and exposes convenience methods
 *       that accept either a {@link java.io.File}/{@link java.nio.file.Path}
 *       or a {@link de.spraener.prjxp.docpipe.content.ContentCreationTask}.</li>
 *   <li>{@link EnvResolver} – a stateless static utility that resolves
 *       {@code ${ENV_VAR}} placeholders against {@link System#getenv()}.
 *       It is intended to be called during configuration parsing to allow
 *       environment-specific values in {@code documents.json}.</li>
 *   <li>{@link ConfigException} – a checked exception signaling failures
 *       while reading or parsing configuration files. Callers that perform
 *       configuration I/O should catch or propagate this type.</li>
 * </ul>
 *
 * <h2>Typical Usage</h2>
 *
 * <p>A consuming component (e.g., a pipeline orchestrator) typically injects
 * {@link JobCreationService} and calls
 * {@link JobCreationService#readJobs(java.util.Optional)} with the current
 * {@link de.spraener.prjxp.common.config.ProjectDefinition} to obtain a
 * {@link java.util.stream.Stream} of ready-to-execute {@code DPJob} instances.
 * For ad-hoc path lookups or environment variable substitution, the
 * {@link DotDPFilesService} and {@link EnvResolver} classes can be used
 * independently.</p>
 *
 * @see JobCreationService
 * @see DotDPFilesService
 * @see EnvResolver
 * @see ConfigException
 */
package de.spraener.prjxp.docpipe.config;

//_This document was generated with Doc|Pipe and qwen3.6-27b-ud-mlx_

