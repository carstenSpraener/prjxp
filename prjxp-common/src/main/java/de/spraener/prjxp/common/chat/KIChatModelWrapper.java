package de.spraener.prjxp.common.chat;

import de.spraener.prjxp.common.config.PrjXPChatModelReference;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.Data;
import lombok.extern.java.Log;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

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
        try {
            return chatModel.chat(question);
        } catch (RateLimitException rlXC) {
            log.warning("Rate limit exceeded, waiting 60 seconds and retrying");
            try {
                Thread.sleep(60000);
            } catch (InterruptedException e) {
            }
            return chatModel.chat(question);
        }
    }


    @Override
    public String analyzeImage(BufferedImage image) {
        try {
            String base64Image = encodeImageToBase64(image);

            UserMessage userMessage = UserMessage.from(
                    TextContent.from("Extrahiere den gesamten Text aus diesem Bild und formatiere ihn als sauberes Markdown. " +
                            "Tabellen sollen als Markdown-Tabellen gerendert werden. Gib nur das Markdown zurück."),
                    ImageContent.from(base64Image, "image/png")
            );

            ChatResponse response = chatModel.chat(userMessage);
            return response.aiMessage().text();
        } catch (Exception e) {
            log.severe("Error analyzing image: " + e.getMessage());
            return "Fehler bei der Bild-Analyse: " + e.getMessage();
        }
    }

    private String encodeImageToBase64(BufferedImage img) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }
}
