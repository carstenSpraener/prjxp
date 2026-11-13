package de.spraener.prjxp.common.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkPathPreferenceTest {

    @Test
    void detectsTestFixtureAndBuildPaths() {
        assertThat(ChunkPathPreference.isTestPath("docpipe/src/test/resources/fixtures/x/Hello.java")).isTrue();
        assertThat(ChunkPathPreference.isTestPath("prjxp-common/src/testFixtures/resources/plugin-fixtures/x/LanguagePack.java")).isTrue();
        assertThat(ChunkPathPreference.isTestPath("core/build/generated/x/Hello.java")).isTrue();
        assertThat(ChunkPathPreference.isTestPath("prjxp-common/src/main/java/de/spraener/prjxp/common/model/PxChunk.java")).isFalse();
        assertThat(ChunkPathPreference.isTestPath(null)).isFalse();
    }

    @Test
    void productionPathReplacesFixtureButNotAnotherProductionPath() {
        assertThat(ChunkPathPreference.prefer(
                "prjxp-common/src/main/java/LanguagePack.java",
                "prjxp-common/src/testFixtures/resources/plugin-fixtures/x/LanguagePack.java")).isTrue();
        assertThat(ChunkPathPreference.prefer(
                "prjxp-common/src/testFixtures/resources/a/LanguagePack.java",
                "prjxp-common/src/main/java/LanguagePack.java")).isFalse();
        assertThat(ChunkPathPreference.prefer(
                "a/Main.java", "b/Main.java")).isFalse();
    }
}
