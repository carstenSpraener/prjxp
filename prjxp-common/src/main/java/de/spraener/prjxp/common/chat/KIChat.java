package de.spraener.prjxp.common.chat;

import de.spraener.prjxp.common.config.PrjXPChatModelReference;

import java.awt.image.BufferedImage;
import java.util.List;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.response.ChatResponse;

public interface KIChat {
    PrjXPChatModelReference getChatModelReference();
    String chat(String question);
    String analyzeImage(BufferedImage image);

    /**
     * Whether this chat supports native function calling (tool calls negotiated via the provider API).
     * Implementations returning {@code false} must be driven through {@link #chat(String)} only.
     */
    default boolean supportsTools() {
        return false;
    }

    /**
     * Sends a full message history together with the available tool schemas and returns the raw
     * provider response, including any tool execution requests the model decided to make.
     */
    default ChatResponse chat(List<ChatMessage> messages, List<ToolSpecification> tools) {
        throw new UnsupportedOperationException(
                getClass().getSimpleName() + " does not support native tool calling");
    }
}
