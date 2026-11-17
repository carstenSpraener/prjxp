package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.config.ProjectDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConfigCatalogServiceTest {

    @TempDir
    Path root;

    @Mock
    private ProjectRegistry projectRegistry;

    private ConfigCatalogService service;

    @BeforeEach
    void setUp() {
        service = new ConfigCatalogService(projectRegistry);
    }

    private void stubProject() {
        ProjectDefinition definition = new ProjectDefinition();
        definition.setName("demo");
        definition.setRootDir(root.toString());
        when(projectRegistry.definitionOf("demo")).thenReturn(definition);
    }

    @Test
    void readsYamlValueAnnotationsAndMarkdownTables() throws Exception {
        stubProject();
        Files.createDirectories(root.resolve("src/main/resources"));
        Files.writeString(root.resolve("src/main/resources/application.yaml"), """
                prjxp:
                  embedding:
                    modelName: mxbai
                    timeout: 20
                """);
        Files.createDirectories(root.resolve("src/main/java/demo/config"));
        Files.writeString(root.resolve("src/main/java/demo/config/DemoConfig.java"), """
                package demo.config;
                import org.springframework.beans.factory.annotation.Value;
                public class DemoConfig {
                    @Value("${prjxp.demo.enabled:true}")
                    private boolean enabled;
                }
                """);
        Files.createDirectories(root.resolve("doc"));
        Files.writeString(root.resolve("doc/configuration.md"), """
                | Property | Typ | Default | Beschreibung |
                |---|---|---|---|
                | `prjxp.demo.mode` | String | `fast` | Der Modus. |
                """);

        ConfigCatalog catalog = service.list("demo");

        assertThat(catalog.error()).isNull();
        assertThat(catalog.properties()).extracting(ConfigProperty::name)
                .contains("prjxp.embedding.modelName", "prjxp.embedding.timeout",
                        "prjxp.demo.enabled", "prjxp.demo.mode");
        assertThat(catalog.properties()).filteredOn(p -> p.name().equals("prjxp.demo.enabled"))
                .singleElement().satisfies(p -> assertThat(p.defaultValue()).isEqualTo("true"));
        assertThat(catalog.properties()).filteredOn(p -> p.name().equals("prjxp.demo.mode"))
                .singleElement().satisfies(p -> {
                    assertThat(p.defaultValue()).isEqualTo("fast");
                    assertThat(p.description()).isEqualTo("Der Modus.");
                });
    }

    @Test
    void unknownProjectReturnsAnError() {
        org.mockito.Mockito.doThrow(new UnknownProjectException("nope", java.util.List.of("demo")))
                .when(projectRegistry).ensureSearchable("nope");

        assertThat(service.list("nope").error()).contains("nope");
    }
}
