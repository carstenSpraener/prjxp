package de.spraener.prjxp.mcp.hub;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(HubProperties.class)
public class HubPropertiesConfiguration {}
