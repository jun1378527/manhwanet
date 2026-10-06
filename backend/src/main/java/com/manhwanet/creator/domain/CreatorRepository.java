package com.manhwanet.creator.domain;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CreatorRepository extends JpaRepository<Creator, Long> {

    @Query("select c.id from Creator c where c.id in :ids")
    List<Long> findExistingIds(@Param("ids") Collection<Long> ids);
}
