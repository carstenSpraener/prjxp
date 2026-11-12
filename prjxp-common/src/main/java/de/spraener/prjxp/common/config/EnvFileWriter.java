package de.spraener.prjxp.common.config;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class EnvFileWriter {
    private static final Pattern KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern ENTRY = Pattern.compile(
            "^(?<prefix>\\s*(?<key>[\\w.\\-]+)\\s*=\\s*)(?<value>'[^']*'|\"[^\"]*\"|[^#]*)?(?<suffix>\\s*(?:#.*)?)$");
    private static final Pattern PLAIN = Pattern.compile("[A-Za-z0-9_./:@-]*");
    private final Path envFile;

    public EnvFileWriter(Path envFile) {
        this.envFile = envFile.toAbsolutePath().normalize();
    }

    public Path getEnvFile() {
        return envFile;
    }

    public void upsert(String key, String value) throws IOException {
        validate(key, value);
        upsert(Map.of(key, value));
    }

    public void upsert(Map<String, String> entries) throws IOException {
        if (entries == null) {
            throw new IllegalArgumentException("Env entries must not be null");
        }
        Map<String, String> rendered = new LinkedHashMap<>();
        for (var entry : entries.entrySet()) {
            validate(entry.getKey(), entry.getValue());
            rendered.put(entry.getKey(), render(entry.getValue()));
        }
        if (entries.isEmpty()) {
            return;
        }
        String original = readText();
        List<Line> lines = splitLines(original);
        Map<String, List<Integer>> positions = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            Parsed parsed = parse(lines.get(i).text());
            if (parsed != null) {
                positions.computeIfAbsent(parsed.key(), key -> new ArrayList<>()).add(i);
            }
        }
        for (String key : entries.keySet()) {
            if (positions.containsKey(key) && positions.get(key).size() > 1) {
                throw new IOException("Ambiguous .env key: " + key);
            }
        }
        String ending = dominantEnding(lines);
        for (var entry : entries.entrySet()) {
            List<Integer> indices = positions.get(entry.getKey());
            if (indices == null) {
                if (!lines.isEmpty()) {
                    int lastIndex = lines.size() - 1;
                    Line last = lines.get(lastIndex);
                    if (last.ending().isEmpty()) {
                        lines.set(lastIndex, new Line(last.text(), ending));
                    }
                }
                lines.add(new Line(entry.getKey() + "=" + rendered.get(entry.getKey()), ending));
            } else {
                int index = indices.getFirst();
                Line line = lines.get(index);
                Parsed parsed = parse(line.text());
                if (!parsed.value().equals(entry.getValue())) {
                    lines.set(index, new Line(parsed.prefix() + rendered.get(entry.getKey()) + parsed.suffix(), line.ending()));
                }
            }
        }
        StringBuilder updated = new StringBuilder();
        lines.forEach(line -> updated.append(line.text()).append(line.ending()));
        if (!original.contentEquals(updated)) {
            writeAtomically(updated.toString());
        }
    }

    public Map<String, String> read() throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        for (Line line : splitLines(readText())) {
            Parsed parsed = parse(line.text());
            if (parsed != null && result.putIfAbsent(parsed.key(), parsed.value()) != null) {
                throw new IOException("Ambiguous .env key: " + parsed.key());
            }
        }
        return result;
    }

    public boolean hasKey(String key) throws IOException {
        return read().containsKey(key);
    }

    private String readText() throws IOException {
        return Files.exists(envFile) ? Files.readString(envFile, StandardCharsets.UTF_8) : "";
    }

    private static void validate(String key, String value) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Invalid .env key");
        }
        if (value == null || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Env value must be non-null and single-line");
        }
    }

    private static String render(String value) {
        String rendered = PLAIN.matcher(value).matches() ? value : "\"" + value + "\"";
        Parsed parsed = parse("KEY=" + rendered);
        if (parsed == null || !parsed.value().equals(value)) {
            throw new IllegalArgumentException("Value cannot be represented losslessly by dotenv-java");
        }
        return rendered;
    }

    private static Parsed parse(String text) {
        var matcher = ENTRY.matcher(text);
        if (!matcher.matches()) {
            return null;
        }
        String raw = matcher.group("value");
        if (raw == null) {
            raw = "";
        }
        String value = raw.trim();
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
            value = value.substring(1, value.length() - 1);
        }
        return new Parsed(matcher.group("key"), matcher.group("prefix"),
                raw.substring(raw.stripTrailing().length()) + matcher.group("suffix"), value);
    }

    private static List<Line> splitLines(String text) {
        List<Line> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\r' || ch == '\n') {
                int end = i + 1;
                if (ch == '\r' && end < text.length() && text.charAt(end) == '\n') {
                    end++;
                }
                lines.add(new Line(text.substring(start, i), text.substring(i, end)));
                start = end;
                i = end - 1;
            }
        }
        if (start < text.length()) {
            lines.add(new Line(text.substring(start), ""));
        }
        return lines;
    }

    private static String dominantEnding(List<Line> lines) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Line line : lines) {
            if (!line.ending().isEmpty()) {
                counts.merge(line.ending(), 1, Integer::sum);
            }
        }
        String result = "\n";
        int highest = 0;
        for (var entry : counts.entrySet()) {
            if (entry.getValue() > highest) {
                highest = entry.getValue();
                result = entry.getKey();
            }
        }
        return result;
    }

    protected void writeAtomically(String text) throws IOException {
        Files.createDirectories(envFile.getParent());
        Path temporary = Files.createTempFile(envFile.getParent(), ".prjxp-env-", ".tmp");
        try {
            preparePermissions(temporary, envFile);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(text);
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
                channel.force(true);
            }
            replace(temporary, envFile);
        } catch (IOException | RuntimeException error) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanup) {
                error.addSuppressed(cleanup);
            }
            throw error;
        }
    }

    protected void replace(Path temporary, Path target) throws IOException {
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    protected void preparePermissions(Path temporary, Path target) throws IOException {
        if (!Files.exists(target)) {
            prepareOwnerOnlyPermissions(temporary);
            return;
        }
        PosixFileAttributeView posix = Files.getFileAttributeView(temporary, PosixFileAttributeView.class);
        if (posix != null) {
            var attributes = Files.readAttributes(target, java.nio.file.attribute.PosixFileAttributes.class);
            posix.setPermissions(attributes.permissions());
            posix.setGroup(attributes.group());
            posix.setOwner(attributes.owner());

            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(temporary, AclFileAttributeView.class);
        if (acl == null) {
            throw new IOException("Owner-only file permissions are not supported");
        }
        AclFileAttributeView existing = Files.getFileAttributeView(target, AclFileAttributeView.class);
        acl.setOwner(existing.getOwner());
        acl.setAcl(existing.getAcl());
    }

    protected void prepareOwnerOnlyPermissions(Path temporary) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(temporary, PosixFileAttributeView.class);
        if (posix != null) {
            posix.setPermissions(PosixFilePermissions.fromString("rw-------"));
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(temporary, AclFileAttributeView.class);
        if (acl == null) {
            throw new IOException("Owner-only file permissions are not supported");
        }
        var owner = temporary.getFileSystem().getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
        acl.setOwner(owner);
        acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
    }

    private record Line(String text, String ending) {}
    private record Parsed(String key, String prefix, String suffix, String value) {}
}
