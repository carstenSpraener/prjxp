package de.spraener.prjxp.common.capability;

import java.util.List;

public record LanguageCapability(
        String language,
        List<String> mimeTypes,
        List<String> symbolTypes,
        List<SearchParamDef> params,
        IdentifierRules identifierRules) {
}
