package com.manhwanet.collection.collector;

import static org.assertj.core.api.Assertions.assertThat;

import com.manhwanet.collection.collector.core.CollectorClock;
import com.manhwanet.collection.collector.core.PlatformThrottle;
import com.manhwanet.collection.collector.core.WaitGuard;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 플랫폼별 동시 1건·간격·Retry-After 미루기 */
class PlatformThrottleTest {

    private static final WaitGuard REAL_GUARD = new WaitGuard() {
        @Override
        public void checkpoint() {
        }

        @Override
        public void await(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    };

    @Test
    void oneRequestAtATimeAndIntervalAcrossThreads() throws Exception {
        PlatformThrottle throttle = new PlatformThrottle(CollectorClock.system());
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        List<long[]> spans = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            futures.add(pool.submit(() -> {
                PlatformThrottle.Permit permit = throttle.acquire("RIDI", REAL_GUARD);
                long start = System.nanoTime();
                maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                inFlight.decrementAndGet();
                long end = System.nanoTime();
                spans.add(new long[] {start, end});
                permit.release(60);
                return null;
            }));
        }
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        assertThat(maxInFlight.get()).isEqualTo(1);
        List<long[]> sorted = new ArrayList<>(spans);
        sorted.sort((a, b) -> Long.compare(a[0], b[0]));
        for (int i = 1; i < sorted.size(); i++) {
            assertThat(sorted.get(i)[0] - sorted.get(i - 1)[1]).isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(59));
        }
    }

    @Test
    void differentPlatformsDoNotWaitForEachOther() {
        VirtualClock clock = new VirtualClock();
        PlatformThrottle throttle = new PlatformThrottle(clock);
        List<Long> waits = new ArrayList<>();
        WaitGuard guard = recording(clock, waits);
        throttle.acquire("RIDI", guard).release(5_000);
        throttle.acquire("LEZHIN", guard).release(5_000);
        assertThat(waits).isEmpty();
        throttle.acquire("RIDI", guard).release(5_000);
        assertThat(waits.stream().mapToLong(Long::longValue).sum()).isGreaterThanOrEqualTo(5_000);
    }

    @Test
    void retryAfterPushesNextRequest() {
        VirtualClock clock = new VirtualClock();
        PlatformThrottle throttle = new PlatformThrottle(clock);
        List<Long> waits = new ArrayList<>();
        WaitGuard guard = recording(clock, waits);
        throttle.acquire("RIDI", guard).release(5_000);
        throttle.delayAtLeast("RIDI", 120_000);
        long before = clock.millis();
        throttle.acquire("RIDI", guard).release(5_000);
        assertThat(clock.millis() - before).isGreaterThanOrEqualTo(120_000);
    }

    private static WaitGuard recording(VirtualClock clock, List<Long> waits) {
        return new WaitGuard() {
            @Override
            public void checkpoint() {
            }

            @Override
            public void await(long millis) {
                waits.add(millis);
                clock.sleep(millis);
            }
        };
    }
}
