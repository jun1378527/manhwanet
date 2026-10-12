package com.manhwanet.work.domain;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface WorkCreatorRepository extends Repository<WorkCreator, Long> {

    /** 목록 조회: 페이지에 포함된 작품들의 크레딧 일괄 조회 */
    @Query("""
            select new com.manhwanet.work.domain.CreditView(wc.work.id, wc.creatorId, wc.role)
            from WorkCreator wc
            where wc.work.id in :workIds
            """)
    List<CreditView> findCredits(@Param("workIds") Collection<Long> workIds);

    /** 관리자 수정: 현재 크레딧(잠근 작품의 것). 비교용 값만 읽는다. */
    @Query("""
            select new com.manhwanet.work.domain.WorkCreditDraft(wc.creatorId, wc.role)
            from WorkCreator wc
            where wc.work.id = :workId
            """)
    List<WorkCreditDraft> findDrafts(@Param("workId") Long workId);

    /** 관리자 수정: 크레딧 전체 교체의 삭제 단계. 같은 트랜잭션에서 insertCredit 이 뒤따른다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "DELETE FROM work_creators WHERE work_id = :workId", nativeQuery = true)
    int deleteByWorkId(@Param("workId") Long workId);

    @Modifying
    @Query(value = """
            INSERT INTO work_creators (work_id, creator_id, role)
            VALUES (:workId, :creatorId, :role)
            """, nativeQuery = true)
    int insertCredit(@Param("workId") Long workId, @Param("creatorId") Long creatorId, @Param("role") String role);
}
