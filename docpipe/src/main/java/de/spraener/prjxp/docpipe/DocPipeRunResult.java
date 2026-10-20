package de.spraener.prjxp.docpipe;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Thread-safe per-run state: outputs written and failures recorded by ContentCreationService. */
public class DocPipeRunResult {

    public record Failure(String task, String message) {}

    private final List<String> outputs = new CopyOnWriteArrayList<>();
    private final List<Failure> failures = new CopyOnWriteArrayList<>();

    public void recordOutput(String absolutePath) { outputs.add(absolutePath); }
    public void recordFailure(String task, String message) { failures.add(new Failure(task, message)); }
    public List<String> outputs() { return List.copyOf(outputs); }
    public List<Failure> failures() { return List.copyOf(failures); }
    public boolean hasFailures() { return !failures.isEmpty(); }
}
