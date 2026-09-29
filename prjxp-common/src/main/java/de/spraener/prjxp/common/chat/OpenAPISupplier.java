package de.spraener.prjxp.common.chat;

import de.spraener.prjxp.common.config.PrjXPChatModelReference;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
/**
 * Supplier for OpenAI-compatible chat models.
 * <p>
 * This class implements {@link de.spraener.prjxp.common.chat.ChatModelSupplier} to provide {@link dev.langchain4j.model.openai.OpenAiChatModel}
 * instances. It supports custom base URLs and ensures they are correctly formatted with the {@code /v1} suffix.
 * </p>
 */
public class OpenAPISupplier implements de.spraener.prjxp.common.chat.ChatModelSupplier {
    @Value("${chat.openapi.api-key:UNKNOWN}")
    private String apiKey;

    /**
     * Checks if this supplier can provide a chat model for the given reference.
     *
     * @param cmRef the chat model reference to check
     * @return true if this supplier can provide a matching chat model, false otherwise
     */
    @Override
    public boolean canProvide(PrjXPChatModelReference cmRef) {
        return cmRef.getServerType().equals(
                de.spraener.prjxp.common.chat.ServerTypes.OPEN_API.serverType()
        );
    }

    /**
     * Provides an OpenAI-compatible chat model based on the given reference.
     *
     * @param cmRef the chat model reference to provide a model for
     * @return the configured {@link ChatModel} instance
     */
    @Override
    public ChatModel provide(PrjXPChatModelReference cmRef) {
        String baseUrl = cmRef.getProviderUrl();
        if (baseUrl != null && !baseUrl.endsWith("/v1") && !baseUrl.endsWith("/v1/")) {
            baseUrl = baseUrl.replaceAll("/$", "") + "/v1";
        }
        return OpenAiChatModel.builder()
                .modelName(cmRef.getModelName())
                .apiKey(apiKey)
                .temperature(cmRef.getTemperature())
                .timeout(Duration.ofSeconds(cmRef.getTimeoutSecs()))
                .baseUrl(baseUrl)
                .build();
    }
}
