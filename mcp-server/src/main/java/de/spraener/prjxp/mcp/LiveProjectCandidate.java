package de.spraener.prjxp.mcp;

import java.util.List;

public record LiveProjectCandidate(
    String name,
    String directory,
    Boolean hasConfigFile,
    List<String> languages
) {}
