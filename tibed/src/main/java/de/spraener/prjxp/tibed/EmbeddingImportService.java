package de.spraener.prjxp.tibed;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.PrjXPJsonStreamProvider;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.common.errorlog.PxLogService;
import de.spraener.prjxp.common.model.EmbeddedChunkRecord;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.streams.BatchingUtils;
import de.spraener.prjxp.common.transfer.TransferPasswordResolver;
import de.spraener.prjxp.lucene.LuceneEmbeddingStore;
import de.spraener.prjxp.tibed.config.EmbeddingStoreSupplier;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

@Service
@Log
@RequiredArgsConstructor
public class EmbeddingImportService {
    private final PxLogService logService;
    private final ObjectMapper objMapper;
    private final EmbeddingStoreSupplier embeddingStoreSupplier;
    private final PrjXPJsonStreamProvider streamProvider;
    private final TransferPasswordResolver transferPasswordResolver;
    private final StoreIdChecker storeIdChecker;
    private final PrjXPConfig cfg;

    public void execute() {
        ProjectDefinition pd = cfg.getActiveProject().orElseThrow(() -> new IllegalStateException(
                "No active project '" + cfg.getActiveProjectName() + "' defined. Available projects: "
                        + cfg.getProjects().stream().map(ProjectDefinition::getName).toList()));
        String input = firstNonBlank(cfg.getTransfer().getInput(), pd.resolvedJsonlFile());
        if (input == null) {
            throw new IllegalStateException("Import mode requires an input file (--input)");
        }
        char[] password = transferPasswordResolver.resolvePassword(cfg.getTransfer().getPasswordEnv());

        EmbeddingStore<TextSegment> store = embeddingStoreSupplier.getStore(pd.getName());
        if (pd.isTibedResetStore()) {
            log.warning("Resetting embedding store for project '" + pd.getName() + "'!");
            Filter resetFilter = store instanceof LuceneEmbeddingStore
                    ? new IsEqualTo(PxChunk.PXCHUNK_PROJECT, pd.getName())  // shared index: only this project
                    : metadataKey("id").isNotEqualTo(0);                     // dedicated store: wipe all
            store.removeAll(resetFilter);
        }

        try {
            Stream<String> lines = streamProvider.getTransferJsonlStream(input, password);
            BatchingUtils.pack(lines, pd.getTibedBatchSize())
                    .map(batch -> batch.stream()
                            .map(this::fromJSONL)
                            .filter(Objects::nonNull)
                            .toList())
                    .filter(list -> !list.isEmpty())
                    .forEach(batch -> importBatch(store, pd.getName(), batch));
        } catch (Exception e) {
            logService.error(e, "Error during embedding import: "+e.getMessage());
        }
    }

    private void importBatch(EmbeddingStore<TextSegment> store, String projectName, List<EmbeddedChunkRecord> records) {
        try {
            List<EmbeddedChunkRecord> toImport = records.stream()
                    .filter(record -> hasVector(record) && storeIdChecker.needsImport(store, record.id()))
                    .toList();
            if (toImport.isEmpty()) {
                log.info("Skipping batch of " + records.size() + " chunks (already in store)");
                return;
            }
            List<Embedding> embeddings = toImport.stream()
                    .map(record -> Embedding.from(record.vector()))
                    .toList();
            List<TextSegment> segments = toImport.stream()
                    .map(record -> {
                        PxChunk chunk = record.toPxChunk();
                        chunk.setProject(projectName);   // stamp (multi-project store separation)
                        return PxChunk2TextSegmentConverter.convert(chunk);
                    })
                    .toList();
            store.addAll(embeddings, segments);
            log.info("Imported " + toImport.size() + "/" + records.size() + " chunks");
        } catch (Exception e) {
            logService.error(e, "Import of chunk batch failed: %s", e.getMessage());
        }
    }

    private boolean hasVector(EmbeddedChunkRecord record) {
        return record.vector() != null && record.vector().length > 0;
    }

    private EmbeddedChunkRecord fromJSONL(String line) {
        try {
            return objMapper.readValue(line, EmbeddedChunkRecord.class);
        } catch (JsonProcessingException e) {
            logService.error(e, "Error while parsing JSONL as an EmbeddedChunkRecord: %s", e.getMessage());
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
