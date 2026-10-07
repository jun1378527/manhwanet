package com.manhwanet.rating.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkRatingRepository extends JpaRepository<WorkRating, Long> {

    Optional<WorkRating> findByUserIdAndWorkId(Long userId, Long workId);

    /**
     * 등록·수정을 한 문장으로 처리한다(원자적 upsert). 동시 요청에서도 유니크 위반 없이 INSERT 또는 UPDATE 로 끝난다.
     * 시각 정책 (docs/rating-design-draft.md §2.1)
     * - 등록: created_at = updated_at = now()
     * - 다른 점수로 수정: updated_at = GREATEST(기존 updated_at, now()). created_at 유지
     * - 같은 점수: 시각 유지
     * now() 는 트랜잭션 시작 시각이라 동시 요청에서 되돌아갈 수 있어 GREATEST 로 막는다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO work_ratings (user_id, work_id, score_half, created_at, updated_at)
            VALUES (:userId, :workId, :scoreHalf, now(), now())
            ON CONFLICT ON CONSTRAINT uk_work_ratings_user_work DO UPDATE
            SET score_half = EXCLUDED.score_half,
                updated_at = CASE WHEN work_ratings.score_half = EXCLUDED.score_half
                                  THEN work_ratings.updated_at
                                  ELSE GREATEST(work_ratings.updated_at, now()) END
            """, nativeQuery = true)
    int upsert(@Param("userId") Long userId, @Param("workId") Long workId, @Param("scoreHalf") short scoreHalf);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "DELETE FROM work_ratings WHERE user_id = :userId AND work_id = :workId", nativeQuery = true)
    int deleteByUserIdAndWorkId(@Param("userId") Long userId, @Param("workId") Long workId);

    /** 작품별 별점 수와 score_half 합계. 두 값은 같은 문장(같은 스냅샷)에서 읽는다. */
    @Query("""
            select r.workId as workId, count(r) as ratingCount, sum(r.scoreHalf) as scoreHalfSum
            from WorkRating r
            where r.workId in :workIds
            group by r.workId
            """)
    List<WorkRatingStats> summarize(@Param("workIds") Collection<Long> workIds);
}
