package de.spraener.prjxp.mcp.setup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

class ConfigurationStoreTest {
    @TempDir Path directory;

    ConfigurationStore store() {
        return new ConfigurationStore(directory.resolve("application.yaml"), directory.resolve(".env"),
                new MockEnvironment(), Clock.systemUTC());
    }

    @Test
    void createsMissingConfigurationAndPublishesOnlyCommittedSnapshot() throws IOException {
        ConfigurationStore store = store();
        var snapshot = store.readSnapshot();
        assertThat(snapshot.configVersion()).doesNotContain("prjxp");
        var updated = store.update(snapshot.configVersion(), current ->
                new ConfigurationStore.Update(Map.of("prjxp", Map.of("setup-completed", true)), Map.of()));
        assertThat(updated.root()).containsEntry("prjxp", Map.of("setup-completed", true));
        assertThat(updated.configVersion()).isNotEqualTo(snapshot.configVersion());
        assertThat(directory.resolve("application.yaml")).exists();
        assertThat(store.getApplicationYamlPath()).isEqualTo(directory.resolve("application.yaml"));
        assertThat(store.getEnvFilePath()).isEqualTo(directory.resolve(".env"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "[]", "prjxp: [wrong]", "prjxp: {a: 1, a: 2}",
            "prjxp: !!java.util.HashMap {}", "prjxp: {}\n---\nprjxp: {}", "prjxp: ["})
    void rejectsMalformedYamlWithoutReplacingIt(String yaml) throws IOException {
        Files.writeString(directory.resolve("application.yaml"), yaml);
        assertThatThrownBy(() -> store().readSnapshot()).isInstanceOf(SetupException.class)
                .hasMessageNotContaining("java.util.HashMap");
        assertThat(Files.readString(directory.resolve("application.yaml"))).isEqualTo(yaml);
        assertThat(directory.resolve(".env")).doesNotExist();
    }

    @Test
    void externalYamlEnvAndDeploymentChangesInvalidateVersion() throws IOException {
        MockEnvironment environment = new MockEnvironment();
        ConfigurationStore store = new ConfigurationStore(directory.resolve("application.yaml"),
                directory.resolve(".env"), environment, Clock.systemUTC());
        String first = store.readSnapshot().configVersion();
        Files.writeString(directory.resolve("application.yaml"), "prjxp: {reader-max-output-chars: 1}");
        assertConflict(store, first);
        String second = store.readSnapshot().configVersion();
        Files.writeString(directory.resolve(".env"), "SETUP_KEY=secret\n");
        assertConflict(store, second);
        String third = store.readSnapshot().configVersion();
        environment.setProperty("prjxp.reader-max-output-chars", "2");
        assertConflict(store, third);
        assertConflict(store(), third);
        assertConflict(store, "forged");
    }

    @Test
    void snapshotsAreDefensiveAndNoopUpdatesKeepVersionAndFile() throws IOException {
        Files.writeString(directory.resolve("application.yaml"), "prjxp: {foreign: preserved}\n");
        ConfigurationStore store = store();
        var snapshot = store.readSnapshot();
        snapshot.root().clear();
        String text = Files.readString(directory.resolve("application.yaml"));
        var updated = store.update(snapshot.configVersion(),
                current -> new ConfigurationStore.Update(current.root(), Map.of()));
        assertThat(updated.configVersion()).isEqualTo(snapshot.configVersion());
        assertThat(Files.readString(directory.resolve("application.yaml"))).isEqualTo(text);
        assertThat(updated.toString()).doesNotContain("preserved");
    }

    @Test
    void independentStoresSerializeUpdatesAndRejectStaleWriter() throws Exception {
        ConfigurationStore first = store(), second = store();
        String firstVersion = first.readSnapshot().configVersion();
        String secondVersion = second.readSnapshot().configVersion();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> first.update(firstVersion,
                    s -> new ConfigurationStore.Update(Map.of("winner", "a"), Map.of())));
            var b = executor.submit(() -> second.update(secondVersion,
                    s -> new ConfigurationStore.Update(Map.of("winner", "b"), Map.of())));
            int successes = 0, conflicts = 0;
            for (var future : java.util.List.of(a, b)) {
                try {
                    future.get(10, TimeUnit.SECONDS);
                    successes++;
                } catch (java.util.concurrent.ExecutionException error) {
                    assertThat(error.getCause()).isInstanceOf(SetupException.class);
                    assertThat(((SetupException) error.getCause()).code()).isEqualTo("CONFIG_CHANGED");
                    conflicts++;
                }
            }
            assertThat(successes).isEqualTo(1);
            assertThat(conflicts).isEqualTo(1);
        }
    }

    @Test
    void failedYamlCommitPreservesOldReferencesAndReportsRedactedError() throws IOException {
        Path yaml = directory.resolve("application.yaml"), env = directory.resolve(".env");
        Files.writeString(yaml, "prjxp: {embedding: {api-key: '${OLD_KEY}'}}\n");
        Files.writeString(env, "OLD_KEY=old-secret\n");
        ConfigurationStore store = new ConfigurationStore(yaml, env, new MockEnvironment(), Clock.systemUTC()) {
            @Override protected void writeYaml(String text) throws IOException {
                throw new IOException("provider-secret-in-io-error");
            }
        };
        String version = store.readSnapshot().configVersion();
        assertThatThrownBy(() -> store.update(version, s -> new ConfigurationStore.Update(
                Map.of("prjxp", Map.of("embedding", Map.of("api-key", "${NEW_KEY}"))),
                Map.of("NEW_KEY", "new-secret"))))
                .isInstanceOf(SetupException.class).hasMessageNotContaining("provider-secret");
        assertThat(Files.readString(yaml)).contains("${OLD_KEY}").doesNotContain("NEW_KEY");
        assertThat(store.resolve("${OLD_KEY}")).isEqualTo("old-secret");
        assertThat(store.resolve("${NEW_KEY}")).isEqualTo("new-secret");
    }

    private void assertConflict(ConfigurationStore store, String version) {
        assertThatThrownBy(() -> store.update(version,
                s -> new ConfigurationStore.Update(s.root(), Map.of())))
                .isInstanceOf(SetupException.class).extracting("code").isEqualTo("CONFIG_CHANGED");
    }

    @Test
    void preservesProfileImportPrecedenceAndReplacesOnlyEditableSource() throws IOException {
        Path yaml = directory.resolve("application.yaml");
        Files.writeString(yaml, "prjxp: {embedding: {model-name: old}}\n");
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addLast(origin("Config resource profile", directory.resolve("application-prod.yaml"), "profile"));
        environment.getPropertySources().addLast(origin("Config resource editable", yaml, "old"));
        ConfigurationStore store = new ConfigurationStore(yaml, directory.resolve(".env"), environment, Clock.systemUTC());
        var snapshot = store.readSnapshot();
        assertThat(store.bind(snapshot.root(), "prjxp", de.spraener.prjxp.common.config.PrjXPConfig.class)
                .getEmbedding().getModelName()).isEqualTo("profile");
        assertThatThrownBy(() -> store.update(snapshot.configVersion(), s -> new ConfigurationStore.Update(
                Map.of("prjxp", Map.of("embedding", Map.of("model-name", "new"))), Map.of())))
                .isInstanceOf(SetupException.class).extracting("code").isEqualTo("CONFIG_OVERRIDDEN");
        environment.getPropertySources().remove("Config resource profile");
        Files.writeString(yaml, "prjxp: {embedding: {model-name: external}}\n");
        assertThat(store.bind(store.readSnapshot().root(), "prjxp", de.spraener.prjxp.common.config.PrjXPConfig.class)
                .getEmbedding().getModelName()).isEqualTo("external");
    }

    private org.springframework.core.env.PropertySource<?> origin(String name, Path path, String value) {
        var origin = new org.springframework.boot.origin.TextResourceOrigin(
                new org.springframework.core.io.FileSystemResource(path),
                new org.springframework.boot.origin.TextResourceOrigin.Location(0, 0));
        return new org.springframework.boot.env.OriginTrackedMapPropertySource(name,
                Map.of("prjxp.embedding.model-name", org.springframework.boot.origin.OriginTrackedValue.of(value, origin)));
    }

    @Test
    void malformedNewSecretIsRejectedBeforeEitherFileIsChanged() throws IOException {
        ConfigurationStore store = store();
        String version = store.readSnapshot().configVersion();
        assertThatThrownBy(() -> store.update(version, s -> new ConfigurationStore.Update(
                Map.of("prjxp", Map.of("embedding", Map.of("api-key", "${NEW}"))),
                Map.of("NEW", "bad\nsecret")))).isInstanceOf(SetupException.class).extracting("code").isEqualTo("INVALID_REQUEST");
        assertThat(directory.resolve(".env")).doesNotExist();
        assertThat(directory.resolve("application.yaml")).doesNotExist();
        assertThat(new ConfigurationStore.Update(Map.of("secret", "plaintext"), Map.of("KEY", "plaintext")).toString())
                .doesNotContain("plaintext");
    }

    @Test
    void versionsExpireAndAreBounded() throws IOException {
        var clock = new SetupTestEvidenceServiceTest.MutableClock();
        ConfigurationStore store = new ConfigurationStore(directory.resolve("application.yaml"), directory.resolve(".env"),
                new MockEnvironment(), clock);
        String first = store.readSnapshot().configVersion();
        for (int i = 0; i < 256; i++) {
            Files.writeString(directory.resolve("application.yaml"), "value: " + i);
            store.readSnapshot();
        }
        assertConflict(store, first);
        String last = store.readSnapshot().configVersion();
        clock.now = clock.now.plusSeconds(1800);
        assertConflict(store, last);
    }

    @Test
    void detectsNonCooperatingChangesDuringReadAndBeforeCommit() {
        ConfigurationStore store = store();
        assertThatThrownBy(() -> store.read(snapshot -> {
            try { Files.writeString(directory.resolve(".env"), "EXTERNAL=read\n"); }
            catch (IOException error) { throw new AssertionError(error); }
            return snapshot.configVersion();
        })).isInstanceOf(SetupException.class).extracting("code").isEqualTo("CONFIG_CHANGED");
        var version = store.readSnapshot().configVersion();
        assertThatThrownBy(() -> store.update(version, snapshot -> {
            try { Files.writeString(directory.resolve(".env"), "EXTERNAL=commit\n"); }
            catch (IOException error) { throw new AssertionError(error); }
            return new ConfigurationStore.Update(Map.of("prjxp", Map.of("setup-completed", true)), Map.of());
        })).isInstanceOf(SetupException.class).extracting("code").isEqualTo("CONFIG_CHANGED");
        assertThat(directory.resolve("application.yaml")).doesNotExist();
    }

    @Test
    void supportsNestedReadsWhileProducingReadAndWriteResponses() {
        ConfigurationStore store = store();
        String version = store.readSnapshot().configVersion();
        String nestedVersion = store.read(snapshot -> store.readSnapshot().configVersion());
        assertThat(nestedVersion).isEqualTo(version);
        var updated = store.update(version, snapshot -> new ConfigurationStore.Update(
                Map.of("prjxp", Map.of("setup-completed", true)), Map.of()),
                snapshot -> store.readSnapshot());
        assertThat(updated.root()).containsEntry("prjxp", Map.of("setup-completed", true));
    }
}
