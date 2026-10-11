package com.manhwanet.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.manhwanet.global.error.BusinessException;
import com.manhwanet.global.error.ErrorCode;
import com.manhwanet.global.error.ServiceBusyException;
import com.manhwanet.global.error.TooManyRequestsException;
import com.manhwanet.global.security.HashLimitProperties;
import com.manhwanet.global.security.PasswordHashGate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * 획득·해제 순서(명세 §6)와 누수 없는 회복. 인증은 가짜 AuthenticationManager 로 대신한다.
 */
class AuthServiceProtectionTest {

    private static final String PASSWORD = "correct-horse-battery";

    private final LoginAttemptLimiterTest.MutableClock clock =
            new LoginAttemptLimiterTest.MutableClock(Instant.parse("2026-10-10T00:00:00Z"));
    private final LoginAttemptLimiter limiter = new LoginAttemptLimiter(
            new LoginLimitProperties(5, Duration.ofMinutes(15), Duration.ofMinutes(15), 100_000), clock);

    /** 인증 동작을 바꿔 끼울 수 있는 가짜. 호출 수를 센다 */
    private static final class FakeAuthenticationManager implements AuthenticationManager {
        final AtomicInteger calls = new AtomicInteger();
        volatile java.util.function.Function<Authentication, Authentication> behavior = auth -> {
            if (PASSWORD.equals(auth.getCredentials())) {
                return UsernamePasswordAuthenticationToken.authenticated(auth.getName(), null, List.of());
            }
            throw new BadCredentialsException("bad");
        };

        @Override
        public Authentication authenticate(Authentication authentication) {
            calls.incrementAndGet();
            return behavior.apply(authentication);
        }
    }

    private final FakeAuthenticationManager authenticationManager = new FakeAuthenticationManager();

    private AuthService service(PasswordHashGate gate) {
        return new AuthService(authenticationManager, limiter, gate);
    }

    private static PasswordHashGate gate(int p, int q, long waitMillis) {
        return new PasswordHashGate(new HashLimitProperties(p, q, Duration.ofMillis(waitMillis)));
    }

    private static void assertNoLeak(PasswordHashGate gate, int p) {
        assertThat(gate.availablePermits()).isEqualTo(p);
        assertThat(gate.inUseCount()).isZero();
        assertThat(gate.waitingCount()).isZero();
    }

