

/**
 * <p>Configuration management and job discovery module for the DocPipe documentation pipeline.</p>
 * <p>This package provides the foundational services and utilities required to locate, parse, and validate
 * pipeline configuration files within a project structure. It abstracts filesystem operations for the 
 * {@code .dp} configuration directories, handles JSON-based configuration loading (e.g., models and document definitions),
 * and orchestrates the discovery of documentation jobs.</p>
 * 
 * <p><b>Architecture & Class Interactions:</b></p>
 * <ul>
 *   <li>{@link de.spraener.prjxp.docpipe.config.DotDPFilesService} acts as the central filesystem utility, 
 *       resolving paths for configuration directories and specific files like {@code models.json}, 
 *       {@code documents.json}, and content hash properties.</li>
 *   <li>{@link de.spraener.prjxp.docpipe.config.JobCreationService} serves as the primary orchestrator for job discovery. 
 *       It scans project directories, leverages {@code DotDPFilesService} to locate configuration files, and uses 
 *       Jackson's {@code ObjectMapper} to parse JSON definitions into executable {@link de.spraener.prjxp.docpipe.model.DPJob} instances.</li>
 *   <li>{@link de.spraener.prjxp.docpipe.config.ModelConfigLoader} specializes in loading Large Language Model (LLM) 
 *       configurations from JSON files, mapping them to {@link de.spraener.prjxp.common.config.PrjXPChatModelReference} objects.</li>
 *   <li>{@link de.spraener.prjxp.docpipe.config.EnvResolver} provides a cross-cutting utility for resolving 
 *       environment variable placeholders (e.g., {@code ${VAR_NAME}}) within configuration strings.</li>
 *   <li>{@link de.spraener.prjxp.docpipe.config.ConfigException} is the domain-specific exception thrown during 
 *       configuration loading or parsing failures.</li>
 * </ul>
 * 
 * <p><b>Key Entry Points & Use Cases:</b></p>
 * <ul>
 *   <li><b>Job Discovery:</b> Use {@link de.spraener.prjxp.docpipe.config.JobCreationService#readJobs(java.util.Optional)} 
 *       to scan a project root and retrieve a stream of configured documentation jobs.</li>
 *   <li><b>Model Configuration Loading:</b> Inject {@link de.spraener.prjxp.docpipe.config.ModelConfigLoader} and call 
 *       {@link de.spraener.prjxp.docpipe.config.ModelConfigLoader#listFrom(String)} to load LLM provider settings.</li>
 *   <li><b>Path Resolution:</b> Utilize {@link de.spraener.prjxp.docpipe.config.DotDPFilesService} methods to dynamically 
 *       resolve configuration file paths relative to project directories or content creation tasks.</li>
 *   <li><b>Environment Variable Substitution:</b> Apply {@link de.spraener.prjxp.docpipe.config.EnvResolver#resolve(String)} 
 *       to safely substitute system environment variables in configuration values.</li>
 * </ul>
 */
package de.spraener.prjxp.docpipe.config;

//_This document was generated with Doc|Pipe and qwen3.6-27b-ud-mlx_

