package de.spraener.prjxp.common.chat;

import de.spraener.prjxp.common.config.PrjXPChatModelReference;

import java.awt.image.BufferedImage;

public interface KIChat {
    PrjXPChatModelReference getChatModelReference();
    String chat(String question);
    String analyzeImage(BufferedImage image);
}
