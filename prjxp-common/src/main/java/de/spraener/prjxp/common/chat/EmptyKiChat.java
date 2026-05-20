package de.spraener.prjxp.common.chat;

import de.spraener.prjxp.common.config.PrjXPChatModelReference;
import lombok.Data;
import lombok.RequiredArgsConstructor;

@Data
@RequiredArgsConstructor
public class EmptyKiChat implements KIChat {
    private final PrjXPChatModelReference  chatModelReference;

    @Override
    public String chat(String question) {
        return "";
    }
}
