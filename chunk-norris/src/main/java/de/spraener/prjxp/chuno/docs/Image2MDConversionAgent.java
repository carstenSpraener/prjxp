package de.spraener.prjxp.chuno.docs;

import de.spraener.prjxp.chuno.docs.model.CostEstimation;
import de.spraener.prjxp.chuno.docs.model.DocArtifakt;
import de.spraener.prjxp.chuno.docs.model.DocArtifaktType;
import de.spraener.prjxp.common.config.PrjXPConfig;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.ollama.OllamaChatModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

@Component
@RequiredArgsConstructor
public class Image2MDConversionAgent implements DocConversionAgent<BufferedImage, String> {
    private final PrjXPConfig config;
    private ChatModel visionModel;

    public ChatModel getVisionModel() {
        if (this.visionModel == null) {
            this.visionModel = OllamaChatModel.builder()
                    .baseUrl(config.getEmbeddingOllamaUrl())
                    .modelName("llava")
                    .temperature(0.0)
                    .timeout(Duration.ofMinutes(5))
                    .build();
        }
        return this.visionModel;
    }

    @Override
    public DocArtifaktType getSourceFormat() {
        return DocArtifaktType.BUFFERED_IMAGE;
    }

    @Override
    public DocArtifaktType getTargetFormat() {
        return DocArtifaktType.MARK_DOWN;
    }

    @Override
    public double estimateCosts(DocArtifakt<BufferedImage, ?> artifakt) {
        return artifakt.getChildQuantityEstimation() * CostEstimation.AI_OCR_COSTS;
    }

    @Override
    public int estimateQuantity(DocArtifakt<BufferedImage, ?> artifakt) {
        return 1;
    }

    @Override
    public void convert(DocArtifakt<BufferedImage, ?> artifakt) {
        DocArtifakt converted = new DocArtifakt(artifakt)
                .setId(artifakt.getId()+".md")
                .setFormat(DocArtifaktType.MARK_DOWN)
                ;
        try {
            BufferedImage img = artifakt.getData();
            String base64Image = encodeImageToBase64(img);

            UserMessage userMessage = dev.langchain4j.data.message.UserMessage.from(
                    TextContent.from("Extrahiere den gesamten Text aus diesem Bild und formatiere ihn als sauberes Markdown. " +
                            "Tabellen sollen als Markdown-Tabellen gerendert werden. Gib nur das Markdown zurück."),
                    ImageContent.from(base64Image, "image/png")
            );

            ChatResponse response = getVisionModel().chat(userMessage);
            converted.setData(response.aiMessage().text());
            artifakt.addChild(converted);
        } catch (IOException e) {
            converted.setData("Fehler bei der Bild-Konvertierung: " + e.getMessage());
        }
    }

    private String encodeImageToBase64(BufferedImage img) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }
}
