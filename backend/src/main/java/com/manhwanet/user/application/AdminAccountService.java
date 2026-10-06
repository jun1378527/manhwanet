package com.manhwanet.user.application;

import com.manhwanet.global.error.InvalidInputException;
import com.manhwanet.user.domain.User;
import com.manhwanet.user.domain.UserRepository;
import com.manhwanet.user.domain.UserRole;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 계정 생성. 별도 비웹 명령(CreateAdminCommand)에서만 호출한다.
 * <ul>
 *   <li>새 이메일 → ADMIN 생성</li>
 *   <li>같은 이메일의 ADMIN 존재 → 변경 없이 완료</li>
 *   <li>같은 이메일의 USER 존재 → 실패 (자동 승격하지 않음)</li>
 * </ul>
 */
@Service
public class AdminAccountService {

    private final UserService userService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AdminAccountService(UserService userService, UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userService = userService;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public AdminCreationResult createAdmin(String rawEmail, String rawPassword, String rawNickname) {
        UserService.ValidatedAccount account;
        try {
            account = userService.validate(rawEmail, rawPassword, rawNickname);
        } catch (InvalidInputException e) {
            throw new AdminCreationException("입력값이 올바르지 않습니다: " + e.getFieldErrors().stream()
                    .map(error -> error.field() + " - " + error.reason())
                    .collect(Collectors.joining("; ")));
        }

        Optional<User> existing = userRepository.findByEmail(account.email());
        if (existing.isPresent()) {
            if (existing.get().getRole() == UserRole.ADMIN) {
                return AdminCreationResult.ALREADY_ADMIN;
            }
            throw new AdminCreationException(
                    "같은 이메일의 일반 사용자(USER)가 이미 있습니다. 자동으로 ADMIN 으로 승격하지 않습니다.");
        }

        try {
            userRepository.saveAndFlush(User.create(account.email(), passwordEncoder.encode(account.password()),
                    account.nickname(), UserRole.ADMIN));
        } catch (DataIntegrityViolationException e) {
            throw new AdminCreationException("같은 이메일의 계정이 동시에 생성되었습니다. 다시 실행해 주세요.");
        }
        return AdminCreationResult.CREATED;
    }
}
