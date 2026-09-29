package de.spraener.prjxp.mcp.hub;

import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.lucene.LuceneEmbeddingStore;
import de.spraener.prjxp.mcp.UnknownProjectException;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Full project deletion in hub mode: scoped index wipe (the Lucene index is shared!) and unregistration
 * from the registry, plus recursive removal of the project directory for SNAPSHOT projects only —
 * LIVE source trees belong to the user and are never touched.
 */
@Component
@ConditionalOnProperty(name = "prjxp.hub.enabled", havingValue = "true")
@RequiredArgsConstructor
public class ProjectLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(ProjectLifecycleService.class);

    private final HubProjectRegistry registry;
    private final LuceneEmbeddingStore luceneStore;
    private final HubProperties hub;

    /**
     * Full deletion: scoped index wipe + unregistration, plus recursive directory removal for SNAPSHOT projects.
     * LIVE source trees belong to the user and are never touched by the hub — so for LIVE projects this is
     * temporary by design: the marker file survives, and the import poller re-discovers + re-enqueues the
     * project on its next cycle. Permanent removal of a live project = delete/rename its marker file.
     */
    public void delete(String name) {
        if (registry.entry(name).isEmpty()) {
            throw new UnknownProjectException(name, registry.availableProjects());
        }
        ProjectEntry entry = registry.entry(name).orElseThrow();
        luceneStore.removeAll(new IsEqualTo(PxChunk.PXCHUNK_PROJECT, name));   // scoped (shared index!)
        if (entry.getKind() == ProjectEntry.Kind.SNAPSHOT) {
            try {
                deleteRecursively(entry.getRootDir());
            } catch (IOException e) {
                throw new UncheckedIOException("Could not delete project directory of '" + name + "'", e);
            }
        } else {
            log.info("Skipping removal of live source tree for '{}'", name);   // the user owns it
        }
        registry.unregisterProject(name);
        log.info("Deleted hub project '{}'", name);
    }

    private void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;   // ignore missing directory (removed externally)
        }
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
