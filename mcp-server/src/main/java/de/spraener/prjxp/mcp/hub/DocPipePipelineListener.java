package de.spraener.prjxp.mcp.hub;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.docpipe.DocPipeManifestWriter;
import de.spraener.prjxp.docpipe.DocPipeRunException;
import de.spraener.prjxp.docpipe.DocPipeRunner;
import de.spraener.prjxp.docpipe.config.JobCreationService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Hub-side DocPipe integration: after a root pipeline run finishes, generates documentation for
 * the project (if it has .dp/ configuration) and schedules a document-embedding retrigger run.
 * DocPipe failures are NON-FATAL: the project still becomes READY (the code index is complete);
 * whatever documents were produced are embedded.
 */
@Component
@ConditionalOnProperty(name = "prjxp.hub.enabled", havingValue = "true")
@RequiredArgsConstructor
public class DocPipePipelineListener implements PipelineListener {

    private static final Logger log = LoggerFactory.getLogger(DocPipePipelineListener.class);

    private final JobCreationService jobCreationService;
    private final DocPipeRunner docPipeRunner;
    private final HubProjectRegistry registry;
    private final ObjectMapper objectMapper;

    @Override
    public void onPipelineStart(PipelineOrchestrator orchestrator, String projectName, PipelineRun run) {
        // no-op: DocPipe acts on finished root runs only (the ledger prevents re-runs in retriggers)
    }

    @Override
    public void onPipelineFinished(PipelineOrchestrator orchestrator, String projectName, PipelineRun run) {
        ProjectDefinition def = registry.definitionOf(projectName);
        if (def == null) { return; }

        // Opt-in: the project must have .dp/ configuration (DocPipe's own convention).
        boolean hasJobs;
        try {
            hasJobs = jobCreationService.readJobs(Optional.of(def)).findFirst().isPresent();
        } catch (IOException e) {
            log.warn("DocPipe: could not read .dp/ configuration for '{}': {} — skipping", projectName, e.getMessage());
            return;
        }
        if (!hasJobs) { return; }

        registry.setReindexStatus(projectName, ReindexStatus.DOCPIPE);
        registry.setReindexProgress(projectName, 0);

        boolean docPipeOk = true;
        try {
            docPipeRunner.runForProject(def);   // inline, synchronous (own thread pool)
        } catch (DocPipeRunException e) {
            docPipeOk = false;
            log.warn("DocPipe run for '{}' failed (non-fatal): {}", projectName, e.getMessage());
        } catch (Exception e) {
            docPipeOk = false;
            log.warn("DocPipe run for '{}' failed (non-fatal): {}", projectName, e.toString());
        }

        Path manifest = Path.of(def.getRootDir()).resolve(DocPipeManifestWriter.MANIFEST_RELATIVE_PATH);
        if (Files.isRegularFile(manifest)) {
            orchestrator.scheduleEmbedding(projectName, new ManifestDocumentSource(manifest, objectMapper));
        } else if (!docPipeOk) {
            log.warn("DocPipe for '{}' produced no manifest — document embedding skipped", projectName);
        }
    }
}
