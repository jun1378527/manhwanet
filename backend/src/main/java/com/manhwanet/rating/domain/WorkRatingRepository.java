package com.manhwanet.rating.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkRatingRepository extends JpaRepository<WorkRating, Long> {

    Optional<WorkRating> findByUserIdAndWorkId(Long userId, Long workId);

    /** 내 별점 목록. 정렬은 호출 측 Pageable(updated_at DESC, id DESC)로 고정한다. uk(user_id, work_id) 인덱스로 사용자 행을 찾는다 */
    Page<WorkRating> findByUserId(Long userId, Pageable pageable);

    long countByUserId(Long userId);

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

    /*
     * 공개 별점 순위(#홈). 별점이 하나 이상 있는 공개 작품만 순위에 넣는다.
     * 작품 공개 상태(works.visibility)는 작품 도메인의 값이지만, 페이지 크기·전체 수를 정확히 맞추려면 같은 SQL 에서
     * 걸러야 해서 이 조회만 works 를 함께 읽는다. 표시 정보는 WorkService 가 다시 공개 작품만 골라 만든다.
     * 평균은 화면과 같은 규칙(소수 첫째 자리 반올림, HALF_UP)으로 비교하고, 같으면 아래 순서로 순서를 고정한다.
     *   평균순:        평균 DESC → 별점 수 DESC → 작품 등록 시각 DESC → 작품 ID DESC
     *   평가 많은 순:  별점 수 DESC → 평균 DESC → 작품 등록 시각 DESC → 작품 ID DESC
     */

    @Query(value = """
            SELECT r.work_id AS "workId", COUNT(*) AS "ratingCount", SUM(r.score_half) AS "scoreHalfSum"
            FROM work_ratings r
            JOIN works w ON w.id = r.work_id
            WHERE w.visibility = 'PUBLIC'
            GROUP BY r.work_id, w.created_at
            ORDER BY ROUND(SUM(r.score_half)::numeric / (COUNT(*) * 2), 1) DESC, COUNT(*) DESC, w.created_at DESC, r.work_id DESC
            LIMIT :limit OFFSET :offset
            """, nativeQuery = true)
    List<WorkRatingStats> rankByAverage(@Param("limit") int limit, @Param("offset") long offset);

    @Query(value = """
            SELECT r.work_id AS "workId", COUNT(*) AS "ratingCount", SUM(r.score_half) AS "scoreHalfSum"
            FROM work_ratings r
            JOIN works w ON w.id = r.work_id
            WHERE w.visibility = 'PUBLIC'
            GROUP BY r.work_id, w.created_at
            ORDER BY COUNT(*) DESC, ROUND(SUM(r.score_half)::numeric / (COUNT(*) * 2), 1) DESC, w.created_at DESC, r.work_id DESC
            LIMIT :limit OFFSET :offset
            """, nativeQuery = true)
    List<WorkRatingStats> rankByCount(@Param("limit") int limit, @Param("offset") long offset);

    /** 순위에 들어가는 작품 수(별점이 하나 이상 있는 공개 작품) */
    @Query(value = """
            SELECT COUNT(DISTINCT r.work_id)
            FROM work_ratings r
            JOIN works w ON w.id = r.work_id
            WHERE w.visibility = 'PUBLIC'
            """, nativeQuery = true)
    long countRankedWorks();
}
