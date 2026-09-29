package de.spraener.prjxp.common.model;

import java.io.File;
import java.nio.file.Path;

public enum PxFileType {
    NONE(),
    JAVA_CODE(".java"),
    TYPESCRIPT_CODE(".ts"),
    VISUAL_BASIC_CODE(".vb", ".bas", ".cls", ".frm"),
    JSP(".jsp"),
    XML(".xml"),
    PDF(".pdf"),
    JAVA_SCRIPT(".js"),
    HTML(".html"),
    UNKNOWN(""),
    TXT(".txt"),
    WORD_DOCX(".docx"),
    WORD_DOC(".doc"),
    RTF(".rtf"),
    MARK_DOWN(".md");

    private final String[] endingMatches;

    PxFileType(String... endingMatches) {
        this.endingMatches = endingMatches;
    }

    public static PxFileType from(Path p) {
        String fileName = p.getFileName().toString();
        for (PxFileType type : values()) {
            if (type.endingMatches == null) {
                continue;
            }
            if (type.matches(fileName)) {
                return type;
            }
        }
        return UNKNOWN;
    }

    public boolean matches(File f) {
        return matches(f.getName());
    }

    private boolean matches(String fileName) {
        if (endingMatches == null) {
            return false;
        }
        for (String endingMatch : endingMatches) {
            if (endingMatch != null && fileName.endsWith(endingMatch)) {
                return true;
            }
        }
        return false;
    }
}
