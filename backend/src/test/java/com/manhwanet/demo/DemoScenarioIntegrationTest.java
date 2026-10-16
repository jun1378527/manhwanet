package com.manhwanet.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.manhwanet.collection.collector.VirtualClock;
import com.manhwanet.collection.collector.core.CollectorClock;
import com.manhwanet.collection.collector.net.CollectorNetwork;
import com.manhwanet.collection.collector.core.RunReport;
import com.manhwanet.collection.domain.RunStatus;
import com.manhwanet.support.AuthTestClient;
import com.manhwanet.support.TestDatabaseCleaner;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * #67 포트폴리오 데모 단계({@link DemoScenario})를 격리 테스트 DB 에서 검증한다.
 * 모의 수집 → 검수 → HIDDEN 등록 → 명시적 공개 → 공개 목록·상세 → 독자 로그인 → 별점·리뷰, 그리고 재실행이 데이터를 지우거나
 * 중복으로 만들지 않는지 확인한다. 실제 플랫폼 요청 없음(127.0.0.1 모의 서버), 비밀번호는 실행마다 만든 값.
 * 데모 명령({@link DemoSeedCommand})은 데모 DB(_demo)가 아니면 멈추므로, 여기서는 단계를 직접 부른다.
 */
@SpringBootTest(properties = {
        "spring.main.web-application-type=servlet",
        "manhwanet.collector.read-timeout=1s",
        "manhwanet.collector.total-timeout=2s"})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "demo-seed"})
@Import(DemoScenarioIntegrationTest.ClockConfig.class)
class DemoScenarioIntegrationTest {

    private static final String ADMIN_PASSWORD = randomPassword();
    private static final String READER_PASSWORD = randomPassword();

    @DynamicPropertySource
    static void passwords(DynamicPropertyRegistry registry) {
        registry.add("MANHWANET_DEMO_ADMIN_PASSWORD", () -> ADMIN_PASSWORD);
        registry.add("MANHWANET_DEMO_READER_PASSWORD", () -> READER_PASSWORD);
    }

    /** 요청 간격(5초)을 실제로 기다리지 않도록 가상 시계를 쓴다. 데모 명령은 실제 시계를 쓴다 */
    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfig {
        @Bean
        VirtualClock virtualClock() {
            return new VirtualClock();
        }

        @Bean
        @Primary
        CollectorClock testCollectorClock(VirtualClock clock) {
            return clock;
        }
    }

    @Autowired
    private DemoScenario scenario;
    @Autowired
    private CollectorNetwork network;
    @Autowired
    private VirtualClock clock;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private Environment environment;

    private final List<String> out = new ArrayList<>();

    @BeforeEach
    void setUp() {
        TestDatabaseCleaner.clean(jdbc, environment);
        clock.reset();
        clock.onSleep(ms -> {
            jdbc.update("UPDATE crawl_tasks SET next_attempt_at = next_attempt_at - make_interval(secs => ?) WHERE status = 'PENDING'",
                    ms / 1000.0);
            jdbc.update("UPDATE crawl_runs SET next_request_at = next_request_at - make_interval(secs => ?) "
                    + "WHERE next_request_at IS NOT NULL", ms / 1000.0);
        });
    }

