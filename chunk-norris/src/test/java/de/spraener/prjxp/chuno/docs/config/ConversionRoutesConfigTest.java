package de.spraener.prjxp.chuno.docs.config;

import de.spraener.prjxp.chuno.docs.DocConversionAgent;
import de.spraener.prjxp.chuno.docs.model.ConversionAccuracy;
import de.spraener.prjxp.chuno.docs.model.CostEstimation;
import de.spraener.prjxp.chuno.docs.model.DocArtifakt;
import de.spraener.prjxp.chuno.docs.model.DocArtifaktType;
import de.spraener.prjxp.common.util.SpringContextSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class ConversionRoutesConfigTest {

    @Autowired
    private ConversionRoutesConfig conversionRoutesConfig;

    // Dummy-Beans für die Test-Konverter, die in application-test-routes.yml referenziert werden
    @Configuration
    static class TestConfig {

        @Bean
        public SpringContextSupplier  springContextSupplier(ApplicationContext applicationContext) {
            SpringContextSupplier bean = new SpringContextSupplier();
            bean.setApplicationContext(applicationContext);
            return bean;
        }

        @Bean
        public List<ConversionRoute> conversionRoutes() {
            return List.of(
                    new ConversionRoute(
                            "pdfToMarkdown",
                            "PDF",
                            "MARK_DOWN",
                            List.of("pdfExtractorAgent", "markdownGeneratorAgent")
                    )
            );
        }

        @Bean
        public ConversionRoutesConfig conversionRoutesConfig(SpringContextSupplier contextSupplier, List<ConversionRoute> conversionRoutes) {
            ConversionRoutesConfig config = new ConversionRoutesConfig(contextSupplier);
            config.setPredefinedRoutes(conversionRoutes);

            return config;
        }
        @Bean
        public DocConversionAgent<?, ?> pdfExtractorAgent() {
            return new DummyConversionAgent(DocArtifaktType.PDF, DocArtifaktType.TEXT, "pdfExtractorAgent");
        }

        @Bean
        public DocConversionAgent<?, ?> markdownGeneratorAgent() {
            return new DummyConversionAgent(DocArtifaktType.TEXT, DocArtifaktType.MARK_DOWN, "markdownGeneratorAgent");
        }
    }

    // Eine einfache Dummy-Implementierung für DocConversionAgent für Testzwecke
    static class DummyConversionAgent implements DocConversionAgent<Object, Object> {
        private final String name;
        private DocArtifaktType source;
        private DocArtifaktType target;

        public DummyConversionAgent(DocArtifaktType source, DocArtifaktType target, String name) {
            this.source = source;
            this.target = target;
            this.name = name;
        }
        @Override
        public String toString() {
            return name;
        }

        @Override
        public DocArtifaktType getSourceFormat() {
            return source;
        }

        @Override
        public DocArtifaktType getTargetFormat() {
            return target;
        }

        @Override
        public double estimateCosts(DocArtifakt<Object, ?> artifakt) {
            return CostEstimation.SIMPLE;
        }

        @Override
        public int estimateQuantity(DocArtifakt<Object, ?> artifakt) {
            return 1;
        }

        @Override
        public void convert(DocArtifakt<Object, ?> artifakt) {
        }
    }

    @Test
    void testPredefinedPdfToMarkdownRouteIsFound() {
        // Abfrage der definierten Route
        List<DocConversionAgent<?, ?>> agents = conversionRoutesConfig.findPredefinedRouteAgents(DocArtifaktType.PDF, DocArtifaktType.MARK_DOWN);

        assertNotNull(agents, "Die Liste der Agents sollte nicht null sein.");
        assertFalse(agents.isEmpty(), "Es sollte eine vordefinierte Route für PDF nach MARKDOWN gefunden werden.");
        assertEquals(2, agents.size(), "Es sollten zwei Agents in der Route sein.");
        assertEquals("pdfExtractorAgent", agents.get(0).toString(), "Der erste Agent sollte 'pdfExtractorAgent' sein.");
        assertEquals("markdownGeneratorAgent", agents.get(1).toString(), "Der zweite Agent sollte 'markdownGeneratorAgent' sein.");
    }

    @Test
    void testUndefinedWordDocxToMarkdownRouteIsNotFound() {
        // Abfrage einer nicht definierten Route
        List<DocConversionAgent<?, ?>> agents = conversionRoutesConfig.findPredefinedRouteAgents(DocArtifaktType.WORD_DOC, DocArtifaktType.MARK_DOWN);

        assertNotNull(agents, "Die Liste der Agents sollte nicht null sein.");
        assertTrue(agents.isEmpty(), "Es sollte KEINE vordefinierte Route für WORD_DOCX nach MARKDOWN gefunden werden.");
    }
}
