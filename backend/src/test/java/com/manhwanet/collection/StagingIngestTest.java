package com.manhwanet.collection;

import static com.manhwanet.collection.CollectionTestSupport.OTHER;
import static com.manhwanet.collection.CollectionTestSupport.OWNER;
import static com.manhwanet.collection.CollectionTestSupport.count;
import static com.manhwanet.collection.CollectionTestSupport.listing;
import static com.manhwanet.collection.CollectionTestSupport.ridi;
import static com.manhwanet.collection.CollectionTestSupport.withStatus;
import static com.manhwanet.collection.CollectionTestSupport.withTitle;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.manhwanet.collection.application.CollectedListing.AgeSignal;
import com.manhwanet.collection.application.CollectedListing;
import com.manhwanet.collection.application.CrawlRunService;
import com.manhwanet.collection.application.IngestResult;
import com.manhwanet.collection.application.StagingIngestService;
import com.manhwanet.collection.domain.RunKind;
import com.manhwanet.global.error.BusinessException;
import com.manhwanet.global.error.ErrorCode;
import com.manhwanet.support.TestDatabaseCleaner;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

/** staging 반영: 식별 키 중복 방지, 재수집 변경 이력, 실패 보존, 등급, 별칭, 동시 요청, 롤백 (#45, 실제 PostgreSQL) */
@SpringBootTest
@ActiveProfiles("test")
class StagingIngestTest {

    private static final int LEASE = 60;

    @Autowired
    private StagingIngestService ingest;

    @Autowired
    private CrawlRunService runs;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Environment environment;

    @Autowired
    private ObjectMapper objectMapper;

    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private long adminId;
    private long run;

    @BeforeEach
    void setUp() {
        dropTrigger();
        TestDatabaseCleaner.clean(jdbc, environment);
        adminId = CollectionTestSupport.insertAdmin(jdbc);
        CollectionTestSupport.setPolicy(jdbc, "RIDI", "ENABLED", adminId);
        CollectionTestSupport.setPolicy(jdbc, "MRBLUE", "ENABLED", adminId);
        run = runs.start("RIDI", RunKind.FULL, "ridi-0.1", OWNER, LEASE, null).id();
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        dropTrigger();
    }

