package de.spraener.prjxp.mcp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HubToolProfileTest {

    @Test
    void defaultsToP1() {
        HubToolProfile profile = new HubToolProfile(null);

        assertThat(profile.profile()).isEqualTo(HubToolProfile.Profile.P1);
        assertThat(profile.isP2()).isFalse();
        assertThat(profile.projectOrDefault("prjxp")).isEqualTo("prjxp");
    }

    @Test
    void p2NeedsNoProjectAtStartup() {
        HubToolProfile profile = new HubToolProfile("P2");

        assertThat(profile.isP2()).isTrue();
    }

    @Test
    void bothProfilesUseTheProjectOfTheCall() {
        assertThat(new HubToolProfile("P2").projectOrDefault("legacy")).isEqualTo("legacy");
        assertThat(new HubToolProfile("p1").projectOrDefault("prjxp")).isEqualTo("prjxp");
    }

    @Test
    void unknownProfileFailsFast() {
        assertThatThrownBy(() -> new HubToolProfile("p3"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unknown");
    }
}
