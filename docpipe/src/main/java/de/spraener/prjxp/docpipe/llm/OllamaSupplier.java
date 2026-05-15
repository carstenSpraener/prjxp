package de.spraener.prjxp.docpipe.llm;

import de.spraener.prjxp.docpipe.model.DPModelConfig;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import org.springframework.boot.autoconfigure.rsocket.RSocketProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class OllamaSupplier implements ChatModelSupplier {
    @Override
    public boolean canProvide(DPModelConfig cfg) {
        return cfg.getServerType().equals(ServerTypes.OLLAMA.serverType());
    }

    @Override
    public ChatModel provide(DPModelConfig cfg) {
        return OllamaChatModel.builder()
                .modelName(cfg.getModelName())
                .baseUrl(cfg.getModelProviderURL())
                .temperature(cfg.getTemperature())
                .timeout(Duration.ofSeconds(cfg.getTimeOutSeconds()))
                .build();
    }
}
