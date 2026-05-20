package de.spraener.prjxp.gldrtrvr;

import de.spraener.prjxp.common.chat.KIChat;
import de.spraener.prjxp.common.config.PrjXPChatModelReference;
import dev.langchain4j.model.chat.ChatModel;
import lombok.Data;
import lombok.extern.java.Log;

@Log
@Data
public class KIChatModelWrapper implements KIChat {
    private ChatModel chatModel;
    private PrjXPChatModelReference chatModelReference;

    public KIChatModelWrapper(ChatModel chatModel, PrjXPChatModelReference chatModelReference) {
        this.chatModel = chatModel;
        this.chatModelReference = chatModelReference;
    }

    @Override
    public String chat(String question) {
        log.fine("sending prompt of " + question.length() + " chars to chat model");
        return chatModel.chat(question);
    }
}
