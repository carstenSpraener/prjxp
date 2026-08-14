package de.spraener.prjxp.chuno.code.visualbasic;

import de.spraener.prjxp.common.annotations.ChunkNorrisComponent;
import de.spraener.prjxp.common.annotations.Chunker;
import de.spraener.prjxp.common.code.visualbasic.VisualBasicCodeSection;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.PxFileType;
import de.spraener.prjxp.common.util.ContentSplitter;
import lombok.extern.java.Log;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Component
@ChunkNorrisComponent
@Log
public class VisualBasicCodeChunker {
    private static final String VISUAL_BASIC_CODE_MIME_TYPE = "text/x-visual-basic-code";
    public static final String MDKEY_CODESECTION = "visualbasic_code_section";

    private static final Pattern ATTRIBUTE_NAME_PATTERN = Pattern.compile("^\\s*Attribute\\s+VB_Name\\s*=\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEADER_PATTERN = Pattern.compile("^\\s*(Imports\\s+.+|Option\\s+.+|Attribute\\s+VB_.+)", Pattern.CASE_INSENSITIVE);
    private static final String VB_IDENTIFIER = "[\\p{L}_][\\p{L}\\p{N}_]*";
    private static final Pattern CONTAINER_PATTERN = Pattern.compile("^\\s*(?:(?:Public|Private|Friend|Partial|NotInheritable|MustInherit|Shared)\\s+)*(Class|Module|Interface|Structure)\\s+(" + VB_IDENTIFIER + ")\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern MEMBER_PATTERN = Pattern.compile("^\\s*(?:(?:Public|Private|Protected|Friend|Static|Shared|Overridable|Overrides|Overloads|MustOverride|Async|Iterator)\\s+)*(Sub|Function|Property(?:\\s+(?:Get|Let|Set))?)\\s+(" + VB_IDENTIFIER + ")\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern END_MEMBER_PATTERN = Pattern.compile("^\\s*End\\s+(Sub|Function|Property)\\b", Pattern.CASE_INSENSITIVE);

    @Value("${visualbasic.chunksize:1300}")
    private int chunkSize = 1300;
    @Value("${visualbasic.chunkoverlap:100}")
    private int overlap = 100;

    @Chunker(fileTypes = PxFileType.VISUAL_BASIC_CODE)
    public Stream<PxChunk> chunk(File f) {
        try {
            List<String> codeLines = readAllLines(f);
            VisualBasicFileInfo fileInfo = inspectFile(f, codeLines);

            List<PxChunk> chunks = new ArrayList<>();
            chunks.addAll(createHeaderChunk(f, codeLines, fileInfo));
            chunks.addAll(createMethodChunks(f, codeLines, fileInfo));
            chunks.addAll(createFrameChunk(f, codeLines, fileInfo));
            if (chunks.isEmpty()) {
                chunks.addAll(createFallbackFrameChunk(f, codeLines, fileInfo));
            }
            return chunks.stream();
        } catch (Exception e) {
            log.warning("Exception while chunking file " + f.getAbsolutePath() + ": " + e.getMessage());
            return Stream.of();
        }
    }

    private List<String> readAllLines(File f) throws IOException {
        try {
            return Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
        } catch (MalformedInputException mifXC) {
            log.fine("File " + f.getAbsolutePath() + " is not UTF-8 encoded. Trying windows-1252 fallback.");
            return Files.readAllLines(f.toPath(), Charset.forName("windows-1252"));
        }
    }

    private VisualBasicFileInfo inspectFile(File f, List<String> codeLines) {
        String fileBaseName = stripExtension(f.getName());
        String attributeName = findAttributeName(codeLines).orElse(null);
        ContainerInfo containerInfo = findContainer(codeLines).orElse(null);
        String moduleName = containerInfo != null ? containerInfo.name : attributeName != null ? attributeName : fileBaseName;
        List<MemberInfo> members = findMembers(codeLines);
        return new VisualBasicFileInfo(moduleName, containerInfo, members);
    }

    private Optional<String> findAttributeName(List<String> codeLines) {
        for (String line : codeLines) {
            Matcher matcher = ATTRIBUTE_NAME_PATTERN.matcher(line);
            if (matcher.find()) {
                return Optional.of(matcher.group(1));
            }
        }
        return Optional.empty();
    }

    private Optional<ContainerInfo> findContainer(List<String> codeLines) {
        for (int i = 0; i < codeLines.size(); i++) {
            Matcher matcher = CONTAINER_PATTERN.matcher(codeLines.get(i));
            if (matcher.find()) {
                return Optional.of(new ContainerInfo(matcher.group(2), i, findContainerEnd(codeLines, i)));
            }
        }
        return Optional.empty();
    }

    private int findContainerEnd(List<String> codeLines, int startLine) {
        for (int i = startLine + 1; i < codeLines.size(); i++) {
            if (codeLines.get(i).trim().matches("(?i)^End\\s+(Class|Module|Interface|Structure)\\b.*")) {
                return i + 1;
            }
        }
        return codeLines.size();
    }

    private List<MemberInfo> findMembers(List<String> codeLines) {
        List<MemberInfo> members = new ArrayList<>();
        for (int i = 0; i < codeLines.size(); i++) {
            Matcher matcher = MEMBER_PATTERN.matcher(codeLines.get(i));
            if (matcher.find() && !codeLines.get(i).trim().matches("(?i)^Declare\\b.*")) {
                String kind = matcher.group(1);
                String endKind = kind.toLowerCase().startsWith("property") ? "Property" : capitalize(kind);
                int endLine = findMemberEnd(codeLines, i, endKind);
                int docStartLine = findDocStart(codeLines, i - 1);
                members.add(new MemberInfo(matcher.group(2), matcher.group(2), i, endLine, docStartLine));
                i = endLine - 1;
            }
        }
        return withOverloadIds(members);
    }

    private List<MemberInfo> withOverloadIds(List<MemberInfo> members) {
        Map<String, Long> totalsByName = new HashMap<>();
        for (MemberInfo member : members) {
            totalsByName.merge(normalizeMemberName(member.name), 1L, Long::sum);
        }

        Map<String, Integer> overloadIndexByName = new HashMap<>();
        List<MemberInfo> result = new ArrayList<>();
        for (MemberInfo member : members) {
            String normalizedName = normalizeMemberName(member.name);
            if (totalsByName.get(normalizedName) > 1) {
                int overloadIndex = overloadIndexByName.merge(normalizedName, 1, Integer::sum);
                result.add(new MemberInfo(member.name, member.name + ".overload" + overloadIndex,
                        member.startLine, member.endLine, member.docStartLine));
            } else {
                result.add(member);
            }
        }
        return result;
    }

    private String normalizeMemberName(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private int findMemberEnd(List<String> codeLines, int startLine, String endKind) {
        for (int i = startLine; i < codeLines.size(); i++) {
            Matcher matcher = END_MEMBER_PATTERN.matcher(codeLines.get(i));
            if (matcher.find() && matcher.group(1).equalsIgnoreCase(endKind)) {
                return i + 1;
            }
        }
        return Math.min(startLine + 1, codeLines.size());
    }

    private int findDocStart(List<String> codeLines, int commentEndLine) {
        int startLine = commentEndLine;
        boolean foundComment = false;
        while (startLine >= 0) {
            String trimmed = codeLines.get(startLine).trim();
            if (trimmed.startsWith("'") || trimmed.matches("(?i)^Rem\\b.*")) {
                foundComment = true;
                startLine--;
                continue;
            }
            if (trimmed.isEmpty() && foundComment) {
                startLine--;
                continue;
            }
            break;
        }
        return foundComment ? startLine + 1 : -1;
    }

    private Collection<PxChunk> createHeaderChunk(File f, List<String> codeLines, VisualBasicFileInfo fileInfo) {
        int toLine = 0;
        for (int i = 0; i < codeLines.size(); i++) {
            String trimmed = codeLines.get(i).trim();
            if (trimmed.isEmpty() || HEADER_PATTERN.matcher(codeLines.get(i)).matches()) {
                toLine = i + 1;
                continue;
            }
            break;
        }
        if (toLine == 0) {
            return List.of();
        }
        String idSuffix = containsImports(codeLines.subList(0, toLine)) ? ".imports" : ".header";
        return split(readLines(codeLines, 0, toLine), 0, toLine, () ->
                createChunk(f, fileInfo.moduleName, fileInfo.moduleName + idSuffix, VisualBasicCodeSection.IMPORTS));
    }

    private boolean containsImports(List<String> lines) {
        return lines.stream().anyMatch(line -> line.trim().toLowerCase().startsWith("imports "));
    }

    private Collection<PxChunk> createMethodChunks(File f, List<String> codeLines, VisualBasicFileInfo fileInfo) {
        List<PxChunk> chunks = new ArrayList<>();
        for (MemberInfo member : fileInfo.members) {
            String id = fileInfo.memberId(member);
            if (member.docStartLine >= 0) {
                chunks.addAll(split(readLines(codeLines, member.docStartLine, member.startLine), member.docStartLine, member.startLine, () ->
                        createChunk(f, id, id + ".doc", VisualBasicCodeSection.METHOD_DOC)));
            }
            chunks.addAll(split(readLines(codeLines, member.startLine, member.endLine), member.startLine, member.endLine, () ->
                    createChunk(f, fileInfo.parentId(), id, VisualBasicCodeSection.METHOD)));
        }
        return chunks;
    }

    private Collection<PxChunk> createFrameChunk(File f, List<String> codeLines, VisualBasicFileInfo fileInfo) {
        StringBuilder content = new StringBuilder();
        int fromLine = fileInfo.containerInfo != null ? fileInfo.containerInfo.startLine : 0;
        int toLine = fileInfo.containerInfo != null ? fileInfo.containerInfo.endLine : codeLines.size();

        int line = 0;
        while (line < codeLines.size()) {
            if (isInsideMember(fileInfo.members, line)) {
                MemberInfo member = memberAt(fileInfo.members, line);
                content.append(codeLines.get(member.startLine)).append('\n');
                line = member.endLine;
                continue;
            }
            if (fileInfo.containerInfo == null || line < toLine) {
                content.append(codeLines.get(line)).append('\n');
            }
            line++;
        }
        return split(content, fromLine, toLine, () ->
                createChunk(f, null, fileInfo.parentId(), VisualBasicCodeSection.CLASS_FRAME));
    }

    private Collection<PxChunk> createFallbackFrameChunk(File f, List<String> codeLines, VisualBasicFileInfo fileInfo) {
        return split(readLines(codeLines, 0, codeLines.size()), 0, codeLines.size(), () ->
                createChunk(f, null, fileInfo.parentId(), VisualBasicCodeSection.CLASS_FRAME));
    }

    private boolean isInsideMember(List<MemberInfo> members, int line) {
        return memberAt(members, line) != null;
    }

    private MemberInfo memberAt(List<MemberInfo> members, int line) {
        for (MemberInfo member : members) {
            if (line >= member.startLine && line < member.endLine) {
                return member;
            }
        }
        return null;
    }

    private List<PxChunk> split(String content, int fromLine, int toLine, java.util.function.Supplier<PxChunk> chunkSupplier) {
        return new ContentSplitter(this.chunkSize, this.overlap).splitContent(content, fromLine, toLine, chunkSupplier);
    }

    private List<PxChunk> split(StringBuilder content, int fromLine, int toLine, java.util.function.Supplier<PxChunk> chunkSupplier) {
        return new ContentSplitter(this.chunkSize, this.overlap).splitContent(content, fromLine, toLine, chunkSupplier);
    }

    private PxChunk createChunk(File f, String parent, String id, VisualBasicCodeSection section) {
        return PxChunk.create(
                c -> c.setMimeType(VISUAL_BASIC_CODE_MIME_TYPE),
                c -> c.setParent(parent),
                c -> c.setId(id),
                c -> c.setFile(f.getAbsolutePath()),
                c -> c.getMetadata().put(MDKEY_CODESECTION, section.getName())
        );
    }

    private String readLines(List<String> codeLines, int from, int to) {
        StringBuilder content = new StringBuilder();
        for (int i = from; i < to && i < codeLines.size(); i++) {
            content.append(codeLines.get(i)).append('\n');
        }
        return content.toString();
    }

    private String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(0, dot) : fileName;
    }

    private String capitalize(String value) {
        String lower = value.toLowerCase();
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private record VisualBasicFileInfo(String moduleName, ContainerInfo containerInfo, List<MemberInfo> members) {
        String parentId() {
            return containerInfo == null ? moduleName : moduleName + "." + containerInfo.name;
        }

        String memberId(MemberInfo member) {
            return parentId() + "." + member.idName;
        }
    }

    private record ContainerInfo(String name, int startLine, int endLine) {
    }

    private record MemberInfo(String name, String idName, int startLine, int endLine, int docStartLine) {
    }
}
