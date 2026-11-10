package de.spraener.prjxp.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Global re-ranking knobs ({@code prjxp.graph.rerank.*}).
 * Retriever path (additive): {@code wBase*base + min(bGraph*weight, cap)}.
 * REST vector search (multiplicative): {@code sim*(floor + (1-floor)*weight)}.
 */
@Component
@ConfigurationProperties(prefix = "prjxp.graph.rerank")
@Data
public class GraphRerankConfig {
    private double floor = 0.8;
    private double bGraph = 5.0;
    private double cap = 5.0;
    private double wBase = 1.0;
}
