package de.spraener.prjxp.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.assertj.core.api.Assertions.*;

class PrjXPCliBootTest {
    @TempDir
    Path directory;
    private final Properties original = new Properties();

    @BeforeEach
    void isolateBootProperties() {
        original.putAll(System.getProperties());
        System.setProperty("user.dir", directory.toString());
        for (String key : new String[]{"PRJXP_CONFIG_FILE", "PRJXP_ENV_FILE",
                "spring.config.location", "spring.config.additional-location", "PRJXP_SETUP_BOOT_VALUE"}) {
            System.clearProperty(key);
        }
    }

    @AfterEach
    void restoreProperties() {
        System.setProperties(original);
    }

    @Test
    void bootLoadsSelectedEnvAndSelectsSameExternalYaml() throws Exception {
        Path env = directory.resolve("custom.env");
        Path yaml = directory.resolve("config").resolve("new.yaml");
        Files.writeString(env, "PRJXP_SETUP_BOOT_VALUE=loaded\n");
        System.setProperty("PRJXP_ENV_FILE", env.toString());
        System.setProperty("PRJXP_CONFIG_FILE", yaml.toString());
        PrjXPCli.readDotEnv(new String[0]);
        assertThat(System.getProperty("PRJXP_SETUP_BOOT_VALUE")).isEqualTo("loaded");
        assertThat(System.getProperty("spring.config.additional-location")).isEqualTo("optional:" + yaml.toUri());
    }

    @Test
    void bootPreservesExplicitSpringConfigAndJvmValue() throws Exception {
        Files.writeString(directory.resolve(".env"), "PRJXP_SETUP_BOOT_VALUE=file\n");
        System.setProperty("PRJXP_SETUP_BOOT_VALUE", "jvm");
        System.setProperty("spring.config.location", directory.resolve("other.yaml").toUri().toString());
        PrjXPCli.readDotEnv(new String[0]);
        assertThat(System.getProperty("PRJXP_SETUP_BOOT_VALUE")).isEqualTo("jvm");
        assertThat(System.getProperty("spring.config.additional-location")).isNull();
    }

    @Test
    void commandLineConfigIsNotOverwritten() {
        PrjXPCli.readDotEnv(new String[]{"--spring.config.additional-location=" + directory.resolve("cli.yaml").toUri()});
        assertThat(System.getProperty("spring.config.additional-location")).isNull();
    }

    @Test
    void missingEnvIsAllowedButUnreadableEnvFailsExplicitly() {
        assertThatNoException().isThrownBy(() -> PrjXPCli.readDotEnv(new String[0]));
        System.setProperty("PRJXP_ENV_FILE", directory.toString());
        assertThatIllegalStateException().isThrownBy(() -> PrjXPCli.readDotEnv(new String[0]))
                .withMessageContaining(".env");
    }
}
