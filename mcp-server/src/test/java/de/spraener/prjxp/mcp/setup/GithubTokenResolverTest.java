package de.spraener.prjxp.mcp.setup;

import de.spraener.prjxp.mcp.hub.HubProperties;
import de.spraener.prjxp.mcp.hub.github.GithubTokenResolver;
import de.spraener.prjxp.mcp.hub.github.TokenCipher;
import de.spraener.prjxp.mcp.hub.github.TokenCipherException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class GithubTokenResolverTest {
    @TempDir Path directory;

    @Test
    void resolvesFreshDefaultsReferencesAndProjectPrecedenceWithoutMutatingCandidateKey() throws Exception {
        Files.writeString(directory.resolve("application.yaml"),
                "prjxp: {hub: {enabled: true, github-token-key-env: OLD_KEY}}\n");
        Files.writeString(directory.resolve(".env"), "OLD_KEY=old-pass\nNEW_KEY=new-pass\nPAT_VALUE=env-pat\n");
        var store = new ConfigurationStore(directory.resolve("application.yaml"), directory.resolve(".env"),
                new MockEnvironment(), Clock.systemUTC());
        var cipher = new TokenCipher(new HubProperties(), new MockEnvironment(), store);
        var resolver = new GithubTokenResolver(store, cipher);
        String encrypted = cipher.encrypt("default-pat", "OLD_KEY");
        setDefault(store, encrypted);
        assertThat(resolver.forTest(new GithubConfigRequest("NEW_KEY", null))).isEqualTo("default-pat");
        assertThat(resolver.forTest(new GithubConfigRequest("NEW_KEY", "********"))).isEqualTo("default-pat");
        assertThat(resolver.forImport(null)).isEqualTo("default-pat");
        assertThat(resolver.forProject(null)).isEqualTo("default-pat");
        assertThat(resolver.forProject(cipher.encrypt("project-pat", "OLD_KEY"))).isEqualTo("project-pat");
        assertThat(resolver.forImport("${PAT_VALUE}")).isEqualTo("env-pat");
        assertThat(resolver.forTest(new GithubConfigRequest("NEW_KEY", cipher.encrypt("candidate-pat", "NEW_KEY"))))
                .isEqualTo("candidate-pat");
        assertThat(resolver.forImport("")).isNull();
        assertThat(resolver.forTest(new GithubConfigRequest(null, ""))).isNull();
        setDefault(store, "${PAT_VALUE}");
        Files.writeString(directory.resolve(".env"), "OLD_KEY=old-pass\nNEW_KEY=new-pass\nPAT_VALUE=updated-pat\n");
        assertThat(resolver.forImport(null)).isEqualTo("updated-pat");
        assertThat(store.bindForDisplay(store.readSnapshot().root(), "prjxp.hub", HubProperties.class)
                .getGithubTokenKeyEnv()).isEqualTo("OLD_KEY");
    }

    @Test
    void invalidExplicitTokensNeverFallBackAndFailuresAreRedacted() throws Exception {
        Files.writeString(directory.resolve("application.yaml"),
                "prjxp: {hub: {enabled: true, github-default-pat: default-pat}}\n");
        var store = new ConfigurationStore(directory.resolve("application.yaml"), directory.resolve(".env"),
                new MockEnvironment(), Clock.systemUTC());
        var resolver = new GithubTokenResolver(store, new TokenCipher(new HubProperties(), new MockEnvironment(), store));
        for (String token : new String[]{" ", "pat\nsecret", "${ABSENT_PHASE04_SECRET}", "${BAD-NAME}", "v1:bad-secret"}) {
            assertThatThrownBy(() -> resolver.forImport(token)).isInstanceOf(TokenCipherException.class)
                    .hasMessageNotContaining("bad-secret").hasMessageNotContaining("pat\nsecret");
        }
        assertThatThrownBy(() -> resolver.forImport("${ABSENT_PHASE04_SECRET}"))
                .extracting("code").isEqualTo("MISSING_SECRET");
        assertThatThrownBy(() -> resolver.forTest(null)).isInstanceOf(SetupException.class);
        assertThatThrownBy(() -> resolver.forTest(new GithubConfigRequest("BAD-NAME", "plain-pat")))
                .isInstanceOf(SetupException.class);
        Files.writeString(directory.resolve("application.yaml"), "prjxp: {hub: {enabled: false}}\n");
        assertThatThrownBy(() -> resolver.forTest(new GithubConfigRequest(null, "plain-pat")))
                .isInstanceOf(SetupException.class).extracting("code").isEqualTo("HUB_DISABLED");
    }

    private void setDefault(ConfigurationStore store, String token) {
        store.update(store.readSnapshot().configVersion(), snapshot -> {
            var root = snapshot.root();
            var prjxp = SetupData.section(root, "prjxp");
            var hub = SetupData.section(prjxp, "hub");
            hub.put("github-default-pat", token);
            prjxp.put("hub", hub);
            root.put("prjxp", prjxp);
            return new ConfigurationStore.Update(root, Map.of());
        });
    }

    @Test
    void setupSaveValidatesTokensAndUsesCandidateKeyForNewCiphertext() throws Exception {
        Files.writeString(directory.resolve("application.yaml"),
                "prjxp: {hub: {enabled: true, github-token-key-env: OLD_KEY}}\n");
        Files.writeString(directory.resolve(".env"), "OLD_KEY=old-pass\nNEW_KEY=new-pass\n");
        var store = new ConfigurationStore(directory.resolve("application.yaml"), directory.resolve(".env"),
                new MockEnvironment(), Clock.systemUTC());
        var cipher = new TokenCipher(new HubProperties(), new MockEnvironment(), store);
        var persistence = new SetupPersistenceService(store, new SetupTestEvidenceService(), java.util.List.of(),
                () -> cipher, () -> false);
        assertThatThrownBy(() -> persistence.save(new SetupSaveRequest(persistence.readStatus().configVersion(),
                "github", false, null, null, null, new GithubConfigRequest("OLD_KEY", "unsafe\npat"), null)))
                .isInstanceOf(SetupException.class);
        String candidate = cipher.encrypt("candidate-pat", "NEW_KEY");
        persistence.save(new SetupSaveRequest(persistence.readStatus().configVersion(), "github", false, null,
                null, null, new GithubConfigRequest("NEW_KEY", candidate), null));
        assertThat(new GithubTokenResolver(store, cipher).forImport(null)).isEqualTo("candidate-pat");
    }

    @Test
    void malformedStoredKeyNamesCannotBeReflectedInErrors() throws Exception {
        Files.writeString(directory.resolve("application.yaml"),
                "prjxp: {hub: {enabled: true, github-token-key-env: 'private-key-name!', github-default-pat: 'v1:bad'}}\n");
        var store = new ConfigurationStore(directory.resolve("application.yaml"), directory.resolve(".env"),
                new MockEnvironment(), Clock.systemUTC());
        var resolver = new GithubTokenResolver(store, new TokenCipher(new HubProperties(), new MockEnvironment(), store));
        assertThatThrownBy(() -> resolver.forImport(null)).isInstanceOf(TokenCipherException.class)
                .hasMessageNotContaining("private-key-name!");
    }

    @Test
    void encryptedEnvironmentReferenceAlsoRequiresExplicitKeyRotation() throws Exception {
        Files.writeString(directory.resolve("application.yaml"),
                "prjxp: {hub: {enabled: true, github-token-key-env: OLD_KEY}}\n");
        Path env = directory.resolve(".env");
        Files.writeString(env, "OLD_KEY=old-pass\nNEW_KEY=new-pass\n");
        var store = new ConfigurationStore(directory.resolve("application.yaml"), env,
                new MockEnvironment(), Clock.systemUTC());
        var cipher = new TokenCipher(new HubProperties(), new MockEnvironment(), store);
        Files.writeString(env, "OLD_KEY=old-pass\nNEW_KEY=new-pass\nPAT_CIPHER=" + cipher.encrypt("stored-pat", "OLD_KEY") + "\n");
        setDefault(store, "${PAT_CIPHER}");
        var persistence = new SetupPersistenceService(store, new SetupTestEvidenceService(), java.util.List.of(),
                () -> cipher, () -> false);
        assertThatThrownBy(() -> persistence.save(new SetupSaveRequest(persistence.readStatus().configVersion(),
                "github", false, null, null, null, new GithubConfigRequest("NEW_KEY", "********"), null)))
                .isInstanceOf(SetupException.class).extracting("code").isEqualTo("KEY_ROTATION_REQUIRED");
        assertThat(new GithubTokenResolver(store, cipher).forImport(null)).isEqualTo("stored-pat");
    }
}
