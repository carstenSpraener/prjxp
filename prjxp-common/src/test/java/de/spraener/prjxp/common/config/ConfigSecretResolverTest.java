package de.spraener.prjxp.common.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.*;

class ConfigSecretResolverTest {
    @TempDir
    Path directory;
    private final Properties original = new Properties();

    ConfigSecretResolverTest() {
        original.putAll(System.getProperties());
    }

    @AfterEach
    void restoreProperties() {
        System.setProperties(original);
    }

    private ConfigSecretResolver resolver(Map<String, String> properties, Map<String, String> environment,
                                          Map<String, String> imported) {
        return new ConfigSecretResolver(directory.resolve(".env"), properties::get, environment::get, imported);
    }

    @Test
    void resolvesNullEmptyLiteralAndMultiplePlaceholdersWithDefaults() throws Exception {
        ConfigSecretResolver resolver = resolver(Map.of(), Map.of("HOST", "localhost"), Map.of());
        assertThat(resolver.resolve(null)).isNull();
        assertThat(resolver.resolve("")).isEmpty();
        assertThat(resolver.resolve("literal")).isEqualTo("literal");
        assertThat(resolver.resolve("http://${HOST}:${PORT:1234}/v1")).isEqualTo("http://localhost:1234/v1");
        assertThat(resolver.resolve("${MISSING:}")).isEmpty();
    }

    @Test
    void explicitJvmOverridesOsWhichOverridesFile() throws Exception {
        Files.writeString(directory.resolve(".env"), "KEY=file\n");
        assertThat(resolver(Map.of("KEY", "jvm"), Map.of("KEY", "os"), Map.of()).resolve("${KEY}")).isEqualTo("jvm");
        assertThat(resolver(Map.of(), Map.of("KEY", "os"), Map.of()).resolve("${KEY}")).isEqualTo("os");
        assertThat(resolver(Map.of(), Map.of(), Map.of()).resolve("${KEY}")).isEqualTo("file");
    }

    @Test
    void importedSystemPropertyDoesNotOverrideFreshFile() throws Exception {
        Path file = directory.resolve(".env");
        Files.writeString(file, "KEY=new\n");
        ConfigSecretResolver resolver = resolver(Map.of("KEY", "old"), Map.of(), Map.of("KEY", "old"));
        assertThat(resolver.resolve("${KEY}")).isEqualTo("new");
        Files.writeString(file, "KEY=newer\n");
        assertThat(resolver.resolve("${KEY}")).isEqualTo("newer");
    }

    @Test
    void changedExplicitPropertyAfterImportWins() throws Exception {
        Files.writeString(directory.resolve(".env"), "KEY=file\n");
        assertThat(resolver(Map.of("KEY", "explicit"), Map.of(), Map.of("KEY", "imported")).resolve("${KEY}"))
                .isEqualTo("explicit");
    }

    @Test
    void removedImportedFileKeyDoesNotResurrectStaleSystemValue() {
        ConfigSecretResolver resolver = resolver(Map.of("KEY", "old"), Map.of(), Map.of("KEY", "old"));
        assertThatIllegalArgumentException().isThrownBy(() -> resolver.resolve("${KEY}"))
                .withMessageContaining("MISSING_SECRET").withMessageContaining("KEY");
    }

    @Test
    void importsDeclaredValuesWithoutReplacingExplicitJvmSettings() throws Exception {
        Path file = directory.resolve(".env");
        Files.writeString(file, "PRJXP_SETUP_IMPORT_A=first\nPRJXP_SETUP_IMPORT_B=file\n");
        System.clearProperty("PRJXP_SETUP_IMPORT_A");
        System.setProperty("PRJXP_SETUP_IMPORT_B", "explicit");
        ConfigSecretResolver resolver = new ConfigSecretResolver(file);
        resolver.importIntoSystemProperties();
        assertThat(System.getProperty("PRJXP_SETUP_IMPORT_A")).isEqualTo("first");
        assertThat(System.getProperty("PRJXP_SETUP_IMPORT_B")).isEqualTo("explicit");
        Files.writeString(file, "PRJXP_SETUP_IMPORT_A=second\nPRJXP_SETUP_IMPORT_B=changed\n");
        assertThat(resolver.resolve("${PRJXP_SETUP_IMPORT_A}")).isEqualTo("second");
        assertThat(resolver.resolve("${PRJXP_SETUP_IMPORT_B}")).isEqualTo("explicit");
    }

    @Test
    void replacementValuesAreNotInterpretedAsRecursivePlaceholders() throws Exception {
        assertThat(resolver(Map.of("KEY", "${literal}"), Map.of(), Map.of()).resolve("${KEY}"))
                .isEqualTo("${literal}");
    }

    @Test
    void malformedOrDuplicateFileIsAnExplicitReadFailure() throws Exception {
        Files.writeString(directory.resolve(".env"), "KEY=one\nKEY=two\n");
        assertThatIOException().isThrownBy(() -> resolver(Map.of(), Map.of(), Map.of()).resolve("${KEY}"));
    }

    @Test
    void malformedFileFailsWithoutReflectingItsContents() throws Exception {
        Files.writeString(directory.resolve(".env"), "malformed secret-sentinel\n");
        assertThatIOException().isThrownBy(() -> resolver(Map.of(), Map.of(), Map.of()).resolve("${KEY}"))
                .withMessageContaining(".env").withMessageNotContaining("secret-sentinel");
    }
}
