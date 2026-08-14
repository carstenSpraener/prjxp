package de.spraener.prjxp.gldrtrvr.code.visualbasic;

import de.spraener.prjxp.common.code.visualbasic.VisualBasicCodeSection;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import de.spraener.prjxp.gldrtrvr.GoldenRetriever;
import de.spraener.prjxp.gldrtrvr.chunks.ChunkRankingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Log
public class VisualBasicRetriever implements GoldenRetriever {
    private static final String METADATA_KEY = "visualbasic_code_section";
    private static final String CODE_BLOCK_LANGUAGE = "vb";
    private static final Pattern SIGNATURE_PATTERN = Pattern.compile(
            "(?i)^(?:(?:Public|Private|Protected|Friend|Static|Shared|Overridable|Overrides|Overloads|MustOverride|Async|Iterator)\\s+)*(?:Sub|Function|Property(?:\\s+(?:Get|Let|Set))?)\\s+.*"
    );
    private static final Pattern END_MEMBER_LINE_PATTERN = Pattern.compile("(?i)^\\s*End\\s+(Sub|Function|Property)\\b.*");
    private final PxChunkDaoProvider chunkDaoProvider;
    private final ChunkRankingService rankingService;

    @SafeVarargs
    public final StringBuilder buildPromptForFindings(String projectName, List<PxChunk> chunks, Function<String, Boolean>... contextValidators) {
        StringBuilder prompt = new StringBuilder();
        PxChunkDao chunkDao = chunkDaoProvider.get(projectName).orElseThrow();
        List<PxChunk> visualBasicChunks = combineChunksByID(chunkDao, chunks);
        if (visualBasicChunks.isEmpty()) {
            return prompt;
        }
        VisualBasicPromptSession session = new VisualBasicPromptSession(chunkDao, rankingService);
        session.setChunks(visualBasicChunks);
        prompt.append(session.buildPrompt(this::modifyPromptByChunk, contextValidators));
        return prompt;
    }

    private String modifyPromptByChunk(PxChunkDao chunkDao, PxChunk pxChunk, String prompt) {
        if (!isVisualBasicChunk(pxChunk)) {
            return prompt;
        }
        VisualBasicCodeSection section = VisualBasicCodeSection.fromName(pxChunk.getMetadata().get(METADATA_KEY));
        return switch (section) {
            case IMPORTS -> addImports(chunkDao, pxChunk, prompt);
            case METHOD_DOC -> addMethodDoc(chunkDao, pxChunk, prompt);
            case METHOD -> addMethod(chunkDao, pxChunk, prompt);
            case CLASS_FRAME -> addClassFrame(chunkDao, pxChunk, prompt);
            default -> prompt;
        };
    }

    private String addImports(PxChunkDao chunkDao, PxChunk importsChunk, String prompt) {
        Optional<PxChunk> frame = findFrameForImports(chunkDao, importsChunk);
        if (frame.isPresent() && prompt.contains(headingForFrame(frame.get()))) {
            return insertBefore(prompt, headingForFrame(frame.get()), importsBlock(importsChunk));
        }
        return appendBlock(prompt, "## VisualBasic-Imports " + importsChunk.getId(), importsChunk.getContent());
    }

    private Optional<PxChunk> findFrameForImports(PxChunkDao chunkDao, PxChunk importsChunk) {
        String moduleName = baseId(importsChunk.getId());
        PxChunk exact = PxChunk.combine(chunkDao.findById(moduleName + "." + moduleName));
        if (exact != null) {
            return Optional.of(exact);
        }
        PxChunk byParent = PxChunk.combine(chunkDao.findById(importsChunk.getParent()));
        if (byParent != null) {
            return Optional.of(byParent);
        }
        return Optional.empty();
    }

    private String addMethodDoc(PxChunkDao chunkDao, PxChunk docChunk, String prompt) {
        PxChunk methodChunk = PxChunk.combine(chunkDao.findById(docChunk.getParent()));
        if (methodChunk != null) {
            Optional<String> signature = firstSignatureLine(methodChunk);
            if (signature.isPresent() && containsSignature(prompt, signature.get())) {
                return insertBeforeSignature(prompt, signature.get(), docChunk.getContent());
            }
        }
        return appendBlock(prompt, "## VisualBasic-Dokumentation " + docChunk.getId(), docChunk.getContent());
    }

