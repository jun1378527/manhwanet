package com.manhwanet.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.manhwanet.global.error.ErrorCode;
import com.manhwanet.global.error.RetryLaterException;
import com.manhwanet.global.error.ServiceBusyException;
import com.manhwanet.global.error.TooManyRequestsException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class LoginAttemptLimiterTest {

    private static final Duration FIFTEEN = Duration.ofMinutes(15);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-06T00:00:00Z"));
    private final LoginAttemptLimiter limiter = limiter(100_000);

    private LoginAttemptLimiter limiter(int maxEntries) {
        return new LoginAttemptLimiter(new LoginLimitProperties(5, FIFTEEN, FIFTEEN, maxEntries), clock);
    }

    private static void fail(LoginAttemptLimiter target, String key) {
        target.reserve(key).failure();
    }

    private static RetryLaterException rejection(LoginAttemptLimiter target, String key) {
        try {
            target.reserve(key).close();
        } catch (RetryLaterException e) {
            return e;
        }
        throw new AssertionError("expected rejection for key");
    }

    private static void assertLocked(LoginAttemptLimiter target, String key, long retryAfter) {
        RetryLaterException e = rejection(target, key);
        assertThat(e).isInstanceOf(TooManyRequestsException.class);
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TOO_MANY_LOGIN_ATTEMPTS);
        assertThat(e.getRetryAfterSeconds()).isEqualTo(retryAfter);
    }

    // ---------- 기존 계정별 규칙 (§4.2) ----------

    @Test
    void locksAfterFiveFailuresWithinWindowForFifteenMinutes() {
        for (int i = 0; i < 5; i++) {
            fail(limiter, "a@example.com");
        }
        assertLocked(limiter, "a@example.com", 15 * 60);

        clock.advance(Duration.ofMinutes(14).plusSeconds(59));
        assertLocked(limiter, "a@example.com", 1);

        clock.advance(Duration.ofSeconds(1));
        limiter.reserve("a@example.com").close();
    }

    @Test
    void lockedRetryAfterRoundsUpPartialSeconds() {
        for (int i = 0; i < 5; i++) {
            fail(limiter, "round@example.com");
        }
        clock.advance(Duration.ofMillis(1500));
        assertLocked(limiter, "round@example.com", 15 * 60 - 1);
    }

    @Test
    void rejectionsWhileLockedDoNotExtendTheLock() {
        for (int i = 0; i < 5; i++) {
            fail(limiter, "keep@example.com");
        }
        for (int i = 0; i < 10; i++) {
            clock.advance(Duration.ofMinutes(1));
            rejection(limiter, "keep@example.com");
        }
        clock.advance(Duration.ofMinutes(5));
        limiter.reserve("keep@example.com").close();
    }

    @Test
    void failuresOlderThanWindowDoNotCount() {
        for (int i = 0; i < 4; i++) {
            fail(limiter, "b@example.com");
        }
        clock.advance(FIFTEEN.plusSeconds(1));
        fail(limiter, "b@example.com");
        limiter.reserve("b@example.com").close();
    }

    @Test
    void successClearsFailuresAndRemovesEntry() {
        for (int i = 0; i < 4; i++) {
            fail(limiter, "c@example.com");
        }
        limiter.reserve("c@example.com").success();
        assertThat(limiter.size()).isZero();
        assertThat(limiter.reservedSlots()).isZero();
        for (int i = 0; i < 4; i++) {
            fail(limiter, "c@example.com");
        }
        limiter.reserve("c@example.com").close();
    }

    @Test
    void keysAreIndependent() {
        for (int i = 0; i < 5; i++) {
            fail(limiter, "d@example.com");
        }
        rejection(limiter, "d@example.com");
        limiter.reserve("other@example.com").close();
    }

    @Test
    void uncountedReleaseIsNotAFailure() {
        for (int i = 0; i < 10; i++) {
            limiter.reserve("e@example.com").releaseUncounted();
        }
        assertThat(limiter.size()).isZero();
        assertThat(limiter.reservedSlots()).isZero();
    }

    @Test
    void inFlightAttemptsCountTowardTheLimitWithRetryAfterOne() {
        List<LoginAttemptLimiter.Reservation> open = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            open.add(limiter.reserve("f@example.com"));
        }
        assertLocked(limiter, "f@example.com", 1);
        open.get(0).releaseUncounted();
        limiter.reserve("f@example.com").close();
        open.forEach(LoginAttemptLimiter.Reservation::close);
        assertThat(limiter.inFlight("f@example.com")).isZero();
        assertThat(limiter.size()).isZero();
    }

    // ---------- 예약 객체 (§6.1) ----------

    @Nested
    class ReservationCompletesOnce {

        @Test
        void onlyFirstCompletionCounts() {
            LoginAttemptLimiter.Reservation reservation = limiter.reserve("once@example.com");
            reservation.failure();
            reservation.failure();
            reservation.success();
            reservation.releaseUncounted();
            reservation.close();
            assertThat(limiter.inFlight("once@example.com")).isZero();
            // 실패는 한 번만 기록되었다: 4번 더 실패해야 잠긴다.
            for (int i = 0; i < 3; i++) {
                fail(limiter, "once@example.com");
            }
            limiter.reserve("once@example.com").close();
            fail(limiter, "once@example.com");
            rejection(limiter, "once@example.com");
        }

        @Test
        void closeAfterSuccessDoesNothing() {
            LoginAttemptLimiter.Reservation other = limiter.reserve("twice@example.com");
            LoginAttemptLimiter.Reservation reservation = limiter.reserve("twice@example.com");
            reservation.success();
            reservation.close();
            assertThat(limiter.inFlight("twice@example.com")).isEqualTo(1);
            other.close();
            assertThat(limiter.inFlight("twice@example.com")).isZero();
            assertThat(limiter.size()).isZero();
        }

        @Test
        void closeWithoutResultReleasesUncounted() {
            for (int i = 0; i < 20; i++) {
                try (LoginAttemptLimiter.Reservation ignored = limiter.reserve("close@example.com")) {
                    // 결과 없이 끝남(예: 게이트 거절, 인증 외 예외)
                }
            }
            assertThat(limiter.size()).isZero();
        }

        @Test
        void concurrentDoubleCompletionNeverGoesNegative() throws Exception {
            ExecutorService pool = Executors.newFixedThreadPool(8);
            try {
                for (int round = 0; round < 200; round++) {
                    LoginAttemptLimiter.Reservation keep = limiter.reserve("neg@example.com");
                    LoginAttemptLimiter.Reservation reservation = limiter.reserve("neg@example.com");
                    CyclicBarrier barrier = new CyclicBarrier(4);
                    List<Future<?>> futures = new ArrayList<>();
                    futures.add(pool.submit(() -> {
                        barrier.await();
                        reservation.success();
                        return null;
                    }));
                    futures.add(pool.submit(() -> {
                        barrier.await();
                        reservation.failure();
                        return null;
                    }));
                    futures.add(pool.submit(() -> {
                        barrier.await();
                        reservation.close();
                        return null;
                    }));
                    futures.add(pool.submit(() -> {
                        barrier.await();
                        reservation.releaseUncounted();
                        return null;
                    }));
                    for (Future<?> future : futures) {
                        future.get(5, TimeUnit.SECONDS);
                    }
                    assertThat(limiter.inFlight("neg@example.com")).isEqualTo(1);
                    keep.success();
                }
            } finally {
                pool.shutdownNow();
            }
            assertThat(limiter.size()).isZero();
        }
    }

    // ---------- 성공·실패 동시 완료 (§4.3) ----------

    @Nested
    class CompletionOrder {

        @Test
        void failuresThenSuccessClearsAllFailures() {
            List<LoginAttemptLimiter.Reservation> open = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                open.add(limiter.reserve("order1@example.com"));
            }
            // 진행 중 5개 중 4개가 먼저 실패로 끝나고 그 뒤 성공: 성공이 실패 목록을 모두 지운다
            for (int i = 0; i < 4; i++) {
                open.get(i).failure();
            }
            open.get(4).success();
            assertThat(limiter.size()).isZero();
            for (int i = 0; i < 4; i++) {
                fail(limiter, "order1@example.com");
            }
            limiter.reserve("order1@example.com").close();
            fail(limiter, "order1@example.com");
            assertLocked(limiter, "order1@example.com", 15 * 60);
        }

        @Test
        void inFlightSuccessAtLimitClearsFailures() {
            for (int i = 0; i < 4; i++) {
                fail(limiter, "order2@example.com");
            }
            // 실패 4 + 진행 중 1 = 한도. 진행 중인 시도가 성공이면 실패 기록을 모두 지운다.
            LoginAttemptLimiter.Reservation started = limiter.reserve("order2@example.com");
            assertLocked(limiter, "order2@example.com", 1);
            started.success();
            assertThat(limiter.size()).isZero();
            limiter.reserve("order2@example.com").close();
        }

        @Test
        void failureAfterSuccessCountsFromOne() {
            for (int i = 0; i < 3; i++) {
                fail(limiter, "order4@example.com");
            }
            LoginAttemptLimiter.Reservation startedBefore = limiter.reserve("order4@example.com");
            limiter.reserve("order4@example.com").success(); // 성공이 먼저 완료
            startedBefore.failure(); // 성공 전에 시작했지만 뒤에 끝난 실패 = 새 기록 1회
            for (int i = 0; i < 3; i++) {
                fail(limiter, "order4@example.com");
            }
            limiter.reserve("order4@example.com").close(); // 4회까지는 허용
            fail(limiter, "order4@example.com");
            assertLocked(limiter, "order4@example.com", 15 * 60);
        }

        @Test
        void concurrentFailuresLockOnceAtTheFifthCompletion() {
            List<LoginAttemptLimiter.Reservation> open = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                open.add(limiter.reserve("order5@example.com"));
            }
            for (int i = 0; i < 4; i++) {
                open.get(i).failure();
            }
            clock.advance(Duration.ofMinutes(1));
            open.get(4).failure(); // 완료 순서상 5번째 실패 → 이 시각부터 15분 잠금
            assertLocked(limiter, "order5@example.com", 15 * 60);
            assertThat(limiter.inFlight("order5@example.com")).isZero();
            clock.advance(FIFTEEN.minusSeconds(1));
            assertLocked(limiter, "order5@example.com", 1);
            clock.advance(Duration.ofSeconds(1));
            limiter.reserve("order5@example.com").close();
        }
    }

    // ---------- 용량 (§8) ----------

    @Nested
    class Capacity {

        @Test
        void lockedKeySurvivesInflowOfNewKeys_M5Fixed() {
            LoginAttemptLimiter small = limiter(3);
            for (int i = 0; i < 5; i++) {
                fail(small, "locked@example.com");
            }
            // 새 키 2개는 들어가고, 세 번째부터는 가득 차 503
            fail(small, "n1@example.com");
            fail(small, "n2@example.com");
            RetryLaterException busy = rejection(small, "n3@example.com");
            assertThat(busy).isInstanceOf(ServiceBusyException.class);
            assertThat(busy.getErrorCode()).isEqualTo(ErrorCode.AUTH_BUSY);
            // 잠긴 키는 그대로 잠겨 있다(이전 구현은 이 시점에 잠금이 사라졌다)
            assertLocked(small, "locked@example.com", 15 * 60);
            assertThat(small.size()).isEqualTo(3);
        }

        @Test
        void existingKeysAreProcessedNormallyWhenFull() {
            LoginAttemptLimiter small = limiter(2);
            fail(small, "x@example.com");
            fail(small, "y@example.com");
            assertThat(rejection(small, "z@example.com")).isInstanceOf(ServiceBusyException.class);
            // 기존 키는 상한과 무관하게 평소대로: 실패 기록·성공 초기화
            for (int i = 0; i < 4; i++) {
                fail(small, "x@example.com");
            }
            assertLocked(small, "x@example.com", 15 * 60);
            small.reserve("y@example.com").success();
            // y 가 성공으로 비워졌으므로 신규 키가 들어갈 자리가 생겼다
            small.reserve("z@example.com").close();
        }

        @Test
        void inFlightOnlyEntriesAreNotEvicted() {
            LoginAttemptLimiter small = limiter(2);
            LoginAttemptLimiter.Reservation a = small.reserve("a@example.com");
            LoginAttemptLimiter.Reservation b = small.reserve("b@example.com");
            RetryLaterException busy = rejection(small, "c@example.com");
            assertThat(busy).isInstanceOf(ServiceBusyException.class);
            // 진행 중만 있는 항목은 만료 시각이 없으므로 1초
            assertThat(busy.getRetryAfterSeconds()).isEqualTo(1);
            assertThat(small.reservedSlots()).isEqualTo(2);
            a.close();
            b.close();
            small.reserve("c@example.com").close();
            assertThat(small.size()).isZero();
        }

        @Test
        void expiredEntriesAreSweptWhenFullAndNewKeysAccepted() {
            LoginAttemptLimiter small = limiter(3);
            for (int i = 0; i < 3; i++) {
                fail(small, "old" + i + "@example.com");
            }
            assertThat(rejection(small, "new@example.com")).isInstanceOf(ServiceBusyException.class);
            clock.advance(FIFTEEN.plusSeconds(1));
            fail(small, "new@example.com");
            assertThat(small.size()).isEqualTo(1);
        }

        @Test
        void saturatedRetryAfterIsTimeUntilEarliestExpiry() {
            LoginAttemptLimiter small = limiter(2);
            fail(small, "first@example.com");          // 만료: t0 + 15분
            clock.advance(Duration.ofMinutes(2));
            for (int i = 0; i < 5; i++) {
                fail(small, "second@example.com");     // 잠금: t0 + 2 + 15분
            }
            clock.advance(Duration.ofSeconds(30));
            RetryLaterException busy = rejection(small, "third@example.com");
            assertThat(busy.getRetryAfterSeconds()).isEqualTo(15 * 60 - 150);
        }

        @Test
        void saturatedScanRunsAtMostOncePerSecondAndUsesElapsedTimeInBetween() {
            LoginAttemptLimiter small = limiter(1);
            fail(small, "only@example.com");
            RetryLaterException first = rejection(small, "n@example.com");
            assertThat(first.getRetryAfterSeconds()).isEqualTo(15 * 60);
            clock.advance(Duration.ofMillis(400));
            // 1초 안: 다시 스캔하지 않고 직전 계산값(만료 시각)에서 경과 시간을 뺀다
            assertThat(rejection(small, "n@example.com").getRetryAfterSeconds()).isEqualTo(15 * 60);
            clock.advance(Duration.ofMillis(700));
            assertThat(rejection(small, "n@example.com").getRetryAfterSeconds()).isEqualTo(15 * 60 - 1);
        }

        @Test
        void scanRateLimitDelaysRecoveryByAtMostOneSecond() {
            LoginAttemptLimiter small = limiter(1);
            fail(small, "only@example.com");
            clock.advance(FIFTEEN.minusMillis(500));
            assertThat(rejection(small, "n@example.com")).isInstanceOf(ServiceBusyException.class); // 스캔(아직 활성)
            clock.advance(Duration.ofMillis(600)); // 만료됐지만 스캔 간격 1초 안
            assertThat(rejection(small, "n@example.com").getRetryAfterSeconds()).isEqualTo(1);
            clock.advance(Duration.ofMillis(500)); // 스캔 간격 지남 → 정리 후 수용
            small.reserve("n@example.com").close();
        }

        @Test
        void saturatedRetryAfterIsCappedAt900Seconds() {
            LoginAttemptLimiter longLock = new LoginAttemptLimiter(
                    new LoginLimitProperties(5, FIFTEEN, Duration.ofHours(1), 1), clock);
            for (int i = 0; i < 5; i++) {
                fail(longLock, "long@example.com");
            }
            assertThat(rejection(longLock, "n@example.com").getRetryAfterSeconds()).isEqualTo(900);
            // 계정 잠금 자체의 Retry-After 는 실제 남은 시간이다
            assertLocked(longLock, "long@example.com", 3600);
        }

        @Test
        void periodicSweepRemovesOnlyExpiredEntries() {
            fail(limiter, "expired@example.com");
            clock.advance(Duration.ofMinutes(5));
            fail(limiter, "recent@example.com");
            LoginAttemptLimiter.Reservation inFlight = limiter.reserve("progress@example.com");
            clock.advance(Duration.ofMinutes(10).plusSeconds(1));
            assertThat(limiter.sweepExpired()).isEqualTo(1);
            assertThat(limiter.size()).isEqualTo(2);
            inFlight.close();
            assertThat(limiter.size()).isEqualTo(1);
            assertThat(limiter.reservedSlots()).isEqualTo(1);
        }

        @Test
        void concurrentNewKeysNeverExceedTheCap() throws Exception {
            LoginAttemptLimiter small = limiter(50);
            ExecutorService pool = Executors.newFixedThreadPool(16);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger accepted = new AtomicInteger();
            AtomicInteger busy = new AtomicInteger();
            AtomicInteger maxSeen = new AtomicInteger();
            ConcurrentLinkedQueue<LoginAttemptLimiter.Reservation> held = new ConcurrentLinkedQueue<>();
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 500; i++) {
                String key = "cap" + i + "@example.com";
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        held.add(small.reserve(key));
                        accepted.incrementAndGet();
                    } catch (ServiceBusyException e) {
                        busy.incrementAndGet();
                    }
                    maxSeen.accumulateAndGet(small.reservedSlots(), Math::max);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
            pool.shutdown();
            assertThat(accepted.get()).isEqualTo(50);
            assertThat(busy.get()).isEqualTo(450);
            assertThat(maxSeen.get()).isLessThanOrEqualTo(50);
            assertThat(small.size()).isEqualTo(50);
            held.forEach(LoginAttemptLimiter.Reservation::close);
            assertThat(small.size()).isZero();
            assertThat(small.reservedSlots()).isZero();
        }

        @Test
        void concurrentMixedTrafficKeepsSlotCountConsistent() throws Exception {
            LoginAttemptLimiter small = limiter(20);
            ExecutorService pool = Executors.newFixedThreadPool(16);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 4000; i++) {
                int n = i;
                futures.add(pool.submit(() -> {
                    String key = "mix" + (n % 40) + "@example.com";
                    try (LoginAttemptLimiter.Reservation reservation = small.reserve(key)) {
                        switch (n % 3) {
                            case 0 -> reservation.success();
                            case 1 -> reservation.failure();
                            default -> {
                                // 결과 없이 닫힘
                            }
                        }
                    } catch (RetryLaterException ignored) {
                        // 잠금·진행 중 한도·포화
                    }
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
            pool.shutdown();
            assertThat(small.reservedSlots()).isEqualTo(small.size());
            assertThat(small.size()).isLessThanOrEqualTo(20);
            for (int i = 0; i < 40; i++) {
                assertThat(small.inFlight("mix" + i + "@example.com")).isZero();
            }
            clock.advance(FIFTEEN.plusMinutes(1));
            small.sweepExpired();
            assertThat(small.size()).isZero();
            assertThat(small.reservedSlots()).isZero();
        }
    }

    // ---------- 최악 유지 시간 (§10.2) ----------

    @Nested
    class Retention {

        @Test
        void failureEntryLivesUntilLastFailurePlusWindow() {
            fail(limiter, "r1@example.com");
            clock.advance(Duration.ofMinutes(10));
            fail(limiter, "r1@example.com");
            clock.advance(FIFTEEN.minusSeconds(1));
            assertThat(limiter.sweepExpired()).isZero();
            clock.advance(Duration.ofSeconds(1));
            assertThat(limiter.sweepExpired()).isEqualTo(1);
        }

        @Test
        void lockEntryLivesUntilLockEnd() {
            for (int i = 0; i < 5; i++) {
                fail(limiter, "r2@example.com");
            }
            clock.advance(FIFTEEN.minusSeconds(1));
            assertThat(limiter.sweepExpired()).isZero();
            clock.advance(Duration.ofSeconds(1));
            assertThat(limiter.sweepExpired()).isEqualTo(1);
        }

        @Test
        void lockLongerThanWindowIsKeptUntilLockEnd() {
            LoginAttemptLimiter longLock = new LoginAttemptLimiter(
                    new LoginLimitProperties(5, FIFTEEN, Duration.ofMinutes(30), 100), clock);
            for (int i = 0; i < 5; i++) {
                fail(longLock, "long@example.com");
            }
            clock.advance(Duration.ofMinutes(29).plusSeconds(59));
            assertThat(longLock.sweepExpired()).isZero();
            assertLocked(longLock, "long@example.com", 1);
            clock.advance(Duration.ofSeconds(1));
            assertThat(longLock.sweepExpired()).isEqualTo(1);
        }

        @Test
        void inFlightAttemptThatStartedBeforeTheLockIsTheCompletionThatCreatesIt() {
            List<LoginAttemptLimiter.Reservation> open = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                open.add(limiter.reserve("bound@example.com"));
            }
            // 잠금보다 먼저 시작한 시도. 시계만 지나고 완료는 아직이다.
            clock.advance(Duration.ofMinutes(10));
            for (int i = 0; i < 4; i++) {
                open.get(i).failure();
            }
            assertThat(limiter.inFlight("bound@example.com")).isEqualTo(1);
            // 다섯 번째 자리가 진행 중이라 잠금이 아니다. Retry-After 는 진행 중 한도(1초)다.
            assertLocked(limiter, "bound@example.com", 1);
            open.get(4).failure();
            // 잠금을 만든 완료가 마지막 진행 중 시도다. 잠금 뒤에 끝날 예약이 남아 있지 않다.
            assertThat(limiter.inFlight("bound@example.com")).isZero();
            assertLocked(limiter, "bound@example.com", 15 * 60);
            // 유지는 잠금 종료까지다. 시작 시각 + window(앞으로 5분)나 잠금 + window 가 아니다.
            clock.advance(Duration.ofMinutes(5));
            assertThat(limiter.sweepExpired()).isZero();
            assertLocked(limiter, "bound@example.com", 10 * 60);
            clock.advance(Duration.ofMinutes(10));
            assertThat(limiter.sweepExpired()).isEqualTo(1);
            assertThat(limiter.reservedSlots()).isZero();
        }

        @Test
        void noScheduleLeavesAnInFlightReservationAfterTheLock() {
            String key = "schedule@example.com";
            List<LoginAttemptLimiter.Reservation> open = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                open.add(limiter.reserve(key));
            }
            // 반환으로 자리를 비워도, 그 뒤에 시작한 시도가 잠금을 만들 때는 이전 예약이 이미 끝난다.
            open.get(0).releaseUncounted();
            open.set(0, limiter.reserve(key));
            clock.advance(Duration.ofMinutes(3));
            for (int i = 0; i < 4; i++) {
                open.get(i).failure();
            }
            assertThat(limiter.inFlight(key)).isEqualTo(1);
            open.get(4).failure();
            assertThat(limiter.inFlight(key)).isZero();
            long lockRetryAfter = rejection(limiter, key).getRetryAfterSeconds();
            assertThat(lockRetryAfter).isEqualTo(15 * 60);
            // 잠금 중 거절은 해시를 타지 않으므로 실패 시각을 남기지 않는다.
            clock.advance(Duration.ofMinutes(1));
            assertLocked(limiter, key, 14 * 60);
            clock.advance(Duration.ofMinutes(14).minusSeconds(1));
            assertThat(limiter.sweepExpired()).isZero();
            assertLocked(limiter, key, 1);
            clock.advance(Duration.ofSeconds(1));
            assertThat(limiter.sweepExpired()).isEqualTo(1);
            assertThat(limiter.reservedSlots()).isZero();
        }

        @Test
        void lockEndAcceptsTheSameKeyWhileAnUnsweptSlotStillBlocksANewKey() {
            LoginAttemptLimiter small = limiter(1);
            for (int i = 0; i < 5; i++) {
                fail(small, "held@example.com");
            }
            clock.advance(FIFTEEN.minusMillis(500));
            assertLocked(small, "held@example.com", 1);
            RetryLaterException beforeEnd = rejection(small, "new@example.com");
            assertThat(beforeEnd).isInstanceOf(ServiceBusyException.class);
            assertThat(beforeEnd.getRetryAfterSeconds()).isEqualTo(1);

            // 잠금은 끝났지만 직전 포화 스캔의 1초 안이라 만료 항목이 슬롯을 아직 차지한다.
            clock.advance(Duration.ofMillis(600));
            assertThat(small.size()).isEqualTo(1);
            RetryLaterException duringGap = rejection(small, "new@example.com");
            assertThat(duringGap).isInstanceOf(ServiceBusyException.class);
            assertThat(duringGap.getRetryAfterSeconds()).isEqualTo(1);

            // 같은 키는 잠금 종료 시각에 바로 예약된다. 새 키의 슬롯 회수와 시각이 다르다.
            LoginAttemptLimiter.Reservation sameKey = small.reserve("held@example.com");
            assertThat(sameKey.isCompleted()).isFalse();
            sameKey.close();
            // 같은 키를 닫으면 빈 항목은 바로 지워진다. 주기 정리를 기다리지 않는다.
            assertThat(small.size()).isZero();
            assertThat(small.reservedSlots()).isZero();
        }

        @Test
        void saturatedScanReclaimsAnExpiredLockOnlyAfterTheScanInterval() {
            LoginAttemptLimiter small = limiter(1);
            for (int i = 0; i < 5; i++) {
                fail(small, "held@example.com");
            }
            clock.advance(FIFTEEN.minusMillis(500));
            assertThat(rejection(small, "new@example.com")).isInstanceOf(ServiceBusyException.class);
            clock.advance(Duration.ofMillis(600));
            assertThat(small.size()).isEqualTo(1);
            assertThat(rejection(small, "new@example.com").getRetryAfterSeconds()).isEqualTo(1);
            clock.advance(Duration.ofMillis(500));
            small.reserve("new@example.com").close();
            assertThat(small.size()).isZero();
            assertThat(small.reservedSlots()).isZero();
        }
    }

    // ---------- 동시성 (기존 보장 유지) ----------

    @Test
    void concurrentAttemptsNeverExceedLimit() throws Exception {
        int threads = 64;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger permitted = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    LoginAttemptLimiter.Reservation reservation = limiter.reserve("race@example.com");
                    permitted.incrementAndGet();
                    Thread.sleep(5); // 비밀번호 검증 시간 흉내 (테스트 스레드만 대기)
                    reservation.failure();
                } catch (TooManyRequestsException ignored) {
                    // 한도 초과
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(permitted.get()).isEqualTo(5);
        assertLocked(limiter, "race@example.com", 15 * 60);
    }

    @Test
    void concurrentDistinctKeysAreAllTracked() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            String key = "user" + i + "@example.com";
            futures.add(pool.submit(() -> fail(limiter, key)));
        }
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(limiter.size()).isEqualTo(1000);
        assertThat(limiter.reservedSlots()).isEqualTo(1000);
    }

    @Test
    void statsCountRejectionReasonsWithoutKeys() {
        LoginAttemptLimiter small = limiter(1);
        for (int i = 0; i < 5; i++) {
            fail(small, "s@example.com");
        }
        rejection(small, "s@example.com");
        rejection(small, "t@example.com");
        LoginAttemptLimiter.Snapshot snapshot = small.drainStats();
        assertThat(snapshot.lockedRejections()).isEqualTo(1);
        assertThat(snapshot.saturatedRejections()).isEqualTo(1);
        assertThat(snapshot.inFlightRejections()).isZero();
        assertThat(snapshot.entries()).isEqualTo(1);
        assertThat(snapshot.toString()).doesNotContain("@example.com");
        assertThat(small.drainStats().hasRejections()).isFalse();
    }

    @Test
    void invalidPropertiesFailFast() {
        assertThatThrownBy(() -> new LoginLimitProperties(0, FIFTEEN, FIFTEEN, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("max-failures");
        assertThatThrownBy(() -> new LoginLimitProperties(5, Duration.ZERO, FIFTEEN, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("window");
        assertThatThrownBy(() -> new LoginLimitProperties(5, FIFTEEN, Duration.ofSeconds(-1), 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lock-duration");
        assertThatThrownBy(() -> new LoginLimitProperties(5, FIFTEEN, FIFTEEN, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("max-entries");
    }

    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
