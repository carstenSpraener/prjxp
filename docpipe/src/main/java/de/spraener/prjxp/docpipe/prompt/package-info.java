/**
 * <p>The {@code de.spraener.prjxp.docpipe.prompt} package contains classes and interfaces related to template resolution for generating project documentation prompts.</p>
 * 
 * <p>The core responsibility of this package is to provide a flexible and extensible mechanism for resolving templates used in the generation of project documentation prompts. This involves interacting with various template resolvers to dynamically generate content based on specific templates and context.</p>
 * 
 * <h3>Main Classes/Interfaces</h3>
 * <ul>
 *   <li>{@code TemplateResolver}: An interface that defines the contract for template resolvers. Implementations of this interface are responsible for resolving templates based on specific context and options.</li>
 *   <li>{@code GRResolver}: A concrete implementation of {@code TemplateResolver} for resolving templates related to project prompts using the GRPromptEnrichment service.</li>
 *   <li>{@code URLResolver}: A resolver for templates that are defined by URLs, allowing dynamic content loading from external sources.</li>
 *   <li>{@code GroovyResolver}: A resolver that uses the {@code ScriptCompileService} to compile and evaluate script content written in Groovy.</li>
 *   <li>{@code SourceDumpResolver}: A resolver capable of fetching source code from a specified directory and formatting it according to the provided options.</li>
 * </ul>
 * 
 * <h3>Primary Use Cases</h3>
 * <p>The primary use cases for this package include:</p>
 * <ul>
 *   <li>Generating documentation prompts that dynamically fetch content from external sources or compile scripts to generate detailed responses.</li>
 *   <li>Extending the system with new template resolvers to support diverse content needs and formats.</li>
 * </ul>
 */
package de.spraener.prjxp.docpipe.prompt;


//_This document was generated with Doc|Pipe and qwen3.6-27b-ud-mlx_

