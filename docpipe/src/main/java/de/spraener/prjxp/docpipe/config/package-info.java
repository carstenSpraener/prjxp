/**
 * Configuration management for the DocPipe documentation pipeline.
 *
 * <p>This package provides the services and utilities responsible for discovering,
 * loading, and resolving all configuration artifacts used by the DocPipe system.
 * The primary configuration unit is the {@code .dp} directory, which contains
 * JSON files defining documentation jobs, LLM model references, and content
 * hash metadata.</p>
 *
 * <h2>Core Responsibilities</h2>
 * <ul>
 *   <li><b>Path resolution</b> – Locating {@code .dp} directories and the files
 *       they contain ({@code documents.json}, {@code models.json},
 *       {@code content-hashes.properties}).</li>
 *   <li><b>Job discovery and creation</b> – Scanning a project's file tree for
 *       {@code .dp} folders and constructing {@code DPJob} instances, including
 *       glob-based expansion of content-creation tasks.</li>
 *   <li><b>Model configuration loading</b> – Deserializing LLM model reference
 *       definitions from JSON into typed objects.</li>
 *   <li><b>Environment variable resolution</b> – Substituting {@code ${VAR}}
 *       placeholders in configuration strings with values from the system
 *       environment.</li>
 * </ul>
 *
 * <h2>Architecture and Class Interactions</h2>
 * <p>The classes in this package form a layered configuration stack:</p>
 * <ul>
 *   <li>{@link DotDPFilesService} is the foundational path-resolution component.
 *       All other services depend on it to compute file and directory locations
 *       relative to a project root or a {@code ContentCreationTask}.</li>
 *   <li>{@link JobCreationService} is the primary entry point for job discovery.
 *       It walks the project directory tree, delegates path checks to
 *       {@code DotDPFilesService}, parses {@code documents.json} via Jackson,
 *       and performs glob-pattern expansion to produce a stream of
 *       {@code DPJob} objects.</li>
 *   <li>{@link ModelConfigLoader} reads {@code models.json} files and deserializes
 *       them into lists of {@code PrjXPChatModelReference} objects. It is
 *       typically invoked with a path obtained from {@code DotDPFilesService}.</li>
 *   <li>{@link EnvResolver} is a stateless static utility that can be applied to
 *       any configuration string to resolve environment-variable placeholders
 *       before further processing.</li>
 *   <li>{@link ConfigException} is the checked exception type thrown when
 *       configuration files cannot be read or parsed.</li>
 * </ul>
 *
 * <h2>Typical Usage Flow</h2>
 * <ol>
 *   <li>Call {@link JobCreationService#readJobs(java.util.Optional)} with a
 *       project definition to obtain a stream of discovered {@code DPJob}
 *       instances.</li>
 *   <li>Use {@link DotDPFilesService} methods to resolve paths to specific
 *       configuration files (models, documents, content hashes) for a given
 *       directory or task.</li>
 *   <li>Invoke {@link ModelConfigLoader#listFrom(String)} to load LLM model
 *       references for a job.</li>
 *   <li>Apply {@link EnvResolver#resolve(String)} to any configuration value
 *       that may contain environment-variable placeholders.</li>
 * </ol>
 *
 * <p>All Spring-managed services in this package are registered as beans
 * ({@code @Service} / {@code @Component}) and can be injected into other
 * components of the DocPipe application.</p>
 *
 * @see DotDPFilesService
 * @see JobCreationService
 * @see ModelConfigLoader
 * @see EnvResolver
 * @see ConfigException
 */
package de.spraener.prjxp.docpipe.config;

//_This document was generated with Doc|Pipe and qwen3.6-27b-ud-mlx_

