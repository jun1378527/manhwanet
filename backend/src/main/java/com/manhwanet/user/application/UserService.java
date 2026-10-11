package com.manhwanet.user.application;

import com.manhwanet.global.error.BusinessException;
import com.manhwanet.global.error.ErrorCode;
import com.manhwanet.global.error.InvalidInputException;
import com.manhwanet.global.response.ApiError;
import com.manhwanet.global.security.PasswordHashGate;
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
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final PasswordHashGate passwordHashGate;
    private final SignupAccountStore signupAccountStore;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder, PasswordPolicy passwordPolicy,
            PasswordHashGate passwordHashGate, SignupAccountStore signupAccountStore) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.passwordHashGate = passwordHashGate;
        this.signupAccountStore = signupAccountStore;
    }

    /**
     * 일반 회원 가입. 요청 내용과 관계없이 항상 USER 로 만든다.
     * 입력 검증, 게이트 대기, 비밀번호 해시는 저장 트랜잭션 밖에서 한다.
     * 사전 중복 확인과 저장은 {@link SignupAccountStore}의 짧은 트랜잭션이다.
     * 자리를 얻지 못하면 503 AUTH_BUSY 이고 아무것도 저장하지 않는다.
     * 중복 이메일은 해시 없이 409 다. 동시에 같은 이메일이 저장되면 DB 유니크 제약이 409 가 된다.
     * 이 메서드가 정상 반환한 뒤에만 호출 측이 자동 로그인한다.
     * (관리자 생성 명령은 {@link AdminAccountService}가 게이트 없이 처리한다)
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public UserResponse signup(SignupRequest request) {
        ValidatedAccount account = validate(request.email(), request.password(), request.nickname());
        if (signupAccountStore.emailExists(account.email())) {
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE, "이미 가입된 이메일입니다.");
        }
        String passwordHash;
        try (PasswordHashGate.Permit permit = passwordHashGate.acquire()) {
            passwordHash = passwordEncoder.encode(account.password());
        }
        return signupAccountStore.insert(
                User.create(account.email(), passwordHash, account.nickname(), UserRole.USER));
    }

    public UserResponse getUser(Long userId) {
        return userRepository.findById(userId)
                .map(UserResponse::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
    }

    /**
     * 사용자 ID 목록의 현재 닉네임을 SQL 1개로 일괄 조회한다. 다른 도메인(review)이 작성자 표시에 쓴다.
     * 빈 입력은 SQL 없이 빈 결과를 돌려준다. 없는 ID는 결과에 넣지 않는다. 이메일·역할 등 다른 정보는 주지 않는다.
     */
    public Map<Long, String> findNicknames(Collection<Long> userIds) {
        Set<Long> ids = new LinkedHashSet<>();
        for (Long userId : userIds) {
            if (userId != null) {
                ids.add(userId);
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> nicknames = new HashMap<>();
        for (UserRepository.UserNickname row : userRepository.findNicknamesByIdIn(ids)) {
            nicknames.put(row.getId(), Objects.requireNonNull(row.getNickname()));
        }
        return Map.copyOf(nicknames);
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

    /** 정규화된 계정 입력. password 는 NFKC 정규화된 평문이므로 로그에 남기지 않는다. */
    record ValidatedAccount(String email, String password, String nickname) {
        @Override
        public String toString() {
            return "ValidatedAccount[email=" + email + ", nickname=" + nickname + "]";
        }
    }
}
