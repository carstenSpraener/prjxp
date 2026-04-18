package de.spraener.chuno.code.java;

import de.spraener.chuno.util.DependencyRegistry;
import de.spraener.chuno.util.DependencyRegistryManager;
import de.spraener.prjxp.common.annotations.ChunkNorrisComponent;
import de.spraener.prjxp.common.annotations.PostWalkChunker;
import de.spraener.prjxp.common.code.java.JavaCodeSection;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.util.ContentSplitter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.stream.Stream;

@RequiredArgsConstructor
@Component
@ChunkNorrisComponent
public class JavaDependenciesChunker {
    @Value("${java.chunksize:600}")
    private int chunkSize;
    @Value("${java.chunkoverlap:50}")
    private int overlap;

    private final DependencyRegistryManager depRegMgr;

    @PostWalkChunker
    public Stream<PxChunk> createDependencyChunks() {
        DependencyRegistry depReg = depRegMgr.get(JavaDependencyHandler.JAVA_DEPENDENCIES);
        return depReg.keyStream()
                .flatMap(this::createDependencyChunkForSource)
                ;
    }

    private Stream<PxChunk> createDependencyChunkForSource(String source) {
        StringBuilder content = new StringBuilder();
        content.append("Die Klasse " + source + " benutzt die folgenden Klassen:").append('\n');
        depRegMgr.get(JavaDependencyHandler.JAVA_DEPENDENCIES)
                .getDependencies(source)
                .stream()
                .forEach(str -> content.append("  * ").append(str).append('\n'));
        content.append("Die folgenden Klassen verweisen auf die Klasse " + source + ":\n");
        depRegMgr.get(JavaDependencyHandler.JAVA_DEPENDENCIES)
                .getUsedBy(source)
                .forEach(str -> content.append("  * ").append(str).append('\n'));
        return new ContentSplitter(chunkSize, overlap).splitContent(content, 0,
                content.length(), () -> PxChunk.create(
                        c -> c.setMimeType("text/plain"),
                        c -> c.setParent(source),
                        c -> c.getMetadata().put("java_code_section", JavaCodeSection.DEPENDENCIE_INFO.getName()),
                        c -> c.setId(source + ".dependencies")
                )).stream();
    }
}
