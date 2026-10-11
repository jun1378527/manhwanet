package com.manhwanet.global.security;

import com.manhwanet.global.error.ErrorCode;
import com.manhwanet.global.error.ServiceBusyException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.stereotype.Component;

/**
 * 웹 요청의 비밀번호 해시 연산(로그인 검증·없는 이메일의 더미 검증·가입 해시 생성)에 거는 공통 부하 제한.
 *
 * <ul>
 *   <li>자리(permit)가 있으면 바로 얻는다. 없으면 대기자가 {@code maxWaiting} 미만일 때만 최대 {@code maxWait} 기다린다
 *       (공정 모드 {@link Semaphore}).</li>
 *   <li>대기자가 가득 찼거나 시간 안에 얻지 못하면, 또는 대기 중 인터럽트되면 {@link ServiceBusyException}
 *       (503 AUTH_BUSY, Retry-After 1)을 던진다. 인터럽트 플래그는 복원한다.</li>
 *   <li>무기한 대기·sleep·별도 스레드 풀은 쓰지 않는다. 요청 스레드가 붙잡히는 시간은 최대 {@code maxWait}다.</li>
 * </ul>
 *
 * <p>{@code PasswordEncoder} Bean 을 감싸지 않는다. 관리자 생성 명령(admin-cli)은 이 게이트를 거치지 않는다.
 * 사용 측은 해시 연산 구간만 {@code try (Permit permit = gate.acquire()) { ... }}로 감싼다.
 */
@Component
public class PasswordHashGate {

    /** 게이트 과부하는 자리가 곧 비므로 1초 뒤 재시도를 안내한다(명세 §7). */
    static final long RETRY_AFTER_SECONDS = 1;

    private final Semaphore semaphore;
    private final int maxWaiting;
    private final long maxWaitNanos;
    private final AtomicInteger waiting = new AtomicInteger();
    private final AtomicInteger inUse = new AtomicInteger();

    // 지표(로그용). 이메일·비밀번호 등 개인 정보는 다루지 않는다.
    private final LongAdder rejected = new LongAdder();
    private final AtomicInteger peakWaiting = new AtomicInteger();
    private final AtomicInteger peakInUse = new AtomicInteger();

    public PasswordHashGate(HashLimitProperties properties) {
        this.semaphore = new Semaphore(properties.maxConcurrent(), true);
        this.maxWaiting = properties.maxWaiting();
        this.maxWaitNanos = properties.maxWait().toNanos();
    }

    /**
     * 해시 연산 자리를 얻는다. 얻은 자리는 반드시 {@link Permit#close()}로 반환한다(try-with-resources).
     *
     * @throws ServiceBusyException 503 AUTH_BUSY. 자리를 얻지 못했으므로 반환할 것이 없다
     */
    public Permit acquire() {
        boolean enteredWaiting = false;
        try {
            // 공정 모드에서 시간 인자가 있는 tryAcquire 는 기다리는 스레드를 앞지르지 않는다. 0이면 기다리지 않는다.
            if (tryAcquireNanos(0)) {
                return grant();
            }
            if (!enterWaiting()) {
                throw reject();
            }
            enteredWaiting = true;
            if (tryAcquireNanos(maxWaitNanos)) {
                return grant();
            }
            throw reject();
        } catch (InterruptedException e) {
            // 자리를 얻지 못했으므로 반환할 것이 없다. 인터럽트 상태는 호출 측이 볼 수 있게 복원한다.
            Thread.currentThread().interrupt();
            throw reject();
        } finally {
            if (enteredWaiting) {
                waiting.decrementAndGet();
            }
        }
    }

    private boolean tryAcquireNanos(long nanos) throws InterruptedException {
        return semaphore.tryAcquire(nanos, TimeUnit.NANOSECONDS);
    }

    private boolean enterWaiting() {
        while (true) {
            int current = waiting.get();
            if (current >= maxWaiting) {
                return false;
            }
            if (waiting.compareAndSet(current, current + 1)) {
                peakWaiting.accumulateAndGet(current + 1, Math::max);
                return true;
            }
        }
    }

    private Permit grant() {
        peakInUse.accumulateAndGet(inUse.incrementAndGet(), Math::max);
        return new Permit();
    }

    private ServiceBusyException reject() {
        rejected.increment();
        return new ServiceBusyException(ErrorCode.AUTH_BUSY, RETRY_AFTER_SECONDS);
    }

    /** 지금 남은 자리 수(테스트·점검용). */
    public int availablePermits() {
        return semaphore.availablePermits();
    }

    /** 지금 자리를 기다리는 요청 수(테스트·점검용). */
    public int waitingCount() {
        return waiting.get();
    }

    /** 지금 자리를 쥐고 있는 요청 수(테스트·점검용). */
    public int inUseCount() {
        return inUse.get();
    }

    /** 직전 호출 이후 게이트 거절 수와 최대 대기 수를 읽고 0으로 되돌린다(1분 로그용). */
    public Snapshot drainStats() {
        return new Snapshot(rejected.sumThenReset(), peakWaiting.getAndSet(waiting.get()),
                peakInUse.getAndSet(inUse.get()));
    }

    /** 1분 로그용 지표. */
    public record Snapshot(long rejected, int peakWaiting, int peakInUse) {
    }

    /** 얻은 해시 자리. 여러 번 닫아도 한 번만 반환한다. */
    public final class Permit implements AutoCloseable {

        private final AtomicBoolean released = new AtomicBoolean();

        private Permit() {
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                inUse.decrementAndGet();
                semaphore.release();
            }
        }
    }
}
