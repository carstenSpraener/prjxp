package de.spraener.prjxp.mcp.hub;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Watches the import directory for tar uploads and extracts them into the projects root. */
@Component
@ConditionalOnProperty(name = "prjxp.hub.enabled", havingValue = "true")
@RequiredArgsConstructor
public class ImportPoller {

    private static final Logger log = LoggerFactory.getLogger(ImportPoller.class);

    private final HubProperties props;
    private final TarExtractor extractor;
    private final ObjectProvider<ImportHandler> handlerProvider;

    @Scheduled(fixedDelayString = "${prjxp.hub.poll-interval-ms:5000}")
    public void poll() {
        Path importDir = Path.of(props.getImportDir());
        if (!Files.isDirectory(importDir)) {
            return; // silently — the directory may not exist yet (e.g. before a volume mount)
        }

        List<Path> tarFiles;
        try (var stream = Files.list(importDir)) {
            tarFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(file -> isTarName(file.getFileName().toString()))
                    .sorted(Comparator.comparing(file -> file.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            log.warn("Cannot list import directory {}: {}", importDir, e.getMessage());
            return;
        }

        for (Path tarFile : tarFiles) {
            process(tarFile); // per-file isolation: one bad tar must not break the others
        }
    }

    private void process(Path tarFile) {
        String name = baseName(tarFile);
        Path target = Path.of(props.getProjectsRoot(), name);
        try {
            extractor.extract(tarFile, target);
            handlerProvider.ifAvailable(handler -> handler.onImported(name, target));
            Files.delete(tarFile);
        } catch (Exception e) {
            log.error("Import of {} failed: {}", tarFile.getFileName(), e.getMessage());
            quarantine(tarFile, name);
            handlerProvider.ifAvailable(handler -> handler.onFailed(name, e.getMessage()));
        }
    }

    private void quarantine(Path tarFile, String name) {
        Path failed = tarFile.resolveSibling(name + ".tar.failed");
        try {
            Files.move(tarFile, failed, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("Cannot quarantine {}: {}", tarFile, e.getMessage());
        }
    }

    private boolean isTarName(String fileName) {
        if (fileName.endsWith(".failed")) {
            return false; // quarantined imports are not reprocessed
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".tar") || lower.endsWith(".tgz") || lower.endsWith(".tar.gz");
    }

    private String baseName(Path tarFile) {
        String fileName = tarFile.getFileName().toString();
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".tar.gz")) {
            return fileName.substring(0, fileName.length() - ".tar.gz".length());
        }
        if (lower.endsWith(".tgz")) {
            return fileName.substring(0, fileName.length() - ".tgz".length());
        }
        if (lower.endsWith(".tar")) {
            return fileName.substring(0, fileName.length() - ".tar".length());
        }
        return fileName; // defensive: only tar names reach this point
    }
}
