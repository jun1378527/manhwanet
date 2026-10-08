package com.manhwanet.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;

import com.manhwanet.global.error.BusinessException;
import com.manhwanet.global.error.ErrorCode;
import com.manhwanet.review.application.ReviewService;
import com.manhwanet.review.dto.CreateReviewRequest;
import com.manhwanet.review.dto.JsonFlag;
import com.manhwanet.review.dto.JsonInteger;
import com.manhwanet.review.dto.JsonText;
import com.manhwanet.review.dto.MyReviewResponse;
import com.manhwanet.review.dto.UpdateReviewRequest;
import com.manhwanet.support.TestDatabaseCleaner;
import com.manhwanet.work.application.WorkService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 롤백과 FK 위반 변환 (실제 PostgreSQL).
 */
@SpringBootTest
@ActiveProfiles("test")
class ReviewRollbackTest {

    @Autowired
    private ReviewService reviewService;

    @MockitoSpyBean
    private WorkService workService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Environment environment;

    private long userId;
    private long workId;

    @BeforeEach
    void setUp() {
        TestDatabaseCleaner.clean(jdbcTemplate, environment);
        userId = jdbcTemplate.queryForObject("""
                INSERT INTO users (email, password_hash, nickname, role)
                VALUES ('reader@manhwanet.test', '{noop}unused', '독자', 'USER') RETURNING id
                """, Long.class);
        workId = jdbcTemplate.queryForObject("INSERT INTO works (work_format) VALUES ('WEBTOON') RETURNING id", Long.class);
    }

    @Test
    void failureLaterInTheSameTransactionRollsBackNewReview() {
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            reviewService.create(userId, workId, create("롤백될 리뷰 내용입니다."));
            assertThat(rows()).hasSize(1); // 같은 트랜잭션 안에서는 보임
            throw new IllegalStateException("boom");
        })).hasMessage("boom");

        assertThat(rows()).isEmpty();
    }

    @Test
    void failureLaterInTheSameTransactionKeepsPreviousReviewVersionAndTimestamps() {
        MyReviewResponse before = reviewService.create(userId, workId, create("원래 리뷰 내용입니다."));
        long id = before.review().id();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            reviewService.update(userId, workId, update(id, 0, "롤백될 수정 내용입니다."));
            throw new IllegalStateException("boom");
        })).hasMessage("boom");
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            reviewService.delete(userId, workId, id, 0L);
            throw new IllegalStateException("boom");
        })).hasMessage("boom");

        MyReviewResponse after = reviewService.getMyReview(userId, workId);
        assertThat(after).isEqualTo(before);
    }

    /** 존재 확인을 통과한 뒤 작품이 사라진 경우: FK 위반만 404 로 바꾸고 아무것도 저장하지 않는다. */
    @Test
    void workMissingAtWriteTimeBecomesNotFoundAndStoresNothing() {
        long missingWorkId = workId + 1000;
        doReturn(true).when(workService).exists(missingWorkId);

        assertThatThrownBy(() -> reviewService.create(userId, missingWorkId, create("사라진 작품에 쓰는 리뷰입니다.")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.WORK_NOT_FOUND);
        assertThat(rows()).isEmpty();
    }

    /** 사용자 FK 위반은 예상한 변환 대상이 아니므로 그대로 던진다(500). */
    @Test
    void unexpectedIntegrityViolationIsNotTranslated() {
        assertThatThrownBy(() -> reviewService.create(userId + 1000, workId, create("없는 사용자의 리뷰 내용입니다.")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_work_reviews_user");
        assertThat(rows()).isEmpty();
    }

    private static CreateReviewRequest create(String body) {
        return new CreateReviewRequest(new JsonText(body), new JsonFlag(false));
    }

    private static UpdateReviewRequest update(long reviewId, long version, String body) {
        return new UpdateReviewRequest(new JsonInteger(reviewId), new JsonInteger(version), new JsonText(body),
                new JsonFlag(false));
    }

    private List<Map<String, Object>> rows() {
        return jdbcTemplate.queryForList("SELECT * FROM work_reviews");
    }
}
