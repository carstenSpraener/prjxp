package de.spraener.prjxp.docpipe.llm;

import de.spraener.prjxp.docpipe.model.DPContentCreation;
import de.spraener.prjxp.docpipe.model.DPJob;
import de.spraener.prjxp.docpipe.model.DPModelConfig;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LLMService {
    private final ChatModelFactory chatModelFactory;

    public String chat(DPJob dpJob, DPContentCreation dpCC, String prompt) {
        String stereotype = dpCC.getStereotype();
        DPModelConfig cfg = dpJob.getModelForStereotype(stereotype);
        ChatModel chatModel = chatModelFactory.create(cfg);
        return chatModel.chat(prompt);
    }
}
