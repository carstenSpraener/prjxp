package de.spraener.prjxp.common.transfer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TransferSessionTest {
    @Test
    void from_forceOnWithPassword_encrypts() {
        TransferSession session = TransferSession.from(TransferEncryptMode.TRUE, "pw".toCharArray(), false);

        assertThat(session.encrypt()).isTrue();
        assertThat(session.password()).isEqualTo("pw".toCharArray());
        assertThat(session.generatedPassword()).isFalse();
    }

    @Test
    void from_forceOnWithoutPassword_stillEncrypts() {
        TransferSession session = TransferSession.from(TransferEncryptMode.TRUE, null, false);

        assertThat(session.encrypt()).isTrue();
        assertThat(session.password()).isNull();
    }

    @Test
    void from_forceOffWithPassword_neverEncrypts() {
        TransferSession session = TransferSession.from(TransferEncryptMode.FALSE, "pw".toCharArray(), false);

        assertThat(session.encrypt()).isFalse();
        assertThat(session.password()).isNull();
    }

    @Test
    void from_autoWithPassword_encrypts() {
        TransferSession session = TransferSession.from(TransferEncryptMode.AUTO, "pw".toCharArray(), false);

        assertThat(session.encrypt()).isTrue();
        assertThat(session.password()).isEqualTo("pw".toCharArray());
    }

    @Test
    void from_autoWithoutPassword_neverEncrypts() {
        TransferSession session = TransferSession.from(TransferEncryptMode.AUTO, null, false);

        assertThat(session.encrypt()).isFalse();
        assertThat(session.password()).isNull();
    }
}
