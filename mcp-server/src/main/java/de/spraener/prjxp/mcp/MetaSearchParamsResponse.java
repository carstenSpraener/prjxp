package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.capability.LanguageCapability;
import de.spraener.prjxp.common.capability.SearchParamDef;

import java.util.List;
import java.util.Map;

public record MetaSearchParamsResponse(
        String version,
        List<SearchParamDef> globalParams,
        Map<String, LanguageCapability> languages) {
}
