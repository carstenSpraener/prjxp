package de.spraener.prjxp.common.transfer;

import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

@Component
@RequiredArgsConstructor
public class TransferPasswordResolver {
    public static final String DEFAULT_ENV_KEY = "PRJXP_TRANSFER_PASSWORD";

    private static final int PASSWORD_BYTES = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final Environment env;

    public char[] resolvePassword(String envKey, boolean forceEncrypt) {
        return prepare(forceEncrypt ? TransferEncryptMode.TRUE : TransferEncryptMode.AUTO, envKey).password();
    }

    public char[] resolvePassword(String envKey) {
        String fromEnv = env.getProperty(envKey);
        if (fromEnv == null || fromEnv.isBlank()) {
            return null;
        }
        return fromEnv.toCharArray();
    }

    public TransferSession prepare(TransferEncryptMode mode, String envKey) {
        char[] password = resolvePassword(envKey);
        boolean generated = false;
        if (password == null && mode == TransferEncryptMode.TRUE) {
            password = generatePassword();
            generated = true;
            printGeneratedBanner(password);
        }
        return TransferSession.from(mode, password, generated);
    }

    public char[] generatePassword() {
        byte[] bytes = new byte[PASSWORD_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).toCharArray();
    }

    public void printGeneratedBanner(char[] password) {
        System.out.println("[SECURITY] Generated transfer password:");
        System.out.println(new String(password));
        System.out.println("[SECURITY] Store this password safely. It will be required for export/import.");
    }

    public void printRepeatBanner(char[] password) {
        System.out.println("[SECURITY] REPEAT transfer password:");
        System.out.println(new String(password));
    }
}
