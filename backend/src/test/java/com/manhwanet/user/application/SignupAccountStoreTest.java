package com.manhwanet.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.manhwanet.global.error.BusinessException;
import com.manhwanet.global.error.ErrorCode;
import com.manhwanet.user.domain.User;
import com.manhwanet.user.domain.UserRepository;
import com.manhwanet.user.domain.UserRole;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class SignupAccountStoreTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final SignupAccountStore store = new SignupAccountStore(userRepository);

    @Test
    void emailUniqueConstraintBecomesTheDuplicateResponse() {
        User user = User.create("a@example.com", "hash", "독자", UserRole.USER);
        when(userRepository.saveAndFlush(user)).thenThrow(violation("uk_users_email"));

        assertThatThrownBy(() -> store.insert(user))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> {
                    BusinessException error = (BusinessException) thrown;
                    assertThat(error.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_RESOURCE);
                    assertThat(error.getMessage()).isEqualTo("이미 가입된 이메일입니다.");
                });
    }

    @Test
    void otherConstraintViolationsPropagateAndAreNotReportedAsDuplicates() {
        User user = User.create("a@example.com", "hash", "독자", UserRole.USER);
        DataIntegrityViolationException violation = violation("ck_users_role");
        when(userRepository.saveAndFlush(user)).thenThrow(violation);

        assertThatThrownBy(() -> store.insert(user)).isSameAs(violation);
    }

    private static DataIntegrityViolationException violation(String constraint) {
        return new DataIntegrityViolationException("constraint",
                new ConstraintViolationException("constraint", null, constraint));
    }
}
