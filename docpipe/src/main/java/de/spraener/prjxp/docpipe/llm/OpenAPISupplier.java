package de.spraener.prjxp.docpipe.llm;

import de.spraener.prjxp.docpipe.model.DPModelConfig;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class OpenAPISupplier implements ChatModelSupplier {
    @Value("${chat.openapi.api-key:UNKNOWN}")
    private String apiKey;

    @Override
    public boolean canProvide(DPModelConfig cfg) {
        return cfg.getServerType().equals(ServerTypes.OPEN_API.serverType()) || cfg.getServerType().equals(ServerTypes.LM_STUDIO.serverType());
    }

    @Override
    public ChatModel provide(DPModelConfig cfg) {
        String baseUrl = cfg.getModelProviderURL();
        if (baseUrl != null && !baseUrl.endsWith("/v1") && !baseUrl.endsWith("/v1/")) {
            baseUrl = baseUrl.replaceAll("/$", "") + "/v1";
        }
        return OpenAiChatModel.builder()
                .modelName(cfg.getModelName())
                .apiKey(apiKey)
                .temperature(cfg.getTemperature())
                .timeout(Duration.ofSeconds(cfg.getTimeOutSeconds()))
                .baseUrl(baseUrl)
                .build();
    }
}
