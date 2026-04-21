package de.spraener.prjxp.chuno.docs.txt;

import de.spraener.prjxp.common.annotations.ChunkNorrisComponent;
import de.spraener.prjxp.common.annotations.Chunker;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.PxFileType;
import de.spraener.prjxp.common.scripting.ScriptCompileService;
import de.spraener.prjxp.common.util.ContentSplitter;
import de.spraener.prjxp.common.util.ValueContainer;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

import javax.script.ScriptException;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

@Component
@ChunkNorrisComponent
@Log
@RequiredArgsConstructor
public class TextChunker {
    private final ScriptCompileService scSrv;

    @Chunker(
            fileTypes = {PxFileType.TXT}
    )
    public Stream<PxChunk> chunkTextFile(File txt) {
        final ValueContainer<BiConsumer<String, TextDocChunkContext>> lineConsumer = new ValueContainer<>((line, ctxt) -> {
        });

        try {
            BufferedReader br = new BufferedReader(new FileReader(txt));
            TextDocChunkContext ctxt = new TextDocChunkContext(txt);
            if (hasGroovyFile(txt)) {
                String scriptName = toGroovyScriptName(txt);
                final var engine = scSrv.createEngine("groovy");
                final var script = scSrv.compile(Path.of(scriptName), engine);
                final var bindings = engine.createBindings();
                lineConsumer.setValue((line, c) -> {
                    bindings.put("context", c);
                    bindings.put("line", line);
                    try {
                        script.eval(bindings);
                    } catch (ScriptException e) {
                        e.printStackTrace();
                        log.severe("Error while evaluating groovy script for " + txt.getAbsolutePath() + ": " + e.getMessage());
                    }
                });
            }

            String line;
            List<PxChunk> chunkList = new ArrayList<>();
            String id = toId(ctxt);
            StringBuilder content = new StringBuilder();
            int lineNr = 0;
            int chunkStartLine = lineNr;
            int chunkPage = 0;
            int chunkLine = 0;
            while ((line = br.readLine()) != null) {
                int page = ctxt.getPageNumber();
                lineConsumer.getValue().accept(line, ctxt);
                if( page != ctxt.getPageNumber() ) {
                    lineNr = 0;
                }
                ctxt.setLineNr(lineNr);
                if (!toId(ctxt).equals(id)) {
                    if( content.length() > 10 ) {
                        final var chunkId = id;
                        final ValueContainer<Integer> vcCount = new ValueContainer<>(0);
                        final var fChunkPage = chunkPage;
                        final var fChunkLine = chunkLine;
                        final ValueContainer<Integer> totalChunks = new ValueContainer<>(0);
                        List<PxChunk> sectionChunks = new ContentSplitter(600, 50).splitContent(content, chunkStartLine, lineNr,
                                () -> PxChunk.create(
                                        c -> c.setId(chunkId),
                                        c -> c.setMimeType("text/plain"),
                                        c -> c.setFile(txt.getAbsolutePath()),
                                        c -> c.setParent(txt.getAbsolutePath()),
                                        c -> c.setPart(vcCount.getValue()),
                                        c -> c.getMetadata().put("page", ""+fChunkPage),
                                        c-> c.getMetadata().put("line", ""+fChunkLine),
                                        c->{
                                            c.setPart(totalChunks.getValue());
                                            totalChunks.setValue(totalChunks.getValue()+1);
                                        }
                                ));
                        sectionChunks.forEach(c->c.setTotal(totalChunks.getValue()));
                        chunkList.addAll(sectionChunks);
                    }
                    id = toId(ctxt);
                    chunkPage = ctxt.getPageNumber();
                    chunkLine = lineNr;
                    chunkStartLine = lineNr+1;
                    content = new StringBuilder();
                } else {
                    content.append(line).append('\n');
                }
                lineNr++;
            }
            return chunkList.stream();
        } catch (Exception e) {
            log.severe("Error while chunking txt file  " + txt.getAbsolutePath() + ": " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private String toId(TextDocChunkContext ctxt) {
        return ctxt.toId();
    }

    private boolean hasGroovyFile(File txt) {
        String scptName = toGroovyScriptName(txt);
        return new File(scptName).exists();
    }

    private static @NonNull String toGroovyScriptName(File txt) {
        return txt.getAbsolutePath().substring(0, txt.getAbsolutePath().lastIndexOf(".")) + ".groovy";
    }
}
