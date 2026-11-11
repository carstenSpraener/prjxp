package de.spraener.prjxp.mcp;

import java.util.List;

/** Raised when a requested project has no searchable store in this server. */
public class UnknownProjectException extends RuntimeException {
    private final String project;
    private final List<String> available;

    public UnknownProjectException(String project, List<String> available) {
        super("No searchable store for project '" + project + "'. " + availableSuffix(available));
        this.project = project;
        this.available = List.copyOf(available);
    }

    /**
     * The project parameter was not specified at all. The project is required — there is no
     * implicit default project. The message lists the available projects so the caller can retry
     * with a concrete name (see {@code listProjects}).
     */
    public static UnknownProjectException required(List<String> available) {
        return new UnknownProjectException("(not specified)", available, true);
    }

    private UnknownProjectException(String project, List<String> available, boolean required) {
        super((required
                ? "Parameter 'project' is required (no implicit default). "
                : "No searchable store for project '" + project + "'. ")
                + availableSuffix(available));
        this.project = project;
        this.available = List.copyOf(available);
    }

    private static String availableSuffix(List<String> available) {
        return "Available projects: " + (available.isEmpty() ? "(none)" : String.join(", ", available));
    }

    public String getProject() { return project; }
    public List<String> getAvailable() { return available; }
}
