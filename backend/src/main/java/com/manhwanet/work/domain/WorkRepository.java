package com.manhwanet.work.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkRepository extends JpaRepository<Work, Long> {

    /** 상세 조회: 작품 + 제목 (컬렉션 1개만 fetch join) */
    @Query("select w from Work w left join fetch w.titles where w.id = :id")
    Optional<Work> findWithTitlesById(@Param("id") Long id);
}
