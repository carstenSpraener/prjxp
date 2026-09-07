package de.spraener.prjxp.common.transfer;

public record TransferSession(boolean encrypt, char[] password, boolean generatedPassword) {
    public static TransferSession from(TransferEncryptMode mode, char[] password, boolean generatedPassword) {
        boolean encrypt = switch (mode) {
            case TRUE -> true;
            case FALSE -> false;
            case AUTO -> password != null;
        };
        return new TransferSession(encrypt, encrypt ? password : null, generatedPassword);
    }
}
