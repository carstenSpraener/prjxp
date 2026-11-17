package de.spraener.prjxp.mcp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HubToolProfileTest {

    @Test
    void defaultsToP1WithoutBinding() {
        HubToolProfile profile = new HubToolProfile(null, null);

        assertThat(profile.profile()).isEqualTo(HubToolProfile.Profile.P1);
        assertThat(profile.isP2()).isFalse();
        assertThat(profile.projectOrDefault("prjxp")).isEqualTo("prjxp");
    }

    @Test
    void p2RequiresABoundProject() {
        assertThatThrownBy(() -> new HubToolProfile("p2", " "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bound-project");
    }

    @Test
    void p2BindsEveryToolCallToItsProject() {
        HubToolProfile profile = new HubToolProfile("P2", "legacy");

        assertThat(profile.isP2()).isTrue();
        assertThat(profile.projectOrDefault("ignored")).isEqualTo("legacy");
    }

    @Test
    void unknownProfileFailsFast() {
        assertThatThrownBy(() -> new HubToolProfile("p3", "prjxp"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unknown");
    }
}
