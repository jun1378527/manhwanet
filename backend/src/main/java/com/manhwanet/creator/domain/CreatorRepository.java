package com.manhwanet.creator.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CreatorRepository extends JpaRepository<Creator, Long> {

    @Query("select c.id from Creator c where c.id in :ids")
    List<Long> findExistingIds(@Param("ids") Collection<Long> ids);

    /**
     * 관리자 수정용: 작가 행을 잠그고 현재 값을 읽는다.
     * FOR NO KEY UPDATE 는 작품 크레딧 추가(FK 의 KEY SHARE 잠금)를 막지 않는다.
     */
    @Query(value = """
            SELECT id AS "id", version AS "version", name AS "name", original_name AS "originalName"
            FROM creators
            WHERE id = :id
            FOR NO KEY UPDATE
            """, nativeQuery = true)
    Optional<LockedCreator> lockById(@Param("id") Long id);

    /** 이름 교체. version 이 기대값과 같을 때만 바꾸고 1 올린다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE creators
            SET name = :name,
                original_name = :originalName,
                version = version + 1,
                updated_at = GREATEST(updated_at, now())
            WHERE id = :id AND version = :expectedVersion
            """, nativeQuery = true)
    int updateNames(@Param("id") Long id, @Param("expectedVersion") long expectedVersion,
            @Param("name") String name, @Param("originalName") String originalName);

    /** 관리자 작가 상세: 이 작가를 크레딧에 둔 작품 수(작품 도메인에 의존하지 않도록 SQL 로 센다) */
    @Query(value = "SELECT count(DISTINCT work_id) FROM work_creators WHERE creator_id = :creatorId", nativeQuery = true)
    long countLinkedWorks(@Param("creatorId") Long creatorId);

    interface LockedCreator {
        Long getId();

        Long getVersion();

        String getName();

        String getOriginalName();
    }
}
