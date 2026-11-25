/**
 * Data model for documentation pipeline job definitions.
 *
 * <p>
 * This package contains the configuration objects used to describe documentation generation
 * work performed by the DocPipe component. The model focuses on representing a documentation
 * job, its project-level configuration, and the individual content creation tasks that make up
 * the job.
 * </p>
 *
 * <p>
 * The central aggregate is {@link de.spraener.prjxp.docpipe.model.DPJob}. A job defines:
 * </p>
 * <ul>
 *   <li>the root directory against which documentation generation is executed,</li>
 *   <li>the project configuration, including available chat model references, and</li>
 *   <li>a list of {@link de.spraener.prjxp.docpipe.model.DPContentCreation} tasks.</li>
 * </ul>
 *
 * <p>
 * Each {@code DPContentCreation} describes one content generation step, including the target
 * output location, the model stereotype to use, the prompt template, optional post-script
 * content, filters, persisted prompt settings, iteration configuration, and task-specific
 * arguments.
 * </p>
 *
 * <p>
 * The package is primarily used by higher-level pipeline components that load job definitions,
 * resolve the configured chat model stereotype through
 * {@link de.spraener.prjxp.docpipe.model.DPJob#getModelForStereotype(String)}, and execute each
 * configured content creation task. {@link de.spraener.prjxp.docpipe.model.DPJob#EMPTY_JOB}
 * provides a safe fallback object for misconfigured or missing job definitions.
 * </p>
 */
package de.spraener.prjxp.docpipe.model;

//_This document was generated with Doc|Pipe and qwen3.6-27b-ud-mlx_

