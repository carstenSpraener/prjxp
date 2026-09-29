package de.spraener.prjxp.common.config;

import de.spraener.prjxp.common.transfer.TransferMode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferConfigValidatorTest {

    private PrjXPConfig.TransferConfig transfer(TransferMode mode, String input, String output) {
        PrjXPConfig.TransferConfig transfer = new PrjXPConfig.TransferConfig();
        transfer.setMode(mode);
        transfer.setInput(input);
        transfer.setOutput(output);
        return transfer;
    }

    @Test
    void validate_exportWithoutOutput_failsClearly() {
        assertThatThrownBy(() -> TransferConfigValidator.validate(transfer(TransferMode.EXPORT, "in.jsonl", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("export")
                .hasMessageContaining("--output");
    }

    @Test
    void validate_exportWithBlankOutput_failsClearly() {
        assertThatThrownBy(() -> TransferConfigValidator.validate(transfer(TransferMode.EXPORT, "in.jsonl", "   ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--output");
    }

    @Test
    void validate_exportWithOutput_succeeds() {
        assertThatCode(() -> TransferConfigValidator.validate(transfer(TransferMode.EXPORT, null, "out.jsonl")))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_importWithoutInput_failsClearly() {
        assertThatThrownBy(() -> TransferConfigValidator.validate(transfer(TransferMode.IMPORT, null, "out.jsonl")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("import")
                .hasMessageContaining("--input");
    }

    @Test
    void validate_importWithBlankInput_failsClearly() {
        assertThatThrownBy(() -> TransferConfigValidator.validate(transfer(TransferMode.IMPORT, "  ", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--input");
    }

    @Test
    void validate_importWithInput_succeeds() {
        assertThatCode(() -> TransferConfigValidator.validate(transfer(TransferMode.IMPORT, "in.jsonl", null)))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_storeModeWithoutFiles_succeeds() {
        assertThatCode(() -> TransferConfigValidator.validate(transfer(TransferMode.STORE, null, null)))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_nullTransfer_succeeds() {
        assertThatCode(() -> TransferConfigValidator.validate(null))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_nullMode_succeeds() {
        assertThatCode(() -> TransferConfigValidator.validate(transfer(null, null, null)))
                .doesNotThrowAnyException();
    }
}
