package de.spraener.chuno;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import de.spraener.chuno.spring.SpringPreWalkEvent;
import de.spraener.chuno.veto.VetoRegistry;
import de.spraener.prjxp.common.model.PxChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

@Service
@Log
@RequiredArgsConstructor
public class ChunkProcess {
    private final ChunkerFactory factory;
    private final ApplicationEventPublisher eventPublisher;
    private final VetoRegistry vetoRegistry;
    private Set<String> processedFiles = new HashSet<>();
    private final JsonMapper jsonMapper = new JsonMapper();


    public void execute(ChunkNorrisConfig chunkNorrisConfig) throws Exception {
        eventPublisher.publishEvent(new SpringPreWalkEvent<>(chunkNorrisConfig));

        Files.walk(Path.of(chunkNorrisConfig.getRootDir()))
                .filter(Files::isRegularFile)
                .filter(path -> checkVetos(path))
                .filter(path -> !processedFiles.contains(path.toAbsolutePath().toString()))
                .forEach(path -> handlePath(chunkNorrisConfig, path));
        ;
        doPostWalk(chunkNorrisConfig);
    }

    protected boolean checkVetos(Path p) {
        return !vetoRegistry.shouldVeto(p);
    }

    protected void handlePath(ChunkNorrisConfig chunkNorrisConfig, Path p) {
        factory.createChunker(p.toFile())
                .parallel()
                .flatMap(c -> c.chunk(p.toFile()))
                .map(chunk -> toJSONL(chunk))
                .forEach(
                        str -> chunkNorrisConfig.getOutput().println(str)
                )
        ;
        chunkNorrisConfig.getOutput().flush();
        processedFiles.add(p.toAbsolutePath().toString());
    }

    protected void doPostWalk(ChunkNorrisConfig chunkNorrisConfig) {
        factory.listPostWalkChunker()
                .parallel()
                .flatMap(pwChunk -> pwChunk.chunk(null))
                .map(chunk -> toJSONL(chunk))
                .forEach(
                        str -> chunkNorrisConfig.getOutput().println(str)
                )
        ;
        chunkNorrisConfig.getOutput().flush();
    }

    public String toJSONL(PxChunk chunk) {
        try {
            return jsonMapper.writeValueAsString(chunk);
        } catch (JsonProcessingException jpXC) {
            log.severe("Could not write jsonl for chunk " + chunk.getId() + ": " + jpXC);
            return "";
        }
    }
}
