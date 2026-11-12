package de.spraener.prjxp.mcp.setup;

import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.mcp.hub.*;
import de.spraener.prjxp.mcp.hub.github.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.*;
import java.time.Clock;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GithubFallbackIntegrationTest {
    @TempDir Path directory;

    @Test
    void savedDefaultFlowsIntoCloneAndYamlAndProjectTokenWinsForPull() throws Exception {
        Files.writeString(directory.resolve("application.yaml"), "prjxp: {hub: {enabled: true, github-token-key-env: KEY}}\n");
        Files.writeString(directory.resolve(".env"), "KEY=passphrase\nPROJECT_PAT=project-env-pat\n");
        var store = new ConfigurationStore(directory.resolve("application.yaml"), directory.resolve(".env"),
                new MockEnvironment(), Clock.systemUTC());
        var cipher = new TokenCipher(new HubProperties(), new MockEnvironment(), store);
        var tokens = new GithubTokenResolver(store, cipher);
        var persistence = new SetupPersistenceService(store, new SetupTestEvidenceService(), List.of(), () -> cipher, () -> false);
        HubProperties staleProperties = new HubProperties();
        staleProperties.setGithubRoot(directory.resolve("clones").toString());
        staleProperties.setGithubJsonlDir(directory.resolve("chunks").toString());
        GithubGitClient git = mock(GithubGitClient.class);
        doAnswer(invocation -> {
            Path target = invocation.getArgument(1);
            Files.createDirectories(target.resolve(".git"));
            return null;
        }).when(git).clone(anyString(), any(), nullable(String.class));
        HubProjectRegistry registry = mock(HubProjectRegistry.class);
        PipelineOrchestrator pipeline = mock(PipelineOrchestrator.class);
        var parser = new ProjectConfigFileParser();
        var importer = new GithubImporter(staleProperties, git, parser, registry, pipeline,
                new GithubOperationTracker(), cipher, tokens);
        var updater = new GithubUpdater(registry, git, pipeline, new GithubOperationTracker(), tokens);
        try {
            save(persistence, "global-one");
            importer.importFromUrl("https://github.com/example/first");
            Path first = directory.resolve("clones").resolve("first");
            verify(git).clone("https://github.com/example/first", first, "global-one");
            ProjectDefinition definition = parser.parse(first, "first");
            assertThat(cipher.decrypt(definition.getGitToken())).isEqualTo("global-one");
            assertThat(Files.readString(first.resolve("prjxp.yaml"))).doesNotContain("global-one");
            save(persistence, "global-two");
            importer.importFromUrl("https://github.com/example/second");
            verify(git).clone("https://github.com/example/second", directory.resolve("clones").resolve("second"), "global-two");
            importer.importFromUrl("https://github.com/example/explicit", "explicit-pat");
            verify(git).clone("https://github.com/example/explicit", directory.resolve("clones").resolve("explicit"), "explicit-pat");
            importer.importFromUrl("https://github.com/example/explicit-empty", "");
            verify(git).clone("https://github.com/example/explicit-empty",
                    directory.resolve("clones").resolve("explicit-empty"), null);
            assertThatThrownBy(() -> importer.importFromUrl("https://github.com/example/invalid", "${ABSENT_PHASE04_PAT}"))
                    .isInstanceOf(TokenCipherException.class);
            verify(git, never()).clone(eq("https://github.com/example/invalid"), any(), any());

            when(registry.entry("first")).thenReturn(Optional.of(
                    new ProjectEntry("first", first, ProjectEntry.Kind.SNAPSHOT, "github", definition, null)));
            updater.update("first");
            verify(git).pull(first, "global-one");
            definition.setGitToken("${PROJECT_PAT}");
            updater.update("first");
            verify(git).pull(first, "project-env-pat");
            definition.setGitToken(null);
            updater.update("first");
            verify(git).pull(first, "global-two");
            definition.setGitToken("v1:corrupt-sensitive-token");
            assertThatThrownBy(() -> updater.update("first")).isInstanceOf(TokenCipherException.class)
                    .hasMessageNotContaining("corrupt-sensitive-token");
            verify(git, times(3)).pull(eq(first), any());

            save(persistence, "");
            importer.importFromUrl("https://github.com/example/public");
            verify(git).clone("https://github.com/example/public", directory.resolve("clones").resolve("public"), null);
        } finally { importer.shutdown(); updater.shutdown(); }
    }

    private void save(SetupPersistenceService service, String token) {
        service.save(new SetupSaveRequest(service.readStatus().configVersion(), "github", false, null,
                null, null, new GithubConfigRequest("KEY", token), null));
    }
}
