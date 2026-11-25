package de.spraener.prjxp.mcp.docs;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
@ConditionalOnProperty(name = "prjxp.hub.enabled", havingValue = "true")
public class DocsService {

    private static final Pattern H1 = Pattern.compile("^#\\s+(.+)$");
    private static final Pattern H2 = Pattern.compile("^##\\s+(.+)$");
    private static final Pattern FENCE = Pattern.compile("^\\s*```");

    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("webp", "image/webp"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("md", "text/plain;charset=UTF-8"),
            Map.entry("txt", "text/plain;charset=UTF-8"),
            Map.entry("json", "application/json"),
            Map.entry("css", "text/css"),
            Map.entry("js", "text/javascript")
    );

    private final Path docsRoot;

    public DocsService(@Value("${prjxp.docs.dir:mcp-server/doc}") String docsDir) {
        this.docsRoot = Path.of(docsDir).toAbsolutePath().normalize();
    }

    /** Full documentation tree. Never throws: missing/empty dir → root with empty children. */
    public DocsNode buildTree() {
        List<DocsNode> children = Files.isDirectory(docsRoot) ? collectChildren(docsRoot) : List.of();
        return DocsNode.root(docsRoot.getFileName().toString(), children);
    }

    /** Raw markdown of a document. Empty if missing, not a regular file, or outside the docs root. */
    public Optional<String> readContent(String relPath) {
        return resolveSafe(relPath)
                .filter(Files::isRegularFile)
                .flatMap(path -> {
                    try {
                        return Optional.of(Files.readString(path));
                    } catch (IOException e) {
                        return Optional.empty();
                    }
                });
    }

    /** Asset bytes + content type. Empty if missing or outside the docs root. */
    public Optional<DocsAsset> readAsset(String relPath) {
        return resolveSafe(relPath)
                .filter(Files::isRegularFile)
                .flatMap(path -> {
                    try {
                        return Optional.of(new DocsAsset(Files.readAllBytes(path), contentType(path)));
                    } catch (IOException e) {
                        return Optional.empty();
                    }
                });
    }

    public record DocsAsset(byte[] data, String contentType) {
    }

    static String slugify(String text) {
        String s = text.toLowerCase()
                .replaceAll("[^\\p{L}\\p{N}\\s-]", "")
                .trim()
                .replaceAll("\\s+", "-");
        return s.isEmpty() ? "section" : s;
    }

    private List<DocsNode> collectChildren(Path dir) {
        List<DocsNode> folders = new ArrayList<>();
        List<DocsNode> docs = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            for (Path path : stream
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .toList()) {
                if (Files.isDirectory(path)) {
                    folders.add(DocsNode.folder(path.getFileName().toString(), collectChildren(path)));
                } else if (Files.isRegularFile(path)
                        && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md")) {
                    docs.add(parseDoc(path));
                }
            }
        } catch (IOException e) {
            // Unreadable directory: keep whatever was collected so far.
        }
        folders.sort(Comparator.comparing(DocsNode::name, String.CASE_INSENSITIVE_ORDER));
        docs.sort(Comparator.comparing(DocsNode::name, String.CASE_INSENSITIVE_ORDER));
        List<DocsNode> children = new ArrayList<>(folders);
        children.addAll(docs);
        return children;
    }

    private DocsNode parseDoc(Path file) {
        String name = file.getFileName().toString().replaceAll("(?i)\\.md$", "");
        List<DocsChapter> chapters = new ArrayList<>();
        Map<String, Integer> anchorCounts = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        boolean nameFound = false;
        boolean inFence = false;
        try (Stream<String> lines = Files.lines(file)) {
            for (String line : lines.toList()) {
                if (FENCE.matcher(line).matches()) {
                    inFence = !inFence;
                    continue;
                }
                if (inFence) {
                    continue;
                }
                if (!nameFound) {
                    Matcher h1 = H1.matcher(line);
                    if (h1.matches()) {
                        name = h1.group(1).trim();
                        nameFound = true;
                        continue;
                    }
                }
                Matcher h2 = H2.matcher(line);
                if (h2.matches()) {
                    String title = h2.group(1).trim();
                    String slug = slugify(title);
                    int count = anchorCounts.merge(slug, 1, Integer::sum);
                    chapters.add(new DocsChapter(title, count == 1 ? slug : slug + "-" + count));
                }
            }
        } catch (IOException e) {
            // Unreadable file: keep the filename fallback and the chapters collected so far.
        }
        return DocsNode.doc(name, relativePath(file), chapters);
    }

    private String relativePath(Path file) {
        return docsRoot.relativize(file).toString().replace(File.separatorChar, '/');
    }

    private Optional<Path> resolveSafe(String relPath) {
        if (relPath == null || relPath.isBlank()) {
            return Optional.empty();
        }
        if (Path.of(relPath).isAbsolute()) {
            return Optional.empty();
        }
        for (String segment : relPath.split("[/\\\\]")) {
            if ("..".equals(segment)) {
                return Optional.empty();
            }
        }
        Path resolved = docsRoot.resolve(relPath).normalize();
        return resolved.startsWith(docsRoot) ? Optional.of(resolved) : Optional.empty();
    }

    private String contentType(Path file) {
        String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = fileName.lastIndexOf('.');
        String extension = dot >= 0 ? fileName.substring(dot + 1) : "";
        return CONTENT_TYPES.getOrDefault(extension, "application/octet-stream");
    }
}
