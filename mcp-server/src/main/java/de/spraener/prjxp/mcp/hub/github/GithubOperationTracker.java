package de.spraener.prjxp.mcp.hub.github;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory record of recent async GitHub operations, for WebUI feedback. Transient by design:
 * lost on restart, capped in size. The WebUI polls {@link #recent()} after triggering an
 * operation until its key resolves.
 */
@Component
@ConditionalOnProperty(name = "prjxp.hub.enabled", havingValue = "true")
public class GithubOperationTracker {

    private static final int MAX_OPERATIONS = 100;

    private final Map<String, GithubOperation> operations = new ConcurrentHashMap<>();

    /** Marks the key as in-flight (called when the async operation is scheduled). */
    public void start(String key) {
        put(new GithubOperation(key, GithubOperation.PENDING, null, System.currentTimeMillis()));
    }

    /** Records the outcome (called when the async operation finishes). */
    public void complete(String key, boolean success, String message) {
        put(new GithubOperation(key, success ? GithubOperation.SUCCESS : GithubOperation.FAILED,
                message, System.currentTimeMillis()));
    }

    public Optional<GithubOperation> latest(String key) {
        return Optional.ofNullable(operations.get(key));
    }

    /** All recorded operations, newest first, capped at 50 (the UI only needs recent ones). */
    public List<GithubOperation> recent() {
        return operations.values().stream()
                .sorted(Comparator.comparingLong(GithubOperation::timestamp).reversed())
                .limit(50)
                .toList();
    }

    private void put(GithubOperation op) {
        operations.put(op.key(), op);
        if (operations.size() > MAX_OPERATIONS) {
            operations.values().stream()
                    .sorted(Comparator.comparingLong(GithubOperation::timestamp))
                    .limit(operations.size() - MAX_OPERATIONS)
                    .forEach(old -> operations.remove(old.key()));
        }
    }
}
