package de.spraener.prjxp.common.errorlog;

import org.junit.jupiter.api.Test;

import java.util.logging.Level;

import static org.assertj.core.api.Assertions.assertThat;

class PxLogMessageTest {

    @Test
    void constructor_severe_storesLevelAndMessage() {
        PxLogMessage msg = new PxLogMessage(Level.SEVERE, "boom");

        assertThat(msg.getLevel()).isEqualTo(Level.SEVERE);
        assertThat(msg.getMessage()).isEqualTo("boom");
    }

    @Test
    void constructor_warning_storesLevelAndMessage() {
        PxLogMessage msg = new PxLogMessage(Level.WARNING, "w");

        assertThat(msg.getLevel()).isEqualTo(Level.WARNING);
        assertThat(msg.getMessage()).isEqualTo("w");
    }
}
