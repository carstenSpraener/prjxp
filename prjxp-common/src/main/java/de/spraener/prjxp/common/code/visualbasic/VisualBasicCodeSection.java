package de.spraener.prjxp.common.code.visualbasic;

public enum VisualBasicCodeSection {
    UNKNOWN("unknown"),
    IMPORTS("imports"),
    METHOD_DOC("methodDoc"),
    METHOD("method"),
    CLASS_FRAME("classFrame"),
    ;

    private String name;

    private VisualBasicCodeSection(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public static VisualBasicCodeSection fromName(String name) {
        for (VisualBasicCodeSection section : values()) {
            if (section.getName().equals(name)) {
                return section;
            }
        }
        return UNKNOWN;
    }
}
