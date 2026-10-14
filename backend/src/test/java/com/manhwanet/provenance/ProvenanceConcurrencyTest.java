package com.manhwanet.provenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.manhwanet.global.error.ConstraintViolations;
import com.manhwanet.global.error.ErrorCode;
import com.manhwanet.global.error.ExternalIdAlreadyLinkedException;
import com.manhwanet.global.error.VersionConflictException;
import com.manhwanet.provenance.application.CreatorExternalIdService;
import com.manhwanet.provenance.application.ExternalIdStatusChanger;
import com.manhwanet.provenance.application.WorkProvenanceService;
import com.manhwanet.provenance.domain.ExternalIdOwner;
import com.manhwanet.provenance.dto.UpdateExternalIdRequest;
import com.manhwanet.provenance.dto.AddExternalIdRequest;
import com.manhwanet.provenance.dto.AddLinkRequest;
import com.manhwanet.provenance.dto.EvidenceRequest;
import com.manhwanet.provenance.dto.ExternalIdMutationResponse;
import com.manhwanet.provenance.dto.LinkMutationResponse;
import com.manhwanet.review.dto.JsonInteger;
import com.manhwanet.support.TestDatabaseCleaner;
import com.manhwanet.work.application.WorkService;
import com.manhwanet.work.dto.CreateWorkRequest.CreditRequest;
import com.manhwanet.work.dto.CreateWorkRequest.TitleRequest;
import com.manhwanet.work.dto.CreateWorkRequest;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 외부 ID·링크의 동시 등록과 변경·기록의 원자성 (#40, 실제 PostgreSQL).
 */
@SpringBootTest
@ActiveProfiles("test")
class ProvenanceConcurrencyTest {

    private static final int ROUNDS = 15;
    private static final int REACTIVATION_ROUNDS = 8;
    private static final Instant VERIFIED_AT = Instant.parse("2026-10-12T00:00:00Z");

    @Autowired
    private WorkProvenanceService provenanceService;

    @Autowired
    private CreatorExternalIdService creatorExternalIdService;

    @Autowired
    private WorkService workService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Environment environment;

    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private long adminId;
    private long creatorId;

    @BeforeEach
    void setUp() {
        dropTriggers();
        TestDatabaseCleaner.clean(jdbc, environment);
        adminId = jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, nickname, role) VALUES ('a@manhwanet.test', '{noop}x', '관리자', 'ADMIN')
                RETURNING id""", Long.class);
        creatorId = ProvenanceTestSupport.insertCreator(jdbc, "작가");
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        dropTriggers();
        ExternalIdStatusChanger.beforeConflictingUpdate = () -> {
        };
    }

    private void dropTriggers() {
        jdbc.execute("DROP TRIGGER IF EXISTS test_fail_change_log ON admin_change_log");
        jdbc.execute("DROP FUNCTION IF EXISTS test_fail_change_log()");
        jdbc.execute("DROP TRIGGER IF EXISTS test_fail_work_bump ON works");
        jdbc.execute("DROP FUNCTION IF EXISTS test_fail_work_bump()");
        jdbc.execute("DROP TRIGGER IF EXISTS test_wait_both_reactivate ON work_external_ids");
        jdbc.execute("DROP TRIGGER IF EXISTS test_wait_both_reactivate ON creator_external_ids");
        jdbc.execute("DROP FUNCTION IF EXISTS test_wait_both_reactivate()");
    }

    @Test
    void sameExternalIdOnTwoWorksAtOnceLinksOnlyOne() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            long workA = ProvenanceTestSupport.insertWork(jdbc, "A" + round, creatorId);
            long workB = ProvenanceTestSupport.insertWork(jdbc, "B" + round, creatorId);
            String id = "Q" + (1000 + round);
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<Object> a = executor.submit(() -> addExternalId(barrier, workA, 0, id));
            Future<Object> b = executor.submit(() -> addExternalId(barrier, workB, 0, id));
            List<Object> results = List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));

            assertThat(results).filteredOn(r -> r instanceof ExternalIdMutationResponse).hasSize(1);
            assertThat(results).filteredOn(r -> r instanceof ExternalIdAlreadyLinkedException).hasSize(1);
            long winner = jdbc.queryForObject("SELECT work_id FROM work_external_ids WHERE external_id = ?", Long.class, id);
            long loser = winner == workA ? workB : workA;
            assertThat(ProvenanceTestSupport.workVersion(jdbc, winner)).isEqualTo(1);
            assertThat(ProvenanceTestSupport.workVersion(jdbc, loser)).isZero();
            // 진 쪽은 기록도 남지 않는다
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_change_log WHERE work_id = ?", Integer.class, loser))
                    .isZero();
        }
    }

    @Test
    void twoSubResourceChangesWithTheSameWorkVersionLetOneWin() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            long work = ProvenanceTestSupport.insertWork(jdbc, "W" + round, creatorId);
            CyclicBarrier barrier = new CyclicBarrier(2);
            int r = round;
            Future<Object> a = executor.submit(() -> addExternalId(barrier, work, 0, "Q" + (5000 + r)));
            Future<Object> b = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                try {
                    return provenanceService.addLink(work, new AddLinkRequest(new JsonInteger(0L), "PUBLISHER",
                            "https://publisher.example.com/" + r, "ko", VERIFIED_AT, null, null), adminId);
                } catch (VersionConflictException e) {
                    return e;
                }
            });
            List<Object> results = List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            assertThat(results).filteredOn(x -> x instanceof ExternalIdMutationResponse || x instanceof LinkMutationResponse)
                    .hasSize(1);
            assertThat(results).filteredOn(x -> x instanceof VersionConflictException).hasSize(1)
                    .allSatisfy(x -> assertThat(((VersionConflictException) x).getErrorCode())
                            .isEqualTo(ErrorCode.WORK_VERSION_CONFLICT));
            assertThat(ProvenanceTestSupport.workVersion(jdbc, work)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_change_log WHERE work_id = ?", Integer.class, work))
                    .isEqualTo(1);
        }
    }

    /**
     * 같은 출처·ID 의 INVALID 행 두 개를 동시에 ACTIVE 로 되돌린다(#57).
     * 두 트랜잭션이 사전 확인을 지난 뒤에야 UPDATE 하므로, 부분 유니크 인덱스가 진 쪽을 정한다.
     */
    @Test
    void reactivatingTwoInvalidWorkExternalIdsReturnsConflictWithIds() throws Exception {
        reactivatePair(ExternalIdOwner.WORK, 8_600_000);
    }

    @Test
    void reactivatingTwoInvalidCreatorExternalIdsReturnsConflictWithIds() throws Exception {
        reactivatePair(ExternalIdOwner.CREATOR, 8_700_000);
    }

    @Test
    void unrelatedConstraintViolationIsNotAnExternalIdConflict() {
        long work = ProvenanceTestSupport.insertWork(jdbc, "제약", creatorId);
        ExternalIdMutationResponse added = provenanceService.addExternalId(work, new AddExternalIdRequest(
                new JsonInteger(0L), "WIKIDATA", "Q42", null, VERIFIED_AT, null, null), adminId);
        int logs = changeLogCount(ExternalIdOwner.WORK, work);
        long missingUserId = 9_000_000_000L;

        assertThatThrownBy(() -> provenanceService.updateExternalId(work, added.externalId().id(),
                new UpdateExternalIdRequest(new JsonInteger(added.ownerVersion()), "ACTIVE", VERIFIED_AT, "다른 메모",
                        "없는 확인자"),
                missingUserId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(ExternalIdAlreadyLinkedException.class)
                .satisfies(error -> assertThat(ConstraintViolations.constraintName((DataIntegrityViolationException) error))
                        .contains("fk_work_external_ids_verified_by"));

        assertThat(ProvenanceTestSupport.workVersion(jdbc, work)).isEqualTo(added.ownerVersion());
        assertThat(changeLogCount(ExternalIdOwner.WORK, work)).isEqualTo(logs);
        assertThat(jdbc.queryForObject("SELECT note FROM work_external_ids WHERE id = ?", String.class,
                added.externalId().id())).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM work_external_ids WHERE id = ?", String.class,
                added.externalId().id())).isEqualTo("ACTIVE");
    }

    private void reactivatePair(ExternalIdOwner owner, int idBase) throws Exception {
        try {
            for (int round = 0; round < REACTIVATION_ROUNDS; round++) {
                long ownerA = newOwner(owner, "A" + round);
                long ownerB = newOwner(owner, "B" + round);
                String externalId = "Q" + (idBase + round);
                ExternalIdStatusChanger.beforeConflictingUpdate = () -> {
                };
                Held invalidA = invalidate(owner, ownerA, externalId);
                Held invalidB = invalidate(owner, ownerB, externalId);
                CyclicBarrier passedCheck = new CyclicBarrier(2);
                ExternalIdStatusChanger.beforeConflictingUpdate = () -> {
                    try {
                        passedCheck.await(10, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                };
                Future<Object> first = executor.submit(() -> reactivate(owner, invalidA));
                Future<Object> second = executor.submit(() -> reactivate(owner, invalidB));
                List<Object> results = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));

                assertThat(results).filteredOn(ExternalIdMutationResponse.class::isInstance).hasSize(1);
                assertThat(results).filteredOn(ExternalIdAlreadyLinkedException.class::isInstance).hasSize(1);
                ExternalIdAlreadyLinkedException conflict = (ExternalIdAlreadyLinkedException) results.stream()
                        .filter(ExternalIdAlreadyLinkedException.class::isInstance).findFirst().orElseThrow();
                assertThat(conflict.getErrorCode()).isEqualTo(ErrorCode.EXTERNAL_ID_ALREADY_LINKED);

                long winnerOwner = jdbc.queryForObject(
                        "SELECT " + owner.ownerColumn() + " FROM " + owner.table()
                                + " WHERE external_id = ? AND status <> 'INVALID'",
                        Long.class, externalId);
                long winnerRow = jdbc.queryForObject(
                        "SELECT id FROM " + owner.table() + " WHERE external_id = ? AND status <> 'INVALID'",
                        Long.class, externalId);
                Held winner = winnerOwner == invalidA.ownerId ? invalidA : invalidB;
                Held loser = winner == invalidA ? invalidB : invalidA;
                if (owner == ExternalIdOwner.WORK) {
                    assertThat(conflict.getExistingWorkId()).isEqualTo(winnerOwner);
                    assertThat(conflict.getExistingCreatorId()).isNull();
                } else {
                    assertThat(conflict.getExistingCreatorId()).isEqualTo(winnerOwner);
                    assertThat(conflict.getExistingWorkId()).isNull();
                }
                assertThat(conflict.getExistingExternalIdId()).isEqualTo(winnerRow);
                assertThat(ownerVersion(owner, loser.ownerId)).isEqualTo(loser.version);
                assertThat(changeLogCount(owner, loser.ownerId)).isEqualTo(loser.logs);
                assertThat(ownerVersion(owner, winner.ownerId)).isEqualTo(winner.version + 1);
                assertThat(jdbc.queryForObject(
                        "SELECT status FROM " + owner.table() + " WHERE id = ?", String.class, loser.rowId))
                        .isEqualTo("INVALID");
            }
        } finally {
            ExternalIdStatusChanger.beforeConflictingUpdate = () -> {
            };
        }
    }

    /** 외부 ID 를 연결한 뒤 INVALID 로 바꿔, 동시 재활성화 직전의 version·기록 수를 남긴다 */
    private Held invalidate(ExternalIdOwner owner, long ownerId, String externalId) {
        ExternalIdMutationResponse added = addExternalId(owner, ownerId, 0, externalId);
        ExternalIdMutationResponse invalid = updateStatus(owner, ownerId, added.externalId().id(), added.ownerVersion(),
                "INVALID");
        return new Held(ownerId, added.externalId().id(), invalid.ownerVersion(), changeLogCount(owner, ownerId));
    }

    private ExternalIdMutationResponse addExternalId(ExternalIdOwner owner, long ownerId, long version, String externalId) {
        AddExternalIdRequest request = new AddExternalIdRequest(new JsonInteger(version), "WIKIDATA", externalId, null,
                VERIFIED_AT, null, null);
        return owner == ExternalIdOwner.WORK
                ? provenanceService.addExternalId(ownerId, request, adminId)
                : creatorExternalIdService.add(ownerId, request, adminId);
    }

    private ExternalIdMutationResponse updateStatus(ExternalIdOwner owner, long ownerId, long rowId, long version,
            String status) {
        UpdateExternalIdRequest request = new UpdateExternalIdRequest(new JsonInteger(version), status, null, null,
                "잘못 연결한 ID");
        return owner == ExternalIdOwner.WORK
                ? provenanceService.updateExternalId(ownerId, rowId, request, adminId)
                : creatorExternalIdService.update(ownerId, rowId, request, adminId);
    }

    private Object reactivate(ExternalIdOwner owner, Held held) {
        try {
            return updateStatus(owner, held.ownerId, held.rowId, held.version, "ACTIVE");
        } catch (ExternalIdAlreadyLinkedException e) {
            return e;
        }
    }

    private long newOwner(ExternalIdOwner owner, String name) {
        return owner == ExternalIdOwner.WORK
                ? ProvenanceTestSupport.insertWork(jdbc, name, creatorId)
                : ProvenanceTestSupport.insertCreator(jdbc, name);
    }

    private long ownerVersion(ExternalIdOwner owner, long ownerId) {
        return owner == ExternalIdOwner.WORK
                ? ProvenanceTestSupport.workVersion(jdbc, ownerId)
                : ProvenanceTestSupport.creatorVersion(jdbc, ownerId);
    }

    private int changeLogCount(ExternalIdOwner owner, long ownerId) {
        String column = owner == ExternalIdOwner.WORK ? "work_id" : "creator_id";
        return jdbc.queryForObject("SELECT COUNT(*) FROM admin_change_log WHERE " + column + " = ?", Integer.class,
                ownerId);
    }

    private record Held(long ownerId, long rowId, long version, int logs) {
    }

    @Test
    void failingChangeLogRollsBackTheWholeRegistration() {
        failChangeLogFor("WORK_EXTERNAL_ID");
        CreateWorkRequest request = new CreateWorkRequest("WEBTOON", null, null,
                List.of(new TitleRequest("롤백 작품", "ko", true)), List.of(new CreditRequest(creatorId, "STORY")),
                null, List.of(new com.manhwanet.provenance.dto.ExternalIdInput("WIKIDATA", "Q77", null, VERIFIED_AT, null)),
                null, List.of(evidence("TITLE"), evidence("CREDIT")), null);

        assertThatThrownBy(() -> workService.register(request, adminId)).isInstanceOf(RuntimeException.class);
        assertThat(ProvenanceTestSupport.count(jdbc, "works")).isZero();
        assertThat(ProvenanceTestSupport.count(jdbc, "work_titles")).isZero();
        assertThat(ProvenanceTestSupport.count(jdbc, "work_external_ids")).isZero();
        assertThat(ProvenanceTestSupport.count(jdbc, "work_field_sources")).isZero();
        assertThat(ProvenanceTestSupport.count(jdbc, "admin_change_log")).isZero();
    }

    @Test
    void failingVersionBumpRollsBackTheChangeAndItsLog() {
        long work = ProvenanceTestSupport.insertWork(jdbc, "버전 실패", creatorId);
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION test_fail_work_bump() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.id = %d AND NEW.version > OLD.version THEN
                        RAISE EXCEPTION 'test failure while bumping work version';
                    END IF;
                    RETURN NEW;
                END $$""".formatted(work));
        jdbc.execute("CREATE TRIGGER test_fail_work_bump BEFORE UPDATE ON works FOR EACH ROW EXECUTE FUNCTION test_fail_work_bump()");

        assertThatThrownBy(() -> provenanceService.addExternalId(work, new AddExternalIdRequest(new JsonInteger(0L),
                "NDL", "n1", null, VERIFIED_AT, null, "기록과 함께 롤백"), adminId))
                .isInstanceOf(RuntimeException.class).isNotInstanceOf(VersionConflictException.class);
        assertThat(ProvenanceTestSupport.count(jdbc, "work_external_ids")).isZero();
        assertThat(ProvenanceTestSupport.count(jdbc, "admin_change_log")).isZero();
        assertThat(ProvenanceTestSupport.workVersion(jdbc, work)).isZero();
    }

    @Test
    void failingChangeLogRollsBackALinkStatusChange() {
        long work = ProvenanceTestSupport.insertWork(jdbc, "링크", creatorId);
        LinkMutationResponse added = provenanceService.addLink(work, new AddLinkRequest(new JsonInteger(0L), "PUBLISHER",
                "https://publisher.example.com/x", "ko", VERIFIED_AT, null, null), adminId);
        failChangeLogFor("WORK_LINK");
        assertThatThrownBy(() -> provenanceService.updateLink(work, added.link().id(),
                new com.manhwanet.provenance.dto.UpdateLinkRequest(new JsonInteger(1L), "REMOVED", null, null, "삭제 대신 상태"),
                adminId)).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM work_links", String.class)).isEqualTo("ACTIVE");
        assertThat(ProvenanceTestSupport.workVersion(jdbc, work)).isEqualTo(1);
    }

    private void failChangeLogFor(String entityType) {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION test_fail_change_log() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.entity_type = '%s' THEN
                        RAISE EXCEPTION 'test failure while writing change log';
                    END IF;
                    RETURN NEW;
                END $$""".formatted(entityType));
        jdbc.execute("CREATE TRIGGER test_fail_change_log BEFORE INSERT ON admin_change_log FOR EACH ROW "
                + "EXECUTE FUNCTION test_fail_change_log()");
    }

    private Object addExternalId(CyclicBarrier barrier, long work, long version, String id) throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        try {
            return provenanceService.addExternalId(work, new AddExternalIdRequest(new JsonInteger(version), "WIKIDATA", id,
                    null, VERIFIED_AT, null, null), adminId);
        } catch (ExternalIdAlreadyLinkedException | VersionConflictException e) {
            return e;
        }
    }

    private static EvidenceRequest evidence(String field) {
        return new EvidenceRequest(field, null, null, "STAFF", null, null, VERIFIED_AT, null);
    }
}
