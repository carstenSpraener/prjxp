package de.spraener.prjxp.mcp.hub;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@ConditionalOnProperty(name = "prjxp.hub.enabled", havingValue = "true")
@EnableScheduling
@EnableConfigurationProperties(HubProperties.class)
public class HubConfig {
}
