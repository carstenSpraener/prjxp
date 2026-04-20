package de.spraener.prjxp.mcp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("#{'${mcp.cors.allowed-patterns}'.split(',')}")
    private List<String> allowedPatterns;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/prjxp/tools/**")
                .allowedOriginPatterns(allowedPatterns.toArray(new String[0]))
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);

        registry.addMapping("/v3/api-docs/**")
                .allowedOriginPatterns(allowedPatterns.toArray(new String[0]));
    }
}