    private String addMethod(PxChunkDao chunkDao, PxChunk methodChunk, String prompt) {
        PxChunk doc = PxChunk.combine(chunkDao.findById(methodChunk.getId() + ".doc"));
        String nextPrompt = prompt;
        Optional<String> signature = firstSignatureLine(methodChunk);
        if (doc != null && signature.isPresent() && containsSignature(nextPrompt, signature.get())) {
            nextPrompt = insertBeforeSignature(nextPrompt, signature.get(), doc.getContent());
        }
        if (signature.isPresent() && containsSignature(nextPrompt, signature.get())) {
            return replaceFirstSignature(nextPrompt, signature.get(), methodChunk.getContent());
        }
        return appendBlock(nextPrompt, "## VisualBasic-Methode " + methodChunk.getId(), methodChunk.getContent());
    }

    private String addClassFrame(PxChunkDao chunkDao, PxChunk frameChunk, String prompt) {
        String frameBlock = "\n\n" + headingForFrame(frameChunk) + "\n\n```" + CODE_BLOCK_LANGUAGE + "\n" + frameChunk.getContent() + "\n```\n";
        String imports = findImportsForFrame(chunkDao, frameChunk)
                .map(this::importsBlock)
                .orElse("");
        return prompt + imports + frameBlock;
    }

    private Optional<PxChunk> findImportsForFrame(PxChunkDao chunkDao, PxChunk frameChunk) {
        String frameId = frameChunk.getId();
        List<String> candidates = new ArrayList<>();
        candidates.add(baseId(frameId) + ".imports");
        int dotIndex = frameId.lastIndexOf('.');
        if (dotIndex > 0) {
            candidates.add(frameId.substring(0, dotIndex) + ".imports");
        }
        for (String candidate : candidates) {
            PxChunk imports = PxChunk.combine(chunkDao.findById(candidate));
            if (imports != null) {
                return Optional.of(imports);
            }
        }
        return Optional.empty();
    }

    private String importsBlock(PxChunk importsChunk) {
        return "\n\n## VisualBasic-Imports " + importsChunk.getId() + "\n\n```" + CODE_BLOCK_LANGUAGE + "\n" + importsChunk.getContent() + "\n```\n";
    }

    private String headingForFrame(PxChunk frameChunk) {
        return "## Hier ein Rumpf des VisualBasic-Typs " + frameChunk.getId() + ":";
    }

    private String appendBlock(String prompt, String heading, String content) {
        return prompt + "\n\n" + heading + "\n\n```" + CODE_BLOCK_LANGUAGE + "\n" + content + "\n```\n";
    }

    private boolean containsSignature(String prompt, String signature) {
        return prompt.contains(signature) || normalizedIndexOf(prompt, signature) >= 0;
    }

    private String insertBeforeSignature(String prompt, String signature, String content) {
        int idx = prompt.indexOf(signature);
        if (idx >= 0) {
            return prompt.substring(0, idx) + content + prompt.substring(idx);
        }
        return insertBeforeNormalized(prompt, signature, content).orElse(prompt);
    }

    private String replaceFirstSignature(String prompt, String signature, String content) {
        int idx = prompt.indexOf(signature);
        if (idx >= 0) {
            int end = extendSimpleStubEnd(prompt, idx + signature.length());
            return prompt.substring(0, idx) + content + prompt.substring(end);
        }
        Optional<Range> normalizedRange = normalizedRangeOf(prompt, signature);
        return normalizedRange
                .map(range -> prompt.substring(0, range.start()) + content + prompt.substring(extendSimpleStubEnd(prompt, range.end())))
                .orElse(prompt);
    }

