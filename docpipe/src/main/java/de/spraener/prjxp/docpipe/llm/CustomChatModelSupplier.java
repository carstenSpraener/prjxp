package de.spraener.prjxp.docpipe.llm;

import de.spraener.prjxp.common.config.PrjXPChatModelReference;
import de.spraener.prjxp.docpipe.DPLogService;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Log
@RequiredArgsConstructor
/**
 * Supplier for custom chat model implementations.
 * <p>
 * This class implements {@link ChatModelSupplier} and delegates the provision of chat models 
 * to a list of registered {@link CustomChatModel} instances that can handle the given model reference.
 * </p>
 */
public class CustomChatModelSupplier implements ChatModelSupplier {
    private final List<CustomChatModel> customChatModelList;
    private final DPLogService logService;

    /**
     * Checks if this supplier can provide a chat model for the given reference.
     *
     * @param cmRef the chat model reference to check
     * @return true if this supplier can provide a matching chat model, false otherwise
     */
    @Override
    public boolean canProvide(PrjXPChatModelReference cmRef) {
        if( !cmRef.getServerType().equals(ServerTypes.CUSTOM.serverType()) ) {
            return false;
        }
        return customChatModelList.stream()
                .filter( cm -> cm.canHandle(cmRef))
                .findFirst()
                .isPresent();
    }

    /**
     * Provides a chat model based on the given reference.
     *
     * @param cmRef the chat model reference to provide a model for
     * @return the matching {@link ChatModel} instance
     * @throws IllegalStateException if no suitable chat model is found for the given reference
     */
    @Override
    public ChatModel provide(PrjXPChatModelReference cmRef) {
        return customChatModelList.stream()
                .filter(cm->cm.canHandle(cmRef))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("There is now chat model defined for config: "+cmRef));
    }
}
