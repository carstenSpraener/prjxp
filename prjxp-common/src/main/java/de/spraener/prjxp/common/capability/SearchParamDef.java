package de.spraener.prjxp.common.capability;

public record SearchParamDef(String name, String description, boolean required) {

    public static SearchParamDef optional(String name, String description) {
        return new SearchParamDef(name, description, false);
    }

    public static SearchParamDef required(String name, String description) {
        return new SearchParamDef(name, description, true);
    }
}
