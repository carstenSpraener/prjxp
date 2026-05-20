package de.spraener.prjxp.common.chat;

import de.spraener.prjxp.common.config.PrjXPChatModelReference;

public interface KIChat {
    PrjXPChatModelReference getChatModelReference();
    String chat(String question);
}
