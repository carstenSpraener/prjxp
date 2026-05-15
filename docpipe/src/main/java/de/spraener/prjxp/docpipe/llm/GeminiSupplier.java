package de.spraener.prjxp.docpipe.llm;

import de.spraener.prjxp.docpipe.config.EnvResolver;
import de.spraener.prjxp.docpipe.model.DPModelConfig;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import lombok.ToString;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class GeminiSupplier implements ChatModelSupplier {
    @Value("${chat.gemini.apikey:NONE-SPECIFIED}")
    @ToString.Exclude
    private String apiKey;
    @Override
    public boolean canProvide(DPModelConfig cfg) {
        return cfg.getServerType().equals(ServerTypes.GEMINI.serverType());
    }

    @Override
    public ChatModel provide(DPModelConfig cfg) {
        return GoogleAiGeminiChatModel.builder()
                .apiKey(apiKey)
                .modelName(cfg.getModelName())
                .temperature(cfg.getTemperature())
                .timeout(Duration.ofSeconds(cfg.getTimeOutSeconds()))
                .build();
    }
}
