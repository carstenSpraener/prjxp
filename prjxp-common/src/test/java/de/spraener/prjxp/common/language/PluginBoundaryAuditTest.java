package de.spraener.prjxp.common.language;

import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.PxChunker;
import de.spraener.prjxp.common.retrieval.GoldenRetriever;
import de.spraener.prjxp.common.store.PxChunkDao;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WP-1 Phase 04 (L3 boundary audit): for the complete type closure of the
 * contract signatures, every type must resolve to the <em>same</em> class
 * object via the plugin class loader and via the host class loader. This is
 * what makes {@code instanceof} checks, casts and method dispatch across the
 * class loader boundary safe.
 */
class PluginBoundaryAuditTest {

    private static final List<String> GOOD_SOURCES =
            List.of("de/spraener/prjxp/pluginfix/good/GoodPackProvider.java");
    private static final String GOOD_PROVIDER = "de.spraener.prjxp.pluginfix.good.GoodPackProvider";

    @TempDir
    Path pluginDir;

    @Test
    void contractTypeClosureStaysOnHostClassIdentity() throws Exception {
        PluginJarBuilder.build(pluginDir, "good", GOOD_SOURCES, GOOD_PROVIDER);
        LanguagePackPluginLoader loader = new LanguagePackPluginLoader(pluginDir.toFile());
        loader.loadAll();

        ClassLoader pluginCL = loader.classLoaders().get(0);
        ClassLoader hostCL = LanguagePackPluginLoader.class.getClassLoader();

        Set<String> closure = typeClosure(Set.of(
                LanguagePack.class,
                LanguagePackProvider.class,
                PxChunker.class,
                GoldenRetriever.class,
                PxChunk.class,
                PxChunkDao.class));

        // sanity: the closure must actually cover the contract
        assertThat(closure).contains(
                "de.spraener.prjxp.common.language.LanguagePack",
                "de.spraener.prjxp.common.model.PxChunk",
                "de.spraener.prjxp.common.store.PxChunkDao");

        for (String typeName : closure) {
            Class<?> viaPlugin = Class.forName(typeName, false, pluginCL);
            Class<?> viaHost = Class.forName(typeName, false, hostCL);
            assertThat(viaPlugin)
                    .as("type %s must resolve to the same class via plugin and host class loader", typeName)
                    .isSameAs(viaHost);
        }
    }

    /**
     * Transitive closure over the public method signatures (parameters +
     * return types, including generic type arguments) of the given roots.
     */
    private static Set<String> typeClosure(Set<Class<?>> roots) {
        Set<String> closure = new TreeSet<>();
        Deque<Class<?>> queue = new ArrayDeque<>(roots);
        while (!queue.isEmpty()) {
            Class<?> c = queue.poll();
            if (c == null || c.isPrimitive() || c.isArray()) {
                continue;
            }
            if (!closure.add(c.getName())) {
                continue;
            }
            for (Method m : c.getMethods()) {
                collect(m.getGenericReturnType(), queue);
                for (Type p : m.getGenericParameterTypes()) {
                    collect(p, queue);
                }
            }
        }
        return closure;
    }

    private static void collect(Type type, Deque<Class<?>> queue) {
        if (type instanceof Class<?> c) {
            queue.add(c);
        } else if (type instanceof ParameterizedType pt) {
            queue.add((Class<?>) pt.getRawType());
            for (Type arg : pt.getActualTypeArguments()) {
                collect(arg, queue);
            }
        } else if (type instanceof WildcardType wt) {
            for (Type bound : wt.getUpperBounds()) {
                collect(bound, queue);
            }
            for (Type bound : wt.getLowerBounds()) {
                collect(bound, queue);
            }
        } else if (type instanceof GenericArrayType gat) {
            collect(gat.getGenericComponentType(), queue);
        }
    }
}
