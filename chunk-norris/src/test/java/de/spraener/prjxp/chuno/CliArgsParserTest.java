package de.spraener.prjxp.chuno;

import de.spraener.prjxp.common.config.CliArgsParsingEvent;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.common.errorlog.PxLogService;
import de.spraener.prjxp.common.transfer.TransferEncryptMode;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class CliArgsParserTest {
    private CliArgsParser uut(PrjXPConfig cfg) {
        return new CliArgsParser(mock(PxLogService.class), cfg, new StandardEnvironment());
    }

    private CliArgsParsingEvent event(PrjXPConfig cfg, String... args) {
        return new CliArgsParsingEvent(args, cfg);
    }

    @Test
    void parseArgs_withEncryptFlag_setsForceOn() {
        PrjXPConfig cfg = new PrjXPConfig();

        uut(cfg).parseArgs(event(cfg, "--encrypt"));

        assertThat(cfg.getTransfer().getEncrypt()).isEqualTo(TransferEncryptMode.TRUE);
    }

    @Test
    void parseArgs_withNoEncryptFlag_setsForceOff() {
        PrjXPConfig cfg = new PrjXPConfig();

        uut(cfg).parseArgs(event(cfg, "--no-encrypt"));

        assertThat(cfg.getTransfer().getEncrypt()).isEqualTo(TransferEncryptMode.FALSE);
    }

    @Test
    void parseArgs_withBothFlags_failsClearly() {
        PrjXPConfig cfg = new PrjXPConfig();

        assertThatThrownBy(() -> uut(cfg).parseArgs(event(cfg, "--encrypt", "--no-encrypt")))
                .hasMessageContaining("--encrypt")
                .hasMessageContaining("--no-encrypt");
    }

    @Test
    void parseArgs_withPasswordEnv_setsKey() {
        PrjXPConfig cfg = new PrjXPConfig();

        uut(cfg).parseArgs(event(cfg, "--password-env", "MY_CUSTOM_PASSWORD"));

        assertThat(cfg.getTransfer().getPasswordEnv()).isEqualTo("MY_CUSTOM_PASSWORD");
    }

    @Test
    void parseArgs_withoutFlags_keepsDefaults() {
        PrjXPConfig cfg = new PrjXPConfig();

        uut(cfg).parseArgs(event(cfg));

        assertThat(cfg.getTransfer().getEncrypt()).isEqualTo(TransferEncryptMode.AUTO);
        assertThat(cfg.getTransfer().getPasswordEnv()).isEqualTo("PRJXP_TRANSFER_PASSWORD");
    }

    @Test
    void parseArgs_withProjectFlag_keepsWorking() {
        PrjXPConfig cfg = new PrjXPConfig();
        ProjectDefinition pd = new ProjectDefinition();
        pd.setName("myproject");
        cfg.getProjects().add(pd);

        uut(cfg).parseArgs(event(cfg, "-p", "myproject"));

        assertThat(cfg.getActiveProject().map(ProjectDefinition::getName).orElse(null)).isEqualTo("myproject");
    }
}
