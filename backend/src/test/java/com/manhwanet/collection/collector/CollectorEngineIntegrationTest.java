package com.manhwanet.collection.collector;

import static com.manhwanet.collection.collector.MockSite.redirect;
import static com.manhwanet.collection.collector.MockSite.send;
import static com.manhwanet.collection.collector.MockSite.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.manhwanet.collection.application.CrawlRunActiveException;
import com.manhwanet.collection.application.CrawlRunService;
import com.manhwanet.collection.application.CrawlTaskService;
import com.manhwanet.collection.application.StagingIngestService;
import com.manhwanet.collection.collector.adapter.PlatformAdapter;
import com.manhwanet.collection.collector.cli.CollectCommand;
import com.manhwanet.collection.collector.core.CollectorEngine;
import com.manhwanet.collection.collector.core.CollectorProperties;
import com.manhwanet.collection.collector.core.Halt;
import com.manhwanet.collection.collector.core.PlatformThrottle;
import com.manhwanet.collection.collector.core.RunReport;
import com.manhwanet.collection.collector.core.UserAgent;
import com.manhwanet.collection.collector.net.HttpTransport;
import com.manhwanet.collection.domain.CollectionPolicyRepository;
import com.manhwanet.collection.domain.CollectorRequestCounterRepository;
import com.manhwanet.collection.domain.RunKind;
import com.manhwanet.collection.domain.RunStatus;
import com.manhwanet.platform.domain.PlatformRepository;
import com.manhwanet.global.error.BusinessException;
import com.manhwanet.global.error.ErrorCode;
import com.manhwanet.support.TestDatabaseCleaner;
import com.zaxxer.hikari.HikariDataSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.random.RandomGenerator;
import java.util.zip.GZIPInputStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 수집기 공통 기반(#46): 로컬 모의 HTTP 서버(127.0.0.1) + 격리 테스트 DB. 실제 사이트·DNS 를 쓰지 않는다.
 * 요청 간격은 가상 시계로 재며, 가상 시계가 흐른 만큼 대기 중인 작업의 next_attempt_at 을 당겨 DB 시각과 맞춘다.
 */
@SpringBootTest(properties = {
        "manhwanet.collector.contact-url=https://manhwanet.test/collector",
        "manhwanet.collector.read-timeout=1s",
        "manhwanet.collector.total-timeout=2s",
        "manhwanet.collector.max-body-bytes=65536"})
@ActiveProfiles("test")
@Import(CollectorTestConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class CollectorEngineIntegrationTest {

    private static final String OWNER = "collector-a";
    private static final String OTHER = "collector-b";
    private static final String B = FakeAdapter.BASE;
    private static final String ROBOTS = "User-agent: *\nDisallow: /private/\n";

    @Autowired
    private CollectorEngine engine;
    @Autowired
    private CrawlRunService runs;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private Environment environment;
    @Autowired
    private LoopbackNetwork net;
    @Autowired
    private VirtualClock clock;
    @Autowired
    private FakeAdapter adapter;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private ApplicationContext context;
    @Autowired
    private CollectorProperties properties;
    @Autowired
    private PlatformRepository platformRepository;
    @Autowired
    private CollectionPolicyRepository policyRepository;
    @Autowired
    private CrawlTaskService taskService;
    @Autowired
    private StagingIngestService ingestService;
    @Autowired
    private CollectorRequestCounterRepository counterRepository;
    @Autowired
    private HttpTransport transport;
    @Autowired
    private RandomGenerator random;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private List<PlatformAdapter> adapters;

    private MockSite site;
    private long adminId;

    @BeforeEach
    void setUp() throws IOException {
        TestDatabaseCleaner.clean(jdbc, environment);
        adminId = jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, nickname, role) VALUES ('ops@manhwanet.test', '{noop}x', '운영자', 'ADMIN')
                RETURNING id""", Long.class);
        jdbc.update("INSERT INTO platform_hosts (platform_code, host) VALUES ('RIDI', 'ridi.mock.test'), ('RIDI', 'meta.mock.test')");
        policy("ENABLED");
        clock.reset();
        adapter.reset();
        site = new MockSite(clock::millis);
        net.reset(site.port());
        net.map("ridi.mock.test", "127.0.0.1");
        net.map("evil.mock.test", "127.0.0.1");
        net.map("meta.mock.test", "169.254.169.254");
        // 가상 시간이 흐른 만큼 재시도 대기 시각을 당긴다(DB now() 와 맞춤)
        clock.onSleep(ms -> {
            jdbc.update("UPDATE crawl_tasks SET next_attempt_at = next_attempt_at - make_interval(secs => ?) "
                    + "WHERE status = 'PENDING'", ms / 1000.0);
            // 가상 시계가 흐른 만큼 저장된 다음 요청 시각도 당긴다(DB now() 와 맞춤)
            jdbc.update("UPDATE crawl_runs SET next_request_at = next_request_at - make_interval(secs => ?) "
                    + "WHERE next_request_at IS NOT NULL", ms / 1000.0);
            jdbc.update("UPDATE platform_collection_policies SET next_request_at = next_request_at - make_interval(secs => ?) "
                    + "WHERE next_request_at IS NOT NULL", ms / 1000.0);
        });
        site.ok("/robots.txt", ROBOTS);
    }

    @AfterEach
    void tearDown() {
        site.close();
        clock.reset();
    }

    // ---------- 실행 게이트 ----------

    @Test
    void disabledPolicyCreatesNoRunAndSendsNothing() {
        policy("DISABLED");
        assertThatThrownBy(() -> engine.start("RIDI", RunKind.FULL, OWNER))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COLLECTION_NOT_ALLOWED));
        assertThatThrownBy(() -> engine.start("RIDI", RunKind.PROBE, OWNER)).isInstanceOf(BusinessException.class);
        policy("PROBE");
        assertThatThrownBy(() -> engine.start("RIDI", RunKind.FULL, OWNER))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COLLECTION_NOT_ALLOWED));
        // 어댑터가 없는 플랫폼은 실행을 만들지 않는다
        assertThatThrownBy(() -> engine.start("NAVER_WEBTOON", RunKind.PROBE, OWNER)).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("crawl_runs")).isZero();
        assertThat(site.requests()).isEmpty();
        assertThat(net.connects()).isZero();
    }

    @Test
    void webContextDoesNotRegisterCollectCommand() {
        assertThat(context.getBeansOfType(CollectCommand.class)).isEmpty();
        assertThat(site.requests()).isEmpty();
    }

    @Test
    void probeRequestsOnlyRobotsAndSitemaps() {
        policy("PROBE");
        site.ok("/sitemap.xml", """
                sitemap https://ridi.mock.test/sitemap-2.xml
                detail https://ridi.mock.test/books/1
                list https://ridi.mock.test/list?page=1
                adult 9
                """);
        site.ok("/sitemap-2.xml", "detail https://ridi.mock.test/books/2\n");

        RunReport report = engine.start("RIDI", RunKind.PROBE, OWNER);

        assertThat(report.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(site.targets()).containsExactly("/robots.txt", "/sitemap.xml", "/sitemap-2.xml");
        assertThat(count("staging_listings")).isZero();
        assertThat(jdbc.queryForList("SELECT DISTINCT task_kind FROM crawl_tasks", String.class)).containsExactly("SITEMAP");
        assertThat(report.stats()).containsKey("probe_refused");
    }

    // ---------- 정상 수집·간격·robots·리다이렉트 ----------

    @Test
    void fullRunAppliesIntervalRobotsAndNeverSendsCookies(CapturedOutput output) throws Exception {
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        AtomicInteger maxActive = new AtomicInteger();
        site.ok("/sitemap.xml", """
                detail https://ridi.mock.test/books/1
                detail https://ridi.mock.test/books/2
                adult 9
                detail https://ridi.mock.test/books/3
                detail https://ridi.mock.test/private/4
                """);
        site.on("/books/1", ex -> {
            ex.getResponseHeaders().add("Set-Cookie", "sid=COOKIEVALUE123; Path=/; HttpOnly");
            maxActive.accumulateAndGet(hikari.getHikariPoolMXBean().getActiveConnections(), Math::max);
            send(ex, 200, "title=하나");
        });
        site.on("/books/2", ex -> {
            maxActive.accumulateAndGet(hikari.getHikariPoolMXBean().getActiveConnections(), Math::max);
            send(ex, 200, "title=둘");
        });
        site.on("/books/3", redirect(301, "/books/3-new"));
        site.ok("/books/3-new", "title=셋");
        List<Boolean> txDuringWait = new CopyOnWriteArrayList<>();
        clock.onSleep(ms -> txDuringWait.add(TransactionSynchronizationManager.isActualTransactionActive()));

        RunReport report = engine.start("RIDI", RunKind.FULL, OWNER);

        assertThat(report.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(report.halt()).isNull();
        assertThat(site.targets()).containsExactly("/robots.txt", "/sitemap.xml", "/books/1", "/books/2", "/books/3",
                "/books/3-new");
        assertGapsAtLeast(5_000);
        String ua = UserAgent.of("0.1.0", "https://manhwanet.test/collector");
        for (MockSite.Request request : site.requests()) {
            assertThat(request.headers().get("User-agent")).containsExactly(ua);
            assertThat(request.headers()).doesNotContainKeys("Cookie", "Authorization");
        }
        assertThat(jdbc.queryForList("SELECT platform_work_id || ':' || detail_status FROM staging_listings ORDER BY platform_work_id",
                String.class)).containsExactly("1:OK", "2:OK", "3:OK", "9:SKIPPED_ADULT");
        assertThat(taskOf("/private/4")).isEqualTo("SKIPPED:SKIPPED_ROBOTS");
        assertThat(jdbc.queryForObject("SELECT robots_sha256 FROM crawl_runs", String.class)).isEqualTo(sha256(ROBOTS));
        assertThat(jdbc.queryForObject("SELECT request_count FROM collector_daily_requests WHERE platform_code = 'RIDI'",
                Integer.class)).isEqualTo(6);
        // 네트워크 대기 중 DB 연결·트랜잭션을 잡지 않는다
        assertThat(maxActive.get()).isZero();
        assertThat(txDuringWait).isNotEmpty().doesNotContain(true);
        assertThat(jdbc.queryForObject("SELECT stats::text FROM crawl_runs", String.class)).doesNotContain("COOKIEVALUE123");
        assertThat(output.getAll()).doesNotContain("COOKIEVALUE123");
    }

    @Test
    void crawlDelayLongerThanFiveSecondsIsHonoured() {
        site.ok("/robots.txt", "User-agent: *\nCrawl-delay: 12\nAllow: /\n");
        site.ok("/sitemap.xml", "detail https://ridi.mock.test/books/1\ndetail https://ridi.mock.test/books/2\n");
        site.ok("/books/1", "title=1");
        site.ok("/books/2", "title=2");

        assertThat(engine.start("RIDI", RunKind.FULL, OWNER).status()).isEqualTo(RunStatus.COMPLETED);
        assertGapsAtLeast(12_000);
    }

    @Test
    void robotsDisallowAfterRefreshSkipsTasks() {
        site.ok("/robots.txt", "User-agent: *\nAllow: /\n");
        site.ok("/sitemap.xml", "detail https://ridi.mock.test/books/1\ndetail https://ridi.mock.test/books/2\n");
        site.ok("/books/1", "title=1");
        site.ok("/books/2", "title=2");
        AtomicBoolean once = new AtomicBoolean();
        adapter.duringDetail = ctx -> {
            if (once.compareAndSet(false, true)) {
                clock.advance(6 * 3_600_000L + 1); // 6시간이 지나 robots.txt 를 다시 읽을 때
                site.ok("/robots.txt", "User-agent: ManhwanetCollector\nDisallow: /books/\n");
            }
        };

        RunReport report = engine.start("RIDI", RunKind.FULL, OWNER);

        assertThat(report.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(site.count("/robots.txt")).isEqualTo(2);
        assertThat(site.targets()).noneMatch(t -> t.startsWith("/books/"));
        assertThat(taskOf("/books/1")).isEqualTo("SKIPPED:SKIPPED_ROBOTS");
        assertThat(taskOf("/books/2")).isEqualTo("SKIPPED:SKIPPED_ROBOTS");
    }

    @Test
    void unreadableRobotsBlocksBeforeAnyOtherRequest() {
        site.on("/robots.txt", status(503, "busy"));
        RunReport report = engine.start("RIDI", RunKind.FULL, OWNER);
        assertThat(report.status()).isEqualTo(RunStatus.BLOCKED);
        assertThat(report.halt()).isEqualTo(Halt.ROBOTS_UNAVAILABLE);
        assertThat(site.targets()).containsExactly("/robots.txt", "/robots.txt", "/robots.txt");
        assertThat(jdbc.queryForObject("SELECT stop_reason FROM crawl_runs", String.class)).isEqualTo("ROBOTS_UNAVAILABLE");
    }

    @Test
    void missingRobotsIsTreatedAsUnavailable() {
        site.on("/robots.txt", status(404, "none"));
        RunReport report = engine.start("RIDI", RunKind.FULL, OWNER);
        assertThat(report.halt()).isEqualTo(Halt.ROBOTS_UNAVAILABLE);
        assertThat(site.targets()).containsExactly("/robots.txt");
    }

    // ---------- 429 ----------

    @Test
    void firstRateLimitHonoursRetryAfterAndDoublesInterval() {
        site.ok("/sitemap.xml", "detail https://ridi.mock.test/books/1\ndetail https://ridi.mock.test/books/2\n");
        AtomicInteger calls = new AtomicInteger();
        site.on("/books/1", ex -> {
            if (calls.incrementAndGet() == 1) {
                ex.getResponseHeaders().add("Retry-After", "120");
                send(ex, 429, "slow down");
            } else {
                send(ex, 200, "title=1");
            }
        });
        site.ok("/books/2", "title=2");

        RunReport report = engine.start("RIDI", RunKind.FULL, OWNER);

        assertThat(report.status()).isEqualTo(RunStatus.COMPLETED);
        List<MockSite.Request> requests = site.requests();
        assertThat(site.targets()).containsExactly("/robots.txt", "/sitemap.xml", "/books/1", "/books/2", "/books/1");
        assertThat(requests.get(3).atMillis() - requests.get(2).atMillis()).isGreaterThanOrEqualTo(120_000);
        assertThat(requests.get(4).atMillis() - requests.get(3).atMillis()).isGreaterThanOrEqualTo(10_000);
        assertThat(jdbc.queryForObject("SELECT rate_limit_hits FROM crawl_runs", Integer.class)).isEqualTo(1);
        assertThat(taskOf("/books/1")).isEqualTo("DONE:null");
    }

    @Test
    void secondRateLimitBlocksRunAndResumeProcessesOnlyRemainingTasks() {
        site.ok("/sitemap.xml", "detail https://ridi.mock.test/books/1\ndetail https://ridi.mock.test/books/2\n");
        site.on("/books/1", status(429, "slow", "Retry-After", "30"));
        site.on("/books/2", status(429, "slow", "Retry-After", "30"));

        RunReport blocked = engine.start("RIDI", RunKind.FULL, OWNER);

        assertThat(blocked.status()).isEqualTo(RunStatus.BLOCKED);
        assertThat(blocked.halt()).isEqualTo(Halt.BLOCKED_RATE);
        assertThat(site.targets()).containsExactly("/robots.txt", "/sitemap.xml", "/books/1", "/books/2");
        assertThat(jdbc.queryForObject("SELECT stop_reason FROM crawl_runs", String.class)).isEqualTo("BLOCKED_RATE");
        assertThat(jdbc.queryForObject("SELECT rate_limit_hits FROM crawl_runs", Integer.class)).isEqualTo(2);
        assertThat(taskOf("/books/2")).isEqualTo("PENDING:RATE_LIMITED");
        assertThat(jdbc.queryForObject("SELECT stats->>'haltReason' FROM crawl_runs", String.class)).isEqualTo("BLOCKED_RATE");

        // 원인을 확인한 뒤 같은 실행으로 재개: 끝난 사이트맵은 다시 요청하지 않는다
        site.ok("/books/1", "title=1");
        site.ok("/books/2", "title=2");
        long runId = jdbc.queryForObject("SELECT id FROM crawl_runs", Long.class);
        RunReport resumed = engine.resume(runId, OTHER);

        assertThat(resumed.halt()).as(resumed.stats().toString()).isNull();
        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(resumed.runId()).isEqualTo(runId);
        assertThat(site.count("/sitemap.xml")).isEqualTo(1);
        assertThat(site.count("/books/1")).isEqualTo(2);
        assertThat(site.count("/books/2")).isEqualTo(2);
        assertThat(count("crawl_runs")).isEqualTo(1);
        assertGapsAtLeast(5_000);
    }

    // ---------- 접근 차단 ----------

    @Test
    void forbiddenStopsPlatformImmediately(CapturedOutput output) {
        site.ok("/sitemap.xml", "detail https://ridi.mock.test/books/1\ndetail https://ridi.mock.test/books/2\n");
        site.on("/books/1", status(403, "forbidden"));
        site.ok("/books/2", "title=2");

        RunReport report = engine.start("RIDI", RunKind.FULL, OWNER);

        assertThat(report.status()).isEqualTo(RunStatus.BLOCKED);
        assertThat(report.halt()).isEqualTo(Halt.BLOCKED_ACCESS);
        assertThat(site.targets()).containsExactly("/robots.txt", "/sitemap.xml", "/books/1");
        assertThat(taskOf("/books/1")).isEqualTo("FAILED:BLOCKED_ACCESS");
        assertThat(jdbc.queryForObject("SELECT stop_reason FROM crawl_runs", String.class)).isEqualTo("BLOCKED_ACCESS");
        assertThat(output.getAll()).contains("[알림 필요]");
    }

    @Test
    void captchaPageStopsPlatformEvenWith200() {
        site.ok("/sitemap.xml", "detail https://ridi.mock.test/books/1\n");
        site.ok("/books/1", "<html><div class=\"g-recaptcha\" data-sitekey=\"x\"></div></html>");
        RunReport report = engine.start("RIDI", RunKind.FULL, OWNER);
        assertThat(report.halt()).isEqualTo(Halt.BLOCKED_ACCESS);
        assertThat(count("staging_listings")).isZero();
    }

    @Test
    void adapterCannotSwallowAHalt() {
        adapter.swallowHalts = true;
        site.ok("/sitemap.xml", "detail https://ridi.mock.test/books/1\n");
        site.on("/books/1", status(403, "forbidden"));
        RunReport report = engine.start("RIDI", RunKind.FULL, OWNER);
        assertThat(report.halt()).isEqualTo(Halt.BLOCKED_ACCESS);
        assertThat(count("staging_listings")).isZero();
    }

    @Test
    void loginAdultAndPaymentRedirectsAreNotFollowed(CapturedOutput output) {
        site.ok("/sitemap.xml", """
                detail https://ridi.mock.test/books/1
                detail https://ridi.mock.test/books/2
                detail https://ridi.mock.test/books/3
                detail https://ridi.mock.test/books/5
                """);
        site.on("/books/1", redirect(302, "https://ridi.mock.test/account/login?session=SECRETSESSION&return=/books/1"));
        site.on("/books/2", redirect(302, "/adult/verify?reason=adult"));
        site.on("/books/3", redirect(302, "https://ridi.mock.test/payment/charge"));
        site.on("/books/5", status(401, "login"));

        RunReport report = engine.start("RIDI", RunKind.FULL, OWNER);

        assertThat(report.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(site.targets()).containsExactly("/robots.txt", "/sitemap.xml", "/books/1", "/books/2", "/books/3", "/books/5");
        for (String path : List.of("/books/1", "/books/2", "/books/3", "/books/5")) {
            assertThat(taskOf(path)).isEqualTo("FAILED:GATED");
        }
        assertThat(jdbc.queryForList("SELECT detail_status FROM staging_listings", String.class)).containsOnly("GATED");
        assertThat(output.getAll()).doesNotContain("SECRETSESSION");
    }

    @Test
    void redirectsToForeignHostsPrivateAddressesOrPlainHttpAreRefused() {
        site.ok("/sitemap.xml", """
                detail https://ridi.mock.test/books/1
                detail https://ridi.mock.test/books/2
                detail https://ridi.mock.test/books/3
                detail https://ridi.mock.test/books/4
                """);
        site.on("/books/1", redirect(302, "https://evil.mock.test/steal"));
        site.on("/books/2", redirect(302, "https://meta.mock.test/latest/meta-data"));
        site.on("/books/3", redirect(302, "http://ridi.mock.test/books/3"));
        site.on("/books/4", redirect(302, "https://ridi.mock.test:8443/books/4"));

        RunReport report = engine.start("RIDI", RunKind.FULL, OWNER);

        assertThat(report.status()).isEqualTo(RunStatus.COMPLETED);
        for (String path : List.of("/books/1", "/books/2", "/books/3", "/books/4")) {
            assertThat(taskOf(path)).isEqualTo("FAILED:BLOCKED_DESTINATION");
        }
        assertThat(site.requests()).noneMatch(r -> r.host().startsWith("evil") || r.host().startsWith("meta"));
        assertThat(net.connectedAddresses()).containsOnly("127.0.0.1");
    }

    // ---------- 재시도·실패 ----------

    @Test
    void serverErrorsBackOffThirtySecondsTwoEightThirtyMinutesThenFail() {
        site.ok("/sitemap.xml", "detail https://ridi.mock.test/books/1\n");
        site.on("/books/1", status(503, "busy"));

        RunReport report = engine.start("RIDI", RunKind.FULL, OWNER);

        List<Long> times = site.requests().stream().filter(r -> r.target().equals("/books/1")).map(MockSite.Request::atMillis).toList();
        assertThat(times).hasSize(5);
        long[] base = {30_000, 120_000, 480_000, 1_800_000};
        for (int i = 0; i < 4; i++) {
            long gap = times.get(i + 1) - times.get(i);
            // 가상 시계는 DB now() 가 쿼리 사이에 흐른 몇 ms 를 포함하지 않는다
            assertThat(gap).isBetween(base[i] - 2_000, base[i] + base[i] / 10 + 7_000);
        }
        assertThat(jdbc.queryForObject("SELECT status || ':' || attempts || ':' || last_error FROM crawl_tasks WHERE url LIKE '%/books/1'",
                String.class)).isEqualTo("FAILED:5:HTTP_5XX");
        // 같은 호스트 연속 5회 실패라 회로 차단기도 열린다
        assertThat(report.halt()).isEqualTo(Halt.CIRCUIT_OPEN);
    }

    @Test
    void consecutiveFailuresOpenCircuitBreaker() {
        StringBuilder sitemap = new StringBuilder();
        for (int i = 1; i <= 6; i++) {
            sitemap.append("detail https://ridi.mock.test/books/").append(i).append('\n');
            site.on("/books/" + i, status(500, "error"));
        }
        site.ok("/sitemap.xml", sitemap.toString());

        RunReport report = engine.start("RIDI", RunKind.FULL, OWNER);

        assertThat(report.status()).isEqualTo(RunStatus.BLOCKED);
        assertThat(report.halt()).isEqualTo(Halt.CIRCUIT_OPEN);
        assertThat(site.targets()).filteredOn(t -> t.startsWith("/books/")).hasSize(5);
        assertThat(jdbc.queryForObject("SELECT stop_reason FROM crawl_runs", String.class)).isEqualTo("CIRCUIT_OPEN");
        assertThat(taskOf("/books/5")).isEqualTo("PENDING:HTTP_5XX");
    }

    @Test
    void timeoutIsRetried() {
        site.ok("/sitemap.xml", "detail https://ridi.mock.test/books/1\n");
        AtomicInteger calls = new AtomicInteger();
        site.on("/books/1", ex -> {
            if (calls.incrementAndGet() == 1) {
                sleep(2_500);
            }
            send(ex, 200, "title=1");
        });

        RunReport report = engine.start("RIDI", RunKind.FULL, OWNER);

        assertThat(report.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(report.stats()).containsEntry("transport_TIMEOUT", 1L);
        assertThat(jdbc.queryForObject("SELECT status || ':' || attempts FROM crawl_tasks WHERE url LIKE '%/books/1'", String.class))
                .isEqualTo("DONE:2");
    }

    @Test
    void oversizedResponseNotFoundAndParseFailureAreRecorded() throws Exception {
        site.ok("/sitemap.xml", """
                detail https://ridi.mock.test/books/1
                detail https://ridi.mock.test/books/2
                detail https://ridi.mock.test/books/3
                detail https://ridi.mock.test/books/4
                """);
        site.ok("/books/1", "x".repeat(70_000));
        site.on("/books/2", status(404, "gone"));
        site.ok("/books/3", """
                PARSE_ERROR csrf_token=ABCDEF123456 sessionid=QWERTY987654 Authorization: Bearer abc.def.ghi
                <input name="csrf_token" value="HTMLSECRET1">
                <meta name="csrf-token" content="HTMLSECRET2">
                """);
        // 값이 이름보다 앞에 있고 태그가 잘리면 비밀인지 확정할 수 없어 본문을 남기지 않는다
        site.ok("/books/4", "PARSE_ERROR <input value=\"TRUNCATEDSECRET");

        RunReport report = engine.start("RIDI", RunKind.FULL, OWNER);

        assertThat(report.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(taskOf("/books/1")).isEqualTo("FAILED:RESPONSE_TOO_LARGE");
        assertThat(taskOf("/books/2")).isEqualTo("FAILED:NOT_FOUND");
        assertThat(taskOf("/books/3")).isEqualTo("FAILED:PARSE_FAILED");
        assertThat(taskOf("/books/4")).isEqualTo("FAILED:PARSE_FAILED");
        byte[] gzip = jdbc.queryForObject("SELECT body_gzip FROM crawl_failure_samples", byte[].class);
        String sample = new String(new GZIPInputStream(new ByteArrayInputStream(gzip)).readAllBytes(), StandardCharsets.UTF_8);
        assertThat(sample).contains("PARSE_ERROR").contains("[REDACTED]")
                .doesNotContain("ABCDEF123456").doesNotContain("QWERTY987654").doesNotContain("abc.def.ghi")
                .doesNotContain("HTMLSECRET1").doesNotContain("HTMLSECRET2").doesNotContain("TRUNCATEDSECRET");
        assertThat(count("crawl_failure_samples")).isEqualTo(1);
    }

    // ---------- 하루 상한·설정 변경 ----------

    @Test
    void dailyCapPausesRun() {
        jdbc.update("UPDATE platform_collection_policies SET daily_request_cap = 3 WHERE platform_code = 'RIDI'");
        site.ok("/sitemap.xml", "detail https://ridi.mock.test/books/1\ndetail https://ridi.mock.test/books/2\n");
        site.ok("/books/1", "title=1");
        site.ok("/books/2", "title=2");

        RunReport report = engine.start("RIDI", RunKind.FULL, OWNER);

        assertThat(report.status()).isEqualTo(RunStatus.PAUSED);
        assertThat(report.halt()).isEqualTo(Halt.DAILY_CAP);
        assertThat(site.requests()).hasSize(3);
        assertThat(jdbc.queryForObject("SELECT request_count FROM collector_daily_requests", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT stats->>'haltReason' FROM crawl_runs", String.class)).isEqualTo("DAILY_CAP");
    }

    @Test
    void disablingPolicyMidRunPausesAndBlocksResume() {
        site.ok("/sitemap.xml", "detail https://ridi.mock.test/books/1\ndetail https://ridi.mock.test/books/2\n");
        site.on("/books/1", ex -> {
            policy("DISABLED");
            send(ex, 200, "title=1");
        });
        site.ok("/books/2", "title=2");

        RunReport report = engine.start("RIDI", RunKind.FULL, OWNER);

        assertThat(report.status()).isEqualTo(RunStatus.PAUSED);
        assertThat(report.halt()).isEqualTo(Halt.POLICY_CHANGED);
        assertThat(site.count("/books/2")).isZero();
        long runId = jdbc.queryForObject("SELECT id FROM crawl_runs", Long.class);
        assertThatThrownBy(() -> engine.resume(runId, OWNER)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COLLECTION_NOT_ALLOWED));
        assertThat(site.count("/books/2")).isZero();
    }

    // ---------- 중단·잠금 ----------

    @Test
    void stopRequestedWhileWaitingStopsWithoutFurtherRequests() {
        site.ok("/sitemap.xml", """
                detail https://ridi.mock.test/books/1
                detail https://ridi.mock.test/books/2
                detail https://ridi.mock.test/books/3
                """);
        site.ok("/books/1", "title=1");
        site.ok("/books/2", "title=2");
        site.ok("/books/3", "title=3");
        AtomicBoolean requested = new AtomicBoolean();
        clock.onSleep(ms -> {
            if (site.count("/books/1") == 1 && requested.compareAndSet(false, true)) {
                long runId = jdbc.queryForObject("SELECT id FROM crawl_runs", Long.class);
                runs.requestStop(runId, "점검", adminId);
            }
        });

        RunReport report = engine.start("RIDI", RunKind.FULL, OWNER);

        assertThat(report.status()).isEqualTo(RunStatus.STOPPED);
        assertThat(report.halt()).isEqualTo(Halt.STOP_REQUESTED);
        assertThat(site.targets()).containsExactly("/robots.txt", "/sitemap.xml", "/books/1");
        assertThat(taskOf("/books/2")).isEqualTo("SKIPPED:OPERATOR_STOP");
        assertThat(taskOf("/books/3")).isEqualTo("SKIPPED:OPERATOR_STOP");
        assertThat(jdbc.queryForObject("SELECT lease_owner FROM crawl_runs", String.class)).isNull();
    }

    @Test
    void leaseIsRenewedDuringLongWait() {
        site.ok("/sitemap.xml", "detail https://ridi.mock.test/books/1\n");
        AtomicInteger calls = new AtomicInteger();
        site.on("/books/1", ex -> {
            if (calls.incrementAndGet() == 1) {
                ex.getResponseHeaders().add("Retry-After", "900");
                send(ex, 429, "slow");
            } else {
                send(ex, 200, "title=1");
            }
        });
        List<java.sql.Timestamp> leases = new CopyOnWriteArrayList<>();
        clock.onSleep(ms -> leases.add(jdbc.queryForObject("SELECT lease_until FROM crawl_runs", java.sql.Timestamp.class)));

        RunReport report = engine.start("RIDI", RunKind.FULL, OWNER);

        assertThat(report.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(((Number) report.stats().get("lease_renewals")).longValue()).isGreaterThanOrEqualTo(8);
        assertThat(leases.stream().filter(java.util.Objects::nonNull).distinct().count()).isGreaterThanOrEqualTo(8);
    }

    @Test
    void lostLeaseWhileWaitingWritesNothingMore() {
        site.ok("/sitemap.xml", "detail https://ridi.mock.test/books/1\ndetail https://ridi.mock.test/books/2\n");
        site.ok("/books/1", "title=1");
        site.ok("/books/2", "title=2");
        AtomicBoolean taken = new AtomicBoolean();
        clock.onSleep(ms -> {
            if (site.count("/sitemap.xml") == 1 && taken.compareAndSet(false, true)) {
                takeOver();
            }
        });

        RunReport report = engine.start("RIDI", RunKind.FULL, OWNER);

        assertThat(report.halt()).isEqualTo(Halt.LEASE_LOST);
        assertThat(site.targets()).containsExactly("/robots.txt", "/sitemap.xml");
        assertThat(jdbc.queryForObject("SELECT status || ':' || lease_owner FROM crawl_runs", String.class))
                .isEqualTo("RUNNING:" + OTHER);
        assertThat(count("staging_listings")).isZero();
        assertThat(jdbc.queryForList("SELECT status FROM crawl_tasks WHERE task_kind = 'DETAIL'", String.class))
                .containsOnly("PENDING");
    }

    @Test
    void lostLeaseDuringRequestRejectsResultWrite() {
        site.ok("/sitemap.xml", "detail https://ridi.mock.test/books/1\ndetail https://ridi.mock.test/books/2\n");
        site.on("/books/1", ex -> {
            takeOver(); // 응답을 기다리는 사이 다른 프로세스가 잠금을 넘겨받음
            send(ex, 200, "title=1");
        });
        site.ok("/books/2", "title=2");

        RunReport report = engine.start("RIDI", RunKind.FULL, OWNER);

        assertThat(report.halt()).isEqualTo(Halt.LEASE_LOST);
        assertThat(count("staging_listings")).isZero();
        assertThat(jdbc.queryForObject("SELECT status || ':' || attempts FROM crawl_tasks WHERE url LIKE '%/books/1'", String.class))
                .isEqualTo("PENDING:1");
        assertThat(site.count("/books/2")).isZero();
        assertThat(jdbc.queryForObject("SELECT status || ':' || lease_owner FROM crawl_runs", String.class))
                .isEqualTo("RUNNING:" + OTHER);
    }

    @Test
    void restartedProcessKeepsTheMinimumGap() {
        AtomicBoolean disarm = new AtomicBoolean();
        site.on("/robots.txt", ex -> {
            send(ex, 200, ROBOTS);
            if (disarm.compareAndSet(false, true)) {
                policy("DISABLED");
            }
        });
        RunReport paused = engine.start("RIDI", RunKind.FULL, OWNER);
        assertThat(paused.halt()).isEqualTo(Halt.POLICY_CHANGED);
        assertThat(paused.status()).isEqualTo(RunStatus.PAUSED);
        assertThat(site.targets()).containsExactly("/robots.txt");
        long firstAt = site.requests().get(0).atMillis();

        policy("ENABLED");
        site.ok("/sitemap.xml", "detail https://ridi.mock.test/books/1\n");
        site.ok("/books/1", "title=1");
        RunReport resumed = freshEngine().resume(paused.runId(), OTHER);
        assertThat(resumed.halt()).as(String.valueOf(resumed.stats())).isNull();
        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(site.requests().get(1).atMillis() - firstAt).isGreaterThanOrEqualTo(5_000);
        assertGapsAtLeast(5_000);
    }

    @Test
    void restartedProcessKeepsRemainingRetryAfter() {
        site.ok("/sitemap.xml", "detail https://ridi.mock.test/books/1\n");
        site.on("/books/1", ex -> {
            ex.getResponseHeaders().add("Retry-After", "30");
            send(ex, 429, "slow");
            policy("DISABLED");
        });
        RunReport paused = engine.start("RIDI", RunKind.FULL, OWNER);
        assertThat(paused.halt()).isEqualTo(Halt.POLICY_CHANGED);
        long limitedAt = site.requests().stream().filter(r -> "/books/1".equals(r.target())).findFirst().orElseThrow().atMillis();
        // 작업 재시도 시각은 이미 지난 것으로 두고, 남은 대기는 요청 간격 저장값만 보게 한다
        jdbc.update("UPDATE crawl_tasks SET next_attempt_at = now() - interval '1 second' WHERE status = 'PENDING'");
        long stored = platformWaitMillis();
        assertThat(stored).isBetween(25_000L, 30_000L);
        policy("ENABLED");
        site.on("/books/1", ex -> send(ex, 200, "title=1"));

        RunReport resumed = freshEngine().resume(paused.runId(), OTHER);
        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        long nextAt = site.requests().stream().filter(r -> r.atMillis() > limitedAt).findFirst().orElseThrow().atMillis();
        assertThat(nextAt - limitedAt).isBetween(stored - 1_000, stored + 1_000);
    }

    @Test
    void newProcessRunKeepsTheMinimumGapAfterCompletion() {
        site.ok("/sitemap.xml", "detail https://ridi.mock.test/books/1\n");
        site.ok("/books/1", "title=1");

        RunReport first = engine.start("RIDI", RunKind.FULL, OWNER);

        assertThat(first.status()).isEqualTo(RunStatus.COMPLETED);
        long lastAt = site.requests().get(site.requests().size() - 1).atMillis();
        long stored = platformWaitMillis();
        assertThat(stored).isBetween(5_000L, 6_000L);
        int before = site.requests().size();

        RunReport second = freshEngine().start("RIDI", RunKind.FULL, OTHER);

        assertThat(second.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(second.runId()).isNotEqualTo(first.runId());
        assertThat(site.requests().get(before).atMillis() - lastAt).isGreaterThanOrEqualTo(5_000);
        assertGapsAtLeast(5_000);
    }

    @Test
    void newProcessRunKeepsRetryAfterAfterStop() {
        site.ok("/sitemap.xml", "detail https://ridi.mock.test/books/1\n");
        site.on("/books/1", ex -> {
            ex.getResponseHeaders().add("Retry-After", "30");
            send(ex, 429, "slow");
            policy("DISABLED");
        });
        RunReport paused = engine.start("RIDI", RunKind.FULL, OWNER);
        assertThat(paused.status()).isEqualTo(RunStatus.PAUSED);
        long limitedAt = site.requests().stream().filter(r -> "/books/1".equals(r.target())).findFirst().orElseThrow().atMillis();
        jdbc.update("UPDATE crawl_tasks SET next_attempt_at = now() - interval '1 second' WHERE status = 'PENDING'");
        long stored = platformWaitMillis();
        assertThat(stored).isBetween(25_000L, 30_000L);

        runs.requestStop(paused.runId(), "점검", adminId);
        assertThat(jdbc.queryForObject("SELECT status FROM crawl_runs WHERE id = ?", String.class, paused.runId())).isEqualTo("STOPPED");
        assertThat(platformWaitMillis()).isBetween(stored - 1_000, stored + 1_000);

        policy("ENABLED");
        site.on("/books/1", ex -> send(ex, 200, "title=1"));
        int before = site.requests().size();
        RunReport second = freshEngine().start("RIDI", RunKind.FULL, OTHER);

        assertThat(second.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(site.requests().get(before).atMillis() - limitedAt).isBetween(stored - 1_000, stored + 1_000);
    }

    @Test
    void blockedRunLeavesThePlatformWait() {
        site.ok("/sitemap.xml", "detail https://ridi.mock.test/books/1\ndetail https://ridi.mock.test/books/2\n");
        site.on("/books/1", status(429, "slow", "Retry-After", "30"));
        site.on("/books/2", status(429, "slow", "Retry-After", "30"));

        RunReport blocked = engine.start("RIDI", RunKind.FULL, OWNER);

        assertThat(blocked.status()).isEqualTo(RunStatus.BLOCKED);
        long stored = platformWaitMillis();
        assertThat(stored).isBetween(25_000L, 30_000L);
        runs.requestStop(blocked.runId(), "점검", adminId);
        assertThat(jdbc.queryForObject("SELECT status FROM crawl_runs WHERE id = ?", String.class, blocked.runId())).isEqualTo("STOPPED");
        assertThat(platformWaitMillis()).isBetween(stored - 1_000, stored + 1_000);

        site.ok("/books/1", "title=1");
        site.ok("/books/2", "title=2");
        long limitedAt = site.requests().get(site.requests().size() - 1).atMillis();
        int before = site.requests().size();
        RunReport second = freshEngine().start("RIDI", RunKind.FULL, OTHER);

        assertThat(second.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(site.requests().get(before).atMillis() - limitedAt).isGreaterThanOrEqualTo(stored - 1_000);
    }

    @Test
    void concurrentNewRunsKeepTheGapAndOnlyOneStarts() throws Exception {
        site.ok("/sitemap.xml", "detail https://ridi.mock.test/books/1\n");
        site.ok("/books/1", "title=1");
        RunReport first = engine.start("RIDI", RunKind.FULL, OWNER);
        assertThat(first.status()).isEqualTo(RunStatus.COMPLETED);
        long lastAt = site.requests().get(site.requests().size() - 1).atMillis();
        int before = site.requests().size();

        CountDownLatch winnerHolding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        site.on("/robots.txt", ex -> {
            send(ex, 200, ROBOTS);
            winnerHolding.countDown();
            try {
                if (!release.await(20, TimeUnit.SECONDS)) {
                    throw new IOException("동시 시작 대기가 끝나지 않았습니다.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("동시 시작 대기가 끊겼습니다.", e);
            }
        });

        CyclicBarrier startTogether = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> left = pool.submit(() -> startQuietly(startTogether, OTHER));
            Future<Object> right = pool.submit(() -> startQuietly(startTogether, "collector-c"));
            assertThat(winnerHolding.await(20, TimeUnit.SECONDS)).isTrue();
            assertThat(site.requests().get(before).atMillis() - lastAt).isGreaterThanOrEqualTo(5_000);
            Object early = left.isDone() ? left.get() : right.isDone() ? right.get() : null;
            assertThat(early).isInstanceOf(CrawlRunActiveException.class);
            release.countDown();
            Object leftResult = left.get(30, TimeUnit.SECONDS);
            Object rightResult = right.get(30, TimeUnit.SECONDS);
            List<Object> results = List.of(leftResult, rightResult);
            assertThat(results).anySatisfy(result -> assertThat(result).isInstanceOf(CrawlRunActiveException.class));
            assertThat(results).anySatisfy(result -> {
                assertThat(result).isInstanceOf(RunReport.class);
                assertThat(((RunReport) result).status()).isEqualTo(RunStatus.COMPLETED);
            });
            assertThat(count("crawl_runs")).isEqualTo(2);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    /** 메모리 간격을 공유하지 않는 새 프로세스 */
    private CollectorEngine freshEngine() {
        return new CollectorEngine(properties, adapters, platformRepository, policyRepository, runs, taskService,
                ingestService, counterRepository, transport, new PlatformThrottle(clock), clock, random,
                transactionManager, objectMapper);
    }

    private Object startQuietly(CyclicBarrier barrier, String owner) {
        try {
            barrier.await(20, TimeUnit.SECONDS);
            return freshEngine().start("RIDI", RunKind.FULL, owner);
        } catch (Exception e) {
            return e;
        }
    }

    private long platformWaitMillis() {
        Long value = jdbc.queryForObject("""
                SELECT CEIL(EXTRACT(EPOCH FROM (next_request_at - now())) * 1000)::bigint
                FROM platform_collection_policies WHERE platform_code = 'RIDI'""", Long.class);
        return value == null ? 0 : value;
    }

    // ---------- 도우미 ----------

    private void takeOver() {
        long runId = jdbc.queryForObject("SELECT id FROM crawl_runs", Long.class);
        jdbc.update("UPDATE crawl_runs SET lease_until = now() - interval '1 second' WHERE id = ?", runId);
        runs.acquire(runId, OTHER, 300);
    }

    private void policy(String value) {
        jdbc.update("UPDATE platform_collection_policies SET collection_policy = ?, decided_by = ?, decided_at = now() "
                + "WHERE platform_code = 'RIDI'", value, adminId);
    }

    private String taskOf(String path) {
        Map<String, Object> row = jdbc.queryForMap("SELECT status, last_error FROM crawl_tasks WHERE url = ?", B + path);
        return row.get("status") + ":" + row.get("last_error");
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private void assertGapsAtLeast(long millis) {
        List<MockSite.Request> requests = site.requests();
        for (int i = 1; i < requests.size(); i++) {
            assertThat(requests.get(i).atMillis() - requests.get(i - 1).atMillis())
                    .as("요청 %d→%d 간격", i - 1, i).isGreaterThanOrEqualTo(millis);
        }
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
