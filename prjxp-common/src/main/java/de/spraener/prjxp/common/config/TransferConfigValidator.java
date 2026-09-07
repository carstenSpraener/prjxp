package de.spraener.prjxp.common.config;

import de.spraener.prjxp.common.transfer.TransferMode;

public final class TransferConfigValidator {
    private TransferConfigValidator() {
    }

    public static void validate(PrjXPConfig.TransferConfig transfer) {
        if (transfer == null || transfer.getMode() == null) {
            return;
        }
        switch (transfer.getMode()) {
            case EXPORT -> require(transfer.getOutput(), "export", "output", "--output", "prjxp.transfer.output");
            case IMPORT -> require(transfer.getInput(), "import", "input", "--input", "prjxp.transfer.input");
            case STORE -> {
            }
        }
    }

    private static void require(String value, String mode, String field, String flag, String configKey) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Transfer mode '" + mode + "' requires an " + field + " file. Use " + flag + " <path|-> or set " + configKey + ".");
        }
    }
}
