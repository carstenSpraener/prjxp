package de.spraener.prjxp.chuno;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import de.spraener.prjxp.chuno.spring.SpringPreWalkEvent;
import de.spraener.prjxp.chuno.veto.VetoRegistry;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.errorlog.PxLogService;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.common.model.PxChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

@Service
@Log
@RequiredArgsConstructor
public class ChunkProcess {
    private final PxLogService logService;
    private final ChunkerFactory factory;
    private final ApplicationEventPublisher eventPublisher;
    private final VetoRegistry vetoRegistry;
    private final Set<String> processedFiles = new HashSet<>();
    private final JsonMapper jsonMapper = new JsonMapper();
    private final PrjXPConfig cfg;

    public void execute() throws Exception {
        final ProjectDefinition pd = cfg.getActiveProject().orElseThrow(()->new IllegalStateException("No active project!"));
        final PrintStream out = createWriter(pd);
        eventPublisher.publishEvent(new SpringPreWalkEvent<>(cfg));

        Files.walk(Path.of(pd.getRootDir()))
                .filter(Files::isRegularFile)
                .filter(path -> checkVetos(path))
                .filter(path -> !processedFiles.contains(path.toAbsolutePath().toString()))
                .forEach(path -> handlePath(out, pd, path));
        ;
        doPostWalk(out);
    }

    private PrintStream createWriter(ProjectDefinition pd) {
        if( pd.getJsonlFile()==null ) {
            return System.out;
        }
        try {
            return new PrintStream(pd.getJsonlFile());
        } catch( FileNotFoundException fnfXC) {
            log.warning("Coulde not find output file "+pd.getJsonlFile()+". Using stdout. Error is: "+fnfXC.getMessage());
            return System.out;
        }
    }

    protected boolean checkVetos(Path p) {
        return !vetoRegistry.shouldVeto(p);
    }

    protected void handlePath(PrintStream out, ProjectDefinition pd, Path p) {
        final String rootDir = new File(pd.getRootDir()).getAbsolutePath();
        factory.createChunker(p.toFile())
                .parallel()
                .flatMap(c -> c.chunk(p.toFile()))
                .map( chunk -> {
                    chunk.setFile(chunk.getFile().replace(rootDir, ""));
                    return chunk;
                })
                .map(chunk -> toJSONL(chunk))
                .forEach(
                        str -> out.println(str)
                )
        ;
        out.flush();
        processedFiles.add(p.toAbsolutePath().toString());
    }

    protected void doPostWalk(PrintStream out) {
        factory.listPostWalkChunker()
                .parallel()
                .flatMap(pwChunk -> pwChunk.chunk(null))
                .map(chunk -> toJSONL(chunk))
                .forEach(
                        str -> out.println(str)
                )
        ;
        out.flush();
    }

    public String toJSONL(PxChunk chunk) {
        try {
            return jsonMapper.writeValueAsString(chunk);
        } catch (JsonProcessingException jpXC) {
            logService.error(jpXC, "Could not write jsonl for chunk %s", chunk.getId());
            return "";
        }
    }
}
