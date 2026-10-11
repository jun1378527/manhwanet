package com.manhwanet.benchmark;

import com.manhwanet.auth.application.LoginAttemptLimiter;
import com.manhwanet.auth.application.LoginLimitProperties;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.time.Duration;

/**
 * 로그인 제한기 항목당 메모리 측정(명세 §10.2, M7 확장). 운영 코드와 분리된 테스트 소스에 둔다.
 * 운영과 같은 LoginAttemptLimiter 에 가짜 이메일 키를 넣고 GC 뒤 힙 증가량을 잰다.
 *
 * <pre>
 * gradlew limiterMemory                                    # 기본: 키 100,000개
 * gradlew limiterMemory -PbenchArgs="--keys 100000"
 * </pre>
 */
public final class LimiterMemoryBenchmark {

    private LimiterMemoryBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        int keys = 100_000;
        for (int i = 0; i + 1 < args.length; i += 2) {
            if (args[i].equals("--keys")) {
                keys = Integer.parseInt(args[i + 1]);
            } else {
                throw new IllegalArgumentException("unknown option: " + args[i]);
            }
        }
        System.out.printf("jdk=%s, maxHeapMiB=%d, keys=%d%n", System.getProperty("java.version"),
                Runtime.getRuntime().maxMemory() / (1024 * 1024), keys);
        System.out.println("emailLength,state,heapDeltaMiB,bytesPerEntry");
        for (int length : new int[] {45, 254}) {
            for (String state : new String[] {"failure1", "failure4", "locked"}) {
                measure(length, state, keys, false);
            }
        }
        // 최악: 로그인 요청 이메일 상한 320자(LoginRequest)를 모두 비 Latin-1 문자로 채운 키(UTF-16, 문자당 2바이트)
        measure(320, "failure4", keys, true);
    }

    private static void measure(int emailLength, String state, int keys, boolean wide) throws Exception {
        LoginAttemptLimiter limiter = new LoginAttemptLimiter(
                new LoginLimitProperties(5, Duration.ofMinutes(15), Duration.ofMinutes(15), keys + 10));
        // 키 문자열은 미리 만들지 않는다: 항목과 함께 키 문자열 메모리까지 센다.
        long before = usedHeapAfterGc();
        int failures = switch (state) {
            case "failure1" -> 1;
            case "failure4" -> 4;
            default -> 5;
        };
        for (int i = 0; i < keys; i++) {
            String key = wide ? wideKey(i, emailLength) : key(i, emailLength);
            for (int f = 0; f < failures; f++) {
                limiter.reserve(key).failure();
            }
        }
        long after = usedHeapAfterGc();
        if (limiter.size() != keys) {
            throw new IllegalStateException("unexpected size " + limiter.size());
        }
        double delta = after - before;
        System.out.printf("%d%s,%s,%.1f,%.0f%n", emailLength, wide ? "(utf16)" : "", state, delta / 1048576.0, delta / keys);
    }

    /** 길이가 정확히 length 인 가짜 이메일(정규화된 키 형태). */
    static String key(int index, int length) {
        String suffix = "@example.test";
        String number = Integer.toString(index);
        StringBuilder builder = new StringBuilder(length);
        builder.append('u').append(number);
        while (builder.length() < length - suffix.length()) {
            builder.append('x');
        }
        builder.append(suffix);
        return builder.toString();
    }

    /** 길이가 정확히 length 인, Latin-1 밖 문자를 포함한 키(정규화 전 로그인 입력 상한을 흉내). */
    static String wideKey(int index, int length) {
        StringBuilder builder = new StringBuilder(length);
        builder.append(Integer.toString(index)).append('@');
        while (builder.length() < length) {
            builder.append('\uAC00');
        }
        return builder.toString();
    }

    private static long usedHeapAfterGc() throws InterruptedException {
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        long used = Long.MAX_VALUE;
        for (int i = 0; i < 5; i++) {
            System.gc();
            Thread.sleep(100);
            used = Math.min(used, memory.getHeapMemoryUsage().getUsed());
        }
        return used;
    }
}
