package de.spraener.prjxp.common.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GraphRerankConfigTest {

    @Test
    void defaultsMatchCalibratedValues() {
        GraphRerankConfig cfg = new GraphRerankConfig();

        assertThat(cfg.getFloor()).isEqualTo(0.8);
        assertThat(cfg.getBGraph()).isEqualTo(5.0);
        assertThat(cfg.getCap()).isEqualTo(5.0);
        assertThat(cfg.getWBase()).isEqualTo(1.0);
    }

    @Test
    void settersOverrideDefaults() {
        GraphRerankConfig cfg = new GraphRerankConfig();
        cfg.setFloor(0.5);
        cfg.setBGraph(2.0);
        cfg.setCap(1.0);
        cfg.setWBase(0.5);

        assertThat(cfg.getFloor()).isEqualTo(0.5);
        assertThat(cfg.getBGraph()).isEqualTo(2.0);
        assertThat(cfg.getCap()).isEqualTo(1.0);
        assertThat(cfg.getWBase()).isEqualTo(0.5);
    }
}
