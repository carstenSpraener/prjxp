package de.spraener.prjxp.common.code.java;

public enum JavaCodeSection {
    UNKNOWN("unknown"),
    IMPORTS("imports"),
    METHOD_DOC("methodDoc"),
    METHOD("method"),
    CONSTRUCTOR("constructor"),
    CLAZZ_FRAME("classFrame"),
    DEPENDENCIE_INFO("dependenciesInfo"),
    ;

    private String name;

    private JavaCodeSection(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public static JavaCodeSection fromName(String name) {
        for (JavaCodeSection section : values()) {
            if (section.getName().equals(name)) {
                return section;
            }
        }
        return UNKNOWN;
    }
}
