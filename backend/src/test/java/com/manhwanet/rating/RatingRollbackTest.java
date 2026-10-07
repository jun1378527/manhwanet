package com.manhwanet.rating;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;

import com.manhwanet.global.error.BusinessException;
import com.manhwanet.global.error.ErrorCode;
import com.manhwanet.rating.application.RatingService;
import com.manhwanet.rating.dto.MyRatingResponse;
import com.manhwanet.rating.dto.RatingRequest;
import com.manhwanet.support.TestDatabaseCleaner;
import com.manhwanet.work.application.WorkRatingSummaryQuery;
import com.manhwanet.work.application.WorkService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 롤백과 FK 위반 변환 (실제 PostgreSQL).
 */
@SpringBootTest
@ActiveProfiles("test")
class RatingRollbackTest {

    @Autowired
    private RatingService ratingService;

    @MockitoSpyBean
    private WorkService workService;

    @Autowired
    private WorkRatingSummaryQuery summaryQuery;

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
    void failureLaterInTheSameTransactionRollsBackNewRating() {
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            ratingService.putMyRating(userId, workId, request("4.5"));
            assertThat(rows()).hasSize(1); // 같은 트랜잭션 안에서는 보임
            throw new IllegalStateException("boom");
        })).hasMessage("boom");

        assertThat(rows()).isEmpty();
        assertThat(publicCount()).isZero();
    }

    @Test
    void failureLaterInTheSameTransactionKeepsPreviousRatingAndTimestamps() {
        MyRatingResponse before = ratingService.putMyRating(userId, workId, request("3.0"));

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            ratingService.putMyRating(userId, workId, request("1.0"));
            throw new IllegalStateException("boom");
        })).hasMessage("boom");
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            ratingService.deleteMyRating(userId, workId);
            throw new IllegalStateException("boom");
        })).hasMessage("boom");

        MyRatingResponse after = ratingService.getMyRating(userId, workId);
        assertThat(after.score()).isEqualByComparingTo("3.0");
        assertThat(after.createdAt()).isEqualTo(before.createdAt());
        assertThat(after.updatedAt()).isEqualTo(before.updatedAt());
    }

    /** 존재 확인을 통과한 뒤 작품이 사라진 경우: FK 위반만 404 로 바꾸고 아무것도 저장하지 않는다. */
    @Test
    void workMissingAtWriteTimeBecomesNotFoundAndStoresNothing() {
        long missingWorkId = workId + 1000;
        doReturn(true).when(workService).exists(missingWorkId);

        assertThatThrownBy(() -> ratingService.putMyRating(userId, missingWorkId, request("4.5")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.WORK_NOT_FOUND);
        assertThat(rows()).isEmpty();
    }

    /** 사용자 FK 위반은 예상한 변환 대상이 아니므로 그대로 던진다(500). */
    @Test
    void unexpectedIntegrityViolationIsNotTranslated() {
        assertThatThrownBy(() -> ratingService.putMyRating(userId + 1000, workId, request("4.5")))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .hasMessageContaining("fk_work_ratings_user");
        assertThat(rows()).isEmpty();
    }

    private static RatingRequest request(String score) {
        return new RatingRequest(new RatingRequest.Score(new BigDecimal(score)));
    }

    private List<Map<String, Object>> rows() {
        return jdbcTemplate.queryForList("SELECT * FROM work_ratings");
    }

    private long publicCount() {
        return summaryQuery.findByWorkIds(List.of(workId)).size();
    }
}
