package com.manhwanet.work.domain;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface WorkTitleRepository extends Repository<WorkTitle, Long> {

    /** 목록 조회: 페이지에 포함된 작품들의 대표 제목 일괄 조회 */
    @Query("""
            select new com.manhwanet.work.domain.PrimaryTitleView(t.work.id, t.title, t.languageCode)
            from WorkTitle t
            where t.work.id in :workIds and t.primary = true
            """)
    List<PrimaryTitleView> findPrimaryTitles(@Param("workIds") Collection<Long> workIds);

    /** 관리자 수정: 현재 제목(잠근 작품의 것). 비교용 값만 읽는다. */
    @Query("""
            select new com.manhwanet.work.domain.WorkTitleDraft(t.title, t.languageCode, t.primary)
            from WorkTitle t
            where t.work.id = :workId
            """)
    List<WorkTitleDraft> findDrafts(@Param("workId") Long workId);

    /** 관리자 수정: 제목 전체 교체의 삭제 단계. 같은 트랜잭션에서 insertTitle 이 뒤따른다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "DELETE FROM work_titles WHERE work_id = :workId", nativeQuery = true)
    int deleteByWorkId(@Param("workId") Long workId);

    @Modifying
    @Query(value = """
            INSERT INTO work_titles (work_id, title, language_code, is_primary)
            VALUES (:workId, :title, :languageCode, :primary)
            """, nativeQuery = true)
    int insertTitle(@Param("workId") Long workId, @Param("title") String title,
            @Param("languageCode") String languageCode, @Param("primary") boolean primary);
}
