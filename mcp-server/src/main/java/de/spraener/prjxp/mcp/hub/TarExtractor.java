package de.spraener.prjxp.mcp.hub;

import lombok.RequiredArgsConstructor;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;

/**
 * Security-hardened tar/tar.gz extraction for hub imports: rejects absolute paths, ".." segments
 * and links, enforces size/entry limits, strips a single top-level directory, wipes stale targets.
 */
@Component
@ConditionalOnProperty(name = "prjxp.hub.enabled", havingValue = "true")
@RequiredArgsConstructor
public class TarExtractor {

    private final HubProperties props;

    /** Extracts tarFile into targetDir (created if missing). Returns the project root dir. */
    public Path extract(Path tarFile, Path targetDir) throws IOException {
        if (Files.size(tarFile) > props.getMaxTarBytes()) {
            throw new TarSecurityException("tar exceeds size limit of " + props.getMaxTarBytes() + " bytes");
        }

        boolean gzipped = isGzipped(tarFile);

        // Pass 1: validate every entry, enforce the entry limit, compute the single-root strip prefix.
        String stripPrefix = null;
        try (TarArchiveInputStream tarIn = open(tarFile, gzipped)) {
            String commonFirstSegment = null;
            boolean allSharePrefix = true;
            int entryCount = 0;
            TarArchiveEntry entry;
            while ((entry = tarIn.getNextEntry()) != null) {
                if (++entryCount > props.getMaxEntries()) {
                    throw new TarSecurityException("tar exceeds entry limit of " + props.getMaxEntries());
                }
                validateEntry(entry);
                if (!entry.isDirectory()) {
                    int slash = entry.getName().indexOf('/');
                    if (slash < 0) {
                        allSharePrefix = false; // top-level file -> no common root to strip
                    } else {
                        String firstSegment = entry.getName().substring(0, slash);
                        if (commonFirstSegment == null) {
                            commonFirstSegment = firstSegment;
                        } else if (!commonFirstSegment.equals(firstSegment)) {
                            allSharePrefix = false; // mixed roots -> extract as-is
                        }
                    }
                }
            }
            if (allSharePrefix && commonFirstSegment != null) {
                stripPrefix = commonFirstSegment + "/";
            }
        }

        // Re-import is a clean slate: wipe any previous extraction before writing.
        if (Files.exists(targetDir)) {
            deleteRecursively(targetDir);
        }

        // Pass 2: write entries, stripping the single-root prefix.
        long writtenBytes = 0;
        Path normalizedTarget = targetDir.toAbsolutePath().normalize();
        try (TarArchiveInputStream tarIn = open(tarFile, gzipped)) {
            Files.createDirectories(targetDir);
            TarArchiveEntry entry;
            while ((entry = tarIn.getNextEntry()) != null) {
                String name = strip(entry.getName(), stripPrefix);
                Path resolved = targetDir.resolve(name).toAbsolutePath().normalize();
                if (!resolved.startsWith(normalizedTarget)) {
                    throw new TarSecurityException("tar entry escapes target directory: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(resolved);
                } else {
                    if (entry.getSize() >= 0 && writtenBytes + entry.getSize() > props.getMaxExtractedBytes()) {
                        throw new TarSecurityException("extracted content exceeds size limit of " + props.getMaxExtractedBytes() + " bytes");
                    }
                    Files.createDirectories(resolved.getParent());
                    writtenBytes += Files.copy(tarIn, resolved, StandardCopyOption.REPLACE_EXISTING);
                    if (writtenBytes > props.getMaxExtractedBytes()) {
                        throw new TarSecurityException("extracted content exceeds size limit of " + props.getMaxExtractedBytes() + " bytes");
                    }
                }
            }
        }

        return normalizedTarget;
    }

    private void validateEntry(TarArchiveEntry entry) {
        String name = entry.getName();
        if (Path.of(name).isAbsolute()) {
            throw new TarSecurityException("tar entry with absolute path: " + name);
        }
        for (String segment : name.split("/")) {
            if ("..".equals(segment)) {
                throw new TarSecurityException("tar entry with '..' path segment: " + name);
            }
        }
        // commons-compress 1.27: no getType() anymore — isSymbolicLink()/isLink() cover both link types
        if (entry.isSymbolicLink() || entry.isLink()) {
            throw new TarSecurityException("tar entry with link is not allowed: " + name);
        }
    }

    private boolean isGzipped(Path tarFile) {
        String fileName = tarFile.getFileName().toString().toLowerCase(Locale.ROOT);
        return fileName.endsWith(".tgz") || fileName.endsWith(".tar.gz");
    }

    private TarArchiveInputStream open(Path tarFile, boolean gzipped) throws IOException {
        InputStream in = new BufferedInputStream(Files.newInputStream(tarFile));
        if (gzipped) {
            in = new GzipCompressorInputStream(in);
        }
        return new TarArchiveInputStream(in);
    }

    private String strip(String name, String prefix) {
        if (prefix != null && name.startsWith(prefix)) {
            return name.substring(prefix.length());
        }
        return name;
    }

    private void deleteRecursively(Path dir) throws IOException {
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
