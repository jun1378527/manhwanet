package com.manhwanet.user.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    /** email 은 정규화된 값으로 조회한다. */
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /** ID 목록의 닉네임만 한 번에 읽는다. 엔티티(비밀번호 해시 포함)를 불러오지 않는다. */
    @Query("select u.id as id, u.nickname as nickname from User u where u.id in :ids")
    List<UserNickname> findNicknamesByIdIn(@Param("ids") Collection<Long> ids);

    interface UserNickname {
        Long getId();

        String getNickname();
    }
}
