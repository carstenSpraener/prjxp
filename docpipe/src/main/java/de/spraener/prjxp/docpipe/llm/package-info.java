

/**
 * The {@code de.spraener.prjxp.docpipe.llm} package encapsulates the logic for interacting with Large Language Models (LLMs)
 * within the documentation pipeline. It serves as the bridge between high-level content creation tasks and underlying LLM providers,
 * managing model selection, configuration resolution, and request execution.
 *
 * <p>
 * This package abstracts the complexity of mapping documentation "stereotypes" to specific LLM model configurations.
 * By leveraging a stereotype-based resolution mechanism, it ensures that each content creation task is processed by the
 * most appropriate LLM as defined in the application configuration.
 * </p>
 *
 * <h3>Architecture and Interaction</h3>
 * <p>
 * The package follows a service-oriented design centered around the {@link de.spraener.prjxp.docpipe.llm.LLMService} class.
 * Key architectural aspects include:
 * </p>
 * <ul>
 *     <li><b>Stereotype Resolution:</b> The service resolves the target LLM model by matching the stereotype associated with a
 *     {@link de.spraener.prjxp.docpipe.content.ContentCreationTask} against configured model references in the
 *     {@link de.spraener.prjxp.common.config.PrjXPConfig}.</li>
 *     <li><b>Model Instantiation:</b> Once a model reference is resolved, the service delegates to a
 *     {@link de.spraener.prjxp.common.chat.ChatModelFactory} to instantiate the concrete
 *     {@link dev.langchain4j.model.chat.ChatModel} implementation.</li>
 *     <li><b>Request Execution:</b> The service handles the transmission of prompts to the resolved chat model and returns
 *     the generated response, shielding callers from provider-specific details.</li>
 * </ul>
 *
 * <h3>Key Entry Points</h3>
 * <ul>
 *     <li>{@link de.spraener.prjxp.docpipe.llm.LLMService#chat(de.spraener.prjxp.docpipe.content.ContentCreationTask, String)}:
 *     The primary method for initiating LLM interactions. It accepts a content creation task and a prompt, resolves the
 *     appropriate model based on the task's stereotype, and returns the LLM's response.</li>
 * </ul>
 *
 * <p>
 * Developers should inject {@link de.spraener.prjxp.docpipe.llm.LLMService} into their components to perform LLM-driven
 * content generation. Configuration of available models and their stereotype mappings should be managed via the application's
 * configuration properties.
 * </p>
 */
package de.spraener.prjxp.docpipe.llm;

//_This document was generated with Doc|Pipe and qwen3.6-27b-ud-mlx_

