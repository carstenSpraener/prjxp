package de.spraener.prjxp.chuno.code.java;

import de.spraener.prjxp.chuno.util.DependencyRegistry;
import de.spraener.prjxp.chuno.util.DependencyRegistryManager;
import de.spraener.prjxp.common.model.PxChunker;
import de.spraener.prjxp.common.code.java.JavaCodeSection;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.util.ContentSplitter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.stream.Stream;

@RequiredArgsConstructor
@Component
public class JavaDependenciesChunker implements PxChunker {
    @Value("${java.chunksize:600}")
    private int chunkSize;
    @Value("${java.chunkoverlap:50}")
    private int overlap;

    private final DependencyRegistryManager depRegMgr;

    @Override
    public Stream<PxChunk> chunk(File f) {
        return createDependencyChunks();
    }

    @Override
    public boolean matches(File f) {
        return false;
    }

    public Stream<PxChunk> createDependencyChunks() {
        DependencyRegistry depReg = depRegMgr.get(JavaDependencyHandler.JAVA_DEPENDENCIES);
        return depReg.keyStream()
                .flatMap(this::createDependencyChunkForSource)
                ;
    }

    private Stream<PxChunk> createDependencyChunkForSource(String source) {
        StringBuilder content = new StringBuilder();
        content.append("## Dependencies of " + source + ":\n\n**Outgoing**\n");
        depRegMgr.get(JavaDependencyHandler.JAVA_DEPENDENCIES)
                .getDependencies(source)
                .stream()
                .forEach(str -> content.append("  * ").append(str).append('\n'));
        content.append("\n\n**Incoming**:\n");
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
