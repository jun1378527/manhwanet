package com.manhwanet.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.manhwanet.global.error.ErrorCode;
import com.manhwanet.global.error.InvalidInputException;
import com.manhwanet.global.error.ListingAlreadyLinkedException;
import com.manhwanet.global.error.VersionConflictException;
import com.manhwanet.platform.application.PlatformAdminService;
import com.manhwanet.platform.application.WorkListingService;
import com.manhwanet.platform.dto.ChangePlatformStatusRequest;
import com.manhwanet.platform.dto.PlatformStatusChangeResponse;
import com.manhwanet.platform.dto.CreateListingRequest;
import com.manhwanet.platform.dto.ListingMutationResponse;
import com.manhwanet.review.dto.JsonInteger;
import com.manhwanet.support.TestDatabaseCleaner;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 연결 등록의 동시성·롤백 (#39, 실제 PostgreSQL).
 */
@SpringBootTest
@ActiveProfiles("test")
class WorkListingConcurrencyTest {

    private static final int ROUNDS = 15;

    @Autowired
    private WorkListingService service;

    @Autowired
    private PlatformAdminService platformAdminService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Environment environment;

    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private long adminId;

    @BeforeEach
    void setUp() {
        dropTrigger();
        TestDatabaseCleaner.clean(jdbc, environment);
        adminId = jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, nickname, role) VALUES ('a@manhwanet.test', '{noop}x', '관리자', 'ADMIN')
                RETURNING id""", Long.class);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        dropTrigger();
    }

    private void dropTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS test_fail_work_bump ON works");
        jdbc.execute("DROP FUNCTION IF EXISTS test_fail_work_bump()");
    }

    @Test
    void samePlatformWorkOnTwoWorksAtOnceLinksOnlyOne() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            long workA = PlatformTestSupport.insertWork(jdbc, "A" + round);
            long workB = PlatformTestSupport.insertWork(jdbc, "B" + round);
            String id = String.valueOf(5000 + round);
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<Object> a = executor.submit(() -> attempt(barrier, workA, request(0, id)));
            Future<Object> b = executor.submit(() -> attempt(barrier, workB, request(0, id)));
            List<Object> results = List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));

            assertThat(results).filteredOn(r -> r instanceof ListingMutationResponse).hasSize(1);
            assertThat(results).filteredOn(r -> r instanceof ListingAlreadyLinkedException).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM work_platform_listings WHERE platform_work_id = ?",
                    Integer.class, id)).isEqualTo(1);
            // 진 쪽 작품의 version 은 그대로
            long winner = jdbc.queryForObject("SELECT work_id FROM work_platform_listings WHERE platform_work_id = ?",
                    Long.class, id);
            long loser = winner == workA ? workB : workA;
            assertThat(PlatformTestSupport.workVersion(jdbc, loser)).isZero();
            assertThat(PlatformTestSupport.workVersion(jdbc, winner)).isEqualTo(1);
        }
    }

    @Test
    void twoListingsOnTheSameWorkWithTheSameVersionLetOneWin() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            long work = PlatformTestSupport.insertWork(jdbc, "W" + round);
            CyclicBarrier barrier = new CyclicBarrier(2);
            String idA = "7" + round;
            String idB = "8" + round;
            Future<Object> a = executor.submit(() -> attempt(barrier, work, request(0, idA)));
            Future<Object> b = executor.submit(() -> attempt(barrier, work, request(0, idB)));
            List<Object> results = List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            assertThat(results).filteredOn(r -> r instanceof ListingMutationResponse).hasSize(1);
            assertThat(results).filteredOn(r -> r instanceof VersionConflictException).hasSize(1)
                    .allSatisfy(r -> assertThat(((VersionConflictException) r).getErrorCode())
                            .isEqualTo(ErrorCode.WORK_VERSION_CONFLICT));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM work_platform_listings WHERE work_id = ?",
                    Integer.class, work)).isEqualTo(1);
        }
    }

    @Test
    void failureWhileBumpingWorkVersionRollsBackTheListing() {
        long work = PlatformTestSupport.insertWork(jdbc, "롤백");
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION test_fail_work_bump() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.id = %d AND NEW.version > OLD.version THEN
                        RAISE EXCEPTION 'test failure while bumping work version';
                    END IF;
                    RETURN NEW;
                END $$""".formatted(work));
        jdbc.execute("CREATE TRIGGER test_fail_work_bump BEFORE UPDATE ON works FOR EACH ROW EXECUTE FUNCTION test_fail_work_bump()");

        assertThatThrownBy(() -> service.create(work, request(0, "9999"), adminId))
                .isInstanceOf(RuntimeException.class).isNotInstanceOf(VersionConflictException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM work_platform_listings", Integer.class)).isZero();
        assertThat(PlatformTestSupport.workVersion(jdbc, work)).isZero();
    }

    @Test
    void closingAPlatformWhileCreatingAListingNeverHidesTheNewListing() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            TestDatabaseCleaner.clean(jdbc, environment);
            adminId = jdbc.queryForObject("""
                    INSERT INTO users (email, password_hash, nickname, role) VALUES ('a@manhwanet.test', '{noop}x', '관리자', 'ADMIN')
                    RETURNING id""", Long.class);
            long work = PlatformTestSupport.insertWork(jdbc, "C" + round);
            String id = String.valueOf(6000 + round);
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<Object> create = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                try {
                    return service.create(work, request(0, id), adminId);
                } catch (InvalidInputException e) {
                    return e;
                }
            });
            Future<PlatformStatusChangeResponse> close = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return platformAdminService.changeStatus("KAKAO_PAGE", new ChangePlatformStatusRequest(new JsonInteger(0L),
                        "CLOSED", "OFFICIAL", null, null, "https://page.kakao.com/notice", null), adminId);
            });
            Object created = create.get(10, TimeUnit.SECONDS);
            PlatformStatusChangeResponse closed = close.get(10, TimeUnit.SECONDS);

            if (created instanceof ListingMutationResponse response) {
                // 연결이 먼저 커밋됐다면 종료 응답의 처리 대상에 반드시 들어 있다
                assertThat(closed.activeListingIds()).containsExactly(response.listing().id());
                assertThat(closed.activeListingCount()).isEqualTo(1);
            } else {
                // 종료가 먼저면 새 연결은 만들어지지 않는다
                assertThat(created).isInstanceOf(InvalidInputException.class);
                assertThat(closed.activeListingCount()).isZero();
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM work_platform_listings", Integer.class)).isZero();
            }
        }
    }

    private Object attempt(CyclicBarrier barrier, long work, CreateListingRequest request) throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        try {
            return service.create(work, request, adminId);
        } catch (ListingAlreadyLinkedException | VersionConflictException e) {
            return e;
        }
    }

    private static CreateListingRequest request(long version, String id) {
        return new CreateListingRequest(new JsonInteger(version), "KAKAO_PAGE", id,
                "https://page.kakao.com/content/" + id, "SERIES", "MAIN", "ko", null, null,
                Instant.parse("2026-10-12T00:00:00Z"), null);
    }
}
