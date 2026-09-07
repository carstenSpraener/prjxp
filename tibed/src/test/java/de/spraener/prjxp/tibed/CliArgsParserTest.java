package de.spraener.prjxp.tibed;

import de.spraener.prjxp.common.config.CliArgsParsingEvent;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.errorlog.PxLogService;
import de.spraener.prjxp.common.transfer.TransferEncryptMode;
import de.spraener.prjxp.common.transfer.TransferMode;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CliArgsParserTest {
    private CliArgsParser uut(PrjXPConfig cfg) {
        return new CliArgsParser(mock(PxLogService.class), cfg, new StandardEnvironment());
    }

    private CliArgsParsingEvent event(PrjXPConfig cfg, String... args) {
        return new CliArgsParsingEvent(args, cfg);
    }

    @Test
    void parseArgs_withModeExport_setsExport() {
        PrjXPConfig cfg = new PrjXPConfig();

        uut(cfg).parseArgs(event(cfg, "--mode", "export", "--output", "embedding.jsonl"));

        assertThat(cfg.getTransfer().getMode()).isEqualTo(TransferMode.EXPORT);
    }

    @Test
    void parseArgs_withModeImport_setsImport() {
        PrjXPConfig cfg = new PrjXPConfig();

        uut(cfg).parseArgs(event(cfg, "--mode", "import", "--input", "embedding.jsonl"));

        assertThat(cfg.getTransfer().getMode()).isEqualTo(TransferMode.IMPORT);
    }

    @Test
    void parseArgs_withoutMode_keepsStoreDefault() {
        PrjXPConfig cfg = new PrjXPConfig();

        uut(cfg).parseArgs(event(cfg));

        assertThat(cfg.getTransfer().getMode()).isEqualTo(TransferMode.STORE);
    }

    @Test
    void parseArgs_withInvalidMode_failsClearly() {
        PrjXPConfig cfg = new PrjXPConfig();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> uut(cfg).parseArgs(event(cfg, "--mode", "bogus")))
                .hasMessageContaining("mode");
    }

    @Test
    void parseArgs_withInputAndOutput_setsBoth() {
        PrjXPConfig cfg = new PrjXPConfig();

        uut(cfg).parseArgs(event(cfg, "--input", "in.jsonl", "--output", "out.jsonl"));

        assertThat(cfg.getTransfer().getInput()).isEqualTo("in.jsonl");
        assertThat(cfg.getTransfer().getOutput()).isEqualTo("out.jsonl");
    }

    @Test
    void parseArgs_withPasswordEnv_setsKey() {
        PrjXPConfig cfg = new PrjXPConfig();

        uut(cfg).parseArgs(event(cfg, "--password-env", "MY_PASSWORD"));

        assertThat(cfg.getTransfer().getPasswordEnv()).isEqualTo("MY_PASSWORD");
    }

    @Test
    void parseArgs_withEncryptAndNoEncrypt_setsModes() {
        PrjXPConfig cfg = new PrjXPConfig();

        uut(cfg).parseArgs(event(cfg, "--encrypt"));
        assertThat(cfg.getTransfer().getEncrypt()).isEqualTo(TransferEncryptMode.TRUE);

        uut(cfg).parseArgs(event(cfg, "--no-encrypt"));
        assertThat(cfg.getTransfer().getEncrypt()).isEqualTo(TransferEncryptMode.FALSE);
    }

    @Test
    void parseArgs_withBothEncryptFlags_failsClearly() {
        PrjXPConfig cfg = new PrjXPConfig();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> uut(cfg).parseArgs(event(cfg, "--encrypt", "--no-encrypt")))
                .hasMessageContaining("--encrypt")
                .hasMessageContaining("--no-encrypt");
    }

    @Test
    void parseArgs_withProjectFlag_keepsWorking() {
        PrjXPConfig cfg = new PrjXPConfig();
        de.spraener.prjxp.common.config.ProjectDefinition pd = new de.spraener.prjxp.common.config.ProjectDefinition();
        pd.setName("myproject");
        cfg.getProjects().add(pd);

        uut(cfg).parseArgs(event(cfg, "-p", "myproject"));

        assertThat(cfg.getActiveProject().map(de.spraener.prjxp.common.config.ProjectDefinition::getName).orElse(null))
                .isEqualTo("myproject");
    }

    @Test
    void parseArgs_exportWithoutOutput_failsEarly() {
        PrjXPConfig cfg = new PrjXPConfig();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> uut(cfg).parseArgs(event(cfg, "--mode", "export")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("export")
                .hasMessageContaining("--output");
    }

    @Test
    void parseArgs_importWithoutInput_failsEarly() {
        PrjXPConfig cfg = new PrjXPConfig();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> uut(cfg).parseArgs(event(cfg, "--mode", "import")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("import")
                .hasMessageContaining("--input");
    }

    @Test
    void parseArgs_exportWithBoundOutput_succeeds() {
        PrjXPConfig cfg = new PrjXPConfig();
        cfg.getTransfer().setOutput("bound.jsonl");

        uut(cfg).parseArgs(event(cfg, "--mode", "export"));

        assertThat(cfg.getTransfer().getMode()).isEqualTo(TransferMode.EXPORT);
    }

    @Test
    void parseArgs_importWithBoundInput_succeeds() {
        PrjXPConfig cfg = new PrjXPConfig();
        cfg.getTransfer().setInput("bound.jsonl");

        uut(cfg).parseArgs(event(cfg, "--mode", "import"));

        assertThat(cfg.getTransfer().getMode()).isEqualTo(TransferMode.IMPORT);
    }

    @Test
    void parseArgs_storeModeWithoutFiles_keepsWorking() {
        PrjXPConfig cfg = new PrjXPConfig();

        uut(cfg).parseArgs(event(cfg));

        assertThat(cfg.getTransfer().getMode()).isEqualTo(TransferMode.STORE);
    }

    @Test
    void parseArgs_cliFlagWinsOverBoundConfig() {
        PrjXPConfig cfg = new PrjXPConfig();
        cfg.getTransfer().setMode(TransferMode.IMPORT);
        cfg.getTransfer().setInput("bound.jsonl");

        uut(cfg).parseArgs(event(cfg, "--mode", "export", "--output", "out.jsonl"));

        assertThat(cfg.getTransfer().getMode()).isEqualTo(TransferMode.EXPORT);
    }

    @Test
    void parseArgs_encryptFlagWinsOverBoundConfig() {
        PrjXPConfig cfg = new PrjXPConfig();
        cfg.getTransfer().setEncrypt(TransferEncryptMode.FALSE);

        uut(cfg).parseArgs(event(cfg, "--encrypt"));

        assertThat(cfg.getTransfer().getEncrypt()).isEqualTo(TransferEncryptMode.TRUE);
    }

    @Test
    void parseArgs_withoutFlags_keepsBoundConfig() {
        PrjXPConfig cfg = new PrjXPConfig();
        cfg.getTransfer().setMode(TransferMode.IMPORT);
        cfg.getTransfer().setInput("bound.jsonl");
        cfg.getTransfer().setEncrypt(TransferEncryptMode.FALSE);

        uut(cfg).parseArgs(event(cfg));

        assertThat(cfg.getTransfer().getMode()).isEqualTo(TransferMode.IMPORT);
        assertThat(cfg.getTransfer().getInput()).isEqualTo("bound.jsonl");
        assertThat(cfg.getTransfer().getEncrypt()).isEqualTo(TransferEncryptMode.FALSE);
    }
}
