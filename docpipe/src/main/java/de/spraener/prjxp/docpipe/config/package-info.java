/**
 * Provides configuration support for the DocPipe documentation pipeline.
 *
 * <p>
 * This package is responsible for locating DocPipe configuration files, loading document
 * definitions, resolving simple configuration placeholders, and creating
 * executable documentation jobs from project-local configuration directories.
 * The configuration convention used by the package is a {@code .dp} directory placed
 * below a project or job root directory.
 * </p>
 *
 * <p>
 * The main configuration files handled by this package are:
 * </p>
 * <ul>
 *   <li>{@code .dp/documents.json} &mdash; defines document/content creation tasks.</li>
 *   <li>{@code .dp/content-hashes.properties} &mdash; stores content hash metadata.</li>
 * </ul>
 *
 * <p>
 * Architecturally, {@link DotDPFilesService} centralizes path and file resolution for
 * all {@code .dp}-related resources. {@link JobCreationService} uses it together with
 * Jackson's object mapping infrastructure to discover directories containing DocPipe
 * configuration, read {@code documents.json}, and create {@code DPJob} instances with
 * their associated content creation definitions. When a content creation definition
 * contains a {@code forEach} glob pattern, the job creation service expands it into
 * one content creation task per matching file. {@link EnvResolver} provides lightweight
 * environment variable substitution for string configuration values. Chat model
 * configuration is not read from files here; it lives exclusively in
 * {@code application.yaml} ({@code prjxp.chatModels[]}).
 * </p>
 *
 * <p>
 * Typical entry points are:
 * </p>
 * <ul>
 *   <li>{@link JobCreationService#readJobs(java.util.Optional)} for discovering and
 *       constructing documentation jobs for a project definition.</li>
 *   <li>{@link DotDPFilesService} for resolving well-known DocPipe configuration and
 *       output paths.</li>
 * </ul>
 *
 * <p>
 * Configuration parsing failures are reported through {@link ConfigException} where
 * applicable. Services in this package are designed for use as Spring-managed
 * components and are intended to be reused by higher-level DocPipe orchestration code.
 * </p>
 */
package de.spraener.prjxp.docpipe.config;

//_This document was generated with Doc|Pipe and qwen3.6-27b-ud-mlx_

