package de.spraener.prjxp.common.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestPathFilterTest {

    @Test
    void srcTestPathIsTestPath() {
        assertThat(TestPathFilter.isTestPath("src/test/java/FooTest.java")).isTrue();
    }

    @Test
    void srcTestFixturesPathIsTestPath() {
        assertThat(TestPathFilter.isTestPath("src/testFixtures/java/Foo.java")).isTrue();
    }

    @Test
    void mainPathIsNoTestPath() {
        assertThat(TestPathFilter.isTestPath("src/main/java/Foo.java")).isFalse();
    }

    @Test
    void nullOrBlankIsNoTestPath() {
        assertThat(TestPathFilter.isTestPath(null)).isFalse();
        assertThat(TestPathFilter.isTestPath("")).isFalse();
        assertThat(TestPathFilter.isTestPath("   ")).isFalse();
    }

    @Test
    void testDirectoryOutsideSrcIsNoTestPath() {
        // "/test/" alone is not enough — the marker is "/src/test/"
        assertThat(TestPathFilter.isTestPath("src/main/java/test/Foo.java")).isFalse();
    }

    @Test
    void nestedModulePaths() {
        assertThat(TestPathFilter.isTestPath("module-a/src/test/java/FooTest.java")).isTrue();
        assertThat(TestPathFilter.isTestPath("module-a/src/testFixtures/java/Foo.java")).isTrue();
        assertThat(TestPathFilter.isTestPath("module-a/src/main/java/Foo.java")).isFalse();
    }

    @Test
    void backslashPathsAreNormalized() {
        assertThat(TestPathFilter.isTestPath("src\\test\\java\\FooTest.java")).isTrue();
        assertThat(TestPathFilter.isTestPath("src\\main\\java\\Foo.java")).isFalse();
    }
}
