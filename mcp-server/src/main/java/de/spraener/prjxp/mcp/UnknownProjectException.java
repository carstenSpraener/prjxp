package de.spraener.prjxp.mcp;

import java.util.List;

/** Raised when a requested project has no searchable store in this server. */
public class UnknownProjectException extends RuntimeException {
    private final String project;
    private final List<String> available;

    public UnknownProjectException(String project, List<String> available) {
        super("No searchable store for project '" + project + "'. Available projects: "
                + (available.isEmpty() ? "(none)" : String.join(", ", available)));
        this.project = project;
        this.available = List.copyOf(available);
    }

    public String getProject() { return project; }
    public List<String> getAvailable() { return available; }
}
