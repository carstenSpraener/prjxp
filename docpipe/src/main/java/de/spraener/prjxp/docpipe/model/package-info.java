

/**
 * Core data models for the documentation pipeline.
 * <p>
 * This package defines the foundational configuration and task structures used to orchestrate automated documentation generation. It serves as the central domain model for mapping project directories, LLM (Large Language Model) configurations, and granular content creation instructions.
 * </p>
 * <p>
 * The package revolves around two primary classes that work together to define a documentation workflow:
 * <ul>
 *   <li>{@link de.spraener.prjxp.docpipe.model.DPJob}: Acts as the top-level container for a documentation job. It holds the root project directory, references to LLM chat models via {@link de.spraener.prjxp.common.config.PrjXPConfig}, and a list of individual content creation tasks.</li>
 *   <li>{@link de.spraener.prjxp.docpipe.model.DPContentCreation}: Represents a single, executable task within the job. It specifies output targets, prompt templates, LLM stereotypes, post-processing scripts, and filtering rules.</li>
 * </ul>
 * The {@link de.spraener.prjxp.docpipe.model.DPJob} orchestrates the execution by iterating over its {@code contentCreationList}, resolving appropriate LLM models via {@link de.spraener.prjxp.docpipe.model.DPJob#getModelForStereotype(String)}, and passing task-specific parameters to downstream processors.
 * </p>
 * <p>
 * Key entry points and primary use cases include:
 * <ul>
 *   <li><strong>Job Configuration:</strong> Define the scope of documentation generation by setting a root directory and associating LLM models with specific stereotypes.</li>
 *   <li><strong>Task Definition:</strong> Configure granular content generation steps, including prompt templates, output paths, and post-processing filters.</li>
 *   <li><strong>Fallback Handling:</strong> Utilize {@link de.spraener.prjxp.docpipe.model.DPJob#EMPTY_JOB} to gracefully handle misconfigurations without halting the entire pipeline.</li>
 * </ul>
 * </p>
 */
package de.spraener.prjxp.docpipe.model;

//_This document was generated with Doc|Pipe and qwen3.6-27b-ud-mlx_

