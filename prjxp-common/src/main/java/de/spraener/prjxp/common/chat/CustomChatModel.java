package de.spraener.prjxp.common.chat;

import de.spraener.prjxp.common.config.PrjXPChatModelReference;
import dev.langchain4j.model.chat.ChatModel;

/**
 * Interface for custom chat model implementations that require specific initialization.
 * <p>
 * This interface extends {@link ChatModel} and adds methods for initialization 
 * and capability checking based on a model reference.
 * </p>
 */
public interface CustomChatModel extends ChatModel {
    /**
     * Initializes the custom chat model with the given configuration reference.
     *
     * @param cmRef the model reference containing configuration details
     */
    void init(PrjXPChatModelReference cmRef);

    /**
     * Checks if this custom model implementation can handle the given model reference.
     *
     * @param cmRef the model reference to check
     * @return true if this model can handle the request, false otherwise
     */
    boolean canHandle(PrjXPChatModelReference cmRef);
}
