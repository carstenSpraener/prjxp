/**
 * Domain model for the PrjXP documentation pipeline (docpipe) module.
 *
 * <p>This package defines the core data structures that represent documentation
 * generation jobs and the individual content-creation tasks they contain.
 * It serves as the model layer of the docpipe component, decoupling the
 * business logic of document generation from the persistence and processing
 * layers.</p>
 *
 * <h2>Architectural Overview</h2>
 * <p>The package is organized around two primary classes that form a
 * one-to-many relationship:</p>
 * <ul>
 *   <li>{@link de.spraener.prjxp.docpipe.model.DPJob} – the top-level
 *       container representing a complete documentation job. It aggregates
 *       a root directory, a {@code PrjXPConfig} (which holds the available
 *       LLM chat-model references), and an ordered list of content-creation
 *       tasks. A static {@code EMPTY_JOB} constant is provided as a safe
 *       fallback to prevent processing halts on misconfiguration.</li>
 *   <li>{@link de.spraener.prjxp.docpipe.model.DPContentCreation} – a single
 *       content-creation task within a job. It specifies the target output
 *       location, the LLM model stereotype to invoke, the prompt template,
 *       optional post-processing scripts, filter expressions, and arbitrary
 *       key-value arguments. A Jackson-based {@code clone} method supports
 *       lightweight duplication of task definitions.</li>
 * </ul>
 *
 * <h2>Primary Use Cases</h2>
 * <ul>
 *   <li>Defining a documentation generation job by composing a
 *       {@code DPJob} with one or more {@code DPContentCreation} entries.</li>
 *   <li>Resolving the appropriate LLM model for a given stereotype via
 *       {@link DPJob#getModelForStereotype(String)}.</li>
 *   <li>Serializing / deserializing job and task definitions (both classes
 *       are Lombok-annotated POJOs compatible with Jackson).</li>
 * </ul>
 *
 * <h2>Dependencies</h2>
 * <p>The model depends on {@code de.spraener.prjxp.common.config} for the
 * project configuration and chat-model reference types, and on
 * {@code com.fasterxml.jackson.databind} for object cloning. It has no
 * dependency on the docpipe processing or I/O layers, keeping the model
 * purely declarative.</p>
 */
package de.spraener.prjxp.docpipe.model;

//_This document was generated with Doc|Pipe and qwen3.6-27b-ud-mlx_

