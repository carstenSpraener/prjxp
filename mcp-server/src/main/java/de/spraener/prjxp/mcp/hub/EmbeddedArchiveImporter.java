package de.spraener.prjxp.mcp.hub;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.common.model.EmbeddedChunkRecord;
import de.spraener.prjxp.mcp.ProjectInfo;
import lombok.RequiredArgsConstructor;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Imports pre-embedded project archives (JSONL, GZ, TGZ, ZIP) into the hub's projects root.
 * Derives project name from filename (must end with "-embedded"), validates content, places JSONL
 * into target dir, updates prjxp.yaml with embeddingsFile, writes .ready marker, and enqueues
 * the pre-embedded pipeline. Merge semantics: existing project files are preserved; only JSONL is
 * added/overwritten and yaml updated.
 */
@Component
@ConditionalOnProperty(name = "prjxp.hub.enabled", havingValue = "true")
@RequiredArgsConstructor
public class EmbeddedArchiveImporter {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedArchiveImporter.class);

    private final HubProperties props;
    private final ProjectConfigFileParser configParser;
    private final HubProjectRegistry registry;
    private final PipelineOrchestrator orchestrator;
    private final ObjectMapper objMapper;

    /** Derives the project name from a filename. The name minus last extension must end with "-embedded" (case-insensitive). */
    public String deriveProjectName(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        String ext;
        if (lower.endsWith(".tar.gz")) {
            ext = ".tar.gz";
        } else if (lower.endsWith(".tgz")) {
            ext = ".tgz";
        } else if (lower.endsWith(".gz")) {
            ext = ".gz";
        } else if (lower.endsWith(".zip")) {
            ext = ".zip";
        } else if (lower.endsWith(".jsonl")) {
            ext = ".jsonl";
        } else {
            throw new IllegalArgumentException("File must be named <ProjectName>-embedded.(jsonl|gz|tgz|zip), got: " + fileName);
        }

        String withoutExt = fileName.substring(0, fileName.length() - ext.length());
        if (!withoutExt.toLowerCase(Locale.ROOT).endsWith("-embedded")) {
            throw new IllegalArgumentException("File must be named <ProjectName>-embedded.(jsonl|gz|tgz|zip), got: " + fileName);
        }

        String name = withoutExt.substring(0, withoutExt.length() - "-embedded".length());
        if (name.isBlank()) {
            throw new IllegalArgumentException("Project name cannot be empty. File must be named <ProjectName>-embedded.(jsonl|gz|tgz|zip), got: " + fileName);
        }
        return name;
    }

    /** Cheap pre-checks: name scheme, extension, archive openable + single-.jsonl rule, first-line parse. */
    public void validate(Path sourceFile) {
        String fileName = sourceFile.getFileName().toString();
        // Check name scheme
        deriveProjectName(fileName);

        // Check size limit
        try {
            if (Files.size(sourceFile) > props.getMaxTarBytes()) {
                throw new IllegalArgumentException("File exceeds size limit of " + props.getMaxTarBytes() + " bytes");
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read file size: " + e.getMessage());
        }

        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jsonl")) {
            validateJsonlFirstLine(sourceFile);
        } else if (lower.endsWith(".gz")) {
            validateGzFirstLine(sourceFile);
        } else if (lower.endsWith(".tgz") || lower.endsWith(".tar.gz")) {
            validateTgz(sourceFile);
        } else if (lower.endsWith(".zip")) {
            validateZip(sourceFile);
        } else {
            throw new IllegalArgumentException("Unsupported extension for embedded import: " + fileName);
        }
    }

    /** Process an embedded archive file: extract, update yaml, write .ready marker, enqueue pipeline. */
    public ProjectInfo process(Path sourceFile) {
        String fileName = sourceFile.getFileName().toString();
        String name = deriveProjectName(fileName);
        Path targetDir = Path.of(props.getProjectsRoot(), name);

        // Create target dir if missing
        try {
            Files.createDirectories(targetDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create project directory " + targetDir, e);
        }

        String lower = fileName.toLowerCase(Locale.ROOT);
        String jsonlBasename;

        if (lower.endsWith(".jsonl")) {
            // Validate first line, then copy
            validateJsonlFirstLine(sourceFile);
            jsonlBasename = fileName; // keep original basename (already ends with -embedded.jsonl)
            try {
                Files.copy(sourceFile, targetDir.resolve(jsonlBasename), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new RuntimeException("Failed to copy JSONL file", e);
            }
        } else if (lower.endsWith(".gz")) {
            // Gunzip and validate first line, write as <name>-embedded.jsonl
            jsonlBasename = name + "-embedded.jsonl";
            try {
                gunzipAndWrite(sourceFile, targetDir.resolve(jsonlBasename));
            } catch (IOException e) {
                throw new RuntimeException("Failed to extract gzip file", e);
            }
        } else if (lower.endsWith(".tgz") || lower.endsWith(".tar.gz")) {
            // Single-entry tar extraction with security validation
            jsonlBasename = extractTgz(sourceFile, targetDir);
        } else if (lower.endsWith(".zip")) {
            // Single-entry zip extraction with security validation
            jsonlBasename = extractZip(sourceFile, targetDir);
        } else {
            throw new IllegalArgumentException("Unsupported extension: " + fileName);
        }

        // Update or create prjxp.yaml with embeddingsFile
        updateProjectYaml(targetDir, name, jsonlBasename);

        // Write .ready marker
        try {
            Files.write(targetDir.resolve(ProjectConfigFileParser.READY_MARKER_FILE_NAME), new byte[0]);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write .ready marker", e);
        }

        // Register project if not already registered, then enqueue forced pipeline
        if (!registry.entry(name).isPresent()) {
            registry.registerProject(name, targetDir);
        } else {
            // Reload definition to pick up the updated yaml
            registry.reloadDefinition(name);
        }

        // Use enqueueForced to bypass hasIndexedChunks shortcut — pre-embedded pipeline does its own scoped reset
        orchestrator.enqueueForced(name);

        log.info("Processed embedded archive for project '{}', JSONL: {}", name, jsonlBasename);

        return new ProjectInfo(name, "IMPORTING", null, null, 0L, 0L, 0,
                null, null, null, null, jsonlBasename, false, "IDLE", 0, null);
    }

    // ------------------------------------------------------------------ JSONL validation

    private void validateJsonlFirstLine(Path jsonlFile) {
        try (var lines = Files.lines(jsonlFile)) {
            String firstLine = lines.filter(l -> !l.isBlank()).findFirst().orElse(null);
            if (firstLine == null) {
                throw new IllegalArgumentException("JSONL file is empty: " + jsonlFile.getFileName());
            }
            EmbeddedChunkRecord record = objMapper.readValue(firstLine, EmbeddedChunkRecord.class);
            if (record.vector() == null || record.vector().length == 0) {
                throw new IllegalArgumentException("First line of JSONL has null or empty vector: " + jsonlFile.getFileName());
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to parse first line of JSONL: " + e.getMessage());
        }
    }

    private void validateGzFirstLine(Path gzFile) {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(gzFile))) {
            try (GzipCompressorInputStream gzIn = new GzipCompressorInputStream(in)) {
                // Read first non-blank line from decompressed stream
                byte[] buf = new byte[8192];
                int len;
                StringBuilder sb = new StringBuilder();
                while ((len = gzIn.read(buf)) > 0) {
                    sb.append(new String(buf, 0, len, StandardCharsets.UTF_8));
                    if (sb.toString().contains("\n")) {
                        break; // we have at least one line
                    }
                }
                String text = sb.toString();
                for (String line : text.split("\n")) {
                    if (!line.isBlank()) {
                        EmbeddedChunkRecord record = objMapper.readValue(line, EmbeddedChunkRecord.class);
                        if (record.vector() == null || record.vector().length == 0) {
                            throw new IllegalArgumentException("First line of gzipped JSONL has null or empty vector");
                        }
                        return;
                    }
                }
                throw new IllegalArgumentException("Gzipped file contains no valid lines");
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to parse gzipped JSONL: " + e.getMessage());
        }
    }

    private void gunzipAndWrite(Path gzFile, Path target) throws IOException {
        // First validate
        try (InputStream in = new BufferedInputStream(Files.newInputStream(gzFile))) {
            try (GzipCompressorInputStream gzIn = new GzipCompressorInputStream(in)) {
                // Read first non-blank line for validation
                byte[] buf = new byte[8192];
                int len;
                StringBuilder sb = new StringBuilder();
                while ((len = gzIn.read(buf)) > 0) {
                    sb.append(new String(buf, 0, len, StandardCharsets.UTF_8));
                    if (sb.toString().contains("\n")) {
                        break;
                    }
                }
                String text = sb.toString();
                for (String line : text.split("\n")) {
                    if (!line.isBlank()) {
                        EmbeddedChunkRecord record = objMapper.readValue(line, EmbeddedChunkRecord.class);
                        if (record.vector() == null || record.vector().length == 0) {
                            throw new IllegalArgumentException("First line of gzipped JSONL has null or empty vector");
                        }
                        break;
                    }
                }
            }
        }

        // Now do the full extraction
        try (InputStream in = new BufferedInputStream(Files.newInputStream(gzFile))) {
            try (GzipCompressorInputStream gzIn = new GzipCompressorInputStream(in)) {
                Files.copy(gzIn, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    // ------------------------------------------------------------------ TGZ extraction (single-entry, security-hardened)

    private String validateTgz(Path tgzFile) {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(tgzFile))) {
            try (GzipCompressorInputStream gzIn = new GzipCompressorInputStream(in)) {
                try (TarArchiveInputStream tarIn = new TarArchiveInputStream(gzIn)) {
                    List<String> entries = new ArrayList<>();
                    TarArchiveEntry entry;
                    while ((entry = tarIn.getNextEntry()) != null) {
                        if (entry.isDirectory()) continue;
                        validateTarEntry(entry);
                        entries.add(entry.getName());
                    }
                    if (entries.isEmpty()) {
                        throw new IllegalArgumentException("TGZ archive contains no file entries");
                    }
                    if (entries.size() > 1) {
                        throw new IllegalArgumentException("TGZ archive must contain exactly one file entry, found " + entries.size());
                    }
                    String singleEntry = entries.get(0);
                    if (!singleEntry.toLowerCase(Locale.ROOT).endsWith(".jsonl")) {
                        throw new IllegalArgumentException("TGZ archive entry must be a .jsonl file, got: " + singleEntry);
                    }
                    // Validate first line of the JSONL content
                    // We need to re-read the archive for content validation
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to validate TGZ archive: " + e.getMessage());
        }
        return null; // structure validated, content validation happens during extraction
    }

    private String extractTgz(Path tgzFile, Path targetDir) {
        // Size check
        try {
            if (Files.size(tgzFile) > props.getMaxTarBytes()) {
                throw new IllegalArgumentException("TGZ exceeds size limit of " + props.getMaxTarBytes() + " bytes");
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read file size: " + e.getMessage());
        }

        // Pass 1: validate entries, compute strip prefix
        String stripPrefix = null;
        List<String> fileEntries = new ArrayList<>();
        try (InputStream in = new BufferedInputStream(Files.newInputStream(tgzFile))) {
            try (GzipCompressorInputStream gzIn = new GzipCompressorInputStream(in)) {
                try (TarArchiveInputStream tarIn = new TarArchiveInputStream(gzIn)) {
                    String commonFirstSegment = null;
                    boolean allSharePrefix = true;
                    TarArchiveEntry entry;
                    while ((entry = tarIn.getNextEntry()) != null) {
                        if (entry.isDirectory()) continue;
                        validateTarEntry(entry);
                        fileEntries.add(entry.getName());

                        int slash = entry.getName().indexOf('/');
                        if (slash < 0) {
                            allSharePrefix = false;
                        } else {
                            String firstSegment = entry.getName().substring(0, slash);
                            if (commonFirstSegment == null) {
                                commonFirstSegment = firstSegment;
                            } else if (!commonFirstSegment.equals(firstSegment)) {
                                allSharePrefix = false;
                            }
                        }
                    }
                    if (allSharePrefix && commonFirstSegment != null) {
                        stripPrefix = commonFirstSegment + "/";
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read TGZ archive: " + e.getMessage(), e);
        }

        // Validate single entry rule
        if (fileEntries.isEmpty()) {
            throw new IllegalArgumentException("TGZ archive contains no file entries");
        }
        if (fileEntries.size() > 1) {
            throw new IllegalArgumentException("TGZ archive must contain exactly one file entry, found " + fileEntries.size());
        }

        String singleEntry = fileEntries.get(0);
        if (!singleEntry.toLowerCase(Locale.ROOT).endsWith(".jsonl")) {
            throw new IllegalArgumentException("TGZ archive entry must be a .jsonl file, got: " + singleEntry);
        }

        // Pass 2: extract the single entry, validate first line
        long writtenBytes = 0;
        Path normalizedTarget = targetDir.toAbsolutePath().normalize();
        try (InputStream in = new BufferedInputStream(Files.newInputStream(tgzFile))) {
            try (GzipCompressorInputStream gzIn = new GzipCompressorInputStream(in)) {
                try (TarArchiveInputStream tarIn = new TarArchiveInputStream(gzIn)) {
                    TarArchiveEntry entry;
                    while ((entry = tarIn.getNextEntry()) != null) {
                        if (entry.isDirectory()) continue;
                        String name = strip(entry.getName(), stripPrefix);
                        Path resolved = targetDir.resolve(name).toAbsolutePath().normalize();
                        if (!resolved.startsWith(normalizedTarget)) {
                            throw new IllegalArgumentException("TGZ entry escapes target directory: " + entry.getName());
                        }

                        // Read content for first-line validation, then write to target
                        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        int read;
                        while ((read = tarIn.read(buf)) > 0) {
                            baos.write(buf, 0, read);
                        }
                        byte[] content = baos.toByteArray();

                        // Validate first line of JSONL content
                        String text = new String(content, StandardCharsets.UTF_8);
                        boolean validated = false;
                        for (String line : text.split("\n")) {
                            if (!line.isBlank()) {
                                EmbeddedChunkRecord record = objMapper.readValue(line, EmbeddedChunkRecord.class);
                                if (record.vector() == null || record.vector().length == 0) {
                                    throw new IllegalArgumentException("First line of TGZ JSONL has null or empty vector");
                                }
                                validated = true;
                                break;
                            }
                        }
                        if (!validated) {
                            throw new IllegalArgumentException("TGZ JSONL contains no valid lines");
                        }

                        // Write to target (merge: do not delete existing files)
                        Files.createDirectories(resolved.getParent());
                        Files.write(resolved, content);
                        writtenBytes += content.length;

                        if (writtenBytes > props.getMaxExtractedBytes()) {
                            throw new IllegalArgumentException("Extracted content exceeds size limit of " + props.getMaxExtractedBytes() + " bytes");
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to extract TGZ archive: " + e.getMessage(), e);
        }

        // Return the basename of the extracted JSONL file
        String name = strip(singleEntry, stripPrefix);
        return Path.of(name).getFileName().toString();
    }

    // ------------------------------------------------------------------ ZIP extraction (single-entry, security-hardened)

    private void validateZip(Path zipFile) {
        try (InputStream in = Files.newInputStream(zipFile)) {
            try (ZipInputStream zipIn = new ZipInputStream(in)) {
                List<String> entries = new ArrayList<>();
                ZipEntry entry;
                while ((entry = zipIn.getNextEntry()) != null) {
                    if (entry.isDirectory()) continue;
                    validateZipEntry(entry);
                    entries.add(entry.getName());
                }
                if (entries.isEmpty()) {
                    throw new IllegalArgumentException("ZIP archive contains no file entries");
                }
                if (entries.size() > 1) {
                    throw new IllegalArgumentException("ZIP archive must contain exactly one file entry, found " + entries.size());
                }
                String singleEntry = entries.get(0);
                if (!singleEntry.toLowerCase(Locale.ROOT).endsWith(".jsonl")) {
                    throw new IllegalArgumentException("ZIP archive entry must be a .jsonl file, got: " + singleEntry);
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to validate ZIP archive: " + e.getMessage());
        }
    }

    private String extractZip(Path zipFile, Path targetDir) {
        // Size check
        try {
            if (Files.size(zipFile) > props.getMaxTarBytes()) {
                throw new IllegalArgumentException("ZIP exceeds size limit of " + props.getMaxTarBytes() + " bytes");
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read file size: " + e.getMessage());
        }

        // Pass 1: validate entries, compute strip prefix
        String stripPrefix = null;
        List<String> fileEntries = new ArrayList<>();
        try (InputStream in = Files.newInputStream(zipFile)) {
            try (ZipInputStream zipIn = new ZipInputStream(in)) {
                String commonFirstSegment = null;
                boolean allSharePrefix = true;
                ZipEntry entry;
                while ((entry = zipIn.getNextEntry()) != null) {
                    if (entry.isDirectory()) continue;
                    validateZipEntry(entry);
                    fileEntries.add(entry.getName());

                    int slash = entry.getName().indexOf('/');
                    if (slash < 0) {
                        allSharePrefix = false;
                    } else {
                        String firstSegment = entry.getName().substring(0, slash);
                        if (commonFirstSegment == null) {
                            commonFirstSegment = firstSegment;
                        } else if (!commonFirstSegment.equals(firstSegment)) {
                            allSharePrefix = false;
                        }
                    }
                }
                if (allSharePrefix && commonFirstSegment != null) {
                    stripPrefix = commonFirstSegment + "/";
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read ZIP archive: " + e.getMessage(), e);
        }

        // Validate single entry rule
        if (fileEntries.isEmpty()) {
            throw new IllegalArgumentException("ZIP archive contains no file entries");
        }
        if (fileEntries.size() > 1) {
            throw new IllegalArgumentException("ZIP archive must contain exactly one file entry, found " + fileEntries.size());
        }

        String singleEntry = fileEntries.get(0);
        if (!singleEntry.toLowerCase(Locale.ROOT).endsWith(".jsonl")) {
            throw new IllegalArgumentException("ZIP archive entry must be a .jsonl file, got: " + singleEntry);
        }

        // Pass 2: extract the single entry, validate first line
        long writtenBytes = 0;
        Path normalizedTarget = targetDir.toAbsolutePath().normalize();
        try (InputStream in = Files.newInputStream(zipFile)) {
            try (ZipInputStream zipIn = new ZipInputStream(in)) {
                ZipEntry entry;
                while ((entry = zipIn.getNextEntry()) != null) {
                    if (entry.isDirectory()) continue;
                    String name = strip(entry.getName(), stripPrefix);
                    Path resolved = targetDir.resolve(name).toAbsolutePath().normalize();
                    if (!resolved.startsWith(normalizedTarget)) {
                        throw new IllegalArgumentException("ZIP entry escapes target directory: " + entry.getName());
                    }

                    // Read content for first-line validation, then write to target
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int read;
                    while ((read = zipIn.read(buf)) > 0) {
                        baos.write(buf, 0, read);
                    }
                    byte[] content = baos.toByteArray();

                    // Validate first line of JSONL content
                    String text = new String(content, StandardCharsets.UTF_8);
                    boolean validated = false;
                    for (String line : text.split("\n")) {
                        if (!line.isBlank()) {
                            EmbeddedChunkRecord record = objMapper.readValue(line, EmbeddedChunkRecord.class);
                            if (record.vector() == null || record.vector().length == 0) {
                                throw new IllegalArgumentException("First line of ZIP JSONL has null or empty vector");
                            }
                            validated = true;
                            break;
                        }
                    }
                    if (!validated) {
                        throw new IllegalArgumentException("ZIP JSONL contains no valid lines");
                    }

                    // Write to target (merge: do not delete existing files)
                    Files.createDirectories(resolved.getParent());
                    Files.write(resolved, content);
                    writtenBytes += content.length;

                    if (writtenBytes > props.getMaxExtractedBytes()) {
                        throw new IllegalArgumentException("Extracted content exceeds size limit of " + props.getMaxExtractedBytes() + " bytes");
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to extract ZIP archive: " + e.getMessage(), e);
        }

        // Return the basename of the extracted JSONL file
        String name = strip(singleEntry, stripPrefix);
        return Path.of(name).getFileName().toString();
    }

    // ------------------------------------------------------------------ Security validation helpers

    private void validateTarEntry(TarArchiveEntry entry) {
        String name = entry.getName();
        if (isAbsoluteTarPath(name)) {
            throw new IllegalArgumentException("TGZ entry with absolute path: " + name);
        }
        for (String segment : name.split("/")) {
            if ("..".equals(segment)) {
                throw new IllegalArgumentException("TGZ entry with '..' path segment: " + name);
            }
        }
        if (entry.isSymbolicLink() || entry.isLink()) {
            throw new IllegalArgumentException("TGZ entry with link is not allowed: " + name);
        }
    }

    private void validateZipEntry(ZipEntry entry) {
        String name = entry.getName();
        if (isAbsoluteTarPath(name)) {
            throw new IllegalArgumentException("ZIP entry with absolute path: " + name);
        }
        for (String segment : name.split("/")) {
            if ("..".equals(segment)) {
                throw new IllegalArgumentException("ZIP entry with '..' path segment: " + name);
            }
        }
    }

    private boolean isAbsoluteTarPath(String name) {
        if (name == null || name.isBlank()) return false;
        if (name.startsWith("/") || name.startsWith("\\")) return true;
        if (name.length() >= 3 && Character.isLetter(name.charAt(0)) && name.charAt(1) == ':'
                && (name.charAt(2) == '/' || name.charAt(2) == '\\')) return true;
        return name.startsWith("\\\\");
    }

    private String strip(String name, String prefix) {
        if (prefix != null && name.startsWith(prefix)) {
            return name.substring(prefix.length());
        }
        return name;
    }

    // ------------------------------------------------------------------ YAML update (merge semantics)

    private void updateProjectYaml(Path projectDir, String name, String jsonlBasename) {
        ProjectDefinition def = configParser.parse(projectDir, name);
        def.setEmbeddingsFile(jsonlBasename);
        configParser.save(projectDir, def);
    }
}
