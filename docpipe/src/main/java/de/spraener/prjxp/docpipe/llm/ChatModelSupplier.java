package de.spraener.prjxp.docpipe.llm;

import de.spraener.prjxp.docpipe.model.DPModelConfig;
import dev.langchain4j.model.chat.ChatModel;

public interface ChatModelSupplier {
    boolean canProvide(DPModelConfig cfg);
    ChatModel provide(DPModelConfig cfg);
}
