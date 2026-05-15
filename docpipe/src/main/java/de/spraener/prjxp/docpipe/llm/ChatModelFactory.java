package de.spraener.prjxp.docpipe.llm;

import de.spraener.prjxp.docpipe.model.DPModelConfig;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ChatModelFactory {
    private final List<ChatModelSupplier> chatModelSuppliers;

    public ChatModel create(DPModelConfig cfg) {
        for (ChatModelSupplier cms : chatModelSuppliers) {
            if (cms.canProvide(cfg)) {
                return cms.provide(cfg);
            }
        }
        throw new IllegalStateException("There is no supplier for server model type " + cfg.getServerType() + ". Please check configuration.");
    }
}