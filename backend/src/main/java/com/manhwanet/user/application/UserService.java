package com.manhwanet.user.application;

import com.manhwanet.global.error.BusinessException;
import com.manhwanet.global.error.ConstraintViolations;
import com.manhwanet.global.error.ErrorCode;
import com.manhwanet.global.error.InvalidInputException;
import com.manhwanet.global.response.ApiError;
import com.manhwanet.user.domain.EmailAddresses;
import com.manhwanet.user.domain.Nicknames;
import com.manhwanet.user.domain.PasswordPolicy;
import com.manhwanet.user.domain.Passwords;
import com.manhwanet.user.domain.User;
import com.manhwanet.user.domain.UserRepository;
import com.manhwanet.user.domain.UserRole;
import com.manhwanet.user.dto.SignupRequest;
import com.manhwanet.user.dto.UserResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class UserService {

    /** 409 로 변환하는 중복 제약. 그 밖의 무결성 오류는 다시 던진다. */
    static final Set<String> DUPLICATE_CONSTRAINTS = Set.of("uk_users_email");

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder, PasswordPolicy passwordPolicy) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
    }

    /** 일반 회원 가입. 요청 내용과 관계없이 항상 USER 로 만든다. */
    @Transactional
    public UserResponse signup(SignupRequest request) {
        ValidatedAccount account = validate(request.email(), request.password(), request.nickname());
        if (userRepository.existsByEmail(account.email())) {
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE, "이미 가입된 이메일입니다.");
        }
        User user = save(User.create(account.email(), passwordEncoder.encode(account.password()), account.nickname(),
                UserRole.USER));
        return UserResponse.from(user);
    }

    public UserResponse getUser(Long userId) {
        return userRepository.findById(userId)
                .map(UserResponse::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
    }

    /** @param normalizedEmail {@link EmailAddresses#normalize} 결과 */
    public Optional<UserCredentials> findCredentials(String normalizedEmail) {
        return userRepository.findByEmail(normalizedEmail)
                .map(user -> new UserCredentials(user.getId(), user.getEmail(), user.getPasswordHash(), user.getRole()));
    }

    /**
     * 이메일·비밀번호·닉네임을 정규화하고 정책을 검사한다. 위반이 있으면 모두 모아 InvalidInputException 을 던진다.
     */
    ValidatedAccount validate(String rawEmail, String rawPassword, String rawNickname) {
        List<ApiError.FieldError> errors = new ArrayList<>();
        String email = null;
        String nickname = null;
        try {
            email = EmailAddresses.normalizeAndValidate(rawEmail);
        } catch (IllegalArgumentException e) {
            errors.add(new ApiError.FieldError("email", e.getMessage()));
        }
        try {
            nickname = Nicknames.normalizeAndValidate(rawNickname);
        } catch (IllegalArgumentException e) {
            errors.add(new ApiError.FieldError("nickname", e.getMessage()));
        }
        String password = Passwords.normalize(rawPassword);
        passwordPolicy.violation(password, email, nickname)
                .ifPresent(reason -> errors.add(new ApiError.FieldError("password", reason)));
        if (!errors.isEmpty()) {
            throw new InvalidInputException(errors);
        }
        return new ValidatedAccount(email, password, nickname);
    }

    private User save(User user) {
        try {
            return userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            if (ConstraintViolations.isOneOf(e, DUPLICATE_CONSTRAINTS)) {
                throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE, "이미 가입된 이메일입니다.");
            }
            throw e;
        }
    }

    /** 정규화된 계정 입력. password 는 NFKC 정규화된 평문이므로 로그에 남기지 않는다. */
    record ValidatedAccount(String email, String password, String nickname) {
        @Override
        public String toString() {
            return "ValidatedAccount[email=" + email + ", nickname=" + nickname + "]";
        }
    }
}
