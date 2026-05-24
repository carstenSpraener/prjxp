

/**
 * <p>Configuration management and job discovery module for the DocPipe processing pipeline.</p>
 * <p>This package provides the core infrastructure for locating, parsing, and initializing document processing jobs
 * within a project directory structure. It handles the discovery of specialized configuration directories (marked as {@code .dp}),
 * deserializes job definitions and model configurations from JSON files, and resolves environment variable placeholders.</p>
 * <p><b>Architectural Overview:</b></p>
 * <ul>
 *   <li>{@link JobCreationService} serves as the primary orchestrator. It scans a given project root, identifies valid
 *       {@code .dp} directories using {@link DotDPFilesService}, and constructs {@code DPJob} instances by parsing
 *       {@code documents.json} files.</li>
 *   <li>{@link DotDPFilesService} acts as a path and file utility service, centralizing logic for locating configuration
 *       assets such as {@code models.json}, {@code documents.json}, and content hash files within the {@code .dp} directory.</li>
 *   <li>{@link ModelConfigLoader} handles the deserialization of AI/chat model reference configurations from JSON files,
 *       leveraging Jackson's {@code ObjectMapper}.</li>
 *   <li>{@link EnvResolver} provides static utility methods to resolve environment variable placeholders (e.g., {@code ${VAR_NAME}})
 *       within configuration strings.</li>
 *   <li>{@link ConfigException} is a custom checked exception thrown when configuration files cannot be read or parsed correctly.</li>
 * </ul>
 * <p><b>Key Use Cases:</b></p>
 * <ul>
 *   <li><b>Job Discovery:</b> Inject {@link JobCreationService} to scan a project directory and retrieve a stream of initialized
 *       {@code DPJob} objects ready for processing.</li>
 *   <li><b>Configuration Loading:</b> Use {@link ModelConfigLoader} to load model definitions from external JSON configuration files.</li>
 *   <li><b>Environment Resolution:</b> Utilize {@link EnvResolver} to dynamically substitute environment variables in configuration values.</li>
 * </ul>
 */
package de.spraener.prjxp.docpipe.config;

//_This document was generated with Doc|Pipe and qwen3.6-27b-ud-mlx_

