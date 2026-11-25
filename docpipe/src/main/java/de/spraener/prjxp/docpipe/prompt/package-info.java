/**
 * Provides prompt template resolution infrastructure for the document pipeline.
 *
 * <p>
 * This package is responsible for turning configured prompt templates into fully resolved
 * prompt text before the prompt is sent to an LLM or other downstream content generation
 * component. It integrates the Handlebars templating engine with Spring-managed resolver
 * components that can inject source code, file contents, dynamically generated text, tool
 * descriptions, scripted output, and other project-specific context into a prompt.
 * </p>
 *
 * <p>
 * The central entry point is {@link de.spraener.prjxp.docpipe.prompt.PromptResolvingService}.
 * It reads a prompt template from the document-pipeline configuration directory, registers all
 * available {@link de.spraener.prjxp.docpipe.prompt.TemplateResolver} implementations as
 * Handlebars helpers, applies the configured content-creation arguments as the template context,
 * and returns the final prompt string. Resolver failures are wrapped in
 * {@link de.spraener.prjxp.docpipe.prompt.TemplateException}.
 * </p>
 *
 * <p>
 * The package is organized around the {@link de.spraener.prjxp.docpipe.prompt.TemplateResolver}
 * extension point:
 * </p>
 *
 * <ul>
 *   <li>
 *     {@link de.spraener.prjxp.docpipe.prompt.SourceDumpResolver} includes complete source files
 *     from a configured directory, optionally scanning subdirectories and filtering by file ending.
 *   </li>
 *   <li>
 *     {@link de.spraener.prjxp.docpipe.prompt.SourceSkeletonResolver} includes reduced source
 *     skeletons using registered {@link de.spraener.prjxp.docpipe.prompt.SourceSkeletonizer}
 *     implementations, falling back to a full source dump if skeletonization is not available or
 *     fails.
 *   </li>
 *   <li>
 *     {@link de.spraener.prjxp.docpipe.prompt.VisualBasicSourceSkeletonizer} provides skeleton
 *     extraction for Visual Basic source files by preserving declarations and member boundaries
 *     while omitting method bodies.
 *   </li>
 *   <li>
 *     {@link de.spraener.prjxp.docpipe.prompt.CurrentFileResolver} injects the content of the
 *     currently processed file from the template context.
 *   </li>
 *   <li>
 *     {@link de.spraener.prjxp.docpipe.prompt.URLResolver} includes file content addressed by a
 *     URL-like template parameter relative to the configuration directory.
 *   </li>
 *   <li>
 *     {@link de.spraener.prjxp.docpipe.prompt.GroovyResolver} executes inline Groovy scripts from
 *     template blocks and exposes useful bindings such as the configuration directory, Handlebars
 *     options, Spring application context, document-pipeline context, and current file name.
 *   </li>
 *   <li>
 *     {@link de.spraener.prjxp.docpipe.prompt.GRResolver} delegates prompt enrichment to the
 *     golden-retriever enrichment component.
 *   </li>
 *   <li>
 *     {@link de.spraener.prjxp.docpipe.prompt.McpProjectResolver} injects available MCP tool
 *     descriptions and optional active-project guidance into the prompt.
 *   </li>
 * </ul>
 *
 * <p>
 * Typical usage starts with a content creation task whose configuration references a prompt
 * template file. The template may call helpers such as {@code {{src-dump "src/main/java"}}},
 * {@code {{src-skeleton "legacy" ending="vb"}}}, {@code {{currentFile}}},
 * {@code {{#groovy}}...{{/groovy}}}, or {@code {{mcp-project project="my-project"}}}.
 * During resolution, Spring supplies all resolver beans to the service, and each resolver receives
 * the configuration directory, the current Handlebars context, and the helper options needed to
 * produce its fragment of prompt text.
 * </p>
 */
package de.spraener.prjxp.docpipe.prompt;

//_This document was generated with Doc|Pipe and qwen3.6-27b-ud-mlx_

