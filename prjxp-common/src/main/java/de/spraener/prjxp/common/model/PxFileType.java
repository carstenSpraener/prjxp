package de.spraener.prjxp.common.model;

import java.io.File;
import java.nio.file.Path;

public enum PxFileType {
    NONE(null),
    JAVA_CODE(".java"),
    JSP(".jsp"),
    XML(".xml"),
    PDF(".pdf"),
    JAVA_SCRIPT(".js"),
    HTML(".html"),
    UNKNOWN(""),
    TXT(".txt")
    ;

    private final String endingMatch;

    PxFileType(String endingMatch) {
        this.endingMatch = endingMatch;
    }

    public static PxFileType from(Path p) {
        String fileName = p.getFileName().toString();
        for (PxFileType type : values()) {
            if (type.endingMatch == null) {
                continue;
            }
            if (fileName.endsWith(type.endingMatch)) {
                return type;
            }
        }
        return UNKNOWN;
    }

    public boolean matches(File f) {
        return f.getName().endsWith(endingMatch);
    }
}
