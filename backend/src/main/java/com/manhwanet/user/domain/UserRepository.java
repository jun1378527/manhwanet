package com.manhwanet.user.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    /** email 은 정규화된 값으로 조회한다. */
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
