package de.spraener.prjxp.docpipe.llm;

import de.spraener.prjxp.docpipe.model.DPModelConfig;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ChatModelFactory {
    private final List<ChatModelSupplier> chatModelSuppliers;
    private Map<String, ChatModel> chatModels = new HashMap<>();

    public ChatModel create(DPModelConfig cfg) {
        String modelKey = cfg.getModelProviderURL() +":"+ cfg.getModelName();
        if( chatModels.containsKey(modelKey) ) {
            return chatModels.get(modelKey);
        }
        for (ChatModelSupplier cms : chatModelSuppliers) {
            if (cms.canProvide(cfg)) {
                ChatModel cm =  cms.provide(cfg);
                chatModels.put(modelKey, cm);
                return cm;
            }
        }
        throw new IllegalStateException("There is no supplier for server model type " + cfg.getServerType() + ". Please check configuration.");
    }
}
