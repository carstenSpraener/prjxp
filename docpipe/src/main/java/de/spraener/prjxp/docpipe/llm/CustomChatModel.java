package de.spraener.prjxp.docpipe.llm;

import de.spraener.prjxp.docpipe.model.DPModelConfig;
import dev.langchain4j.model.chat.ChatModel;

public interface CustomChatModel extends ChatModel {
    void init(DPModelConfig cfg);
    boolean canHandle(DPModelConfig cfg);
}
