package de.spraener.prjxp.mcp.hub;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@ConditionalOnProperty(name = "prjxp.hub.enabled", havingValue = "true")
@EnableScheduling
@Import(HubPropertiesConfiguration.class)
public class HubConfig {
}
