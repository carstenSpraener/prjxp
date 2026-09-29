package de.spraener.prjxp.docpipe.prompt;

import org.apache.commons.io.IOUtils;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class VisualBasicSourceSkeletonizer implements SourceSkeletonizer {
    private static final Set<String> SUPPORTED_ENDINGS = Set.of("vb", "bas", "cls", "frm");
    private static final Pattern MEMBER_PATTERN = Pattern.compile("^\\s*(?:(?:Public|Private|Protected|Friend|Static|Shared|Overridable|Overrides|Overloads|MustOverride|Async|Iterator)\\s+)*(Sub|Function|Property(?:\\s+(?:Get|Let|Set))?)\\b.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern END_MEMBER_PATTERN = Pattern.compile("^\\s*End\\s+(Sub|Function|Property)\\b", Pattern.CASE_INSENSITIVE);

    @Override
    public boolean supports(File sourceFile, String ending) {
        String normalizedEnding = normalizeEnding(ending);
        if (!SUPPORTED_ENDINGS.contains(normalizedEnding)) {
            return false;
        }
        return sourceFile.getName().toLowerCase().endsWith("." + normalizedEnding);
    }

    @Override
    public String skeletonize(File sourceFile) throws Exception {
        try (FileInputStream fis = new FileInputStream(sourceFile)) {
            String content = IOUtils.toString(fis, StandardCharsets.UTF_8);
            return skeletonize(content);
        }
    }

    String skeletonize(String content) {
        List<String> lines = content.lines().toList();
        StringBuilder skeleton = new StringBuilder();
        boolean insideMember = false;
        String currentEndKind = null;
        String currentIndent = "";
        for (String line : lines) {
            if (!insideMember) {
                Matcher memberMatcher = MEMBER_PATTERN.matcher(line);
                if (memberMatcher.matches()) {
                    skeleton.append(line).append('\n');
                    insideMember = true;
                    currentEndKind = endKindFor(memberMatcher.group(1));
                    currentIndent = leadingWhitespace(line);
                } else {
                    skeleton.append(line).append('\n');
                }
                continue;
            }

            Matcher endMatcher = END_MEMBER_PATTERN.matcher(line);
            if (endMatcher.matches() && endMatcher.group(1).equalsIgnoreCase(currentEndKind)) {
                skeleton.append(currentIndent).append(line.trim()).append('\n');
                insideMember = false;
                currentEndKind = null;
                currentIndent = "";
            }
        }
        return skeleton.toString();
    }

    private String endKindFor(String memberKind) {
        return memberKind.toLowerCase().startsWith("property") ? "Property" : capitalize(memberKind);
    }

    private String capitalize(String value) {
        String lower = value.toLowerCase();
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private String leadingWhitespace(String line) {
        int idx = 0;
        while (idx < line.length() && Character.isWhitespace(line.charAt(idx))) {
            idx++;
        }
        return line.substring(0, idx);
    }

    private String normalizeEnding(String ending) {
        String normalized = ending == null ? "" : ending.toLowerCase();
        return normalized.startsWith(".") ? normalized.substring(1) : normalized;
    }
}