    @Test
    void demoStepsGoThroughServicesAndEndInPublicGeneralWorks() throws Exception {
        scenario.accounts(out::add);
        assertThat(out).noneMatch(line -> line.contains(ADMIN_PASSWORD) || line.contains(READER_PASSWORD));
        assertThat(jdbc.queryForObject("SELECT role FROM users WHERE email = ?", String.class, DemoScenario.ADMIN_EMAIL)).isEqualTo("ADMIN");
        assertThat(jdbc.queryForObject("SELECT role FROM users WHERE email = ?", String.class, DemoScenario.READER_EMAIL)).isEqualTo("USER");

        // 1) 모의 수집: 설정 변경은 관리 서비스를 거쳐 변경 기록이 남는다
        RunReport report = scenario.collect(out::add);
        assertThat(report.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(jdbc.queryForObject("SELECT collection_policy FROM platform_collection_policies WHERE platform_code = 'TOOMICS'",
                String.class)).isEqualTo("ENABLED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_change_log WHERE reason LIKE '[DEMO]%'", Integer.class)).isGreaterThanOrEqualTo(2);
        List<String> titles = jdbc.queryForList("SELECT title_clean FROM staging_listings WHERE platform_code = 'TOOMICS' ORDER BY id", String.class);
        assertThat(titles).hasSize(DemoCatalog.ITEMS.size()).allMatch(t -> t.startsWith("[DEMO] "));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM works", Integer.class)).isZero();

        // 2) 검수 → 처음부터 HIDDEN 등록, 공개 경로는 없는 작품과 같다
        AuthTestClient visitor = new AuthTestClient(mockMvc, objectMapper);
        List<Long> created = scenario.review(out::add);
        assertThat(created).hasSize(DemoCatalog.ITEMS.size()).doesNotContainNull();
        for (long workId : created) {
            assertThat(jdbc.queryForObject("SELECT visibility FROM works WHERE id = ?", String.class, workId)).isEqualTo("HIDDEN");
            assertThat(visitor.get("/api/v1/works/" + workId).getResponse().getStatus()).isEqualTo(404);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_change_log WHERE action = 'STATUS_CHANGE' AND entity_type = 'WORK'",
                Integer.class)).isZero();
        assertThat(JsonPath.<Integer>read(body(visitor.get("/api/v1/works")), "$.data.totalElements")).isZero();
        assertThat(jdbc.queryForList("SELECT DISTINCT age_class FROM work_platform_listings", String.class)).containsExactly("GENERAL");

        // 3) 명시적 공개(version·사유·변경 기록)
        assertThat(scenario.publish(out::add)).containsExactlyElementsOf(created);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_change_log WHERE action = 'STATUS_CHANGE' AND entity_type = 'WORK'",
                Integer.class)).isEqualTo(created.size());
        assertThat(JsonPath.<Integer>read(body(visitor.get("/api/v1/works")), "$.data.totalElements")).isEqualTo(created.size());
        long first = created.get(0);
        assertThat(JsonPath.<String>read(body(visitor.get("/api/v1/works/" + first)), "$.data.primaryTitle.title")).startsWith("[DEMO] ");

        // 4) 독자 로그인 → 별점·리뷰 (프론트가 쓰는 API 그대로)
        AuthTestClient reader = new AuthTestClient(mockMvc, objectMapper);
        assertThat(reader.login(DemoScenario.READER_EMAIL, READER_PASSWORD).getResponse().getStatus()).isEqualTo(200);
        reader.withHeader("X-Expected-User-Id", String.valueOf(
                jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, DemoScenario.READER_EMAIL)));
        assertThat(reader.putRaw("/api/v1/me/ratings/" + first, "{\"score\": 4.5}").getResponse().getStatus()).isEqualTo(200);
        MvcResult review = reader.postJson("/api/v1/me/reviews/" + first, Map.of("body", "[DEMO] 가상 작품에 남긴 시연용 리뷰입니다.", "containsSpoiler", false));
        assertThat(review.getResponse().getStatus()).isIn(200, 201);
        String detail = body(visitor.get("/api/v1/works/" + first));
        assertThat(JsonPath.<Double>read(detail, "$.data.rating.average")).isEqualTo(4.5);
        assertThat(JsonPath.<Integer>read(body(visitor.get("/api/v1/works/" + first + "/reviews")), "$.data.totalElements")).isEqualTo(1);

        // 5) 다시 실행해도 지우거나 중복으로 만들지 않는다
        out.clear();
        scenario.accounts(out::add);
        assertThat(scenario.collect(out::add).status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(scenario.review(out::add)).isEmpty();
        assertThat(scenario.publish(out::add)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM works", Integer.class)).isEqualTo(created.size());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM work_reviews", Integer.class)).isEqualTo(1);
        assertThat(reader.login(DemoScenario.READER_EMAIL, READER_PASSWORD).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void demoNetworkResolvesOnlyTheDemoHost() {
        assertThatThrownBy(() -> network.resolver().resolve("example.com")).isInstanceOf(UnknownHostException.class);
        assertThatThrownBy(() -> network.resolver().resolve("toomics.com")).isInstanceOf(UnknownHostException.class);
    }

    @Test
    void demoCommandRefusesNonDemoDatabase() {
        DemoSeedCommand command = new DemoSeedCommand(scenario, jdbc, environment, "all");
        assertThatThrownBy(() -> command.run(null)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isZero();
    }

    private static String body(MvcResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private static String randomPassword() {
        byte[] bytes = new byte[12];
        new SecureRandom().nextBytes(bytes);
        return "demo-" + HexFormat.of().formatHex(bytes);
    }
}
