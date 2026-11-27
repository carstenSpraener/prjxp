package de.spraener.prjxp.mcp.cache;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SearchResultCache} with a controllable nano clock.
 */
class SearchResultCacheTest {

    private static final long SECOND_NANOS = 1_000_000_000L;

    private final AtomicLong now = new AtomicLong(0);

    private SearchResultCache cache(int maxEntries, long ttlSeconds) {
        return new SearchResultCache(maxEntries, ttlSeconds, now::get);
    }

    @Test
    void firstCallIsMissSecondCallIsHit() {
        SearchResultCache cache = cache(512, 60);
        AtomicInteger loaderCalls = new AtomicInteger(0);

        SearchResultCache.Lookup<String> first = cache.get("k", String.class,
                () -> {
                    loaderCalls.incrementAndGet();
                    return "v";
                });
        SearchResultCache.Lookup<String> second = cache.get("k", String.class,
                () -> {
                    loaderCalls.incrementAndGet();
                    return "v";
                });

        assertThat(first.hit()).isFalse();
        assertThat(first.value()).isEqualTo("v");
        assertThat(second.hit()).isTrue();
        assertThat(second.value()).isEqualTo("v");
        assertThat(loaderCalls.get()).isEqualTo(1);
        assertThat(cache.hits()).isEqualTo(1);
        assertThat(cache.misses()).isEqualTo(1);
    }

    @Test
    void expiredEntryIsReloaded() {
        SearchResultCache cache = cache(512, 60);
        AtomicInteger loaderCalls = new AtomicInteger(0);

        cache.get("k", String.class, () -> {
            loaderCalls.incrementAndGet();
            return "v1";
        });

        now.addAndGet(61 * SECOND_NANOS);

        SearchResultCache.Lookup<String> second = cache.get("k", String.class,
                () -> {
                    loaderCalls.incrementAndGet();
                    return "v2";
                });

        assertThat(second.hit()).isFalse();
        assertThat(second.value()).isEqualTo("v2");
        assertThat(loaderCalls.get()).isEqualTo(2);
        assertThat(cache.hits()).isZero();
        assertThat(cache.misses()).isEqualTo(2);
    }

    @Test
    void lruEvictsOldestWhenFull() {
        SearchResultCache cache = cache(2, 60);

        cache.get("a", String.class, () -> "va");
        cache.get("b", String.class, () -> "vb");
        cache.get("c", String.class, () -> "vc");

        // "a" was evicted as least recently used
        SearchResultCache.Lookup<String> aAgain = cache.get("a", String.class, () -> "va2");
        assertThat(aAgain.hit()).isFalse();
        assertThat(aAgain.value()).isEqualTo("va2");

        // Re-inserting "a" evicts "b" (now oldest); "c" is still cached
        assertThat(cache.get("c", String.class, () -> "other").hit()).isTrue();
    }

    @Test
    void invalidateProjectRemovesOnlyThatProject() {
        SearchResultCache cache = cache(512, 60);
        String keyP1 = SearchResultCache.vectorKey("p1", "q", "java", 10, Map.of());
        String keyP2 = SearchResultCache.vectorKey("p2", "q", "java", 10, Map.of());

        cache.get(keyP1, String.class, () -> "v1");
        cache.get(keyP2, String.class, () -> "v2");

        cache.invalidateProject("p1");

        assertThat(cache.get(keyP1, String.class, () -> "v1-reloaded").hit()).isFalse();
        assertThat(cache.get(keyP2, String.class, () -> "v2-reloaded").hit()).isTrue();
    }

    @Test
    void nullResultIsNotCached() {
        SearchResultCache cache = cache(512, 60);
        AtomicInteger loaderCalls = new AtomicInteger(0);

        SearchResultCache.Lookup<String> first = cache.get("k", String.class,
                () -> {
                    loaderCalls.incrementAndGet();
                    return null;
                });
        SearchResultCache.Lookup<String> second = cache.get("k", String.class,
                () -> {
                    loaderCalls.incrementAndGet();
                    return null;
                });

        assertThat(first.hit()).isFalse();
        assertThat(first.value()).isNull();
        assertThat(second.hit()).isFalse();
        assertThat(second.value()).isNull();
        assertThat(loaderCalls.get()).isEqualTo(2);
        assertThat(cache.hits()).isZero();
        assertThat(cache.misses()).isEqualTo(2);
    }

    @Test
    void keySerializationIsStableForDifferentFilterOrder() {
        Map<String, String> filtersA = new HashMap<>();
        filtersA.put("alpha", "1");
        filtersA.put("beta", "2");
        filtersA.put("gamma", "3");

        Map<String, String> filtersB = new HashMap<>();
        filtersB.put("gamma", "3");
        filtersB.put("alpha", "1");
        filtersB.put("beta", "2");

        assertThat(SearchResultCache.vectorKey("p", "q", "java", 10, filtersA))
                .isEqualTo(SearchResultCache.vectorKey("p", "q", "java", 10, filtersB));
    }

    @Test
    void concurrentAccessWithSameKeyDoesNotFail() throws InterruptedException {
        SearchResultCache cache = cache(512, 60);
        int threads = 2;
        int iterations = 100;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger(0);

        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < iterations; i++) {
                        cache.get("k", String.class, () -> "v");
                    }
                } catch (Throwable e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            }).start();
        }

        start.countDown();
        assertThat(done.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        assertThat(errors.get()).isZero();
        assertThat(cache.hits() + cache.misses()).isEqualTo((long) threads * iterations);
    }
}
