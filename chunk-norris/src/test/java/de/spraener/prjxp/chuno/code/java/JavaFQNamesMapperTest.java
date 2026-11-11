package de.spraener.prjxp.chuno.code.java;

import de.spraener.prjxp.chuno.spring.SpringPreWalkEvent;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.ProjectDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class JavaFQNamesMapperTest {

    @TempDir
    Path root;

    private JavaFQNamesMapper mapper;

    private SpringPreWalkEvent<PrjXPConfig> eventFor(Path rootDir) {
        ProjectDefinition pd = new ProjectDefinition();
        pd.setName("p");
        pd.setRootDir(rootDir.toString());
        return new SpringPreWalkEvent<>(new PrjXPConfig(), pd);
    }

    @Test
    void oneUnparseableFileMustNotEmptyTheMap() throws Exception {
        Files.writeString(root.resolve("Good.java"),
                "package demo;\npublic class Good {}\n");
        Files.writeString(root.resolve("Bad.java"),
                "package demo;\npublic class Bad { // unclosed brace\n");

        mapper = new JavaFQNamesMapper(new JavaParserFactory());
        assertThatCode(() -> mapper.fillFQNamesMap(eventFor(root)))
                .doesNotThrowAnyException();

        assertThat(mapper.getFQName("Good", "demo", List.of()))
                .isEqualTo("demo.Good");
    }

    @Test
    void onlyUnparseableFilesLeavesEmptyMapWithoutException() throws Exception {
        Files.writeString(root.resolve("Bad1.java"),
                "class { }\n");
        Files.writeString(root.resolve("Bad2.java"),
                "package demo;\npublic class Broken {\n");

        mapper = new JavaFQNamesMapper(new JavaParserFactory());
        assertThatCode(() -> mapper.fillFQNamesMap(eventFor(root)))
                .doesNotThrowAnyException();

        assertThat(mapper.getFQName("Broken", "demo", List.of()))
                .isEmpty();
    }

    @Test
    void java14PlusFeaturesMustParse() throws Exception {
        Files.writeString(root.resolve("Rec.java"),
                "package demo;\npublic record Rec(int a, String b) {}\n");

        mapper = new JavaFQNamesMapper(new JavaParserFactory());
        mapper.fillFQNamesMap(eventFor(root));

        assertThat(mapper.getFQName("Rec", "demo", List.of()))
                .isEqualTo("demo.Rec");
    }
}
