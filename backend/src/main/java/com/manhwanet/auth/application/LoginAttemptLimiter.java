package com.manhwanet.auth.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 이메일(정규화된 값) 키별 로그인 실패 제한. 서버 메모리에만 저장한다.
 *
 * <p>규칙: {@code window} 안에 {@code maxFailures}번 실패하면 {@code lockDuration} 동안 거절한다.
 * 존재하지 않는 이메일도 같은 규칙을 적용하고, 성공하면 기록을 지운다.
 *
 * <p>동시성: 키별 상태는 {@link ConcurrentHashMap#compute}로 원자적으로 바꾼다. 인증 결과가 나오기 전의
 * 시도(inFlight)도 한도에 포함하므로, 동시에 요청이 몰려도 한도보다 많은 비밀번호 검증이 일어나지 않는다.
 * 요청 스레드를 재우는(sleep) 지연은 쓰지 않는다.
 *
 * <p>용량: 키 수가 {@code maxEntries}에 이르면 만료된 항목을 지우고, 그래도 가득 차면 진행 중 시도가 없는
 * 가장 오래된 항목을 내보낸다. 상한은 동시 요청 수만큼 일시적으로 넘을 수 있는 근사값이다.
 *
 * <p>한계: 서버 재시작 시 기록이 사라지고, 여러 인스턴스 사이에 공유되지 않는다. (docs/auth-design-draft.md)
 */
@Component
public class LoginAttemptLimiter {

    private final LoginLimitProperties properties;
    private final Clock clock;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Object evictionLock = new Object();

    @Autowired
    public LoginAttemptLimiter(LoginLimitProperties properties) {
        this(properties, Clock.systemUTC());
    }

    public LoginAttemptLimiter(LoginLimitProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 비밀번호 검증을 시작해도 되는지 확인하고, 허용되면 진행 중 시도 1건을 예약한다.
     * 허용된 경우 반드시 {@link #recordSuccess}, {@link #recordFailure}, {@link #release} 중 하나를 호출해야 한다.
     */
    public Decision tryAcquire(String key) {
        Instant now = clock.instant();
        if (!entries.containsKey(key)) {
            ensureCapacity(now);
        }
        Decision[] decision = new Decision[1];
        entries.compute(key, (k, entry) -> {
            Entry current = entry == null ? new Entry() : entry;
            current.prune(now, properties.window());
            current.lastTouched = now;
            if (current.lockedUntil != null && current.lockedUntil.isAfter(now)) {
                decision[0] = Decision.rejected(Duration.between(now, current.lockedUntil));
            } else if (current.failures.size() + current.inFlight >= properties.maxFailures()) {
                // 진행 중인 시도의 결과가 나오기 전까지 추가 시도를 받지 않는다.
                decision[0] = Decision.rejected(Duration.ofSeconds(1));
            } else {
                current.lockedUntil = null;
                current.inFlight++;
                decision[0] = Decision.PERMITTED;
            }
            return current;
        });
        return decision[0];
    }

    /** 인증 성공: 실패 기록을 지운다. */
    public void recordSuccess(String key) {
        entries.computeIfPresent(key, (k, entry) -> {
            entry.inFlight = Math.max(0, entry.inFlight - 1);
            entry.failures.clear();
            entry.lockedUntil = null;
            return entry.inFlight == 0 ? null : entry;
        });
    }

    /** 인증 실패(잘못된 이메일 또는 비밀번호): 실패를 기록하고 한도에 이르면 잠근다. */
    public void recordFailure(String key) {
        Instant now = clock.instant();
        entries.compute(key, (k, entry) -> {
            Entry current = entry == null ? new Entry() : entry;
            current.inFlight = Math.max(0, current.inFlight - 1);
            current.prune(now, properties.window());
            current.failures.addLast(now);
            current.lastTouched = now;
            if (current.failures.size() >= properties.maxFailures()) {
                current.lockedUntil = now.plus(properties.lockDuration());
                current.failures.clear();
            }
            return current;
        });
    }

    /** 인증 결과와 무관한 오류로 끝난 시도: 예약만 반환한다. */
    public void release(String key) {
        entries.computeIfPresent(key, (k, entry) -> {
            entry.inFlight = Math.max(0, entry.inFlight - 1);
            return entry.isEmpty(clock.instant()) ? null : entry;
        });
    }

    int size() {
        return entries.size();
    }

    private void ensureCapacity(Instant now) {
        if (entries.size() < properties.maxEntries()) {
            return;
        }
        synchronized (evictionLock) {
            if (entries.size() < properties.maxEntries()) {
                return;
            }
            entries.forEach((key, entry) -> entries.computeIfPresent(key, (k, e) -> {
                e.prune(now, properties.window());
                return e.isEmpty(now) ? null : e;
            }));
            while (entries.size() >= properties.maxEntries()) {
                String oldest = entries.entrySet().stream()
                        .filter(e -> e.getValue().inFlight == 0)
                        .min(Comparator.comparing(e -> e.getValue().lastTouched))
                        .map(Map.Entry::getKey)
                        .orElse(null);
                if (oldest == null) {
                    return;
                }
                entries.computeIfPresent(oldest, (k, e) -> e.inFlight == 0 ? null : e);
            }
        }
    }

    public record Decision(boolean permitted, Duration retryAfter) {

        static final Decision PERMITTED = new Decision(true, Duration.ZERO);

        static Decision rejected(Duration retryAfter) {
            return new Decision(false, retryAfter);
        }

        /** Retry-After 헤더용 초 단위 (올림, 최소 1) */
        public long retryAfterSeconds() {
            long seconds = retryAfter.toSeconds() + (retryAfter.toNanosPart() > 0 ? 1 : 0);
            return Math.max(1, seconds);
        }
    }

    /** 키별 상태. compute 안에서만 읽고 바꾼다. */
    private static final class Entry {
        private final ArrayDeque<Instant> failures = new ArrayDeque<>();
        private int inFlight;
        private Instant lockedUntil;
        private Instant lastTouched = Instant.EPOCH;

        void prune(Instant now, Duration window) {
            Instant threshold = now.minus(window);
            while (!failures.isEmpty() && !failures.peekFirst().isAfter(threshold)) {
                failures.pollFirst();
            }
            if (lockedUntil != null && !lockedUntil.isAfter(now)) {
                lockedUntil = null;
            }
        }

        boolean isEmpty(Instant now) {
            return inFlight == 0 && failures.isEmpty() && (lockedUntil == null || !lockedUntil.isAfter(now));
        }
    }
}
