package com.manhwanet.auth.application;

import com.manhwanet.global.error.ErrorCode;
import com.manhwanet.global.error.ServiceBusyException;
import com.manhwanet.global.error.TooManyRequestsException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 이메일(정규화된 값) 키별 로그인 실패 제한. 서버 메모리에만 저장한다(docs/login-limit-design-draft.md §4·§8).
 *
 * <p>규칙: {@code window} 안에 {@code maxFailures}번 실패하면 {@code lockDuration} 동안 거절한다(429).
 * 존재하지 않는 이메일도 같은 규칙을 적용한다. 진행 중 시도(inFlight)도 한도에 포함하므로 한 키에 한도보다 많은
 * 비밀번호 검증이 동시에 시작되지 않는다. 키별 상태는 {@link ConcurrentHashMap#compute}로 원자적으로 바꾸고,
 * 완료 처리는 적용되는 순서대로 하나씩 반영한다(명세 §4.3).
 *
 * <p>용량: 활성 항목(기간 내 실패·잠금·진행 중)은 용량 때문에 지우지 않는다. 만료된 항목만 주기 정리
 * ({@link #sweepExpired()})와 포화 시 즉시 정리로 지운다. 신규 키는 상한 안에서 자리를 원자적으로 예약한 뒤에만
 * 받으므로 키 수가 {@code maxEntries}를 넘지 않는다. 정리 후에도 가득 차면 신규 키의 로그인은 503 AUTH_BUSY 이고,
 * 이미 항목이 있는 키는 평소대로 처리한다.
 *
 * <p>요청 스레드를 재우는(sleep) 지연은 쓰지 않는다. 한계: 서버 재시작 시 기록이 사라지고, 여러 인스턴스 사이에
 * 공유되지 않는다(#30).
 */
@Component
public class LoginAttemptLimiter {

    /** 진행 중 시도가 한도에 닿았을 때의 Retry-After. 진행 중 시도 하나가 끝나면 풀린다(명세 §7). */
    static final Duration IN_FLIGHT_RETRY_AFTER = Duration.ofSeconds(1);
    /** 포화 상태에서 만료 정리 스캔을 다시 하는 최소 간격(명세 §7). */
    static final Duration SATURATED_SCAN_INTERVAL = Duration.ofSeconds(1);
    /** 저장소 포화 Retry-After 상한(초, 명세 §7). */
    static final long MAX_SATURATED_RETRY_AFTER_SECONDS = 900;

    private final LoginLimitProperties properties;
    private final Clock clock;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    /** 저장된 키 수 + 신규 키 삽입을 위해 예약했지만 아직 넣지 않은 자리 수. 항상 {@code maxEntries} 이하다. */
    private final AtomicInteger slots = new AtomicInteger();

    private final ReentrantLock saturationLock = new ReentrantLock();
    /** 포화 시 즉시 정리를 마지막으로 한 시각. saturationLock 안에서만 바꾼다. */
    private Instant lastSaturatedScan;
    /** 마지막 정리에서 계산한 가장 이른 활성 항목 만료 시각(포화 Retry-After 용). */
    private volatile Instant earliestExpiry;

    // 지표(1분 로그용). 키(이메일)는 담지 않는다.
    private final LongAdder lockedRejections = new LongAdder();
    private final LongAdder inFlightRejections = new LongAdder();
    private final LongAdder saturatedRejections = new LongAdder();

    @Autowired
    public LoginAttemptLimiter(LoginLimitProperties properties) {
        this(properties, Clock.systemUTC());
    }

    public LoginAttemptLimiter(LoginLimitProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 비밀번호 검증을 시작해도 되는지 확인하고, 허용되면 진행 중 시도 1건을 예약한다. 해시 연산은 하지 않는다.
     * 돌려받은 예약은 반드시 완료하거나 닫아야 한다(try-with-resources).
     *
     * @throws TooManyRequestsException 429 TOO_MANY_LOGIN_ATTEMPTS. 잠김(남은 잠금 시간) 또는 진행 중 시도 한도(1초)
     * @throws ServiceBusyException     503 AUTH_BUSY. 저장소가 가득 차 신규 키를 받을 수 없음(가장 이른 만료까지)
     */
    public Reservation reserve(String key) {
        Instant now = clock.instant();
        boolean slotHeld = false;
        try {
            while (true) {
                if (!slotHeld && !entries.containsKey(key)) {
                    slotHeld = tryTakeSlot() || (makeRoom() && tryTakeSlot());
                    if (!slotHeld && !entries.containsKey(key)) {
                        saturatedRejections.increment();
                        throw new ServiceBusyException(ErrorCode.AUTH_BUSY, saturatedRetryAfterSeconds(clock.instant()));
                    }
                }
                boolean canCreate = slotHeld;
                Attempt attempt = new Attempt();
                entries.compute(key, (k, entry) -> {
                    Entry current = entry;
                    if (current == null) {
                        if (!canCreate) {
                            // 확인한 뒤 그 사이 항목이 지워졌다. 자리를 예약해 다시 시도한다.
                            attempt.result = Result.RETRY;
                            return null;
                        }
                        current = new Entry(properties.maxFailures());
                        attempt.usedSlot = true;
                    }
                    current.prune(now, properties.window());
                    if (current.isLocked()) {
                        attempt.result = Result.LOCKED;
                        attempt.retryAfter = Duration.between(now, current.lockedUntil);
                    } else if (current.failures.size() + current.inFlight >= properties.maxFailures()) {
                        attempt.result = Result.IN_FLIGHT_LIMIT;
                    } else {
                        current.inFlight++;
                        attempt.result = Result.RESERVED;
                    }
                    return current;
                });
                if (attempt.usedSlot) {
                    slotHeld = false;
                }
                switch (attempt.result) {
                    case RETRY -> {
                        continue;
                    }
                    case LOCKED -> {
                        lockedRejections.increment();
                        throw new TooManyRequestsException(ErrorCode.TOO_MANY_LOGIN_ATTEMPTS,
                                ceilSeconds(attempt.retryAfter));
                    }
                    case IN_FLIGHT_LIMIT -> {
                        inFlightRejections.increment();
                        throw new TooManyRequestsException(ErrorCode.TOO_MANY_LOGIN_ATTEMPTS,
                                ceilSeconds(IN_FLIGHT_RETRY_AFTER));
                    }
                    case RESERVED -> {
                        return new Reservation(key);
                    }
                }
            }
        } finally {
            if (slotHeld) {
                slots.decrementAndGet();
            }
        }
    }

    /**
     * 만료된 항목을 지운다(1분 주기 정리). 활성 항목은 지우지 않는다.
     *
     * @return 지운 항목 수
     */
    public int sweepExpired() {
        return sweep(clock.instant());
    }

    /** 1분 로그용 지표를 읽고 거절 수를 0으로 되돌린다. */
    public Snapshot drainStats() {
        return new Snapshot(lockedRejections.sumThenReset(), inFlightRejections.sumThenReset(),
                saturatedRejections.sumThenReset(), entries.size(), properties.maxEntries());
    }

    /** 저장된 키 수 */
    public int size() {
        return entries.size();
    }

    /** 예약 중인 자리를 포함한 키 수(테스트용). 항상 maxEntries 이하다. */
    int reservedSlots() {
        return slots.get();
    }

    /** 키의 진행 중 시도 수(테스트용). 항목이 없으면 0 */
    int inFlight(String key) {
        Entry[] seen = new Entry[1];
        int[] value = new int[1];
        entries.computeIfPresent(key, (k, entry) -> {
            seen[0] = entry;
            value[0] = entry.inFlight;
            return entry;
        });
        return seen[0] == null ? 0 : value[0];
    }

    // ---------- 완료 처리 (Reservation 에서만 호출) ----------

    /** 인증 성공: 실패 목록과 잠금을 지운다. 동시 실패가 만든 잠금도 해제한다(명세 §4.3). */
    private void recordSuccess(String key) {
        update(key, entry -> {
            entry.inFlight = Math.max(0, entry.inFlight - 1);
            entry.failures.clear();
            entry.lockedUntil = null;
        });
    }

    /**
     * 인증 실패: 실패를 기록하고, 잠겨 있지 않은데 기간 안 실패가 한도에 이르면 잠근다.
     * 예약 규칙에서는 잠금이 생기는 순간 진행 중 시도가 0이라, 잠금 전에 시작한 실패가 잠금 뒤에 끝나지 않는다.
     * 이미 잠긴 항목에 실패가 들어오면 목록에만 쌓고 잠금을 늘리지 않는다(도달하지 않는 경로의 방어, 명세 §4.3).
     */
    private void recordFailure(String key) {
        Instant now = clock.instant();
        update(key, entry -> {
            entry.inFlight = Math.max(0, entry.inFlight - 1);
            entry.prune(now, properties.window());
            entry.failures.addLast(now);
            if (!entry.isLocked() && entry.failures.size() >= properties.maxFailures()) {
                entry.lockedUntil = now.plus(properties.lockDuration());
                entry.failures.clear();
            }
        });
    }

    /** 인증 결과와 무관하게 끝난 시도(게이트 거절·인증 외 예외·인터럽트): 예약만 반환한다. */
    private void releaseUncounted(String key) {
        Instant now = clock.instant();
        update(key, entry -> {
            entry.inFlight = Math.max(0, entry.inFlight - 1);
            entry.prune(now, properties.window());
        });
    }

    /** 키 항목을 원자적으로 바꾸고, 비활성이 되면 지운다. 진행 중 시도가 있는 항목은 지워지지 않으므로 항상 존재한다. */
    private void update(String key, java.util.function.Consumer<Entry> change) {
        boolean[] removed = new boolean[1];
        entries.computeIfPresent(key, (k, entry) -> {
            change.accept(entry);
            if (entry.isEmpty()) {
                removed[0] = true;
                return null;
            }
            return entry;
        });
        if (removed[0]) {
            slots.decrementAndGet();
        }
    }

    // ---------- 용량 ----------

    private boolean tryTakeSlot() {
        while (true) {
            int current = slots.get();
            if (current >= properties.maxEntries()) {
                return false;
            }
            if (slots.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /**
     * 포화 시 즉시 정리. 스캔은 1초에 최대 1번이고, 그 사이에는 정리하지 않고 직전 계산값을 쓴다.
     *
     * @return 정리 후 자리가 있을 수 있으면 true
     */
    private boolean makeRoom() {
        saturationLock.lock();
        try {
            if (slots.get() < properties.maxEntries()) {
                return true;
            }
            Instant now = clock.instant();
            if (lastSaturatedScan != null && now.isBefore(lastSaturatedScan.plus(SATURATED_SCAN_INTERVAL))) {
                return false;
            }
            lastSaturatedScan = now;
            sweep(now);
            return slots.get() < properties.maxEntries();
        } finally {
            saturationLock.unlock();
        }
    }

    /** 만료 항목을 지우고, 남은 활성 항목 중 가장 이른 만료 시각을 계산한다. */
    private int sweep(Instant now) {
        int removedCount = 0;
        Instant[] earliest = new Instant[1];
        for (String key : entries.keySet()) {
            boolean[] removed = new boolean[1];
            entries.computeIfPresent(key, (k, entry) -> {
                entry.prune(now, properties.window());
                if (entry.isEmpty()) {
                    removed[0] = true;
                    return null;
                }
                Instant expiry = entry.expiry(now, properties.window());
                if (earliest[0] == null || expiry.isBefore(earliest[0])) {
                    earliest[0] = expiry;
                }
                return entry;
            });
            if (removed[0]) {
                slots.decrementAndGet();
                removedCount++;
            }
        }
        earliestExpiry = earliest[0];
        return removedCount;
    }

    private long saturatedRetryAfterSeconds(Instant now) {
        Instant expiry = earliestExpiry;
        long seconds = expiry == null ? 1 : ceilSeconds(Duration.between(now, expiry));
        return Math.min(MAX_SATURATED_RETRY_AFTER_SECONDS, Math.max(1, seconds));
    }

    /** Retry-After 헤더용 초 단위(올림, 최소 1) */
    static long ceilSeconds(Duration duration) {
        if (duration.isNegative() || duration.isZero()) {
            return 1;
        }
        long seconds = duration.toSeconds() + (duration.toNanosPart() > 0 ? 1 : 0);
        return Math.max(1, seconds);
    }

    // ---------- 타입 ----------

    /**
     * 진행 중 시도 1건의 예약. {@link #success()}, {@link #failure()}, {@link #releaseUncounted()} 중 처음 호출만
     * 반영되고, {@link #close()}는 아직 완료되지 않았을 때만 예약을 반환한다. 그래서 inFlight 가 두 번 줄지 않는다.
     */
    public final class Reservation implements AutoCloseable {

        private final String key;
        private final AtomicBoolean completed = new AtomicBoolean();

        private Reservation(String key) {
            this.key = key;
        }

        /** 인증 성공 */
        public void success() {
            if (completed.compareAndSet(false, true)) {
                recordSuccess(key);
            }
        }

        /** 인증 실패(없는 이메일·틀린 비밀번호). 실패 1회로 센다 */
        public void failure() {
            if (completed.compareAndSet(false, true)) {
                recordFailure(key);
            }
        }

        /** 결과 없이 끝남. 실패로 세지 않는다 */
        public void releaseUncounted() {
            if (completed.compareAndSet(false, true)) {
                LoginAttemptLimiter.this.releaseUncounted(key);
            }
        }

        public boolean isCompleted() {
            return completed.get();
        }

        /** 아직 완료되지 않았으면 실패로 세지 않고 반환한다. 이미 완료됐으면 아무것도 하지 않는다 */
        @Override
        public void close() {
            releaseUncounted();
        }
    }

    /** 1분 로그용 지표 */
    public record Snapshot(long lockedRejections, long inFlightRejections, long saturatedRejections, int entries,
            int maxEntries) {

        public boolean hasRejections() {
            return lockedRejections + inFlightRejections + saturatedRejections > 0;
        }
    }

    private enum Result { RETRY, LOCKED, IN_FLIGHT_LIMIT, RESERVED }

    /** compute 안에서 정한 결과를 밖으로 전달한다 */
    private static final class Attempt {
        private Result result;
        private Duration retryAfter;
        private boolean usedSlot;
    }

    /** 키별 상태. compute 안에서만 읽고 바꾼다. */
    private static final class Entry {
        private final ArrayDeque<Instant> failures;
        private int inFlight;
        private Instant lockedUntil;

        Entry(int maxFailures) {
            this.failures = new ArrayDeque<>(maxFailures);
        }

        /** 기간이 지난 실패와 끝난 잠금을 지운다. */
        void prune(Instant now, Duration window) {
            Instant threshold = now.minus(window);
            while (!failures.isEmpty() && !failures.peekFirst().isAfter(threshold)) {
                failures.pollFirst();
            }
            if (lockedUntil != null && !lockedUntil.isAfter(now)) {
                lockedUntil = null;
            }
        }

        /** prune 이후 호출. 잠금이 남아 있는지 */
        boolean isLocked() {
            return lockedUntil != null;
        }

        /** prune 이후 호출. 활성(기간 안 실패·잠금·진행 중)이 하나도 없는지 */
        boolean isEmpty() {
            return inFlight == 0 && failures.isEmpty() && lockedUntil == null;
        }

        /**
         * prune 이후 호출. 이 항목이 비활성이 되는 가장 이른 시각.
         * 진행 중만 있는 항목은 만료 시각이 없으므로 1초 뒤로 본다(명세 §7).
         */
        Instant expiry(Instant now, Duration window) {
            Instant expiry = null;
            if (!failures.isEmpty()) {
                expiry = failures.peekLast().plus(window);
            }
            if (lockedUntil != null && (expiry == null || lockedUntil.isAfter(expiry))) {
                expiry = lockedUntil;
            }
            return expiry == null ? now.plusSeconds(1) : expiry;
        }
    }
}
