package com.manhwanet.auth.application;

import com.manhwanet.user.application.UserService;
import com.manhwanet.user.domain.EmailAddresses;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * 이메일로 로그인 정보를 읽는다. 사용자 정보는 user 도메인의 공개 서비스(UserService)로만 조회한다.
 */
@Service
public class UserAccountDetailsService implements UserDetailsService {

    private final UserService userService;

    public UserAccountDetailsService(UserService userService) {
        this.userService = userService;
    }

    @Override
    public UserDetails loadUserByUsername(String email) {
        return userService.findCredentials(EmailAddresses.normalize(email))
                .map(credentials -> new AuthenticatedUser(
                        credentials.id(), credentials.email(), credentials.role(), credentials.passwordHash()))
                .orElseThrow(() -> new UsernameNotFoundException("user not found"));
    }
}
