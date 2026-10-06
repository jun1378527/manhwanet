package com.manhwanet.work.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.manhwanet.global.error.BusinessException;
import com.manhwanet.global.error.ErrorCode;
import com.manhwanet.support.TestDatabaseCleaner;
import com.manhwanet.work.application.WorkService;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 등록 API의 선행 검증을 우회하지 않고, 실제 PostgreSQL flush 가 만든 무결성 오류가
 * {@link WorkService} 의 저장 경로에서 어떻게 변환되는지 확인한다.
 *
 * <p>도메인 팩토리는 중복 제목·대표 제목·크레딧을 거부한다. 이 테스트는 이미 만든 집계에
 * 자식 행만 시험용으로 덧붙여 {@code saveAndFlush} 가 데이터베이스 오류를 내게 한다.
 * 운영 코드에는 검증을 건너뛰는 입력을 두지 않는다.
 *
 * <p>확인 범위는 서비스가 던지는 예외까지다. HTTP 409 는 {@code ErrorCode.DUPLICATE_RESOURCE} 의
 * 상태 코드와 예외 핸들러 매핑이며, 이 클래스에서 요청을 보내지 않는다.
 */
@SpringBootTest
@ActiveProfiles("test")
class WorkDuplicateConstraintIntegrationTest {

    @Autowired
    private WorkService workService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Environment environment;

    private long creatorId;

    @BeforeEach
    void setUp() {
        TestDatabaseCleaner.clean(jdbcTemplate, environment);
        creatorId = jdbcTemplate.queryForObject(
                "INSERT INTO creators (name) VALUES ('한서윤') RETURNING id", Long.class);
    }

    @Test
    void duplicateTitleInSameLanguageBecomesDuplicateResourceAndRollsBack() {
        Work work = validWork();
        addTitle(work, "달빛 기사단", "ko", false);

        assertDuplicateResource(work);
        assertWorkGraphAbsent();
    }

    @Test
    void secondPrimaryTitleBecomesDuplicateResourceAndRollsBack() {
        Work work = validWork();
        addTitle(work, "月光騎士団", "ja", true);

        assertDuplicateResource(work);
        assertWorkGraphAbsent();
    }

    @Test
    void duplicateCreatorRoleBecomesDuplicateResourceAndRollsBack() {
        Work work = validWork();
        addCredit(work, creatorId, CreatorRole.STORY);

        assertDuplicateResource(work);
        assertWorkGraphAbsent();
        assertThat(count("creators")).isEqualTo(1);
    }

    @Test
    void foreignKeyViolationIsNotTranslatedToDuplicateResource() {
        Work work = Work.create(
                WorkFormat.WEBTOON,
                "KR",
                null,
                List.of(new WorkTitleDraft("달빛 기사단", "ko", true)),
                List.of(new WorkCreditDraft(9_999L, CreatorRole.STORY)));

        assertThatThrownBy(() -> persist(work))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_work_creators_creator");
        assertWorkGraphAbsent();
    }

    @Test
    void checkViolationIsNotTranslatedToDuplicateResource() {
        Work work = Work.create(
                WorkFormat.WEBTOON,
                "kr",
                null,
                List.of(new WorkTitleDraft("달빛 기사단", "ko", true)),
                List.of(new WorkCreditDraft(creatorId, CreatorRole.STORY)));

        assertThatThrownBy(() -> persist(work))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_works_origin_country");
        assertWorkGraphAbsent();
    }

    private void assertDuplicateResource(Work work) {
        assertThatThrownBy(() -> persist(work))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.DUPLICATE_RESOURCE);
    }

    /**
     * 등록 메서드와 같이 바깥 트랜잭션 안에서 저장한다.
     * 제약 오류를 잡은 뒤에도 그 트랜잭션의 작품 행이 커밋되지 않아야 한다.
     */
    private void persist(Work work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> invokeSave(work));
    }

    private void invokeSave(Work work) {
        try {
            WorkService target = AopTestUtils.getUltimateTargetObject(workService);
            Method save = WorkService.class.getDeclaredMethod("save", Work.class);
            save.setAccessible(true);
            save.invoke(target, work);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Work validWork() {
        return Work.create(
                WorkFormat.WEBTOON,
                "KR",
                null,
                List.of(new WorkTitleDraft("달빛 기사단", "ko", true)),
                List.of(new WorkCreditDraft(creatorId, CreatorRole.STORY)));
    }

    private static void addTitle(Work work, String title, String languageCode, boolean primary) {
        titles(work).add(new WorkTitle(work, title, languageCode, primary));
    }

    private static void addCredit(Work work, long creator, CreatorRole role) {
        credits(work).add(new WorkCreator(work, creator, role));
    }

    @SuppressWarnings("unchecked")
    private static List<WorkTitle> titles(Work work) {
        return (List<WorkTitle>) field(work, "titles");
    }

    @SuppressWarnings("unchecked")
    private static List<WorkCreator> credits(Work work) {
        return (List<WorkCreator>) field(work, "creators");
    }

    private static Object field(Work work, String name) {
        try {
            Field field = Work.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(work);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void assertWorkGraphAbsent() {
        assertThat(count("works")).isZero();
        assertThat(count("work_titles")).isZero();
        assertThat(count("work_creators")).isZero();
    }

    private int count(String table) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }
}
