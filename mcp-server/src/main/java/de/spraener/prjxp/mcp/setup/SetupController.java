package de.spraener.prjxp.mcp.setup;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/setup")
public class SetupController {
    private final SetupPersistenceService persistence;
    private final SetupModelTestService tests;

    public SetupController(SetupPersistenceService persistence, SetupModelTestService tests) {
        this.persistence = persistence; this.tests = tests;
    }

    @GetMapping("/status")
    public SetupStatus status() { return persistence.readStatus(); }

    @GetMapping("/configuration")
    public SetupConfiguration configuration() { return persistence.readConfiguration(); }

    @PostMapping("/test-embedding")
    public SetupTestResult testEmbedding(@Valid @RequestBody SetupTestRequest<EmbeddingConfigRequest> request) {
        return tests.testEmbedding(request);
    }

    @PostMapping("/test-chat")
    public SetupTestResult testChat(@Valid @RequestBody SetupTestRequest<ChatModelRequest> request) {
        return tests.testChat(request);
    }

    @PostMapping("/test-github")
    public SetupTestResult testGithub(@Valid @RequestBody SetupTestRequest<GithubConfigRequest> request) {
        return tests.testGithub(request);
    }

    @PostMapping("/save")
    public SetupStatus save(@Valid @RequestBody SetupSaveRequest request) { return persistence.save(request); }

    @PostMapping("/complete")
    public SetupStatus complete(@Valid @RequestBody SetupCompleteRequest request) { return persistence.complete(request); }
}
