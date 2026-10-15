package com.manhwanet.collection.collector.core;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 플랫폼별 요청 순서·간격(설계 §7.2). 프로세스 안의 한 빈이라 같은 플랫폼의 모든 작업·재시도·리다이렉트 단계·robots 요청이
 * 이 한 줄을 지난다.
 * <ul>
 *   <li>동시 요청 1건: 허가(permit)를 쥔 동안 같은 플랫폼의 다른 요청은 기다린다.</li>
 *   <li>간격: 앞 요청이 끝난 시각 + 그때 정한 간격 이전에는 다음 요청을 보내지 않는다.</li>
 *   <li>Retry-After: {@link #delayAtLeast} 로 다음 요청 가능 시각을 뒤로 민다.</li>
 * </ul>
 * 기다리는 동안 {@link WaitGuard} 로 잠금 연장·중단 요청을 확인한다. 다른 프로세스와의 순서는 "플랫폼당 활성 실행 하나"와 실행 잠금이 보장한다.
 * 다음 허용 시각 자체는 실행 행(`next_request_at`)에도 남겨, 이 메모리 상태가 없는 재개 프로세스도 남은 간격을 지킨다.
 */
public class PlatformThrottle {

    private final CollectorClock clock;
    private final ConcurrentMap<String, Slot> slots = new ConcurrentHashMap<>();

    public PlatformThrottle(CollectorClock clock) {
        this.clock = clock;
    }

    private static final class Slot {
        final ReentrantLock lock = new ReentrantLock(true);
        long nextAllowedNanos;
        boolean used;
    }

    /** 요청 허가. 끝나면 반드시 {@link Permit#release(long)} */
    public final class Permit {
        private final Slot slot;
        private boolean released;

        private Permit(Slot slot) {
            this.slot = slot;
        }

        /** 요청을 끝냈다. 다음 요청은 지금부터 intervalMillis 뒤(Retry-After 로 더 미뤄졌으면 그 시각) 이후 */
        public void release(long intervalMillis) {
            if (released) {
                return;
            }
            released = true;
            synchronized (slot) {
                long next = clock.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, intervalMillis));
                slot.nextAllowedNanos = slot.used ? Math.max(slot.nextAllowedNanos, next) : next;
                slot.used = true;
            }
            slot.lock.unlock();
        }
    }

    public Permit acquire(String key, WaitGuard guard) {
        Slot slot = slots.computeIfAbsent(key, k -> new Slot());
        try {
            while (!slot.lock.tryLock(1, TimeUnit.SECONDS)) {
                guard.checkpoint();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RunHaltException(Halt.INTERRUPTED, "요청 대기 중 인터럽트");
        }
        Permit permit = new Permit(slot);
        try {
            while (true) {
                long waitNanos;
                synchronized (slot) {
                    waitNanos = slot.used ? slot.nextAllowedNanos - clock.nanoTime() : 0;
                }
                if (waitNanos <= 0) {
                    return permit;
                }
                guard.await(Math.max(1, TimeUnit.NANOSECONDS.toMillis(waitNanos + 999_999)));
            }
        } catch (RuntimeException e) {
            slot.lock.unlock();
            throw e;
        }
    }

    /** 다음 요청을 지금부터 적어도 millis 뒤로 미룬다(Retry-After) */
    public void delayAtLeast(String key, long millis) {
        Slot slot = slots.computeIfAbsent(key, k -> new Slot());
        synchronized (slot) {
            long at = clock.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, millis));
            slot.nextAllowedNanos = slot.used ? Math.max(slot.nextAllowedNanos, at) : at;
            slot.used = true;
        }
    }
}
