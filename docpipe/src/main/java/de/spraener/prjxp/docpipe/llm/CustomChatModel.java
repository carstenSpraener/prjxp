package de.spraener.prjxp.docpipe.llm;

import de.spraener.prjxp.common.config.PrjXPChatModelReference;
import dev.langchain4j.model.chat.ChatModel;

public interface CustomChatModel extends ChatModel {
    void init(PrjXPChatModelReference cmRef);
    boolean canHandle(PrjXPChatModelReference cmRef);
}
