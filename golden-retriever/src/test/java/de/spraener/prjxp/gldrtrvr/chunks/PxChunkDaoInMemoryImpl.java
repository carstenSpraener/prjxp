package de.spraener.prjxp.gldrtrvr.chunks;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.common.PxChunkFromJsonLReader;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.gldrtrvr.PxChunkDao;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Service
@Profile("test")
@RequiredArgsConstructor
@Accessors(fluent = true)
@Data
public class PxChunkDaoInMemoryImpl implements PxChunkDao {
    private Map<String, List<PxChunk>> chunkDB = null;
    private final ObjectMapper objectMapper;
    private final PrjXPConfig cfg;
    private String jsonlStream;

    private PxChunk fromJSONL(String jsonl) {
        try {
            return this.objectMapper.readValue(jsonl, PxChunk.class);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, List<PxChunk>> getChunkDB() {
        if (chunkDB == null) {
            try {
                chunkDB = new HashMap<>();
                new PxChunkFromJsonLReader().readChunksFromJsonlStreamBatched(cfg.getJsonlStream(jsonlStream), 50, this::fromJSONL)
                        .forEach(batch -> {
                            for (var chunk : batch) {
                                List<PxChunk> cList = chunkDB.computeIfAbsent(chunk.getId(), k -> new ArrayList<>());
                                cList.add(chunk);
                            }
                        });
            } catch (Exception e) {
                chunkDB = null;
                throw new RuntimeException(e);
            }
        }
        return chunkDB;
    }

    @Override
    public List<PxChunk> findById(String id) {
        return getChunkDB().get(id);
    }

    @Override
    public List<PxChunk> findByMetaData(Map<String, String> metaData) {
        return List.of();
    }

    @Override
    public List<PxChunk> findRelevant(String question, int maxResults, double minScore) {
        return List.of();
    }

    public Stream<PxChunk> findAll() {
        try {
            return cfg.getJsonlStream(jsonlStream)
                    .map(line -> {
                        try {
                            return objectMapper.readValue(line, PxChunk.class);
                        } catch (Exception e) {
                            throw new RuntimeException("Fehler beim Mapping: " + line, e);
                        }
                    });
        } catch( IOException e ) {
            throw new RuntimeException(e);
        }
    }
}
