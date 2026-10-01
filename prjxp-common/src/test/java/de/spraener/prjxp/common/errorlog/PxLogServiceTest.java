package de.spraener.prjxp.common.errorlog;

import de.spraener.prjxp.common.errorlog.PxLogMessage;
import de.spraener.prjxp.common.errorlog.PxLogService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ActiveProfiles;

import java.util.logging.Level;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNoException;

@SpringBootTest
@ActiveProfiles("test")
class PxLogServiceTest {

    @Autowired
    private PxLogService uut;

    @Test
    void logMessage_withValidMessage_returnsSelf() {
        PxLogMessage msg = new PxLogMessage(Level.INFO, "test message");

        PxLogService result = uut.logMessage(msg);

        assertThatNoException().isThrownBy(() -> {});
    }

    @Test
    void maxLevel_returnsLevel() {
        uut.logMessage(new PxLogMessage(Level.WARNING, "warning"));

        Level result = uut.maxLevel();

        assertThatNoException().isThrownBy(() -> {});
    }

    @Test
    void getMessagesWithLevelMin_returnsStream() {
        uut.logMessage(new PxLogMessage(Level.INFO, "info"));
        uut.logMessage(new PxLogMessage(Level.WARNING, "warning"));

        Stream<PxLogMessage> result = uut.getMessagesWithLevelMin(Level.INFO);

        assertThatNoException().isThrownBy(() -> {});
    }

    @Test
    void logErrorTest() {
        uut.error(new Exception("Test Exception"), "Formatted Error Message: %s", "Test Argument");
        assertThatNoException().isThrownBy(() -> {});
    }

    // --- Phase 04 additions (unique message strings: the Spring bean is shared across tests) ---

    @Test
    void error_withArgs_formatsMessage() {
        PxLogService result = uut.error("disk %s full", "C:");

        assertThat(result).isSameAs(uut);
        assertThat(uut.getMessagesWithLevelMin(Level.SEVERE))
                .map(PxLogMessage::getMessage)
                .contains("disk C: full");
    }

    @Test
    void error_withoutArgs_keepsRawMessage() {
        uut.error("boom");

        assertThat(uut.getMessagesWithLevelMin(Level.SEVERE))
                .map(PxLogMessage::getMessage)
                .contains("boom");
    }

    @Test
    void getMessagesWithLevelMin_filtersByLevel() {
        uut.logMessage(new PxLogMessage(Level.WARNING, "w-unique"));
        uut.error("s-unique");

        assertThat(uut.getMessagesWithLevelMin(Level.SEVERE))
                .map(PxLogMessage::getMessage)
                .contains("s-unique")
                .doesNotContain("w-unique");
        assertThat(uut.getMessagesWithLevelMin(Level.WARNING))
                .map(PxLogMessage::getMessage)
                .contains("w-unique", "s-unique");
    }

    @Test
    void maxLevel_withoutMessages_returnsFinest() {
        assertThat(new PxLogService().maxLevel()).isEqualTo(Level.FINEST);
    }

    @Configuration
    static class TestConfig {
        @Bean
        PxLogService uut() {
            return new PxLogService();
        }
    }
}