    private int extendSimpleStubEnd(String prompt, int signatureEnd) {
        int cursor = signatureEnd;
        while (cursor < prompt.length() && (prompt.charAt(cursor) == ' ' || prompt.charAt(cursor) == '\t')) {
            cursor++;
        }
        if (cursor < prompt.length() && prompt.charAt(cursor) == '\r') {
            cursor++;
        }
        if (cursor < prompt.length() && prompt.charAt(cursor) == '\n') {
            cursor++;
        }

        int lineStart = cursor;
        while (lineStart < prompt.length()) {
            int lineEnd = prompt.indexOf('\n', lineStart);
            if (lineEnd < 0) {
                lineEnd = prompt.length();
            }
            String line = prompt.substring(lineStart, lineEnd).strip();
            if (line.isEmpty()) {
                lineStart = lineEnd < prompt.length() ? lineEnd + 1 : lineEnd;
                continue;
            }
            if (END_MEMBER_LINE_PATTERN.matcher(line).matches()) {
                return lineEnd < prompt.length() ? lineEnd + 1 : lineEnd;
            }
            return signatureEnd;
        }
        return signatureEnd;
    }

    private String insertBefore(String prompt, String marker, String content) {
        int idx = prompt.indexOf(marker);
        if (idx < 0) {
            return prompt + content;
        }
        return prompt.substring(0, idx) + content + prompt.substring(idx);
    }

    private Optional<String> insertBeforeNormalized(String prompt, String signature, String content) {
        return normalizedRangeOf(prompt, signature)
                .map(range -> prompt.substring(0, range.start()) + content + prompt.substring(range.start()));
    }

    private int normalizedIndexOf(String text, String needle) {
        return normalizedRangeOf(text, needle).map(Range::start).orElse(-1);
    }

    private Optional<Range> normalizedRangeOf(String text, String needle) {
        List<Token> textTokens = tokens(text);
        List<Token> needleTokens = tokens(needle);
        if (needleTokens.isEmpty() || textTokens.size() < needleTokens.size()) {
            return Optional.empty();
        }
        for (int i = 0; i <= textTokens.size() - needleTokens.size(); i++) {
            boolean matches = true;
            for (int j = 0; j < needleTokens.size(); j++) {
                if (!textTokens.get(i + j).value().equals(needleTokens.get(j).value())) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return Optional.of(new Range(textTokens.get(i).start(), textTokens.get(i + needleTokens.size() - 1).end()));
            }
        }
        return Optional.empty();
    }

    private List<Token> tokens(String text) {
        List<Token> result = new ArrayList<>();
        java.util.regex.Matcher matcher = Pattern.compile("\\S+").matcher(text);
        while (matcher.find()) {
            result.add(new Token(matcher.group(), matcher.start(), matcher.end()));
        }
        return result;
    }

    private Optional<String> firstSignatureLine(PxChunk chunk) {
        return chunk.getContent().lines()
                .map(String::strip)
                .filter(line -> SIGNATURE_PATTERN.matcher(line).matches())
                .findFirst();
    }

    private String baseId(String id) {
        int dotIndex = id.indexOf('.');
        return dotIndex < 0 ? id : id.substring(0, dotIndex);
    }

    private List<PxChunk> combineChunksByID(PxChunkDao chunkDao, List<PxChunk> chunks) {
        Map<String, List<PxChunk>> chunkMap = new HashMap<>();
        for (var c : chunks) {
            if (isVisualBasicChunk(c)) {
                List<PxChunk> idList = chunkMap.computeIfAbsent(c.getId(), k -> new ArrayList<>());
                idList.add(c);
            }
        }
        List<PxChunk> result = new ArrayList<>();
        for (var chunkList : chunkMap.values()) {
            PxChunk c = chunkList.getFirst();
            if (c.getTotal() > chunkList.size()) {
                PxChunk combinedChunk = combineChunks(chunkDao.findById(c.getId()));
                if (combinedChunk != null) {
                    result.add(combinedChunk);
                } else {
                    log.warning("The chunk [id='" + c.getId() + "'] to combine does not exist in the embedding store. Check your configuration.");
                }
            } else {
                PxChunk combinedChunk = combineChunks(chunkList);
                if (combinedChunk != null) {
                    result.add(combinedChunk);
                }
            }
        }
        return result;
    }

    private boolean isVisualBasicChunk(PxChunk c) {
        return c != null && c.getMetadata().containsKey(METADATA_KEY);
    }

    private PxChunk combineChunks(List<PxChunk> chunkList) {
        return PxChunk.combine(chunkList);
    }

    private record Token(String value, int start, int end) {
    }

    private record Range(int start, int end) {
    }
}
