package de.spraener.prjxp.common.chat;

import de.spraener.prjxp.common.config.PrjXPChatModelReference;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
/**
 * Supplier for Ollama-based chat models.
 * <p>
 * This class implements {@link de.spraener.prjxp.common.chat.ChatModelSupplier} to provide {@link dev.langchain4j.model.ollama.OllamaChatModel}
 * instances configured with the model name, base URL, temperature, and timeout specified in the model reference.
 * </p>
 */
public class OllamaSupplier implements de.spraener.prjxp.common.chat.ChatModelSupplier {
    /**
     * Checks if this supplier can provide a chat model for the given reference.
     *
     * @param cmRef the chat model reference to check
     * @return true if this supplier can provide a matching chat model, false otherwise
     */
    @Override
    public boolean canProvide(PrjXPChatModelReference cmRef) {
        return cmRef.getServerType().equals(de.spraener.prjxp.common.chat.ServerTypes.OLLAMA.serverType());
    }

    /**
     * Provides an Ollama-based chat model based on the given reference.
     *
     * @param cmRef the chat model reference to provide a model for
     * @return the configured {@link ChatModel} instance
     */
    @Override
    public ChatModel provide(PrjXPChatModelReference cmRef) {
        return OllamaChatModel.builder()
                .modelName(cmRef.getModelName())
                .baseUrl(cmRef.getProviderUrl())
                .temperature(cmRef.getTemperature())
                .timeout(Duration.ofSeconds(cmRef.getTimeoutSecs()))
                .build();
    }
}
