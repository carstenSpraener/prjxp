package de.spraener.prjxp.docpipe.llm;

import de.spraener.prjxp.common.config.PrjXPChatModelReference;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.docpipe.content.ContentCreationTask;
import de.spraener.prjxp.docpipe.model.DPContentCreation;
import de.spraener.prjxp.docpipe.model.DPJob;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Log
public class LLMService {
    private final ChatModelFactory chatModelFactory;
    private final PrjXPConfig cfg;
    /**
     * Sends a chat request to the LLM for a content creation task.
     * <p>
     * Resolves the model configuration based on the stereotype of the
     * {@link DPContentCreation}, creates a {@link ChatModel} via the
     * {@link ChatModelFactory}, and sends the given prompt to the model.
     *
     * @param ccTask the content creation task containing job and stereotype information
     * @param prompt the prompt text to send to the LLM
     * @return the model's response as a string
     * @throws IllegalArgumentException if no model configuration is found for the
     *                                  given stereotype in the job configuration
     */
    public String chat(ContentCreationTask ccTask, String prompt) {
        DPContentCreation dpCC =  ccTask.getDpContentCreation();
        DPJob dpJob =  ccTask.getDpJob();

        final String stereotype = dpCC.getStereotype();
        PrjXPChatModelReference cmRef = cfg.getChatModels().stream()
                .filter( cm->
                        cm.getStereoType().equals(stereotype)
                )
                .findFirst()
                .orElseThrow(
                    () -> new IllegalArgumentException("No Model found for Stereotype " + stereotype)
                );
        ChatModel chatModel = chatModelFactory.create(cmRef);
        return chatModel.chat(prompt);
    }
}
