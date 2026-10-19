package com.manhwanet.review.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 리뷰 SQL. 시각·버전 정책은 docs/review-design-draft.md §2.1, 동시성은 §4.3~4.5.
 */
public interface WorkReviewRepository extends JpaRepository<WorkReview, Long> {

    Optional<WorkReview> findByUserIdAndWorkId(Long userId, Long workId);

    /** 작품별 공개 목록. 정렬은 호출 측 Pageable(created_at DESC, id DESC)로 고정한다. */
    Page<WorkReview> findByWorkId(Long workId, Pageable pageable);

    /** 내 리뷰 목록. 정렬은 호출 측 Pageable(created_at DESC, id DESC)로 고정한다 */
    Page<WorkReview> findByUserId(Long userId, Pageable pageable);

    long countByUserId(Long userId);

    /**
     * 작성. 이미 리뷰가 있으면 아무것도 하지 않고 0을 돌려준다(유니크 위반 예외 없음, 트랜잭션 유지).
     * 다른 트랜잭션의 미커밋 INSERT·DELETE 와 충돌하면 그 결과를 기다린 뒤 판단한다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO work_reviews (user_id, work_id, body, contains_spoiler, version, created_at, updated_at)
            VALUES (:userId, :workId, :body, :containsSpoiler, 0, now(), now())
            ON CONFLICT ON CONSTRAINT uk_work_reviews_user_work DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("userId") Long userId, @Param("workId") Long workId,
            @Param("body") String body, @Param("containsSpoiler") boolean containsSpoiler);

    /**
     * 현재 리뷰를 행 잠금과 함께 읽는다. 잠금은 커밋까지 유지되어 확인과 변경 사이에 다른 수정·삭제가 끼어들지 못한다.
     * 영속성 컨텍스트에 남은 엔티티를 돌려받지 않도록 엔티티가 아닌 값(projection)으로 읽는다.
     */
    @Query(value = """
            SELECT id AS "id", body AS "body", contains_spoiler AS "containsSpoiler", version AS "version"
            FROM work_reviews
            WHERE user_id = :userId AND work_id = :workId
            FOR UPDATE
            """, nativeQuery = true)
    Optional<LockedReview> lockByUserIdAndWorkId(@Param("userId") Long userId, @Param("workId") Long workId);

    /** 잠금으로 확인한 리뷰의 내용 변경. 버전 +1, updated_at 은 되돌아가지 않는다(GREATEST). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE work_reviews
            SET body = :body,
                contains_spoiler = :containsSpoiler,
                version = version + 1,
                updated_at = GREATEST(updated_at, now())
            WHERE id = :id
            """, nativeQuery = true)
    int updateContent(@Param("id") Long id, @Param("body") String body,
            @Param("containsSpoiler") boolean containsSpoiler);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "DELETE FROM work_reviews WHERE id = :id", nativeQuery = true)
    int deleteReview(@Param("id") Long id);

    interface LockedReview {
        Long getId();

        String getBody();

        Boolean getContainsSpoiler();

        Long getVersion();
    }

    /**
     * 최근 공개 리뷰(홈). 작성 시각 DESC, id DESC 로 순서를 고정한다.
     * 숨김 작품의 리뷰가 개수를 잡아먹지 않도록 작품 공개 상태(works.visibility)를 같은 SQL 에서 거른다.
     * 작품명 등 표시 정보는 WorkService 가 다시 공개 작품만 골라 붙인다.
     */
    @Query(value = """
            SELECT r.* FROM work_reviews r
            JOIN works w ON w.id = r.work_id
            WHERE w.visibility = 'PUBLIC'
            ORDER BY r.created_at DESC, r.id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<WorkReview> findRecentOfPublicWorks(@Param("limit") int limit);

    /** 작품별 리뷰 수(리뷰가 있는 작품만). uk_work_reviews_user_work 로 사용자당 한 건이다 */
    @Query(value = """
            SELECT work_id AS "workId", COUNT(*) AS "reviewCount"
            FROM work_reviews
            WHERE work_id IN (:workIds)
            GROUP BY work_id
            """, nativeQuery = true)
    List<WorkReviewCount> countByWorkIds(@Param("workIds") Collection<Long> workIds);

    interface WorkReviewCount {
        Long getWorkId();

        Long getReviewCount();
    }
}
