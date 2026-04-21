package de.spraener.prjxp.common.code.typescript;

public enum TypeScriptCodeSection {
    UNKNOWN("unknown"),
    IMPORTS("imports"),
    METHOD_DOC("methodDoc"),
    METHOD("method"),
    CLASS_FRAME("classFrame"),
    DEPENDENCIE_INFO("dependenciesInfo"),
    ;

    private String name;

    private TypeScriptCodeSection(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public static TypeScriptCodeSection fromName(String name) {
        for (TypeScriptCodeSection section : values()) {
            if (section.getName().equals(name)) {
                return section;
            }
        }
        return UNKNOWN;
    }
}

