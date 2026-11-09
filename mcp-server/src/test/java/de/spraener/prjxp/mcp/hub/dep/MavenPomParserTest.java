package de.spraener.prjxp.mcp.hub.dep;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 02: {@link MavenPomParser} maps POM XML onto a {@link MavenPomParser.PomModel}.
 * Pure string → model; no I/O.
 */
class MavenPomParserTest {

    private final MavenPomParser parser = new MavenPomParser();

    @Test
    void parsesExplicitGavAndDependencies() throws MavenDependencyException {
        String pom = """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>demo</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>org.jsoup</groupId>
                      <artifactId>jsoup</artifactId>
                      <version>1.23.2</version>
                    </dependency>
                  </dependencies>
                </project>
                """;

        MavenPomParser.PomModel model = parser.parse(pom);

        assertThat(model.groupId()).isEqualTo("org.example");
        assertThat(model.artifactId()).isEqualTo("demo");
        assertThat(model.version()).isEqualTo("1.0.0");
        assertThat(model.parent()).isNull();
        assertThat(model.dependencies()).containsExactly(
                new MavenPomParser.DepRef("org.jsoup", "jsoup", "1.23.2", null));
        assertThat(model.dependencyManagement()).isEmpty();
    }

    @Test
    void interpolatesPropertyPlaceholdersInDependencyVersions() throws MavenDependencyException {
        String pom = """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>demo</artifactId>
                  <version>1.0.0</version>
                  <properties>
                    <demo.version>2.0.0</demo.version>
                  </properties>
                  <dependencies>
                    <dependency>
                      <groupId>org.example</groupId>
                      <artifactId>lib</artifactId>
                      <version>${demo.version}</version>
                    </dependency>
                  </dependencies>
                </project>
                """;

        MavenPomParser.PomModel model = parser.parse(pom);

        assertThat(model.properties()).containsEntry("demo.version", "2.0.0");
        assertThat(model.dependencies()).containsExactly(
                new MavenPomParser.DepRef("org.example", "lib", "2.0.0", null));
    }

    @Test
    void inheritsGroupIdAndVersionFromParent() throws MavenDependencyException {
        String pom = """
                <project>
                  <parent>
                    <groupId>org.example</groupId>
                    <artifactId>parent-pom</artifactId>
                    <version>3.0.0</version>
                  </parent>
                  <artifactId>child</artifactId>
                </project>
                """;

        MavenPomParser.PomModel model = parser.parse(pom);

        assertThat(model.groupId()).isEqualTo("org.example");
        assertThat(model.artifactId()).isEqualTo("child");
        assertThat(model.version()).isEqualTo("3.0.0");
        assertThat(model.parent()).isEqualTo(
                new MavenPomParser.ParentRef("org.example", "parent-pom", "3.0.0"));
    }

    @Test
    void keepsTestScopedDependenciesInList() throws MavenDependencyException {
        String pom = """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>demo</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>org.junit.jupiter</groupId>
                      <artifactId>junit-jupiter</artifactId>
                      <version>5.10.0</version>
                      <scope>test</scope>
                    </dependency>
                  </dependencies>
                </project>
                """;

        MavenPomParser.PomModel model = parser.parse(pom);

        assertThat(model.dependencies()).containsExactly(
                new MavenPomParser.DepRef("org.junit.jupiter", "junit-jupiter", "5.10.0", "test"));
    }

    @Test
    void parsesDependencyManagement() throws MavenDependencyException {
        String pom = """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>demo</artifactId>
                  <version>1.0.0</version>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.example</groupId>
                        <artifactId>managed-lib</artifactId>
                        <version>4.0.0</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """;

        MavenPomParser.PomModel model = parser.parse(pom);

        assertThat(model.dependencyManagement()).containsExactly(
                new MavenPomParser.DepRef("org.example", "managed-lib", "4.0.0", null));
        assertThat(model.dependencies()).isEmpty();
    }

    @Test
    void doctypeDeclarationIsRejected() {
        String pom = """
                <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <project><artifactId>&xxe;</artifactId></project>
                """;

        assertThatThrownBy(() -> parser.parse(pom)).isInstanceOf(MavenDependencyException.class);
    }

    @Test
    void malformedXmlThrows() {
        assertThatThrownBy(() -> parser.parse("<project><artifactId>demo"))
                .isInstanceOf(MavenDependencyException.class);
    }
}