    private void dropTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS test_fail_staging_change ON staging_listing_changes");
        jdbc.execute("DROP FUNCTION IF EXISTS test_fail_staging_change()");
    }

    @Test
    void recollectingTheSameWorkKeepsOneRowAndRecordsChangesOncePerRun() throws Exception {
        IngestResult first = ingest.ingest(run, OWNER, ridi("100", "유미의 세포들", "12세 이용가"));
        assertThat(first.created()).isTrue();
        assertThat(first.contentChanged()).isTrue();
        Map<String, Object> row = row(first.stagingId());
        assertThat(row.get("age_class")).isEqualTo("GENERAL");
        assertThat(row.get("age_rating_raw")).isEqualTo("12세 이용가");
        assertThat(row.get("detail_status")).isEqualTo("OK");
        assertThat(row.get("review_status")).isEqualTo("NEW");
        assertThat(row.get("synopsis_length")).isEqualTo("소개문 유미의 세포들".length());
        assertThat(count(jdbc, "staging_credits")).isEqualTo(2);

        // 같은 실행에서 다시 반영(재개)해도 행·이력이 늘지 않는다
        IngestResult same = ingest.ingest(run, OWNER, ridi("100", "유미의 세포들", "12세 이용가"));
        assertThat(same.created()).isFalse();
        assertThat(same.contentChanged()).isFalse();
        assertThat(same.changedFields()).isEmpty();
        assertThat(count(jdbc, "staging_listings")).isEqualTo(1);
        assertThat(count(jdbc, "staging_listing_changes")).isEqualTo(1);

        // 다음 실행에서 제목이 바뀌면 그 실행의 이력 한 줄에 이전·이후 값을 남긴다
        long second = nextRun();
        IngestResult changed = ingest.ingest(second, OWNER, withTitle(ridi("100", "유미의 세포들", "12세 이용가"), "유미의 세포들 1부"));
        assertThat(changed.contentChanged()).isTrue();
        assertThat(changed.changedFields()).containsExactly("titleClean", "titleRaw");
        // 같은 실행에서 또 바뀌어도 줄은 하나, 처음 이전 값은 그대로
        ingest.ingest(second, OWNER, withTitle(ridi("100", "유미의 세포들", "12세 이용가"), "유미의 세포들 완결"));
        JsonNode fields = changes(first.stagingId(), second);
        assertThat(fields.get("titleRaw").get("before").asText()).isEqualTo("유미의 세포들");
        assertThat(fields.get("titleRaw").get("after").asText()).isEqualTo("유미의 세포들 완결");
        assertThat(count(jdbc, "staging_listing_changes")).isEqualTo(2);
        assertThat(row(first.stagingId()).get("last_run_id")).isEqualTo(second);
        assertThat(row(first.stagingId()).get("first_run_id")).isEqualTo(run);

        // 같은 번호라도 플랫폼이 다르면 다른 행
        long mrblue = runs.start("MRBLUE", RunKind.FULL, "mrblue-0.1", OTHER, LEASE, null).id();
        ingest.ingest(mrblue, OTHER, listing("MRBLUE", "100", "https://www.mrblue.com/webtoon/detail/100", "OK", "다른 작품",
                null, List.of(), List.of(), null, null, List.of()));
        assertThat(count(jdbc, "staging_listings")).isEqualTo(2);
        // 공개 작품·연결로 등록하지 않는다
        assertThat(count(jdbc, "works") + count(jdbc, "work_platform_listings")).isZero();
    }

    @Test
    void failuresKeepPreviousContentAndAreCountedOncePerRun() throws Exception {
        long id = ingest.ingest(run, OWNER, ridi("200", "제목", "15세 이용가")).stagingId();
        String hash = (String) row(id).get("content_hash");

        long second = nextRun();
        CollectedListing hostile = failureListing("200", "NOT_FOUND");
        IngestResult notFound = ingest.ingest(second, OWNER, hostile);
        assertThat(notFound.contentChanged()).isFalse();
        assertThat(notFound.changedFields()).containsExactly("detailStatus");
        Map<String, Object> failed = row(id);
        assertThat(failed.get("title_raw")).isEqualTo("제목");
        assertThat(failed.get("content_hash")).isEqualTo(hash);
        assertThat(failed.get("age_class")).isEqualTo("GENERAL");
        assertThat(failed.get("age_rating_raw")).isEqualTo("15세 이용가");
        assertThat(failed.get("age_signals").toString()).doesNotContain("19세");
        assertThat(failed.get("field_sources").toString()).contains("h1").doesNotContain("h2");
        assertThat(failed.get("synopsis_text").toString()).contains("제목");
        assertThat(failed.get("url")).isEqualTo("https://ridibooks.com/books/200");
        assertThat(failed.get("detail_status")).isEqualTo("NOT_FOUND");
        assertThat(failed.get("last_failure_reason")).isEqualTo("NOT_FOUND");
        assertThat(failed.get("last_failure_run_id")).isEqualTo(second);
        assertThat(failed.get("consecutive_failures")).isEqualTo(1);
        Object failureAt = failed.get("last_failure_at");
        // 같은 실패를 다시 반영해도 횟수와 내용·등급·출처가 그대로다
        IngestResult repeated = ingest.ingest(second, OWNER, hostile);
        assertThat(repeated.changedFields()).isEmpty();
        assertThat(repeated.contentChanged()).isFalse();
        Map<String, Object> again = row(id);
        assertThat(again.get("consecutive_failures")).isEqualTo(1);
        assertThat(again.get("last_failure_at")).isEqualTo(failureAt);
        assertThat(again.get("title_raw")).isEqualTo("제목");
        assertThat(again.get("age_class")).isEqualTo("GENERAL");
        assertThat(again.get("age_signals").toString()).doesNotContain("19세");
        assertThat(again.get("field_sources").toString()).contains("h1");
        assertThat(again.get("content_hash")).isEqualTo(hash);

        long third = nextRun();
        ingest.ingest(third, OWNER, failureListing("200", "NOT_FOUND"));
        assertThat(row(id).get("consecutive_failures")).isEqualTo(2);
        assertThat(row(id).get("age_class")).isEqualTo("GENERAL");
        assertThat(row(id).get("content_hash")).isEqualTo(hash);

        // 성공으로 회복하면 새 내용이 반영되고, 연속 실패만 0 이 된다
        long fourth = nextRun();
        ingest.ingest(fourth, OWNER, ridi("200", "회복", "19세 이용가"));
        Map<String, Object> recovered = row(id);
        assertThat(recovered.get("consecutive_failures")).isEqualTo(0);
        assertThat(recovered.get("detail_status")).isEqualTo("OK");
        assertThat(recovered.get("title_raw")).isEqualTo("회복");
        assertThat(recovered.get("age_class")).isEqualTo("ADULT");
        assertThat(recovered.get("age_signals").toString()).doesNotContain("detail:label");
        assertThat(recovered.get("last_failure_reason")).isEqualTo("NOT_FOUND");
        assertThat(recovered.get("last_failure_run_id")).isEqualTo(third);
        assertThat(count(jdbc, "staging_listing_changes")).isEqualTo(3);
    }

    @Test
    void ageClassIsOnlyDerivedFromExplicitSignalsAndNeverDefaultsToGeneral() {
        String[][] cases = {
                {"a1", null, null, null, "UNKNOWN"},
                {"a2", "독자 판단", null, null, "UNKNOWN"},
                {"a3", "19세 이용가", null, null, "ADULT"},
                {"a4", null, "list:label", "19세 이용가", "ADULT"},
                {"a5", "15세 이용가", "list:label", "청소년 이용불가", "UNKNOWN"},
        };
        for (String[] c : cases) {
            List<AgeSignal> signals = c[2] == null ? List.of() : List.of(new AgeSignal(c[2], c[3], true));
            long id = ingest.ingest(run, OWNER, listing("RIDI", c[0], "https://ridibooks.com/books/" + c[0], "OK", "작품",
                    c[1], signals, List.of(), null, null, List.of())).stagingId();
            assertThat(row(id).get("age_class")).as(c[0]).isEqualTo(c[4]);
        }
        // 후보 신호(판본 접미사 등)는 등급을 정하지 않는다
        long candidate = ingest.ingest(run, OWNER, listing("RIDI", "a6", "https://ridibooks.com/books/a6", "OK", "작품", null,
                List.of(new AgeSignal("edition:suffix", "_nonadult 15세", false)), List.of(), null, null, List.of())).stagingId();
        assertThat(row(candidate).get("age_class")).isEqualTo("UNKNOWN");
        assertThat(row(candidate).get("age_signals").toString()).contains("edition:suffix");

        // 성인 목록 신호로 상세를 건너뛴 작품은 목록 값만 남긴다
        long gated = ingest.ingest(run, OWNER, listing("RIDI", "a7", "https://ridibooks.com/books/a7", "SKIPPED_ADULT", "성인작",
                null, List.of(new AgeSignal("list:category", "성인", true)), List.of(), "보이면 안 되는 소개", null, List.of()))
                .stagingId();
        Map<String, Object> gatedRow = row(gated);
        assertThat(gatedRow.get("detail_status")).isEqualTo("SKIPPED_ADULT");
        assertThat(gatedRow.get("synopsis_text")).isNull();
        assertThat(gatedRow.get("title_raw")).isEqualTo("성인작");
    }

    @Test
    void synopsisPolicyAliasesAndValidation() throws Exception {
        CollectionTestSupport.setSynopsisPolicy(jdbc, "RIDI", "NOT_COLLECTED", adminId);
        long id = ingest.ingest(run, OWNER, ridi("300", "작품", null)).stagingId();
        assertThat(row(id).get("synopsis_text")).isNull();
        assertThat(row(id).get("synopsis_sha256")).isNull();

        // 리디 회차 ID 를 별칭으로 남기면, 그 ID 로 다시 발견해도 같은 행을 쓴다
        CollectedListing withAlias = listing("RIDI", "300", "https://ridibooks.com/books/300", "OK", "작품", null, List.of(),
                List.of(), null, "sitemap", List.of("301", "302"));
        ingest.ingest(run, OWNER, withAlias);
        IngestResult byAlias = ingest.ingest(run, OWNER, listing("RIDI", "302", "https://ridibooks.com/books/302", "OK", "작품",
                null, List.of(), List.of(), null, "list:weekly", List.of()));
        assertThat(byAlias.resolvedByAlias()).isTrue();
        assertThat(byAlias.stagingId()).isEqualTo(id);
        assertThat(count(jdbc, "staging_listings")).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT source_key FROM staging_listing_discoveries ORDER BY source_key", String.class))
                .containsExactly("list:weekly", "sitemap");

        // 다른 행의 대표 ID·별칭은 별칭으로 쓸 수 없다(롤백되어 그 반영 전체가 남지 않음)
        long other = ingest.ingest(run, OWNER, ridi("400", "다른 작품", null)).stagingId();
        assertThatThrownBy(() -> ingest.ingest(run, OWNER, listing("RIDI", "400", "https://ridibooks.com/books/400", "OK",
                "바뀐 제목", null, List.of(), List.of(), null, null, List.of("301"))))
                .extracting("errorCode").isEqualTo(ErrorCode.STAGING_ALIAS_CONFLICT);
        assertThat(row(other).get("title_raw")).isEqualTo("다른 작품");
        assertThatThrownBy(() -> ingest.ingest(run, OWNER, listing("RIDI", "300", "https://ridibooks.com/books/300", "OK",
                "작품", null, List.of(), List.of(), null, null, List.of("400"))))
                .extracting("errorCode").isEqualTo(ErrorCode.STAGING_ALIAS_CONFLICT);

        String[][] bad = {
                {"MRBLUE", "1", "https://ridibooks.com/books/1", "OK"},
                {"RIDI", "1 2", "https://ridibooks.com/books/1", "OK"},
                {"RIDI", "1", "https://evil.example.com/books/1", "OK"},
                {"RIDI", "1", "http://ridibooks.com/books/1", "OK"},
                {"RIDI", "1", "https://ridibooks.com/books/1", "PENDING"},
                {"RIDI", "1", "https://ridibooks.com/books/1", "DONE"},
        };
        for (String[] b : bad) {
            assertThatThrownBy(() -> ingest.ingest(run, OWNER, listing(b[0], b[1], b[2], b[3], "작품", null, List.of(),
                    List.of(), null, null, List.of()))).as(String.join(",", b))
                    .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
        }
        assertThat(count(jdbc, "staging_listings")).isEqualTo(2);
    }

    @Test
    void concurrentIngestOfANewKeyCreatesOneRow() throws Exception {
        for (int round = 0; round < 10; round++) {
            String id = "c" + round;
            CyclicBarrier barrier = new CyclicBarrier(3);
            List<Future<IngestResult>> futures = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                futures.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return ingest.ingest(run, OWNER, ridi(id, "동시 " + id, null));
                }));
            }
            List<IngestResult> results = new ArrayList<>();
            for (Future<IngestResult> f : futures) {
                results.add(f.get(10, TimeUnit.SECONDS));
            }
            assertThat(results).filteredOn(IngestResult::created).hasSize(1);
            assertThat(results).extracting(IngestResult::stagingId).containsOnly(results.get(0).stagingId());
        }
        assertThat(count(jdbc, "staging_listings")).isEqualTo(10);
        assertThat(count(jdbc, "staging_listing_changes")).isEqualTo(10);
    }

    @Test
    void failureWhileRecordingTheChangeRollsBackTheUpdate() {
        long id = ingest.ingest(run, OWNER, ridi("500", "처음 제목", null)).stagingId();
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION test_fail_staging_change() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'test failure while recording staging change';
                END $$""");
        jdbc.execute("CREATE TRIGGER test_fail_staging_change BEFORE INSERT OR UPDATE ON staging_listing_changes "
                + "FOR EACH ROW EXECUTE FUNCTION test_fail_staging_change()");
        long second = nextRun();
        assertThatThrownBy(() -> ingest.ingest(second, OWNER, withTitle(ridi("500", "처음 제목", null), "바뀐 제목")))
                .isInstanceOf(RuntimeException.class);
        Map<String, Object> unchanged = row(id);
        assertThat(unchanged.get("title_raw")).isEqualTo("처음 제목");
        assertThat(unchanged.get("last_run_id")).isEqualTo(run);
        assertThatThrownBy(() -> ingest.ingest(second, OWNER, ridi("501", "새 작품", null))).isInstanceOf(RuntimeException.class);
        assertThat(count(jdbc, "staging_listings")).isEqualTo(1);
    }

    @Test
    void writerThatLostItsLeaseCannotIngest() {
        CollectionTestSupport.expireLease(jdbc, run);
        assertThatThrownBy(() -> ingest.ingest(run, OWNER, ridi("600", "작품", null))).extracting("errorCode")
                .isEqualTo(ErrorCode.CRAWL_RUN_LEASE_LOST);
        runs.acquire(run, OTHER, LEASE);
        assertThatThrownBy(() -> ingest.ingest(run, OWNER, ridi("600", "작품", null))).extracting("errorCode")
                .isEqualTo(ErrorCode.CRAWL_RUN_LEASE_LOST);
        assertThat(ingest.ingest(run, OTHER, ridi("600", "작품", null)).created()).isTrue();
    }

    @Test
    void conflictingAliasRegistrationRollsBackAndRecollectionKeepsTheOriginal() {
        long id = ingest.ingest(run, OWNER, ridi("orig", "원래 제목", "15세 이용가")).stagingId();
        String hash = (String) row(id).get("content_hash");
        CollectedListing conflict = cross("other", "다른 작품", "orig");
        assertThatThrownBy(() -> ingest.ingest(run, OWNER, conflict)).extracting("errorCode")
                .isEqualTo(ErrorCode.STAGING_ALIAS_CONFLICT);
        assertThat(count(jdbc, "staging_listings")).isEqualTo(1);
        assertThat(row(id).get("title_raw")).isEqualTo("원래 제목");
        assertThat(row(id).get("content_hash")).isEqualTo(hash);

        IngestResult again = ingest.ingest(run, OWNER, ridi("orig", "원래 제목", "15세 이용가"));
        assertThat(again.stagingId()).isEqualTo(id);
        assertThat(again.contentChanged()).isFalse();
        assertThat(row(id).get("title_raw")).isEqualTo("원래 제목");
        assertThatThrownBy(() -> ingest.ingest(run, OWNER, conflict)).extracting("errorCode")
                .isEqualTo(ErrorCode.STAGING_ALIAS_CONFLICT);
        assertThat(count(jdbc, "staging_listings")).isEqualTo(1);
        assertThat(row(id).get("title_raw")).isEqualTo("원래 제목");
        assertThat(count(jdbc, "works") + count(jdbc, "work_platform_listings")).isZero();
    }

    @Test
    void swappedOrderConcurrentAliasRegistrationLeavesOneRow() throws Exception {
        for (int round = 0; round < 6; round++) {
            String left = "L" + round;
            String right = "R" + round;
            String leftTitle = "왼쪽" + round;
            String rightTitle = "오른쪽" + round;
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<Object> first = executor.submit(() -> crossIngest(barrier, left, leftTitle, right));
            Future<Object> second = executor.submit(() -> crossIngest(barrier, right, rightTitle, left));
            List<Object> results = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertThat(results.stream().filter(IngestResult.class::isInstance).count()).isEqualTo(1);
            assertThat(results.stream().filter(ErrorCode.STAGING_ALIAS_CONFLICT::equals).count()).isEqualTo(1);

            IngestResult winner = (IngestResult) results.stream().filter(IngestResult.class::isInstance).findFirst().orElseThrow();
            String winnerId = (String) row(winner.stagingId()).get("platform_work_id");
            String title = (String) row(winner.stagingId()).get("title_raw");
            String loserId = winnerId.equals(left) ? right : left;
            assertThat(listingsOf(left, right)).isEqualTo(1);

            ingest.ingest(run, OWNER, cross(winnerId, title, loserId));
            assertThat(row(winner.stagingId()).get("title_raw")).isEqualTo(title);
            assertThatThrownBy(() -> ingest.ingest(run, OWNER, cross(loserId, "덮어쓴 제목", winnerId)))
                    .extracting("errorCode").isEqualTo(ErrorCode.STAGING_ALIAS_CONFLICT);
            assertThat(row(winner.stagingId()).get("title_raw")).isEqualTo(title);
            assertThat(listingsOf(left, right)).isEqualTo(1);
        }
        assertThat(count(jdbc, "works") + count(jdbc, "work_platform_listings")).isZero();
    }

    @Test
    void parseFailedAndFailedKeepStoredSignalsAndSources() {
        for (String status : List.of("PARSE_FAILED", "FAILED")) {
            String workId = "pf-" + status;
            long id = ingest.ingest(run, OWNER, ridi(workId, "유지", "15세 이용가")).stagingId();
            String hash = (String) row(id).get("content_hash");
            ingest.ingest(run, OWNER, failureListing(workId, status));
            ingest.ingest(run, OWNER, failureListing(workId, status));
            Map<String, Object> stored = row(id);
            assertThat(stored.get("title_raw")).isEqualTo("유지");
            assertThat(stored.get("age_class")).isEqualTo("GENERAL");
            assertThat(stored.get("age_rating_raw")).isEqualTo("15세 이용가");
            assertThat(stored.get("consecutive_failures")).isEqualTo(1);
            assertThat(stored.get("field_sources").toString()).contains("h1").doesNotContain("h2");
            assertThat(stored.get("age_signals").toString()).doesNotContain("19세");
            assertThat(stored.get("content_hash")).isEqualTo(hash);
            assertThat(stored.get("synopsis_text").toString()).contains("유지");
        }
    }

    /** 앞 실행을 끝내고 같은 플랫폼의 새 실행을 연다 */
    private long nextRun() {
        jdbc.update("UPDATE crawl_runs SET status = 'COMPLETED', finished_at = now(), lease_owner = NULL, lease_until = NULL "
                + "WHERE platform_code = 'RIDI' AND finished_at IS NULL");
        return runs.start("RIDI", RunKind.INCREMENTAL, "ridi-0.1", OWNER, LEASE, null).id();
    }

    private Object crossIngest(CyclicBarrier barrier, String id, String title, String alias) throws Exception {
        barrier.await(5, TimeUnit.SECONDS);
        try {
            return ingest.ingest(run, OWNER, cross(id, title, alias));
        } catch (BusinessException exception) {
            return exception.getErrorCode();
        }
    }

    private static CollectedListing cross(String id, String title, String alias) {
        return listing("RIDI", id, "https://ridibooks.com/books/" + id, "OK", title, null, List.of(), List.of(),
                "소개문 " + title, "list:weekly", List.of(alias));
    }

    private static CollectedListing failureListing(String id, String status) {
        return new CollectedListing("RIDI", id, "https://ridibooks.com/books/" + id + "-gone", status, "SERIES", "ko",
                "덮어쓴 제목", "덮어쓴 제목", List.of("드라마"), "19세 이용가",
                List.of(new AgeSignal("detail:label", "19세 이용가", true)), "연재중", "ONGOING",
                "https://img.ridicdn.net/cover/" + id + "/large", "다른 소개", Map.of("publisher", "다른 출판사"),
                Map.of("titleRaw", Map.of("path", "h2")), List.of(), "failure", List.of());
    }

    private int listingsOf(String left, String right) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM staging_listings WHERE platform_code = 'RIDI' AND platform_work_id IN (?, ?)",
                Integer.class, left, right);
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("SELECT * FROM staging_listings WHERE id = ?", id);
    }

    private JsonNode changes(long stagingId, long runId) throws Exception {
        return objectMapper.readTree(jdbc.queryForObject(
                "SELECT changed_fields::text FROM staging_listing_changes WHERE staging_id = ? AND run_id = ?",
                String.class, stagingId, runId));
    }
}
