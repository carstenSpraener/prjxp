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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class EmbeddingExportService {
    private final PxLogService logService;
    private final ObjectMapper objMapper;
    private final EmbeddingExecutor embedder;
    private final PrjXPJsonStreamProvider streamProvider;
    private final TransferPasswordResolver transferPasswordResolver;
    private final PrjXPConfig cfg;

    public void execute() {
        ProjectDefinition pd = cfg.getActiveProject().orElseThrow(() -> new IllegalStateException(
                "No active project '" + cfg.getActiveProjectName() + "' defined. Available projects: "
                        + cfg.getProjects().stream().map(ProjectDefinition::getName).toList()));
        String input = firstNonBlank(cfg.getTransfer().getInput(), pd.resolvedJsonlFile());
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
                export(lines, writer, pd);
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
            logService.error(e, "Error during embedding export: "+e.getMessage(), e);
        }
    }

    /**
     * Hub in-process use: embeds the project's chunk JSONL and writes EmbeddedChunkRecord
     * JSONL (plain, unencrypted) to the given output path. Rethrows on failure so the
     * caller (pipeline orchestrator) can apply its failure semantics.
     */
    public void exportForProject(ProjectDefinition pd, Path output) {
        String input = pd.resolvedJsonlFile();
        if (input == null) {
            throw new IllegalStateException("Export for project '" + pd.getName() + "' requires a jsonlFile");
        }
        char[] inputPassword = transferPasswordResolver.resolvePassword(cfg.getTransfer().getPasswordEnv());
        try {
            var lines = streamProvider.getTransferJsonlStream(input, inputPassword);
            Writer writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(output.toFile()), StandardCharsets.UTF_8));
            try {
                export(lines, writer, pd);
            } finally {
                writer.close();
            }
        } catch (Exception e) {
            logService.error(e, "Error during embedding export for project '" + pd.getName() + "': " + e.getMessage(), e);
            throw new RuntimeException("Embedding export failed for project '" + pd.getName() + "'", e);
        }
    }

    private void export(Stream<String> lines, Writer writer, ProjectDefinition pd) {
        new PxChunkFromJsonLReader()
                .readChunksFromJsonlStreamBatched(lines, Math.max(1, pd.getTibedBatchSize()), this::fromJSONL)
                .forEach(batch -> exportBatch(writer, batch));
    }

    private void exportBatch(Writer writer, PxChunk[] chunks) {
        for (EmbeddedChunkRecord record : embedder.embed(Arrays.asList(chunks))) {
            try {
                writer.write(toJSONL(record));
                writer.write('\n');
            } catch (IOException ioXC) {
                logService.error(ioXC, "Could not write embedded chunk %s", record.id());
            }
        }
    }

    private Writer openWriter(String output, TransferSession session) throws IOException {
        OutputStream raw = "-".equals(output) ? System.out : new FileOutputStream(output);
        if (session.encrypt()) {
            raw = TransferCrypto.openEncryptedOutputStream(raw, session.password());
        }
        return new BufferedWriter(new OutputStreamWriter(raw, StandardCharsets.UTF_8));
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
