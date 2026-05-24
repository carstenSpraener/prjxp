package de.spraener.prjxp.common.chat;

import de.spraener.prjxp.common.config.PrjXPChatModelReference;
import dev.langchain4j.model.chat.ChatModel;

/**
 * Interface for providing specific {@link ChatModel} implementations.
 * <p>
 * Implementations of this interface are used by the {@link ChatModelFactory} to instantiate
 * chat models from different providers (e.g., Ollama, OpenAI, Gemini) based on the
 * provided model reference configuration.
 * </p>
 */
public interface ChatModelSupplier {
    /**
     * Checks if this supplier can provide a chat model for the given reference.
     *
     * @param cmRef the model reference configuration to check
     * @return true if this supplier can provide the requested model, false otherwise
     */
    boolean canProvide(PrjXPChatModelReference cmRef);

    /**
     * Provides a configured {@link ChatModel} instance based on the given reference.
     *
     * @param cmRef the model reference configuration used to instantiate the model
     * @return a configured {@link ChatModel} instance
     */
    ChatModel provide(PrjXPChatModelReference cmRef);
}
