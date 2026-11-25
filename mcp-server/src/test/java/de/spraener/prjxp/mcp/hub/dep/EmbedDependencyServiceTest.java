package de.spraener.prjxp.mcp.hub.dep;

import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.mcp.hub.HubProperties;
import de.spraener.prjxp.mcp.hub.HubProjectRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase 05: {@link EmbedDependencyService} drives dependency materialization on a dedicated
 * single-thread executor (network I/O never runs on the pipeline worker). {@code syncFor} is
 * non-blocking and never throws.
 */
class EmbedDependencyServiceTest {

    @TempDir
    Path tempDir;

    private HubProperties props;
    private HubProjectRegistry registry;
    private MavenCentralClient client;
    private LibraryMaterializer materializer;
    private DependencyResolver resolver;
    private EmbedDependencyService service;

    @BeforeEach
    void setUp() throws Exception {
        props = new HubProperties();
        registry = mock(HubProjectRegistry.class);
        client = mock(MavenCentralClient.class);
        materializer = mock(LibraryMaterializer.class);
        resolver = mock(DependencyResolver.class);
        service = new EmbedDependencyService(props, registry, List.of(resolver), client, materializer);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    private ProjectDefinition ownerDef(boolean embed) throws Exception {
        Path root = tempDir.resolve("owner");
        Files.createDirectories(root);
        ProjectDefinition def = new ProjectDefinition();
        def.setName("owner");
        def.setRootDir(root.toString());
        def.setEmbedDependencies(embed);
        return def;
    }

    private ResolvedDependency resolved(String artifact, int depth) {
        return new ResolvedDependency(new MavenCoordinates("com.example", artifact, "1.0"), depth, true, true);
    }

    private DependencyResolution twoDeps() {
        return new DependencyResolution(
                List.of(new RawDependency(new MavenCoordinates("com.example", "a", "1.0"), 1),
                        new RawDependency(new MavenCoordinates("com.example", "b", "1.0"), 2)),
                new DependencyResolveConfig(2, List.of()));
    }

    @Test
    void syncForDisabledIsNoOp() throws Exception {
        when(registry.definitionOf("owner")).thenReturn(ownerDef(false));

        service.syncFor("owner");

        verify(resolver, never()).supports(any());
        verifyNoInteractions(materializer);
    }

    @Test
    void syncForUnknownOwnerIsNoOp() {
        when(registry.definitionOf("ghost")).thenReturn(null);

        assertThatCode(() -> service.syncFor("ghost")).doesNotThrowAnyException();
        verify(resolver, never()).supports(any());
    }

    @Test
    void syncForInvokesMaterializerForEachEmbeddableDep() throws Exception {
        when(registry.definitionOf("owner")).thenReturn(ownerDef(true));
        when(resolver.supports(any())).thenReturn(true);
        when(resolver.resolve(any(), any())).thenReturn(twoDeps());
        when(client.exists(any(MavenCoordinates.class), anyString())).thenReturn(true);   // both embeddable

        CountDownLatch done = new CountDownLatch(2);
        doAnswer(inv -> {
            done.countDown();
            return null;
        }).when(materializer).materialize(any(ResolvedDependency.class), anyBoolean());

        service.syncFor("owner");   // non-blocking — the work runs on the dedicated executor

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        verify(materializer, times(2)).materialize(any(ResolvedDependency.class), eq(false));   // idempotent
    }

    @Test
    void materializerFailureIsIsolatedPerDep() throws Exception {
        when(registry.definitionOf("owner")).thenReturn(ownerDef(true));
        when(resolver.supports(any())).thenReturn(true);
        when(resolver.resolve(any(), any())).thenReturn(twoDeps());
        when(client.exists(any(MavenCoordinates.class), anyString())).thenReturn(true);

        ResolvedDependency a = resolved("a", 1);   // depths must match twoDeps() — record equality includes depth
        ResolvedDependency b = resolved("b", 2);
        doThrow(new MavenDependencyException("boom")).when(materializer).materialize(eq(a), anyBoolean());
        CountDownLatch done = new CountDownLatch(1);
        doAnswer(inv -> {
            done.countDown();
            return null;
        }).when(materializer).materialize(eq(b), anyBoolean());   // unstubbed calls on a void mock are no-ops

        service.syncFor("owner");

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        verify(materializer).materialize(eq(a), eq(false));   // attempted (and failed)
        verify(materializer).materialize(eq(b), eq(false));   // the remaining dep is still materialized
    }

    // ------------------------------------------------------------------ Phase 06: reimport (library re-materialize)

    private ProjectDefinition libraryDef(String gav) {
        ProjectDefinition def = new ProjectDefinition();
        def.setName("lib");
        def.setMavenDependency(gav);
        return def;
    }

    @Test
    void reimportOfLibraryForcesMaterialization() throws Exception {
        when(registry.definitionOf("lib-1.0")).thenReturn(libraryDef("com.example:lib:1.0"));
        when(client.exists(any(MavenCoordinates.class), eq("sources"))).thenReturn(true);
        when(client.exists(any(MavenCoordinates.class), eq("javadoc"))).thenReturn(false);

        CountDownLatch done = new CountDownLatch(1);
        doAnswer(inv -> {
            done.countDown();
            return null;
        }).when(materializer).materialize(any(ResolvedDependency.class), anyBoolean());

        service.reimport("lib-1.0");   // non-blocking — the work runs on the dedicated executor

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        ArgumentCaptor<ResolvedDependency> captor = ArgumentCaptor.forClass(ResolvedDependency.class);
        verify(materializer).materialize(captor.capture(), eq(true));   // force re-download + re-extract
        assertThat(captor.getValue().coords()).isEqualTo(new MavenCoordinates("com.example", "lib", "1.0"));
        assertThat(captor.getValue().hasSources()).isTrue();
        assertThat(captor.getValue().hasJavadoc()).isFalse();
    }

    @Test
    void reimportOfNonLibraryIsNoOp() {
        ProjectDefinition def = new ProjectDefinition();
        def.setName("alpha");   // no mavenDependency marker
        when(registry.definitionOf("alpha")).thenReturn(def);

        assertThatCode(() -> service.reimport("alpha")).doesNotThrowAnyException();
        verifyNoInteractions(client, materializer);
    }

    @Test
    void reimportOfUnknownProjectIsNoOp() {
        when(registry.definitionOf("ghost")).thenReturn(null);

        assertThatCode(() -> service.reimport("ghost")).doesNotThrowAnyException();
        verifyNoInteractions(client, materializer);
    }

    @Test
    void reimportWithMalformedGavIsSkipped() throws Exception {
        when(registry.definitionOf("lib")).thenReturn(libraryDef("com.example:lib"));   // missing version
        service.reimport("lib");   // the task runs on the dedicated executor

        // The executor is single-threaded: a follow-up syncFor task only runs AFTER the reimport
        // task has completed, so the latch is a deterministic sync point for the silent skip.
        CountDownLatch done = new CountDownLatch(1);
        when(registry.definitionOf("owner")).thenReturn(ownerDef(true));
        when(resolver.supports(any())).thenAnswer(inv -> {
            done.countDown();
            return true;
        });
        when(resolver.resolve(any(), any())).thenReturn(new DependencyResolution(List.of(), new DependencyResolveConfig(1, List.of())));

        service.syncFor("owner");
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();

        verifyNoInteractions(materializer);   // the malformed GAV was skipped, nothing materialized
    }

    @Test
    void reimportMaterializerFailureIsSwallowed() throws Exception {
        when(registry.definitionOf("lib-1.0")).thenReturn(libraryDef("com.example:lib:1.0"));
        when(client.exists(any(MavenCoordinates.class), anyString())).thenReturn(true);
        doThrow(new MavenDependencyException("boom")).when(materializer).materialize(any(ResolvedDependency.class), anyBoolean());

        assertThatCode(() -> service.reimport("lib-1.0")).doesNotThrowAnyException();   // never throws
    }
}
