package com.manhwanet.benchmark;

import com.manhwanet.global.error.ServiceBusyException;
import com.manhwanet.global.security.AuthenticationConfig;
import com.manhwanet.global.security.HashLimitProperties;
import com.manhwanet.global.security.PasswordHashGate;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 비밀번호 해시(Argon2id m=19MiB, t=2, p=1) 측정 도구(명세 §10.1 M1·M2·M8). 운영 코드와 분리된 테스트 소스에 둔다.
 * HTTP·DB 없이 운영과 같은 PasswordEncoder·PasswordHashGate 를 쓴다.
 *
 * <pre>
 * gradlew hashBenchmark                                   # 기본값
 * gradlew hashBenchmark -PbenchArgs="--concurrency 1,2,4,8,16 --seconds 5 --gate-clients 200"
 * </pre>
 *
 * 출력: 환경(CPU·JDK·최대 힙), 1회 encode·matches 시간과 할당량, 동시성별 matches 처리량·p50·p95·최대,
 * 게이트(P·Q·W) 경유 시 처리량·검증 최대·거절 결정 최대. 개인 정보는 쓰지 않는다(고정 테스트 문자열).
 */
public final class HashBenchmark {

    private HashBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        PasswordEncoder encoder = new AuthenticationConfig().passwordEncoder();
        String password = "benchmark-password-not-a-secret";
        String hash = encoder.encode(password);

        System.out.println("== environment ==");
        System.out.printf("cpu.availableProcessors=%d, os=%s %s, jdk=%s (%s), maxHeapMiB=%d%n",
                Runtime.getRuntime().availableProcessors(), System.getProperty("os.name"),
                System.getProperty("os.arch"), System.getProperty("java.version"), System.getProperty("java.vendor"),
                Runtime.getRuntime().maxMemory() / (1024 * 1024));

        // 예열
        for (int i = 0; i < options.warmup; i++) {
            encoder.matches(password, hash);
        }

        System.out.println("== M2 single-thread cost ==");
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        long[] encodeNanos = new long[10];
        long[] matchNanos = new long[10];
        long allocBefore = threads.getThreadAllocatedBytes(tid);
        for (int i = 0; i < 10; i++) {
            long start = System.nanoTime();
            encoder.matches(password, hash);
            matchNanos[i] = System.nanoTime() - start;
        }
        long allocPerMatch = (threads.getThreadAllocatedBytes(tid) - allocBefore) / 10;
        for (int i = 0; i < 10; i++) {
            long start = System.nanoTime();
            encoder.encode(password);
            encodeNanos[i] = System.nanoTime() - start;
        }
        System.out.printf("matches.p50Ms=%.1f, encode.p50Ms=%.1f, matches.allocMiB=%.1f%n",
                percentile(matchNanos, 50) / 1e6, percentile(encodeNanos, 50) / 1e6, allocPerMatch / 1048576.0);

        System.out.println("== M1 matches by concurrency (no gate) ==");
        System.out.println("concurrency,throughputPerSec,p50Ms,p95Ms,maxMs,count");
        double best = 0;
        List<double[]> rows = new ArrayList<>();
        for (int concurrency : options.concurrency) {
            double[] row = runFixed(encoder, password, hash, concurrency, options.seconds);
            rows.add(row);
            best = Math.max(best, row[1]);
            System.out.printf("%d,%.1f,%.1f,%.1f,%.1f,%d%n", concurrency, row[1], row[2], row[3], row[4], (long) row[5]);
        }
        for (double[] row : rows) {
            if (row[1] >= best * 0.9) {
                System.out.printf("P candidate (smallest concurrency with >= 90%% of max %.1f/s)=%d, p95Ms=%.1f%n", best,
                        (int) row[0], row[3]);
                break;
            }
        }

