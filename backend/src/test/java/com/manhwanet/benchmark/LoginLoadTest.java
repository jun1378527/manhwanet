package com.manhwanet.benchmark;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 실제 로그인 API 부하 측정(명세 §10.1 "실제 API", M3). 실행 중인 서버에 요청한다. 운영 코드와 분리된 테스트 소스에 둔다.
 *
 * <p>각 동시 클라이언트는 CSRF 토큰을 받은 뒤 서로 다른 <b>없는 이메일</b>(무작위 example.test 주소)로 로그인을 보낸다.
 * DB 에 계정·데이터를 만들지 않는다(서버 메모리의 제한기 항목만 생긴다). 부하 중 /health 지연을 함께 잰다.
 *
 * <pre>
 * gradlew loginLoad -PbenchArgs="--base http://127.0.0.1:8080/api/v1 --concurrency 16,64,128 --requests 4"
 * # 프론트 프록시 경유: --base http://127.0.0.1:3000/api/v1
 * # 부하 후 정상 로그인 회복 확인(선택): --check-email you@example.test --check-password-env CHECK_PASSWORD
 * </pre>
 */
public final class LoginLoadTest {

    private static final Pattern XSRF = Pattern.compile("XSRF-TOKEN=([^;]+)");

    private LoginLoadTest() {
    }

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .executor(Executors.newFixedThreadPool(32))
                .build();
        String run = UUID.randomUUID().toString().substring(0, 8);
        System.out.printf("base=%s, jdk=%s, cpu=%d, run=%s%n", options.base, System.getProperty("java.version"),
                Runtime.getRuntime().availableProcessors(), run);
        System.out.println("concurrency,requests,throughputPerSec,p50Ms,p95Ms,maxMs,status(count),503ratio,"
                + "retryAfter503,healthCount,healthP95Ms,healthMaxMs");
        for (int concurrency : options.concurrency) {
            runLevel(http, options, run, concurrency);
            Thread.sleep(options.pauseMillis);
        }
        // 회복: 새 없는 이메일 1건은 401 이어야 하고(503 아님), 계정이 주어지면 로그인 200
        String token = csrf(http, options.base);
        long t = System.nanoTime();
        HttpResponse<String> probe = login(http, options.base, token, "recover-" + run + "@example.test",
                "wrong-password-for-load");
        System.out.printf("recovery.unknownEmail status=%d, ms=%.1f%n", probe.statusCode(), (System.nanoTime() - t) / 1e6);
        if (options.checkEmail != null) {
            String password = System.getenv(options.checkPasswordEnv);
            if (password == null) {
                throw new IllegalArgumentException("password env not set: " + options.checkPasswordEnv);
            }
            t = System.nanoTime();
            HttpResponse<String> ok = login(http, options.base, csrf(http, options.base), options.checkEmail, password);
            System.out.printf("recovery.validLogin status=%d, ms=%.1f%n", ok.statusCode(), (System.nanoTime() - t) / 1e6);
        }
        System.exit(0);
    }

    private static void runLevel(HttpClient http, Options options, String run, int concurrency) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            tokens.add(csrf(http, options.base));
        }
        Map<Integer, AtomicInteger> statuses = new ConcurrentHashMap<>();
        Map<String, AtomicInteger> retryAfter = new ConcurrentHashMap<>();
        AtomicBoolean running = new AtomicBoolean(true);
        List<Long> health = java.util.Collections.synchronizedList(new ArrayList<>());
        Thread prober = new Thread(() -> {
            while (running.get()) {
                long t = System.nanoTime();
                try {
                    http.send(HttpRequest.newBuilder(URI.create(options.base + "/health")).timeout(Duration.ofSeconds(30))
                            .GET().build(), HttpResponse.BodyHandlers.discarding());
                    health.add(System.nanoTime() - t);
                } catch (Exception e) {
                    health.add(Long.MAX_VALUE);
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        CountDownLatch start = new CountDownLatch(1);
        List<Future<long[]>> futures = new ArrayList<>();
        for (int c = 0; c < concurrency; c++) {
            int client = c;
            futures.add(pool.submit(() -> {
                start.await();
                long[] latencies = new long[options.requests];
                for (int r = 0; r < options.requests; r++) {
                    String email = "load-" + run + "-n" + concurrency + "-c" + client + "-r" + r + "@example.test";
                    long t = System.nanoTime();
                    HttpResponse<String> response;
                    try {
                        response = login(http, options.base, tokens.get(client), email, "wrong-password-for-load");
                    } catch (Exception e) {
                        latencies[r] = System.nanoTime() - t;
                        statuses.computeIfAbsent(-1, k -> new AtomicInteger()).incrementAndGet();
                        continue;
                    }
                    latencies[r] = System.nanoTime() - t;
                    statuses.computeIfAbsent(response.statusCode(), k -> new AtomicInteger()).incrementAndGet();
                    if (response.statusCode() == 503) {
                        String value = response.headers().firstValue("Retry-After").orElse("none");
                        retryAfter.computeIfAbsent(value, k -> new AtomicInteger()).incrementAndGet();
                    }
                }
                return latencies;
            }));
        }
        prober.start();
        long begin = System.nanoTime();
        start.countDown();
        long[] all = new long[0];
        for (Future<long[]> future : futures) {
            long[] part = future.get();
            long[] merged = Arrays.copyOf(all, all.length + part.length);
            System.arraycopy(part, 0, merged, all.length, part.length);
            all = merged;
        }
        double elapsed = (System.nanoTime() - begin) / 1e9;
        running.set(false);
        prober.join();
        pool.shutdown();
        long[] healthArray = health.stream().mapToLong(Long::longValue).toArray();
        Map<Integer, Integer> sorted = new TreeMap<>();
        statuses.forEach((k, v) -> sorted.put(k, v.get()));
        int busy = sorted.getOrDefault(503, 0);
        System.out.printf("%d,%d,%.1f,%.0f,%.0f,%.0f,%s,%.2f,%s,%d,%.0f,%.0f%n", concurrency, all.length,
                all.length / elapsed, HashBenchmark.percentile(all, 50) / 1e6, HashBenchmark.percentile(all, 95) / 1e6,
                HashBenchmark.percentile(all, 100) / 1e6, sorted.toString().replace(", ", " "),
                all.length == 0 ? 0 : (double) busy / all.length, new TreeMap<>(retryAfter).toString().replace(", ", " "),
                healthArray.length, HashBenchmark.percentile(healthArray, 95) / 1e6,
                HashBenchmark.percentile(healthArray, 100) / 1e6);
    }

    private static String csrf(HttpClient http, String base) throws Exception {
        HttpResponse<Void> response = http.send(HttpRequest.newBuilder(URI.create(base + "/auth/csrf"))
                .timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.discarding());
        for (String cookie : response.headers().allValues("set-cookie")) {
            Matcher matcher = XSRF.matcher(cookie);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        throw new IllegalStateException("no XSRF-TOKEN cookie (status " + response.statusCode() + ")");
    }

    private static HttpResponse<String> login(HttpClient http, String base, String token, String email,
            String password) throws Exception {
        String body = "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
        return http.send(HttpRequest.newBuilder(URI.create(base + "/auth/login"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Cookie", "XSRF-TOKEN=" + token)
                .header("X-XSRF-TOKEN", token)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static final class Options {
        String base = "http://127.0.0.1:8080/api/v1";
        int[] concurrency = {16, 64, 128};
        int requests = 4;
        long pauseMillis = 3000;
        String checkEmail;
        String checkPasswordEnv = "CHECK_PASSWORD";

        static Options parse(String[] args) {
            Options o = new Options();
            for (int i = 0; i < args.length; i += 2) {
                String value = i + 1 < args.length ? args[i + 1] : "";
                switch (args[i]) {
                    case "--base" -> o.base = value.replaceAll("/$", "");
                    case "--concurrency" -> o.concurrency = Arrays.stream(value.split(",")).mapToInt(Integer::parseInt)
                            .toArray();
                    case "--requests" -> o.requests = Integer.parseInt(value);
                    case "--pause-ms" -> o.pauseMillis = Long.parseLong(value);
                    case "--check-email" -> o.checkEmail = value;
                    case "--check-password-env" -> o.checkPasswordEnv = value;
                    default -> throw new IllegalArgumentException("unknown option: " + args[i]);
                }
            }
            return o;
        }
    }
}
