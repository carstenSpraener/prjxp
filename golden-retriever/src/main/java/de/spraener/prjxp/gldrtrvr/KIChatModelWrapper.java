package de.spraener.prjxp.gldrtrvr;

import de.spraener.prjxp.common.chat.KIChat;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.java.Log;

@Log
public class KIChatModelWrapper implements KIChat {
    private ChatModel chatModel;

    public KIChatModelWrapper(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public String chat(String question) {
        log.info("sending prompt of " + question.length() + " chars to chat model");
        return chatModel.chat(question);
    }
}