        System.out.println("== M8 through gate (P, Q, W; concurrent clients) ==");
        HashLimitProperties gateProperties = new HashLimitProperties(options.gateP, options.gateQ,
                Duration.ofMillis(options.gateWaitMillis));
        runGate(encoder, password, hash, gateProperties, options.gateClients, options.seconds, options.backoffMillis);
    }

    /** concurrency 개 스레드가 seconds 동안 matches 를 반복한다. [동시성, 처리량, p50, p95, max, 개수] */
    private static double[] runFixed(PasswordEncoder encoder, String password, String hash, int concurrency,
            int seconds) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        List<Future<long[]>> futures = new ArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        long begin = System.nanoTime();
        for (int i = 0; i < concurrency; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                long[] samples = new long[4096];
                int n = 0;
                while (System.nanoTime() < deadline) {
                    long t = System.nanoTime();
                    encoder.matches(password, hash);
                    if (n == samples.length) {
                        samples = Arrays.copyOf(samples, n * 2);
                    }
                    samples[n++] = System.nanoTime() - t;
                }
                return Arrays.copyOf(samples, n);
            }));
        }
        start.countDown();
        long[] all = new long[0];
        for (Future<long[]> future : futures) {
            long[] part = future.get();
            long[] merged = Arrays.copyOf(all, all.length + part.length);
            System.arraycopy(part, 0, merged, all.length, part.length);
            all = merged;
        }
        double elapsed = (System.nanoTime() - begin) / 1e9;
        pool.shutdown();
        return new double[] {concurrency, all.length / elapsed, percentile(all, 50) / 1e6, percentile(all, 95) / 1e6,
                percentile(all, 100) / 1e6, all.length};
    }

    /** clients 개 스레드가 쉬지 않고 게이트 + matches 를 요청한다. 거절 결정 시간과 검증 시간을 따로 잰다. */
    private static void runGate(PasswordEncoder encoder, String password, String hash, HashLimitProperties properties,
            int clients, int seconds, long backoffMillis) throws Exception {
        PasswordHashGate gate = new PasswordHashGate(properties);
        ExecutorService pool = Executors.newFixedThreadPool(clients);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        AtomicInteger maxConcurrent = new AtomicInteger();
        AtomicInteger concurrent = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<long[][]>> futures = new ArrayList<>();
        long begin = System.nanoTime();
        for (int i = 0; i < clients; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                long[] ok = new long[1024];
                long[] rejected = new long[1024];
                int okN = 0;
                int rejN = 0;
                while (System.nanoTime() < deadline) {
                    long t = System.nanoTime();
                    try (PasswordHashGate.Permit ignored = gate.acquire()) {
                        maxConcurrent.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
                        encoder.matches(password, hash);
                        concurrent.decrementAndGet();
                        if (okN == ok.length) {
                            ok = Arrays.copyOf(ok, okN * 2);
                        }
                        ok[okN++] = System.nanoTime() - t;
                    } catch (ServiceBusyException e) {
                        if (rejN == rejected.length) {
                            rejected = Arrays.copyOf(rejected, rejN * 2);
                        }
                        rejected[rejN++] = System.nanoTime() - t;
                        Thread.sleep(backoffMillis); // 거절 뒤 클라이언트가 다시 보내기까지의 간격(흉내)
                    }
                }
                return new long[][] {Arrays.copyOf(ok, okN), Arrays.copyOf(rejected, rejN)};
            }));
        }
        start.countDown();
        long[] ok = new long[0];
        long[] rejected = new long[0];
        for (Future<long[][]> future : futures) {
            long[][] part = future.get();
            ok = concat(ok, part[0]);
            rejected = concat(rejected, part[1]);
        }
        double elapsed = (System.nanoTime() - begin) / 1e9;
        pool.shutdown();
        System.out.printf("P=%d, Q=%d, W=%dms, clients=%d, clientBackoffAfterRejectMs=%d%n", properties.maxConcurrent(),
                properties.maxWaiting(), properties.maxWait().toMillis(), clients, backoffMillis);
        System.out.printf("verified=%d (%.1f/s), waitPlusHashP50Ms=%.1f, waitPlusHashP95Ms=%.1f, waitPlusHashMaxMs=%.1f, "
                        + "rejected=%d, rejectDecisionP95Ms=%.1f, rejectDecisionMaxMs=%.1f, maxConcurrentHash=%d%n",
                ok.length, ok.length / elapsed, percentile(ok, 50) / 1e6, percentile(ok, 95) / 1e6,
                percentile(ok, 100) / 1e6, rejected.length, percentile(rejected, 95) / 1e6,
                percentile(rejected, 100) / 1e6, maxConcurrent.get());
    }

    private static long[] concat(long[] a, long[] b) {
        long[] merged = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, merged, a.length, b.length);
        return merged;
    }

    static double percentile(long[] values, int p) {
        if (values.length == 0) {
            return 0;
        }
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        int index = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    private static final class Options {
        int[] concurrency = {1, 2, 4, 8, 16};
        int seconds = 5;
        int warmup = 20;
        int gateP = 2;
        int gateQ = 4;
        int gateWaitMillis = 250;
        int gateClients = 200;
        long backoffMillis = 20;

        static Options parse(String[] args) {
            Options o = new Options();
            for (int i = 0; i < args.length; i += 2) {
                String value = i + 1 < args.length ? args[i + 1] : "";
                switch (args[i]) {
                    case "--concurrency" -> o.concurrency = Arrays.stream(value.split(",")).mapToInt(Integer::parseInt)
                            .toArray();
                    case "--seconds" -> o.seconds = Integer.parseInt(value);
                    case "--warmup" -> o.warmup = Integer.parseInt(value);
                    case "--gate-p" -> o.gateP = Integer.parseInt(value);
                    case "--gate-q" -> o.gateQ = Integer.parseInt(value);
                    case "--gate-wait-ms" -> o.gateWaitMillis = Integer.parseInt(value);
                    case "--gate-clients" -> o.gateClients = Integer.parseInt(value);
                    case "--backoff-ms" -> o.backoffMillis = Long.parseLong(value);
                    default -> throw new IllegalArgumentException("unknown option: " + args[i]);
                }
            }
            return o;
        }
    }
}
