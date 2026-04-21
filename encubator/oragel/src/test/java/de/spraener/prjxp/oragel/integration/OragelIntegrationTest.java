package de.spraener.prjxp.oragel.integration;

import de.spraener.prjxp.gldrtrvr.PxChunkDao;
import de.spraener.prjxp.gldrtrvr.enrichment.GRPromptEnrichment;
import de.spraener.prjxp.oragel.ClipboardService;
import de.spraener.prjxp.oragel.OragelCliRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest()
@ActiveProfiles("test")
class OragelIntegrationTest {

    @Autowired
    private OragelCliRunner oragelRunner;

    @Autowired
    private TestPromptSource testPromptSource;

    @Autowired
    private java.util.List<Object> allBeans;

    @MockitoBean
    private GRPromptEnrichment grPromptEnrichmentMock;

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        public TestPromptSource testPromptSource() {
            return new TestPromptSource();
        }

        @Bean
        public PxChunkDao pxChunkDao() {
            return Mockito.mock(PxChunkDao.class);
        }

    }

    @BeforeEach
    void setup() {
        when(grPromptEnrichmentMock.enrich(any())).thenAnswer(i -> i.getArgument(0));
        System.setProperty("java.awt.headless", "false");
    }

    @Test
    void testFullFlowToClipboard() throws Exception {
        for (var b : allBeans) {
            if (b.getClass().getName().startsWith("de.spraener.prjxp")) {
                System.out.println("Bean: " + b.getClass().getName());
            }
        }

        clearClipboard();

        // 1. Einzigartige Test-Frage generieren
        String uniqueId = UUID.randomUUID().toString();
        String testQuestion = "Wie funktioniert Modul X? " + uniqueId;
        testPromptSource.ask(testQuestion);

        // 2. Runner starten (er verarbeitet die Frage und beendet sich beim "exit")
        oragelRunner.startApp(new String[]{});
        // 3. System-Zwischenablage auslesen
        String clipboardContent = (String) Toolkit.getDefaultToolkit()
                .getSystemClipboard()
                .getData(DataFlavor.stringFlavor);

        // 4. Verifizieren: Der angereicherte Prompt muss die ID enthalten
        assertThat(clipboardContent)
                .contains(testQuestion)
        ;
    }

    private void clearClipboard() {
        try {
            StringSelection emptySelection = new StringSelection("");
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(emptySelection, null);
        } catch (Exception e) {
            // Falls die Zwischenablage blockiert ist
        }
    }
}