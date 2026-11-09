package de.spraener.prjxp.mcp.hub.dep;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 03: {@link DependencyResolution#toEmbeddable} — the shared embeddable
 * post-processing (dedup / depth bound / GroupId whitelist / classifier probe).
 * Pure: a stub {@link MavenCentralClient} stands in for the network.
 */
class DependencyResolutionTest {

    private static final MavenCoordinates A = new MavenCoordinates("org.example", "a", "1.0.0");
    private static final MavenCoordinates B = new MavenCoordinates("org.example", "b", "1.0.0");
    private static final MavenCoordinates SPRING = new MavenCoordinates("org.springframework", "spring-core", "6.0.0");
    private static final MavenCoordinates FOO = new MavenCoordinates("com.foo", "bar", "1.0.0");

    /** No network: classifier existence is scripted per GAV. */
    private static class StubClient extends MavenCentralClient {
        private final Set<String> sources = new HashSet<>();
        private final Set<String> javadoc = new HashSet<>();

        StubClient() {
            super("http://stub.invalid", Duration.ofSeconds(1), Duration.ofSeconds(1), 1024);
        }

        void addSources(MavenCoordinates c) { sources.add(c.gav()); }
        void addJavadoc(MavenCoordinates c) { javadoc.add(c.gav()); }

        @Override
        public boolean exists(MavenCoordinates c, String classifier) {
            return "sources".equals(classifier) ? sources.contains(c.gav()) : javadoc.contains(c.gav());
        }

        @Override
        public String fetchPom(MavenCoordinates c) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public void download(MavenCoordinates c, String classifier, Path target) {
            throw new UnsupportedOperationException("stub");
        }
    }

    private static DependencyResolveConfig config(int maxDepth, String... prefixes) {
        return new DependencyResolveConfig(maxDepth, List.of(prefixes));
    }

    @Test
    void toEmbeddableDedupsByGavKeepingShallowest() throws MavenDependencyException {
        StubClient client = new StubClient();
        client.addSources(A);
        client.addSources(B);
        DependencyResolution res = new DependencyResolution(
                List.of(new RawDependency(A, 2), new RawDependency(A, 1), new RawDependency(B, 1)),
                config(5));

        List<ResolvedDependency> out = res.toEmbeddable(client);

        assertThat(out).containsExactly(
                new ResolvedDependency(A, 1, true, false),
                new ResolvedDependency(B, 1, true, false));
    }

    @Test
    void toEmbeddableAppliesDepthBound() throws MavenDependencyException {
        StubClient client = new StubClient();
        client.addSources(A);
        client.addSources(B);
        DependencyResolution res = new DependencyResolution(
                List.of(new RawDependency(A, 1), new RawDependency(B, 3)),
                config(2));

        assertThat(res.toEmbeddable(client)).containsExactly(
                new ResolvedDependency(A, 1, true, false));
    }

    @Test
    void toEmbeddableAppliesGroupIdWhitelist() throws MavenDependencyException {
        StubClient client = new StubClient();
        client.addSources(SPRING);
        client.addSources(FOO);
        DependencyResolution res = new DependencyResolution(
                List.of(new RawDependency(SPRING, 1), new RawDependency(FOO, 1)),
                config(5, "org.spring"));

        assertThat(res.toEmbeddable(client)).containsExactly(
                new ResolvedDependency(SPRING, 1, true, false));
    }

    @Test
    void toEmbeddableEmptyWhitelistKeepsEverything() throws MavenDependencyException {
        StubClient client = new StubClient();
        client.addSources(SPRING);
        client.addSources(FOO);
        DependencyResolution res = new DependencyResolution(
                List.of(new RawDependency(SPRING, 1), new RawDependency(FOO, 1)),
                config(5));

        assertThat(res.toEmbeddable(client)).containsExactly(
                new ResolvedDependency(SPRING, 1, true, false),
                new ResolvedDependency(FOO, 1, true, false));
    }

    @Test
    void toEmbeddableDropsDependenciesWithoutAnyClassifier() throws MavenDependencyException {
        StubClient client = new StubClient();
        client.addSources(A);   // A: sources only
        // B: neither sources nor javadoc
        DependencyResolution res = new DependencyResolution(
                List.of(new RawDependency(A, 1), new RawDependency(B, 1)),
                config(5));

        assertThat(res.toEmbeddable(client)).containsExactly(
                new ResolvedDependency(A, 1, true, false));
    }

    @Test
    void isEmbeddableWhenAtLeastOneClassifierPresent() {
        assertThat(new ResolvedDependency(A, 1, true, false).isEmbeddable()).isTrue();
        assertThat(new ResolvedDependency(A, 1, false, true).isEmbeddable()).isTrue();
        assertThat(new ResolvedDependency(A, 1, false, false).isEmbeddable()).isFalse();
    }

    @Test
    void rawListIsDefensivelyCopied() {
        List<RawDependency> raw = new ArrayList<>(List.of(new RawDependency(A, 1)));
        DependencyResolution res = new DependencyResolution(raw, config(5));

        raw.add(new RawDependency(B, 1));

        assertThat(res.raw()).hasSize(1);
        assertThat(res.config()).isEqualTo(config(5));
    }

    @Test
    void fromDefinitionParsesCsvAndClampsDepth() {
        DependencyResolveConfig cfg = DependencyResolveConfig.fromDefinition(true, 0, "org.spring, com.foo ,");

        assertThat(cfg.maxDepth()).isEqualTo(1);
        assertThat(cfg.groupIdPrefixes()).containsExactly("org.spring", "com.foo");
    }

    @Test
    void fromDefinitionNullWhitelistMeansNoFilter() {
        DependencyResolveConfig cfg = DependencyResolveConfig.fromDefinition(true, 3, null);

        assertThat(cfg.maxDepth()).isEqualTo(3);
        assertThat(cfg.groupIdPrefixes()).isEmpty();
        assertThat(cfg.isWhitelisted("anything")).isTrue();
    }

    @Test
    void isWhitelistedRejectsNullGroupId() {
        assertThat(config(5).isWhitelisted(null)).isFalse();
        assertThat(config(5, "org.spring").isWhitelisted(null)).isFalse();
    }
}
