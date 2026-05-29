

/**
 * Configuration management and initialization infrastructure for the DocPipe documentation pipeline.
 * <p>
 * This package provides the core services responsible for discovering project directories, resolving configuration file paths, loading model definitions, and instantiating documentation jobs based on the {@code .docpipe} directory structure. It serves as the foundational layer for bootstrapping and configuring documentation generation tasks within a project.
 * </p>
 * <p>
 * The package follows a service-oriented design leveraging Spring's dependency injection. Key components interact as follows:
 * </p>
 * <ul>
 *     <li><b>{@code JobCreationService}</b>: Acts as the primary orchestrator for job discovery. It scans project roots, identifies directories containing a {@code .docpipe} folder, and parses their {@code documents.json} files to construct {@link de.spraener.prjxp.docpipe.model.DPJob} instances. It also handles dynamic expansion of content creation tasks via {@code forEach} patterns.</li>
 *     <li><b>{@code DotDPFilesService}</b>: Provides path resolution utilities for the standard {@code .dp} configuration directory structure. It abstracts file system operations to locate {@code models.json}, {@code documents.json}, and {@code content-hashes.properties} relative to given directories or tasks.</li>
 *     <li><b>{@code ModelConfigLoader}</b>: Handles the deserialization of LLM model configurations from JSON files into {@link de.spraener.prjxp.common.config.PrjXPChatModelReference} objects, enabling dynamic model provider and stereotype configuration.</li>
 *     <li><b>{@code EnvResolver}</b>: A lightweight utility for environment variable substitution, replacing {@code ${VARIABLE_NAME}} placeholders in configuration strings with their corresponding system environment values.</li>
 *     <li><b>{@code ConfigException}</b>: A custom checked exception used across the package to signal failures during configuration file loading, parsing, or validation.</li>
 * </ul>
 * <p>
 * Together, these components form a cohesive configuration layer that abstracts file system navigation, JSON deserialization, and environment-aware string resolution, providing a clean API for pipeline initialization.
 * </p>
 * <h2>Key Entry Points & Use Cases</h2>
 * <p>
 * The primary entry point for consumers is {@code JobCreationService.readJobs()}, which bootstraps documentation jobs from a given {@link de.spraener.prjxp.common.config.ProjectDefinition}. Typical use cases include:
 * </p>
 * <ul>
 *     <li>Bootstrapping documentation pipelines by scanning project directories for {@code .docpipe} configurations.</li>
 *     <li>Dynamically generating content creation tasks based on file patterns and environment variables.</li>
 *     <li>Loading and validating LLM model configurations for different documentation stereotypes.</li>
 *     <li>Catching and handling configuration-related failures via {@code ConfigException} to ensure robust pipeline initialization.</li>
 * </ul>
 */
package de.spraener.prjxp.docpipe.config;

//_This document was generated with Doc|Pipe and qwen3.6-27b-ud-mlx_

