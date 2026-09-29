package de.spraener.prjxp.common.transfer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TransferPasswordResolverTest {
    private final PrintStream originalOut = System.out;

    @AfterEach
    void restoreOut() {
        System.setOut(originalOut);
    }

    private TransferPasswordResolver resolverWith(String envKey, String value) {
        StandardEnvironment env = new StandardEnvironment();
        Map<String, Object> props = new HashMap<>();
        if (value != null) {
            props.put(envKey, value);
        }
        env.getPropertySources().addFirst(new MapPropertySource("test", props));
        return new TransferPasswordResolver(env);
    }

    @Test
    void resolvePassword_withEnvValue_returnsIt() {
        TransferPasswordResolver uut = resolverWith("PRJXP_TRANSFER_PASSWORD", "from-env");

        assertThat(uut.resolvePassword("PRJXP_TRANSFER_PASSWORD", false)).isEqualTo("from-env".toCharArray());
    }

    @Test
    void resolvePassword_withBlankEnvValue_treatsAsMissing() {
        TransferPasswordResolver uut = resolverWith("PRJXP_TRANSFER_PASSWORD", "   ");

        assertThat(uut.resolvePassword("PRJXP_TRANSFER_PASSWORD", false)).isNull();
    }

    @Test
    void resolvePassword_withoutEnvAndNoForce_returnsNull() {
        TransferPasswordResolver uut = resolverWith("PRJXP_TRANSFER_PASSWORD", null);

        assertThat(uut.resolvePassword("PRJXP_TRANSFER_PASSWORD", false)).isNull();
    }

    @Test
    void resolvePassword_withoutEnvAndForce_generatesAndPasswordIsPrinted() {
        TransferPasswordResolver uut = resolverWith("PRJXP_TRANSFER_PASSWORD", null);
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true));

        char[] password = uut.resolvePassword("PRJXP_TRANSFER_PASSWORD", true);

        String console = captured.toString();
        assertThat(password).isNotNull().isNotEmpty();
        assertThat(console).contains("[SECURITY] Generated transfer password:");
        assertThat(console).contains(new String(password));
        assertThat(console).contains("[SECURITY] Store this password safely.");
    }

    @Test
    void resolvePassword_withEnvValueAndForce_prefersEnvValue() {
        TransferPasswordResolver uut = resolverWith("PRJXP_TRANSFER_PASSWORD", "env-wins");
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true));

        char[] password = uut.resolvePassword("PRJXP_TRANSFER_PASSWORD", true);

        assertThat(password).isEqualTo("env-wins".toCharArray());
        assertThat(captured.toString()).doesNotContain("[SECURITY] Generated transfer password:");
    }

    @Test
    void generatePassword_isUrlSafeBase64() {
        TransferPasswordResolver uut = resolverWith("X", null);

        String password = new String(uut.generatePassword());

        assertThat(password).matches("[A-Za-z0-9_-]{43}");
    }

    @Test
    void generatePassword_twice_yieldsDifferentPasswords() {
        TransferPasswordResolver uut = resolverWith("X", null);

        assertThat(new String(uut.generatePassword())).isNotEqualTo(new String(uut.generatePassword()));
    }

    @Test
    void printRepeatBanner_printsPasswordAgain() {
        TransferPasswordResolver uut = resolverWith("X", null);
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true));

        uut.printRepeatBanner("abc123".toCharArray());

        String console = captured.toString();
        assertThat(console).contains("[SECURITY] REPEAT transfer password:");
        assertThat(console).contains("abc123");
    }

    @Test
    void prepare_forceOnWithoutEnv_generatesPasswordAndPrintsBanner() {
        TransferPasswordResolver uut = resolverWith("PRJXP_TRANSFER_PASSWORD", null);
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true));

        TransferSession session = uut.prepare(TransferEncryptMode.TRUE, "PRJXP_TRANSFER_PASSWORD");

        assertThat(session.encrypt()).isTrue();
        assertThat(session.generatedPassword()).isTrue();
        assertThat(session.password()).isNotNull().isNotEmpty();
        String console = captured.toString();
        assertThat(console).contains("[SECURITY] Generated transfer password:");
        assertThat(console).contains(new String(session.password()));
    }

    @Test
    void prepare_forceOnWithEnv_usesEnvPasswordWithoutBanner() {
        TransferPasswordResolver uut = resolverWith("PRJXP_TRANSFER_PASSWORD", "env-pw");
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true));

        TransferSession session = uut.prepare(TransferEncryptMode.TRUE, "PRJXP_TRANSFER_PASSWORD");

        assertThat(session.encrypt()).isTrue();
        assertThat(session.generatedPassword()).isFalse();
        assertThat(session.password()).isEqualTo("env-pw".toCharArray());
        assertThat(captured.toString()).doesNotContain("[SECURITY] Generated transfer password:");
    }

    @Test
    void prepare_autoWithEnv_encrypts() {
        TransferPasswordResolver uut = resolverWith("PRJXP_TRANSFER_PASSWORD", "env-pw");

        TransferSession session = uut.prepare(TransferEncryptMode.AUTO, "PRJXP_TRANSFER_PASSWORD");

        assertThat(session.encrypt()).isTrue();
        assertThat(session.password()).isEqualTo("env-pw".toCharArray());
    }

    @Test
    void prepare_autoWithoutEnv_neverEncrypts() {
        TransferPasswordResolver uut = resolverWith("PRJXP_TRANSFER_PASSWORD", null);

        TransferSession session = uut.prepare(TransferEncryptMode.AUTO, "PRJXP_TRANSFER_PASSWORD");

        assertThat(session.encrypt()).isFalse();
        assertThat(session.password()).isNull();
    }

    @Test
    void prepare_forceOffWithEnv_neverEncrypts() {
        TransferPasswordResolver uut = resolverWith("PRJXP_TRANSFER_PASSWORD", "env-pw");

        TransferSession session = uut.prepare(TransferEncryptMode.FALSE, "PRJXP_TRANSFER_PASSWORD");

        assertThat(session.encrypt()).isFalse();
        assertThat(session.password()).isNull();
    }
}
