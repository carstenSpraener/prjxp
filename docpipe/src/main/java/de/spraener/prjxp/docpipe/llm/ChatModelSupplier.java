package de.spraener.prjxp.docpipe.llm;

import de.spraener.prjxp.common.config.PrjXPChatModelReference;
import dev.langchain4j.model.chat.ChatModel;

public interface ChatModelSupplier {
    boolean canProvide(PrjXPChatModelReference cmRef);
    ChatModel provide(PrjXPChatModelReference cmRef);
}
