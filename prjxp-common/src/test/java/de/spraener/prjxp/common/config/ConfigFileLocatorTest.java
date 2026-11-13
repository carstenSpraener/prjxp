package de.spraener.prjxp.common.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class ConfigFileLocatorTest {
    @TempDir
    Path directory;

    private ConfigFileLocator locator(Map<String, String> properties, Map<String, String> environment, String... args) {
        return new ConfigFileLocator(directory, properties::get, environment::get, args);
    }

    @Test
    void defaultsToExternalFilesInWorkingDirectory() {
        ConfigFileLocator locator = locator(Map.of(), Map.of());
        assertThat(locator.getApplicationYamlPath()).isEqualTo(directory.resolve("application.yaml"));
        assertThat(locator.getEnvFilePath()).isEqualTo(directory.resolve(".env"));
        assertThat(locator.getSpringAdditionalLocation()).isEqualTo(
                "optional:" + directory.resolve("application.yaml").toUri());
    }

    @Test
    void explicitNonexistentRelativePathsDoNotFallBack() {
        ConfigFileLocator locator = locator(Map.of(),
                Map.of("PRJXP_CONFIG_FILE", "config/new.yaml", "PRJXP_ENV_FILE", "secrets/custom.env"));
        assertThat(locator.getApplicationYamlPath()).isEqualTo(directory.resolve("config").resolve("new.yaml"));
        assertThat(locator.getEnvFilePath()).isEqualTo(directory.resolve("secrets").resolve("custom.env"));
    }

    @Test
    void systemPropertyOverridesEnvironmentAndNormalizesPaths() {
        ConfigFileLocator locator = locator(
                Map.of("PRJXP_CONFIG_FILE", "config/../custom.yaml", "PRJXP_ENV_FILE", directory.resolve("other.env").toString()),
                Map.of("PRJXP_CONFIG_FILE", "ignored.yaml", "PRJXP_ENV_FILE", "ignored.env"));
        assertThat(locator.getApplicationYamlPath()).isEqualTo(directory.resolve("custom.yaml"));
        assertThat(locator.getEnvFilePath()).isEqualTo(directory.resolve("other.env"));
    }

    @Test
    void blankOverridesUseDefaults() {
        ConfigFileLocator locator = locator(Map.of("PRJXP_CONFIG_FILE", " "), Map.of("PRJXP_ENV_FILE", ""));
        assertThat(locator.getApplicationYamlPath()).isEqualTo(directory.resolve("application.yaml"));
        assertThat(locator.getEnvFilePath()).isEqualTo(directory.resolve(".env"));
    }

    @Test
    void selectsExplicitSpringFileInsteadOfAddingCompetingLocation() {
        Path file = directory.resolve("spring.yml");
        ConfigFileLocator locator = locator(Map.of("spring.config.location", "optional:" + file.toUri()), Map.of());
        assertThat(locator.getApplicationYamlPath()).isEqualTo(file);
        assertThat(locator.getSpringAdditionalLocation()).isNull();
    }

    @Test
    void commandLineSpringLocationWinsOverSystemAndEnvironment() {
        Path file = directory.resolve("command.yaml");
        ConfigFileLocator locator = locator(Map.of("spring.config.location", "ignored.yaml"),
                Map.of("SPRING_CONFIG_LOCATION", "also-ignored.yaml"),
                "--spring.config.location=" + file.toUri());
        assertThat(locator.getApplicationYamlPath()).isEqualTo(file);
    }

    @Test
    void springEnvironmentAdditionalLocationIsRecognized() {
        ConfigFileLocator locator = locator(Map.of(), Map.of("SPRING_CONFIG_ADDITIONAL_LOCATION", "custom.yaml"));
        assertThat(locator.getApplicationYamlPath()).isEqualTo(directory.resolve("custom.yaml"));
        assertThat(locator.getSpringAdditionalLocation()).isNull();
    }

    @Test
    void conflictingExplicitWizardAndSpringPathsAreRejected() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                locator(Map.of("PRJXP_CONFIG_FILE", "wizard.yaml", "spring.config.location", "spring.yaml"), Map.of()))
                .withMessageContaining("PRJXP_CONFIG_FILE");
    }

    @Test
    void multipleWritableSpringSourcesAreRejected() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                locator(Map.of("spring.config.additional-location", "a.yaml,b.yaml"), Map.of()))
                .withMessageContaining("spring.config");
    }

    @Test
    void unsupportedSourceAndMissingCommandLineValueAreRejected() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                locator(Map.of("spring.config.location", "settings.properties"), Map.of()));
        assertThatIllegalArgumentException().isThrownBy(() ->
                locator(Map.of(), Map.of(), "--spring.config.location"));
    }

    @Test
    void directoryLocationResolvesToApplicationYaml() throws IOException {
        // Mirrors the docker hub contract: entry.sh sets spring.config.location=file:/app/ (a directory)
        Path appDir = directory.resolve("app");
        Files.createDirectories(appDir);
        ConfigFileLocator locator = locator(Map.of("spring.config.location", appDir.toUri().toString()), Map.of());
        assertThat(locator.getApplicationYamlPath()).isEqualTo(appDir.resolve("application.yaml"));
        assertThat(locator.getSpringAdditionalLocation()).isNull();
    }

    @Test
    void extensionlessLocationIsTreatedAsDirectoryEvenWhenAbsent() {
        ConfigFileLocator locator = locator(
                Map.of("spring.config.location", directory.toUri() + "/not-yet-created"), Map.of());
        assertThat(locator.getApplicationYamlPath())
                .isEqualTo(directory.resolve("not-yet-created").resolve("application.yaml"));
    }

    @Test
    void optionalDirectoryFallbackChainPicksFirstExisting() throws IOException {
        // Mirrors entry.sh when a config is mounted at /app-source:
        //   optional:file:/app-source/,optional:file:/app/
        Path first = directory.resolve("app-source");
        Path second = directory.resolve("app");
        Files.createDirectories(first);
        Files.createDirectories(second);
        Files.writeString(first.resolve("application.yaml"), "a: 1");
        // second intentionally has no application.yaml

        ConfigFileLocator locator = locator(
                Map.of("spring.config.location",
                        "optional:" + first.toUri() + ",optional:" + second.toUri()),
                Map.of());
        assertThat(locator.getApplicationYamlPath()).isEqualTo(first.resolve("application.yaml"));
    }

    @Test
    void optionalFallbackChainWithNothingPresentFallsBackToFirst() {
        Path first = directory.resolve("app-source");
        Path second = directory.resolve("app");
        ConfigFileLocator locator = locator(
                Map.of("spring.config.location",
                        "optional:" + first.toUri() + ",optional:" + second.toUri()),
                Map.of());
        assertThat(locator.getApplicationYamlPath()).isEqualTo(first.resolve("application.yaml"));
    }
}
