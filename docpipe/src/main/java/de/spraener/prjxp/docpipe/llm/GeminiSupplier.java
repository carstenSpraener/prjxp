package de.spraener.prjxp.docpipe.llm;

import de.spraener.prjxp.common.config.PrjXPChatModelReference;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import lombok.ToString;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
/**
 * Supplier for Google Gemini-based chat models.
 * <p>
 * This class implements {@link ChatModelSupplier} to provide {@link dev.langchain4j.model.googleai.GoogleAiGeminiChatModel} 
 * instances using an API key provided via environment variables or Spring properties.
 * </p>
 */
public class GeminiSupplier implements ChatModelSupplier {
    @Value("${GOOGLE_AI_API_KEY:NONE-SPECIFIED}")
    @ToString.Exclude
    private String apiKey;
    /**
     * Checks if this supplier can provide a chat model for the given reference.
     *
     * @param cmRef the chat model reference to check
     * @return true if this supplier can provide a matching chat model, false otherwise
     */
    @Override
    public boolean canProvide(PrjXPChatModelReference cmRef) {
        return cmRef.getServerType().equals(ServerTypes.GEMINI.serverType());
    }

    /**
     * Provides a Google Gemini chat model based on the given reference.
     *
     * @param cmRef the chat model reference to provide a model for
     * @return the configured {@link ChatModel} instance
     */
    @Override
    public ChatModel provide(PrjXPChatModelReference cmRef) {
        return GoogleAiGeminiChatModel.builder()
                .apiKey(apiKey)
                .modelName(cmRef.getModelName())
                .temperature(cmRef.getTemperature())
                .timeout(Duration.ofSeconds(cmRef.getTimeoutSecs()))
                .build();
    }
}
