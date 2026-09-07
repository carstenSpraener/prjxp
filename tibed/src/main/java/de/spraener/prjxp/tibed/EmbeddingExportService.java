package de.spraener.prjxp.tibed;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.common.PxChunkFromJsonLReader;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.PrjXPJsonStreamProvider;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.common.errorlog.PxLogService;
import de.spraener.prjxp.common.model.EmbeddedChunkRecord;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.transfer.TransferCrypto;
import de.spraener.prjxp.common.transfer.TransferPasswordResolver;
import de.spraener.prjxp.common.transfer.TransferSession;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

@Service
@RequiredArgsConstructor
public class EmbeddingExportService {
    private final PxLogService logService;
    private final ObjectMapper objMapper;
    private final EmbeddingModel embeddingModel;
    private final PrjXPJsonStreamProvider streamProvider;
    private final TransferPasswordResolver transferPasswordResolver;
    private final PrjXPConfig cfg;

    public void execute() {
        ProjectDefinition pd = cfg.getActiveProject().orElseThrow(()->new IllegalStateException("No active project!"));
        String input = firstNonBlank(cfg.getTransfer().getInput(), pd.getJsonlFile());
        String output = cfg.getTransfer().getOutput();
        if (output == null || output.isBlank()) {
            throw new IllegalStateException("Export mode requires an output file (--output)");
        }

        TransferSession session = transferPasswordResolver.prepare(
                cfg.getTransfer().getEncrypt(), cfg.getTransfer().getPasswordEnv());
        char[] inputPassword = transferPasswordResolver.resolvePassword(cfg.getTransfer().getPasswordEnv());

        try {
            var lines = streamProvider.getTransferJsonlStream(input, inputPassword);
            boolean closeOutput = !"-".equals(output);
            Writer writer = openWriter(output, session);
            try {
                new PxChunkFromJsonLReader()
                        .readChunksFromJsonlStreamBatched(lines, pd.getTibedBatchSize(), this::fromJSONL)
                        .forEach(batch -> exportBatch(writer, batch));
            } finally {
                if (closeOutput) {
                    writer.close();
                } else {
                    try {
                        writer.flush();
                    } catch (IOException ioXC) {
                        logService.error(ioXC, "Could not flush export output");
                    }
                }
            }
        } catch (Exception e) {
            logService.error(e, "Error during embedding export");
        }
    }

    private Writer openWriter(String output, TransferSession session) throws IOException {
        OutputStream raw = "-".equals(output) ? System.out : new FileOutputStream(output);
        if (session.encrypt()) {
            raw = TransferCrypto.openEncryptedOutputStream(raw, session.password());
        }
        return new BufferedWriter(new OutputStreamWriter(raw, StandardCharsets.UTF_8));
    }

    private void exportBatch(Writer writer, PxChunk[] chunks) {
        List<PxChunk> withText = Arrays.asList(chunks).stream()
                .filter(c -> StringUtils.hasText(c.getContent()))
                .toList();
        if (withText.isEmpty()) {
            return;
        }
        List<TextSegment> segments = withText.stream()
                .map(PxChunk2TextSegmentConverter::convert)
                .toList();
        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();

        for (int i = 0; i < withText.size(); i++) {
            PxChunk chunk = withText.get(i);
            EmbeddedChunkRecord record = EmbeddedChunkRecord.from(chunk, embeddings.get(i).vector());
            try {
                writer.write(toJSONL(record));
                writer.write('\n');
            } catch (IOException ioXC) {
                logService.error(ioXC, "Could not write embedded chunk %s", chunk.getId());
            }
        }
    }

    private String toJSONL(EmbeddedChunkRecord record) {
        try {
            return objMapper.writeValueAsString(record);
        } catch (JsonProcessingException e) {
            logService.error(e, "Could not serialize embedded chunk %s", record.id());
            return "";
        }
    }

    private PxChunk fromJSONL(String line) {
        try {
            return objMapper.readValue(line, PxChunk.class);
        } catch (JsonProcessingException e) {
            logService.error(e, "Error while parsing JSONL as a PxChunk: %s", e.getMessage());
            return null;
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
