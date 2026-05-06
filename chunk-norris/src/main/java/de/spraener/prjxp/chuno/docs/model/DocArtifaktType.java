package de.spraener.prjxp.chuno.docs.model;

import java.io.File;

public enum DocArtifaktType {
    UNKNOWN(""),
    RAW_IMAGE ("RAW"),
    PNG_IMG("PNG"),
    JPG_IMG("JPG", "JPEG"),
    HTML_TABLE ("HTML_TABLE"),
    LATEX_FORMULA ("LATEX_FORMULA"),
    WORD_DOC("DOCX", "DOC"),
    PDF("PDF"),
    HTML("HTML", "HTM"),
    TEXT("TXT", "TEXT"),
    RTF("RTF"),
    MARK_DOWN("MD"),
    BUFFERED_IMAGE("BUFFERED_IMAGE");

    private String[] names;
    private DocArtifaktType(String... name) {
        this.names = name;
    }

    public static DocArtifaktType fromFile(File f) {
        String ending = f.getName().substring(f.getName().lastIndexOf(".") + 1);
        for( var da : DocArtifaktType.values() ) {
            for( var name : da.names ) {
                if( name.equalsIgnoreCase(ending) ) {
                    return da;
                }
            }
        }
        return UNKNOWN;
    }
}
