package de.spraener.prjxp.mcp.hub;

import de.spraener.prjxp.mcp.ProjectInfo;
import de.spraener.prjxp.mcp.ProjectRegistry;
import de.spraener.prjxp.mcp.UnknownProjectException;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST endpoints for hub project management (lifecycle overview, deletion and reindex).
 * Active only in hub mode; the registry dependency resolves to {@link HubProjectRegistry}.
 */
@RestController
@RequestMapping("/prjxp/projects")
@ConditionalOnProperty(name = "prjxp.hub.enabled", havingValue = "true")
@RequiredArgsConstructor
public class HubProjectsController {

    private final ProjectRegistry projectRegistry;   // interface — resolves to HubProjectRegistry in hub mode
    private final ProjectLifecycleService lifecycle;
    private final PipelineOrchestrator orchestrator;

    @GetMapping
    public List<ProjectInfo> list() {
        return projectRegistry.projectInfos();
    }

    /**
     * Deletes a project from the hub. SNAPSHOT: scoped index wipe + recursive removal of the (hub-managed)
     * project directory. LIVE: scoped index wipe + unregistration only — the source tree belongs to the user
     * and is never touched. Note: for LIVE projects this is temporary by design — the marker file survives,
     * so the import poller re-discovers and re-enqueues the project on its next cycle. Permanent removal of
     * a live project = delete/rename its prjxp.yaml (or move it out of the import tree).
     */
    @DeleteMapping("/{name}")
    public ResponseEntity<Void> delete(@PathVariable("name") String name) {
        lifecycle.delete(name);
        return ResponseEntity.noContent().build();
    }

    /** Full pipeline re-run (chunk → scoped reset + embed) for a known project; the status walks READY/FAILED → CHUNKING → … */
    @PostMapping("/{name}/reindex")
    public ResponseEntity<Void> reindex(@PathVariable("name") String name) {
        if ("UNKNOWN".equals(projectRegistry.statusOf(name))) {
            throw new UnknownProjectException(name, projectRegistry.availableProjects());   // same error behavior as DELETE
        }
        orchestrator.enqueue(name);
        return ResponseEntity.accepted().build();   // the pipeline runs asynchronously on the single worker
    }
}
