package de.spraener.prjxp.mcp.hub;

import de.spraener.prjxp.mcp.ProjectInfo;
import de.spraener.prjxp.mcp.ProjectRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST endpoints for hub project management (lifecycle overview + deletion).
 * Active only in hub mode; the registry dependency resolves to {@link HubProjectRegistry}.
 */
@RestController
@RequestMapping("/prjxp/projects")
@ConditionalOnProperty(name = "prjxp.hub.enabled", havingValue = "true")
@RequiredArgsConstructor
public class HubProjectsController {

    private final ProjectRegistry projectRegistry;   // interface — resolves to HubProjectRegistry in hub mode
    private final ProjectLifecycleService lifecycle;

    @GetMapping
    public List<ProjectInfo> list() {
        return projectRegistry.projectInfos();
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Void> delete(@PathVariable("name") String name) {
        lifecycle.delete(name);
        return ResponseEntity.noContent().build();
    }
}
