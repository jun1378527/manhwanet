package com.manhwanet.work.domain;

import java.util.Collection;
import java.util.List;
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
}
