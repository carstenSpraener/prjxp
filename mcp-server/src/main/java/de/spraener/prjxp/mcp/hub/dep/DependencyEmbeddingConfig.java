package de.spraener.prjxp.mcp.hub.dep;

import de.spraener.prjxp.mcp.hub.HubProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

/** Wires the dependency-embedding beans (active only in hub mode). */
@Configuration
@ConditionalOnProperty(name = "prjxp.hub.enabled", havingValue = "true")
public class DependencyEmbeddingConfig {

    @Bean
    public MavenCentralClient mavenCentralClient(HubProperties props) {
        String repoBase = props.getMavenRepositories().split(",")[0].trim();
        return new MavenCentralClient(repoBase,
                Duration.ofSeconds(props.getMavenConnectTimeoutSeconds()),
                Duration.ofSeconds(props.getMavenRequestTimeoutSeconds()),
                props.getMaxDependencyBytes());
    }

    @Bean
    public MavenPomParser mavenPomParser() { return new MavenPomParser(); }

    @Bean
    public MavenDependencyResolver mavenDependencyResolver(MavenCentralClient client, MavenPomParser parser) {
        return new MavenDependencyResolver(client, parser);
    }

    @Bean
    public BuildGradleParser buildGradleParser() { return new BuildGradleParser(); }

    @Bean
    public GradleDependencyResolver gradleDependencyResolver(BuildGradleParser parser, HubProperties props) {
        return new GradleDependencyResolver(parser, props.getGradleTimeoutSeconds());
    }

    @Bean
    public List<DependencyResolver> dependencyResolvers(MavenDependencyResolver maven, GradleDependencyResolver gradle) {
        return List.of(maven, gradle);
    }
}
