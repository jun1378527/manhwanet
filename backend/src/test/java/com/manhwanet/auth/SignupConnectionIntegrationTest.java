package com.manhwanet.auth;

import static com.manhwanet.support.AuthTestClient.STRONG_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.manhwanet.global.error.ServiceBusyException;
import com.manhwanet.global.security.PasswordHashGate;
import com.manhwanet.support.AuthTestClient;
import com.manhwanet.support.TestDatabaseCleaner;
import com.manhwanet.user.application.UserService;
import com.manhwanet.user.dto.SignupRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 가입이 게이트 대기·해시 중에 DB 연결을 붙잡지 않는지, 동시 가입의 유니크 제약과 자동 로그인을 확인한다.
 */
@SpringBootTest(properties = {
        "manhwanet.auth.hash-limit.max-concurrent=2",
        "manhwanet.auth.hash-limit.max-waiting=4",
        "manhwanet.auth.hash-limit.max-wait=1s"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SignupConnectionIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Environment environment;

    @Autowired
    private PasswordHashGate gate;

    @Autowired
    private UserService userService;

    @MockitoSpyBean
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        TestDatabaseCleaner.clean(jdbcTemplate, environment);
        gate.drainStats();
    }

    @Test
    void signupDoesNotHoldAConnectionWhileWaitingForTheGate() throws Exception {
        String email = uniqueEmail();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                userService.signup(new SignupRequest(email, STRONG_PASSWORD, "독자"));
            } catch (Throwable thrown) {
                error.set(thrown);
            }
        });
        try (PasswordHashGate.Permit first = gate.acquire(); PasswordHashGate.Permit second = gate.acquire()) {
            assertThat(gate.availablePermits()).isZero();
            worker.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (gate.waitingCount() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(gate.waitingCount()).isEqualTo(1);
            assertThat(otherBackendsBusy()).isZero();
        }
        worker.join(10_000);
        assertThat(worker.isAlive()).isFalse();
        assertThat(error.get()).isNull();
        assertThat(userCount()).isEqualTo(1);
        assertThat(gate.availablePermits()).isEqualTo(2);
        assertThat(gate.waitingCount()).isZero();
    }

    @Test
    void signupDoesNotHoldAConnectionWhileHashing() throws Exception {
        CountDownLatch hashing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            hashing.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(passwordEncoder).encode(any());

        String email = uniqueEmail();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                userService.signup(new SignupRequest(email, STRONG_PASSWORD, "독자"));
            } catch (Throwable thrown) {
                error.set(thrown);
            }
        });
        worker.start();
        assertThat(hashing.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(otherBackendsBusy()).isZero();
        release.countDown();
        worker.join(10_000);
        assertThat(worker.isAlive()).isFalse();
        assertThat(error.get()).isNull();
        assertThat(userCount()).isEqualTo(1);
        assertThat(gate.availablePermits()).isEqualTo(2);
    }

    @Test
    void hashFailureReleasesTheGateAndSavesNothing() {
        doThrow(new IllegalStateException("hash failed")).when(passwordEncoder).encode(any());
        String email = uniqueEmail();

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> userService.signup(new SignupRequest(email, STRONG_PASSWORD, "독자")))
                .isInstanceOf(IllegalStateException.class);

        assertThat(userCount()).isZero();
        assertThat(gate.availablePermits()).isEqualTo(2);
        assertThat(gate.waitingCount()).isZero();
    }

    @Test
    void interruptWhileWaitingReleasesTheGateAndSavesNothing() throws Exception {
        String email = uniqueEmail();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                userService.signup(new SignupRequest(email, STRONG_PASSWORD, "독자"));
            } catch (Throwable thrown) {
                error.set(thrown);
            }
        });
        try (PasswordHashGate.Permit first = gate.acquire(); PasswordHashGate.Permit second = gate.acquire()) {
            worker.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (gate.waitingCount() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(gate.waitingCount()).isEqualTo(1);
            worker.interrupt();
            worker.join(10_000);
        }
        assertThat(worker.isAlive()).isFalse();
        assertThat(error.get()).isInstanceOf(ServiceBusyException.class);
        assertThat(userCount()).isZero();
        assertThat(gate.availablePermits()).isEqualTo(2);
        assertThat(gate.waitingCount()).isZero();
    }

    @Test
    void concurrentSameEmailSavesOneRollsBackTheOtherAndLogsInOnlyTheWinner() throws Exception {
        CountDownLatch bothHashing = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            bothHashing.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(passwordEncoder).encode(any());

        String email = uniqueEmail();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<MvcResult>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> new AuthTestClient(mockMvc, objectMapper)
                        .signup(email, STRONG_PASSWORD, "동시")));
            }
            assertThat(bothHashing.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(otherBackendsBusy()).isZero();
            release.countDown();
            MvcResult first = results.get(0).get(30, TimeUnit.SECONDS);
            MvcResult second = results.get(1).get(30, TimeUnit.SECONDS);
            MvcResult winner = first.getResponse().getStatus() == 201 ? first : second;
            MvcResult loser = winner == first ? second : first;
            assertThat(winner.getResponse().getStatus()).isEqualTo(201);
            assertThat(loser.getResponse().getStatus()).isEqualTo(409);
            assertThat((String) JsonPath.read(loser.getResponse().getContentAsString(), "$.error.code"))
                    .isEqualTo("DUPLICATE_RESOURCE");
            assertThat(userCount()).isEqualTo(1);
            assertThat(winner.getRequest().getSession(false)).isNotNull();
            MvcResult me = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .get("/api/v1/auth/me")
                    .session((org.springframework.mock.web.MockHttpSession) winner.getRequest().getSession(false)))
                    .andReturn();
            assertThat(me.getResponse().getStatus()).isEqualTo(200);
            assertThat((String) JsonPath.read(me.getResponse().getContentAsString(), "$.data.email")).isEqualTo(email);
            if (loser.getRequest().getSession(false) != null) {
                MvcResult loserMe = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/auth/me")
                        .session((org.springframework.mock.web.MockHttpSession) loser.getRequest().getSession(false)))
                        .andReturn();
                assertThat(loserMe.getResponse().getStatus()).isEqualTo(401);
            }
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(gate.availablePermits()).isEqualTo(2);
    }

    private int otherBackendsBusy() {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)::int from pg_stat_activity
                where datname = current_database()
                  and pid <> pg_backend_pid()
                  and state in ('active', 'idle in transaction', 'idle in transaction (aborted)')
                """, Integer.class);
        return count == null ? 0 : count;
    }

    private long userCount() {
        Long count = jdbcTemplate.queryForObject("select count(*) from users", Long.class);
        return count == null ? 0 : count;
    }

    private static String uniqueEmail() {
        return "conn-" + UUID.randomUUID() + "@example.com";
    }
}
