package com.manhwanet.collection.collector.core;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 수집기 설정(manhwanet.collector.*). 아래 값은 코드에서 바꿀 수 없는 하한·상한을 다시 적용한다.
 * <ul>
 *   <li>요청 간격 하한 5초(+0~1초 무작위)는 코드 상수다({@link CollectorEngine#MIN_INTERVAL_MILLIS}). 설정으로 줄일 수 없다.</li>
 *   <li>contact-url 기본값은 없다. 지정하지 않으면 수집을 시작하지 않는다(D-16 미결정).</li>
 *   <li>로컬 주소 허용·평문 연결 설정은 없다(테스트 소스에서만 네트워크 구성을 바꿈).</li>
 * </ul>
 */
@ConfigurationProperties("manhwanet.collector")
public record CollectorProperties(
        String contactUrl,
        String version,
        Duration connectTimeout,
        Duration readTimeout,
        Duration totalTimeout,
        Long maxBodyBytes,
        Integer maxRedirects,
        Integer leaseSeconds) {

    public CollectorProperties {
        version = version == null || version.isBlank() ? "0.1.0" : version.strip();
        connectTimeout = clamp(connectTimeout, Duration.ofSeconds(10), Duration.ofSeconds(1), Duration.ofSeconds(30));
        readTimeout = clamp(readTimeout, Duration.ofSeconds(20), Duration.ofSeconds(1), Duration.ofSeconds(60));
        totalTimeout = clamp(totalTimeout, Duration.ofSeconds(60), Duration.ofSeconds(1), Duration.ofSeconds(120));
        maxBodyBytes = maxBodyBytes == null ? 10L * 1024 * 1024 : Math.max(1024, Math.min(maxBodyBytes, 50L * 1024 * 1024));
        maxRedirects = maxRedirects == null ? 5 : Math.max(0, Math.min(maxRedirects, 5));
        leaseSeconds = leaseSeconds == null ? 300 : Math.max(180, Math.min(leaseSeconds, 3600));
    }

    private static Duration clamp(Duration value, Duration fallback, Duration min, Duration max) {
        Duration v = value == null ? fallback : value;
        return v.compareTo(min) < 0 ? min : v.compareTo(max) > 0 ? max : v;
    }
}
