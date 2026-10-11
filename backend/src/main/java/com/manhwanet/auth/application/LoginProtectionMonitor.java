package com.manhwanet.auth.application;

import com.manhwanet.global.security.HashLimitProperties;
import com.manhwanet.global.security.PasswordHashGate;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * 로그인 보호의 주기 작업(docs/login-limit-design-draft.md §8.1·§9). 웹 서버에서만 동작한다(admin-cli 에는 없음).
 *
 * <ul>
 *   <li>시작 시 설정값 1줄(INFO): P, Q, W, max-entries, 최대 힙</li>
 *   <li>1분마다 제한기의 만료 항목 정리</li>
 *   <li>거절이 있었던 1분 구간마다 사유별 개수 1줄(WARN). 요청마다 남기지 않는다</li>
 * </ul>
 *
 * <p>이메일(원문·일부·해시)·비밀번호·IP 는 남기지 않는다. 다루는 값은 개수뿐이다.
 */
@Component
@ConditionalOnWebApplication
public class LoginProtectionMonitor implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(LoginProtectionMonitor.class);

    /** 만료 정리·지표 로그 주기(T_sweep) */
    static final Duration INTERVAL = Duration.ofMinutes(1);

    private final LoginAttemptLimiter limiter;
    private final PasswordHashGate gate;
    private final LoginLimitProperties limitProperties;
    private final HashLimitProperties hashProperties;
    private ScheduledExecutorService scheduler;

    public LoginProtectionMonitor(LoginAttemptLimiter limiter, PasswordHashGate gate,
            LoginLimitProperties limitProperties, HashLimitProperties hashProperties) {
        this.limiter = limiter;
        this.gate = gate;
        this.limitProperties = limitProperties;
        this.hashProperties = hashProperties;
    }

    @Override
    public void afterPropertiesSet() {
        log.info("login-protection config: hashMaxConcurrent={}, hashMaxWaiting={}, hashMaxWaitMs={}, "
                        + "loginMaxFailures={}, loginWindow={}, loginLockDuration={}, loginMaxEntries={}, maxHeapMiB={}",
                hashProperties.maxConcurrent(), hashProperties.maxWaiting(), hashProperties.maxWait().toMillis(),
                limitProperties.maxFailures(), limitProperties.window(), limitProperties.lockDuration(),
                limitProperties.maxEntries(), Runtime.getRuntime().maxMemory() / (1024 * 1024));
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "login-protection-monitor");
            thread.setDaemon(true);
            return thread;
        });
        long millis = INTERVAL.toMillis();
        scheduler.scheduleWithFixedDelay(this::runSafely, millis, millis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void destroy() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private void runSafely() {
        try {
            runOnce();
        } catch (RuntimeException e) {
            // 주기 작업이 멈추지 않게 한다. 예외 메시지에 개인 정보가 없도록 종류만 남긴다.
            log.warn("login-protection maintenance failed: {}", e.getClass().getSimpleName());
        }
    }

    /** 만료 항목을 정리하고, 거절이 있었으면 사유별 개수를 1줄 남긴다. (주기 작업·테스트용) */
    void runOnce() {
        int swept = limiter.sweepExpired();
        LoginAttemptLimiter.Snapshot limits = limiter.drainStats();
        PasswordHashGate.Snapshot hashes = gate.drainStats();
        if (limits.hasRejections() || hashes.rejected() > 0) {
            log.warn("login-protection rejections in last {}s: accountLocked429={}, accountInFlight429={}, "
                            + "hashBusy503={}, storeFull503={}, peakHashWaiting={}, peakHashInUse={}, "
                            + "limiterEntries={}/{}, sweptExpired={}",
                    INTERVAL.toSeconds(), limits.lockedRejections(), limits.inFlightRejections(), hashes.rejected(),
                    limits.saturatedRejections(), hashes.peakWaiting(), hashes.peakInUse(), limits.entries(),
                    limits.maxEntries(), swept);
        }
    }
}
