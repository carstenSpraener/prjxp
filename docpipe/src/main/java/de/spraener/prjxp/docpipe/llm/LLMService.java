package de.spraener.prjxp.docpipe.llm;

import de.spraener.prjxp.common.chat.KIChatProvider;
import de.spraener.prjxp.docpipe.content.ContentCreationTask;
import de.spraener.prjxp.docpipe.model.DPContentCreation;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Log
/**
 * Service for interacting with Large Language Models (LLMs).
 * <p>
 * This service abstracts the complexity of mapping documentation "stereotypes" to specific
 * LLM model configurations and handles the communication with the underlying chat models.
 * </p>
 */
public class LLMService {
    private final KIChatProvider chatProvider;

    /**
     * Sends a chat request to the LLM for a content creation task.
     * <p>
     * Resolves the model configuration based on the stereotype of the
     * {@link DPContentCreation} and sends the given prompt to the model.
     *
     * @param ccTask the content creation task containing job and stereotype information
     * @param prompt the prompt text to send to the LLM
     * @return the model's response as a string
     * @throws IllegalArgumentException if no model configuration is found for the
     *                                  given stereotype in the job configuration
     */
    public String chat(ContentCreationTask ccTask, String prompt) {
        DPContentCreation dpCC =  ccTask.getDpContentCreation();

        final String stereotype = dpCC.getStereotype();
        return chatProvider.getByStereotype(stereotype)
                .map(chat -> chat.chat(prompt))
                .orElseThrow(
                    () -> new IllegalArgumentException("No Model found for Stereotype " + stereotype)
                );
    }
}
