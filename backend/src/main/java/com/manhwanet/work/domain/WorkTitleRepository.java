package com.manhwanet.work.domain;

import java.util.Collection;
import java.util.List;
import org.springframework.data.repository.Repository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkTitleRepository extends Repository<WorkTitle, Long> {

    /** 목록 조회: 페이지에 포함된 작품들의 대표 제목 일괄 조회 */
    @Query("""
            select new com.manhwanet.work.domain.PrimaryTitleView(t.work.id, t.title, t.languageCode)
            from WorkTitle t
            where t.work.id in :workIds and t.primary = true
            """)
    List<PrimaryTitleView> findPrimaryTitles(@Param("workIds") Collection<Long> workIds);
}
