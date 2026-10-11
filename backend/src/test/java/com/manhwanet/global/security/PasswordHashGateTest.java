package com.manhwanet.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.manhwanet.global.error.ErrorCode;
import com.manhwanet.global.error.ServiceBusyException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PasswordHashGateTest {

    private static PasswordHashGate gate(int p, int q, long waitMillis) {
        return new PasswordHashGate(new HashLimitProperties(p, q, Duration.ofMillis(waitMillis)));
    }

    @Test
    void acquiresImmediatelyWhenFreeAndReleasesOnce() {
        PasswordHashGate gate = gate(2, 4, 250);
        PasswordHashGate.Permit permit = gate.acquire();
        assertThat(gate.availablePermits()).isEqualTo(1);
        assertThat(gate.inUseCount()).isEqualTo(1);
        permit.close();
        permit.close(); // 두 번 닫아도 한 번만 반환
        assertThat(gate.availablePermits()).isEqualTo(2);
        assertThat(gate.inUseCount()).isZero();
    }

    @Test
    void zeroWaitingRejectsImmediatelyWhenFull() {
        PasswordHashGate gate = gate(1, 0, 1000);
        try (PasswordHashGate.Permit held = gate.acquire()) {
            long start = System.nanoTime();
            assertThatThrownBy(gate::acquire)
                    .isInstanceOfSatisfying(ServiceBusyException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AUTH_BUSY);
                        assertThat(e.getRetryAfterSeconds()).isEqualTo(1);
                    });
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(100));
        }
        assertThat(gate.availablePermits()).isEqualTo(1);
    }

    @Test
    void waitTimeoutRejectsWithinMaxWaitPlusMargin() {
        PasswordHashGate gate = gate(1, 4, 200);
        try (PasswordHashGate.Permit held = gate.acquire()) {
            long start = System.nanoTime();
            assertThatThrownBy(gate::acquire).isInstanceOf(ServiceBusyException.class);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
            assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(190));
            assertThat(elapsed).isLessThan(Duration.ofMillis(300)); // W + 100ms
        }
        assertThat(gate.waitingCount()).isZero();
        assertThat(gate.availablePermits()).isEqualTo(1);
    }

    @Test
    void waiterGetsPermitWhenReleasedWithinMaxWait() throws Exception {
        PasswordHashGate gate = gate(1, 1, 1000);
        PasswordHashGate.Permit held = gate.acquire();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> waiter = pool.submit(() -> {
                try (PasswordHashGate.Permit ignored = gate.acquire()) {
                    return true;
                }
            });
            awaitWaiting(gate, 1);
            held.close();
            assertThat(waiter.get(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(gate.availablePermits()).isEqualTo(1);
        assertThat(gate.waitingCount()).isZero();
    }

    @Test
    void interruptWhileWaitingRejectsRestoresFlagAndLeaksNothing() throws Exception {
        PasswordHashGate gate = gate(1, 2, 1000);
        PasswordHashGate.Permit held = gate.acquire();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean interruptedAfter = new AtomicBoolean();
        Thread waiter = new Thread(() -> {
            try {
                gate.acquire().close();
            } catch (Throwable t) {
                thrown.set(t);
            }
            interruptedAfter.set(Thread.currentThread().isInterrupted());
        });
        waiter.start();
        awaitWaiting(gate, 1);
        long start = System.nanoTime();
        waiter.interrupt();
        waiter.join(2000);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(500));
        assertThat(thrown.get()).isInstanceOf(ServiceBusyException.class);
        assertThat(interruptedAfter.get()).isTrue();
        assertThat(gate.waitingCount()).isZero();
        held.close();
        assertThat(gate.availablePermits()).isEqualTo(1);
        assertThat(gate.inUseCount()).isZero();
    }

    @Test
    void alreadyInterruptedCallerIsRejectedWithoutTakingAPermit() {
        PasswordHashGate gate = gate(2, 4, 250);
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(gate::acquire).isInstanceOf(ServiceBusyException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted(); // 테스트 스레드 정리
        }
        assertThat(gate.availablePermits()).isEqualTo(2);
    }

    @Test
    void manyConcurrentRequestsNeverExceedPAndQAndDecideWithinW() throws Exception {
        int p = 2;
        int q = 4;
        long waitMillis = 250;
        PasswordHashGate gate = gate(p, q, waitMillis);
        int clients = 128;
        ExecutorService pool = Executors.newFixedThreadPool(clients);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        AtomicInteger maxWaiting = new AtomicInteger();
        AtomicInteger granted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicLong slowestRejectionNanos = new AtomicLong();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < clients; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                long begin = System.nanoTime();
                try (PasswordHashGate.Permit ignored = gate.acquire()) {
                    granted.incrementAndGet();
                    maxConcurrent.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
                    maxWaiting.accumulateAndGet(gate.waitingCount(), Math::max);
                    Thread.sleep(40); // 해시 연산 시간 흉내
                    concurrent.decrementAndGet();
                } catch (ServiceBusyException e) {
                    rejected.incrementAndGet();
                    slowestRejectionNanos.accumulateAndGet(System.nanoTime() - begin, Math::max);
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(maxConcurrent.get()).isLessThanOrEqualTo(p);
        assertThat(maxWaiting.get()).isLessThanOrEqualTo(q);
        assertThat(granted.get() + rejected.get()).isEqualTo(clients);
        assertThat(granted.get()).isGreaterThanOrEqualTo(p);
        assertThat(rejected.get()).isPositive();
        assertThat(Duration.ofNanos(slowestRejectionNanos.get())).isLessThan(Duration.ofMillis(waitMillis + 100));
        assertThat(gate.availablePermits()).isEqualTo(p);
        assertThat(gate.waitingCount()).isZero();
        assertThat(gate.inUseCount()).isZero();
        PasswordHashGate.Snapshot stats = gate.drainStats();
        assertThat(stats.rejected()).isEqualTo(rejected.get());
        assertThat(stats.peakWaiting()).isLessThanOrEqualTo(q);
        assertThat(stats.peakInUse()).isLessThanOrEqualTo(p);
        assertThat(gate.drainStats().rejected()).isZero();
    }

    @Test
    void fairQueueGrantsWaitersInArrivalOrderAndAFullQueueRejectsTheNext() throws Exception {
        PasswordHashGate gate = gate(1, 4, 1000);
        PasswordHashGate.Permit held = gate.acquire();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Integer> grantOrder = Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> waiters = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) {
                int id = i;
                waiters.add(pool.submit(() -> {
                    try (PasswordHashGate.Permit ignored = gate.acquire()) {
                        grantOrder.add(id);
                        Thread.sleep(20);
                    }
                    return null;
                }));
                awaitWaiting(gate, i + 1);
            }
            long rejectStart = System.nanoTime();
            assertThatThrownBy(gate::acquire).isInstanceOf(ServiceBusyException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - rejectStart)).isLessThan(Duration.ofMillis(100));
            held.close();
            held = null;
            for (Future<?> waiter : waiters) {
                waiter.get(5, TimeUnit.SECONDS);
            }
        } finally {
            if (held != null) {
                held.close();
            }
            pool.shutdownNow();
        }
        assertThat(grantOrder).containsExactly(0, 1, 2, 3);
        assertThat(gate.availablePermits()).isEqualTo(1);
        assertThat(gate.waitingCount()).isZero();
        assertThat(gate.inUseCount()).isZero();
    }

    @Test
    void invalidPropertiesFailFast() {
        assertThatThrownBy(() -> new HashLimitProperties(0, 4, Duration.ofMillis(250)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("max-concurrent");
        assertThatThrownBy(() -> new HashLimitProperties(2, -1, Duration.ofMillis(250)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("max-waiting");
        assertThatThrownBy(() -> new HashLimitProperties(2, 4, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("max-wait");
        assertThatThrownBy(() -> new HashLimitProperties(2, 4, Duration.ofMillis(1001)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("max-wait");
        assertThat(new HashLimitProperties(1, 0, Duration.ofSeconds(1)).maxWaiting()).isZero();
    }

    private static void awaitWaiting(PasswordHashGate gate, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (gate.waitingCount() < expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("waiter did not start waiting");
            }
            Thread.sleep(1);
        }
        Thread.sleep(20); // waiting 증가 후 semaphore 대기열에 들어갈 시간
    }
}
