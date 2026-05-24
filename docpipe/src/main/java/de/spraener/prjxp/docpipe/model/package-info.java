

/**
 * The {@code de.spraener.prjxp.docpipe.model} package defines the core domain model for the documentation pipeline.
 * It encapsulates the data structures required to configure and execute documentation generation jobs,
 * primarily leveraging Large Language Models (LLMs) for content creation.
 *
 * <p>
 * The package centers around two main entities:
 * </p>
 * <ul>
 *   <li>{@link DPJob}: Represents a complete documentation job configuration. It aggregates the root directory,
 *       project-specific settings (including LLM model mappings via {@code PrjXPConfig}), and a list of
 *       content creation tasks. It also provides utility methods to resolve LLM model references based on stereotypes
 *       and includes a fallback {@code EMPTY_JOB} instance for handling misconfigurations gracefully.</li>
 *   <li>{@link DPContentCreation}: Represents an individual content generation task within a job. It specifies
 *       the output file path, the LLM model stereotype to use, the prompt template, optional post-scripts,
 *       and filters for content processing.</li>
 * </ul>
 *
 * <p>
 * <b>Architectural Overview:</b><br>
 * A {@code DPJob} acts as the container for a documentation run. It holds a collection of {@code DPContentCreation}
 * instances, each defining a specific output artifact to be generated. The job configuration includes references
 * to LLM models mapped by stereotypes; during execution, the pipeline can query the job to resolve the appropriate
 * model configuration for a given stereotype defined in a content creation task. This design decouples the specific
 * model implementation from the job definition, allowing flexibility in model selection via stereotypes.
 * </p>
 *
 * <p>
 * <b>Key Use Cases:</b><br>
 * <ul>
 *   <li>Configuring a documentation job by setting the root directory, project configuration, and list of tasks.</li>
 *   <li>Defining content creation tasks with specific prompts, output targets, and model stereotypes.</li>
 *   <li>Resolving LLM chat model references dynamically based on stereotypes within the context of a job.</li>
 *   <li>Utilizing {@code DPJob.EMPTY_JOB} as a safe fallback when job configuration is invalid or missing.</li>
 * </ul>
 * </p>
 */

package de.spraener.prjxp.docpipe.model;

//_This document was generated with Doc|Pipe and qwen3.6-27b-ud-mlx_

