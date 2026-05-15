package de.spraener.prjxp.docpipe.llm;

import de.spraener.prjxp.docpipe.model.DPModelConfig;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class ChatModelFactory {
    public ChatModel create(DPModelConfig cfg) {
        return OllamaChatModel.builder()
                .modelName(cfg.getModelName())
                .baseUrl(cfg.getModelProviderURL())
                .timeout(Duration.ofMinutes(20))
                .temperature(0.2)
                .build();
    }
}
