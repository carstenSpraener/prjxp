package de.spraener.prjxp.mcp.setup;

import de.spraener.prjxp.common.chat.ChatModelSupplier;
import de.spraener.prjxp.common.chat.KIChatModelProvider;
import de.spraener.prjxp.common.embedding.EmbeddingModelFactory;
import de.spraener.prjxp.mcp.hub.*;
import de.spraener.prjxp.mcp.hub.github.TokenCipher;
import de.spraener.prjxp.mcp.hub.github.GithubApiClient;
import de.spraener.prjxp.mcp.hub.github.GithubTokenResolver;
import de.spraener.prjxp.mcp.hub.github.GithubImporter;
import de.spraener.prjxp.mcp.hub.github.GithubUpdater;
import de.spraener.prjxp.mcp.hub.github.GithubGitClient;
import de.spraener.prjxp.mcp.hub.github.GithubOperationTracker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SetupContextTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void setupAndPropertiesExistWithHubOnOrOffWithoutProviderCalls(boolean enabled) throws Exception {
        Path yaml = directory.resolve("application.yaml"), env = directory.resolve(".env");
        Files.writeString(yaml, "prjxp: {}\n");
        ChatModelSupplier supplier = mock(ChatModelSupplier.class);
        new ApplicationContextRunner().withSystemProperties("PRJXP_CONFIG_FILE=" + yaml, "PRJXP_ENV_FILE=" + env)
                .withPropertyValues("prjxp.hub.enabled=" + enabled)
                .withBean(ChatModelSupplier.class, () -> supplier)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(KIChatModelProvider.class, () -> mock(KIChatModelProvider.class))
                .withBean(EmbeddingModelFactory.class, () -> mock(EmbeddingModelFactory.class))
                .withBean(HubProjectRegistry.class, () -> mock(HubProjectRegistry.class))
                .withBean(PipelineOrchestrator.class, () -> mock(PipelineOrchestrator.class))
                .withBean(ProjectConfigFileParser.class, ProjectConfigFileParser::new)
                .withUserConfiguration(ConfigurationStore.class, SetupTestEvidenceService.class,
                        SetupPersistenceService.class, HubPropertiesConfiguration.class, HubConfig.class, TokenCipher.class,
                        GithubTokenResolver.class, GithubApiClient.class, GithubGitClient.class, GithubOperationTracker.class,
                        GithubImporter.class, GithubUpdater.class, SetupModelTestService.class, SetupController.class)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(HubProperties.class)
                            .hasSingleBean(SetupController.class).hasSingleBean(SetupModelTestService.class)
                            .hasSingleBean(SetupPersistenceService.class).hasSingleBean(ConfigurationStore.class);
                    assertThat(context.getBean(HubProperties.class).isEnabled()).isEqualTo(enabled);
                    assertThat(context.getBeansOfType(TokenCipher.class)).hasSize(enabled ? 1 : 0);
                    assertThat(context.getBeansOfType(GithubTokenResolver.class)).hasSize(enabled ? 1 : 0);
                    assertThat(context.getBeansOfType(GithubApiClient.class)).hasSize(enabled ? 1 : 0);
                    assertThat(context.getBeansOfType(GithubImporter.class)).hasSize(enabled ? 1 : 0);
                    assertThat(context.getBeansOfType(GithubUpdater.class)).hasSize(enabled ? 1 : 0);
                    var service = context.getBean(SetupPersistenceService.class);
                    assertThat(service.getApplicationYamlPath()).isEqualTo(yaml);
                    assertThat(service.getEnvFilePath()).isEqualTo(env);
                    assertThat(service.readConfiguration().githubEnabled()).isEqualTo(enabled);
                    assertThat(service.readStatus().completed()).isFalse();
                    assertThat(service.readStatus().embedding().configured()).isFalse();
                    verifyNoInteractions(supplier);
                    verifyNoInteractions(context.getBean(EmbeddingModelFactory.class),
                            context.getBean(KIChatModelProvider.class));
                });
    }
}