    @Test
    void gateRejectionIsNotCountedAsFailure() {
        PasswordHashGate gate = gate(1, 0, 50);
        AuthService service = service(gate);
        try (PasswordHashGate.Permit held = gate.acquire()) {
            for (int i = 0; i < 20; i++) {
                assertThatThrownBy(() -> service.authenticate("busy@example.com", "wrong"))
                        .isInstanceOf(ServiceBusyException.class);
            }
        }
        assertThat(authenticationManager.calls.get()).isZero();
        assertThat(limiter.inFlight("busy@example.com")).isZero();
        assertThat(limiter.size()).isZero();
        // 실패 기록이 없으므로 4번 틀린 뒤에도 정상 로그인된다
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> service.authenticate("busy@example.com", "wrong"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS));
        }
        assertThat(service.authenticate("busy@example.com", PASSWORD).isAuthenticated()).isTrue();
        assertNoLeak(gate, 1);
    }

    @Test
    void lockedKeyIsRejectedBeforeTheGateWithoutHashing() {
        PasswordHashGate gate = gate(1, 0, 50);
        AuthService service = service(gate);
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> service.authenticate("lock@example.com", "wrong"))
                    .isInstanceOf(BusinessException.class);
        }
        int calls = authenticationManager.calls.get();
        try (PasswordHashGate.Permit held = gate.acquire()) {
            // 게이트가 가득 차 있어도 잠긴 키는 503 이 아니라 429 (게이트를 거치지 않음)
            assertThatThrownBy(() -> service.authenticate("lock@example.com", PASSWORD))
                    .isInstanceOf(TooManyRequestsException.class);
        }
        assertThat(authenticationManager.calls.get()).isEqualTo(calls);
        assertNoLeak(gate, 1);
    }

    @Test
    void nonAuthenticationExceptionReleasesPermitAndReservationUncounted() {
        PasswordHashGate gate = gate(2, 4, 250);
        AuthService service = service(gate);
        authenticationManager.behavior = auth -> {
            throw new InternalAuthenticationServiceException("db down");
        };
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> service.authenticate("db@example.com", PASSWORD))
                    .isInstanceOf(InternalAuthenticationServiceException.class);
        }
        assertThat(limiter.size()).isZero();
        assertNoLeak(gate, 2);
    }

    @Test
    void errorDuringHashingAlsoReleasesEverything() {
        PasswordHashGate gate = gate(2, 4, 250);
        AuthService service = service(gate);
        authenticationManager.behavior = auth -> {
            throw new StackOverflowError("simulated");
        };
        assertThatThrownBy(() -> service.authenticate("err@example.com", PASSWORD)).isInstanceOf(StackOverflowError.class);
        assertThat(limiter.size()).isZero();
        assertNoLeak(gate, 2);
    }

    @Test
    void timeoutWhileWaitingForGateIsUncounted() {
        PasswordHashGate gate = gate(1, 4, 100);
        AuthService service = service(gate);
        try (PasswordHashGate.Permit held = gate.acquire()) {
            long start = System.nanoTime();
            assertThatThrownBy(() -> service.authenticate("slow@example.com", "wrong"))
                    .isInstanceOf(ServiceBusyException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(200));
        }
        assertThat(limiter.size()).isZero();
        assertNoLeak(gate, 1);
    }

    @Test
    void interruptWhileWaitingForGateIsUncountedAndRecoverable() throws Exception {
        PasswordHashGate gate = gate(1, 4, 1000);
        AuthService service = service(gate);
        PasswordHashGate.Permit held = gate.acquire();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            try {
                service.authenticate("intr@example.com", "wrong");
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        waiter.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (gate.waitingCount() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        Thread.sleep(20);
        waiter.interrupt();
        waiter.join(2000);
        assertThat(thrown.get()).isInstanceOf(ServiceBusyException.class);
        held.close();
        assertThat(limiter.size()).isZero();
        assertNoLeak(gate, 1);
        assertThat(service.authenticate("intr@example.com", PASSWORD).isAuthenticated()).isTrue();
    }

    @Test
    void badCredentialsAreCountedAndPermitReturned() {
        PasswordHashGate gate = gate(2, 4, 250);
        AuthService service = service(gate);
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> service.authenticate(" Mixed@Example.com ", "wrong"))
                    .isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(() -> service.authenticate("mixed@example.com", PASSWORD))
                .isInstanceOf(TooManyRequestsException.class);
        assertNoLeak(gate, 2);
    }

    @Test
    void concurrentMixedFailuresRecoverWithoutLeaks() throws Exception {
        int p = 2;
        PasswordHashGate gate = gate(p, 4, 50);
        AuthService service = service(gate);
        authenticationManager.behavior = auth -> {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            String name = auth.getName();
            int bucket = Math.floorMod(name.hashCode(), 4);
            return switch (bucket) {
                case 0 -> UsernamePasswordAuthenticationToken.authenticated(name, null, List.of());
                case 1 -> throw new BadCredentialsException("bad");
                case 2 -> throw new InternalAuthenticationServiceException("db");
                default -> throw new IllegalStateException("bug");
            };
        };
        ExecutorService pool = Executors.newFixedThreadPool(64);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        AtomicInteger busy = new AtomicInteger();
        for (int i = 0; i < 2000; i++) {
            String email = "c" + (i % 97) + "@example.com";
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    service.authenticate(email, "pw");
                } catch (ServiceBusyException e) {
                    busy.incrementAndGet();
                } catch (RuntimeException ignored) {
                    // 401·429·인증 외 예외
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(busy.get()).isPositive();
        assertNoLeak(gate, p);
        for (int i = 0; i < 97; i++) {
            assertThat(limiter.inFlight("c" + i + "@example.com")).isZero();
        }
        assertThat(limiter.reservedSlots()).isEqualTo(limiter.size());
        // 회복: 정상 동작으로 되돌리면 새 이메일 로그인 성공
        authenticationManager.behavior = auth -> UsernamePasswordAuthenticationToken.authenticated(auth.getName(),
                null, List.of());
        assertThat(service.authenticate("fresh@example.com", PASSWORD).isAuthenticated()).isTrue();
    }
}
