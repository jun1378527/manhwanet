package com.manhwanet.user.application;

import com.manhwanet.global.error.BusinessException;
import com.manhwanet.global.error.ConstraintViolations;
import com.manhwanet.global.error.ErrorCode;
import com.manhwanet.user.domain.User;
import com.manhwanet.user.domain.UserRepository;
import com.manhwanet.user.dto.UserResponse;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 가입의 DB 접근만 맡는다. 조회와 저장은 서로 다른 짧은 트랜잭션이고, 메서드가 끝나면 연결을 돌려준다.
 * {@link UserService#signup}은 이 빈을 호출한다. 같은 빈 안에서 호출하면 {@code @Transactional}이 적용되지 않는다.
 */
@Service
public class SignupAccountStore {

    /** 409 로 변환하는 중복 제약. 그 밖의 무결성 오류는 다시 던진다. */
    static final Set<String> DUPLICATE_CONSTRAINTS = Set.of("uk_users_email");

    private final UserRepository userRepository;

    public SignupAccountStore(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** 정규화된 이메일이 이미 있으면 true. 조회가 끝나면 트랜잭션과 연결을 반환한다. */
    @Transactional(readOnly = true)
    public boolean emailExists(String normalizedEmail) {
        return userRepository.existsByEmail(normalizedEmail);
    }

    /**
     * 회원을 저장하고 커밋한 뒤 응답을 돌려준다. 커밋 전에 반환하지 않는다.
     * {@code uk_users_email} 위반만 409 로 바꾸고, 다른 제약 위반은 그대로 전파해 롤백한다.
     */
    @Transactional
    public UserResponse insert(User user) {
        try {
            return UserResponse.from(userRepository.saveAndFlush(user));
        } catch (DataIntegrityViolationException e) {
            if (ConstraintViolations.isOneOf(e, DUPLICATE_CONSTRAINTS)) {
                throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE, "이미 가입된 이메일입니다.");
            }
            throw e;
        }
    }
}
