package de.spraener.prjxp.mcp;

/** REST view of a project: name + lifecycle status (+ last error, nullable). */
public record ProjectInfo(String name, String status, String lastError) { }
