package com.manhwanet.work.domain;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkRepository extends JpaRepository<Work, Long> {

    /** 상세 조회: 작품 + 제목 (컬렉션 1개만 fetch join) */
    @Query("select w from Work w left join fetch w.titles where w.id = :id")
    Optional<Work> findWithTitlesById(@Param("id") Long id);

    /**
     * 관리자 수정용: 작품 행을 잠그고 현재 값을 읽는다. 잠금은 커밋까지 유지되어 같은 작품의 다른 수정을 기다리게 한다.
     * FOR NO KEY UPDATE 는 키를 바꾸지 않는 잠금이라, 이 작품을 참조하는 별점·리뷰 작성(FK 의 KEY SHARE 잠금)은 막지 않는다.
     * 영속성 컨텍스트의 엔티티를 돌려받지 않도록 값(projection)으로 읽는다.
     */
    @Query(value = """
            SELECT id AS "id", version AS "version", work_format AS "workFormat",
                   origin_country AS "originCountry", description AS "description", visibility AS "visibility"
            FROM works
            WHERE id = :id
            FOR NO KEY UPDATE
            """, nativeQuery = true)
    Optional<LockedWork> lockById(@Param("id") Long id);

    /**
     * 핵심 정보 교체. version 이 기대값과 같을 때만 바꾸고 1 올린다. updated_at 은 되돌아가지 않는다(GREATEST).
     * 잠금으로 확인한 뒤 호출하므로 정상 흐름에서는 항상 1을 돌려준다(0이면 프로그래밍 오류).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE works
            SET work_format = :workFormat,
                origin_country = :originCountry,
                description = :description,
                version = version + 1,
                updated_at = GREATEST(updated_at, now())
            WHERE id = :id AND version = :expectedVersion
            """, nativeQuery = true)
    int updateCore(@Param("id") Long id, @Param("expectedVersion") long expectedVersion,
            @Param("workFormat") String workFormat, @Param("originCountry") String originCountry,
            @Param("description") String description);

    /**
     * 작품 하위 리소스(플랫폼 연결 등) 변경 시 작품 version 만 올린다(명세 S-3: 작품당 버전 하나).
     * 잠금으로 확인한 뒤 호출하므로 정상 흐름에서는 항상 1을 돌려준다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE works
            SET version = version + 1,
                updated_at = GREATEST(updated_at, now())
            WHERE id = :id AND version = :expectedVersion
            """, nativeQuery = true)
    int bumpVersion(@Param("id") Long id, @Param("expectedVersion") long expectedVersion);

    interface LockedWork {
        Long getId();

        Long getVersion();

        String getWorkFormat();

        String getOriginCountry();

        String getDescription();

        String getVisibility();
    }

    /** #41 공개 목록: PUBLIC 작품만 */
    Page<Work> findAllByVisibility(WorkVisibility visibility, Pageable pageable);

    /** #41 공개 상태(없으면 빈 값). 읽기 경로용 */
    @Query(value = "SELECT visibility FROM works WHERE id = :id", nativeQuery = true)
    Optional<String> findVisibility(@Param("id") Long id);

    /**
     * #41 별점·리뷰 쓰기 전 공개 상태 확인. FOR SHARE 로 읽어 커밋까지 숨김 전환(FOR NO KEY UPDATE)과 직렬화한다.
     * 숨기는 중에 새 별점·리뷰가 끼어들지 않는다.
     */
    @Query(value = "SELECT visibility FROM works WHERE id = :id FOR SHARE", nativeQuery = true)
    Optional<String> findVisibilityForShare(@Param("id") Long id);

    /** #41 공개 상태 변경. version 이 기대값과 같을 때만 바꾸고 1 올린다 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE works
            SET visibility = :visibility,
                version = version + 1,
                updated_at = GREATEST(updated_at, now())
            WHERE id = :id AND version = :expectedVersion
            """, nativeQuery = true)
    int updateVisibility(@Param("id") Long id, @Param("expectedVersion") long expectedVersion,
            @Param("visibility") String visibility);
}
