package com.manhwanet.auth.application;

import com.manhwanet.user.domain.UserRole;
import java.io.Serial;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * 세션에 저장되는 로그인 사용자 정보. 인증이 끝나면 비밀번호 해시를 지운다(eraseCredentials).
 */
public final class AuthenticatedUser implements UserDetails, CredentialsContainer {

    @Serial
    private static final long serialVersionUID = 1L;

    private final Long id;
    private final String email;
    private final UserRole role;
    private String passwordHash;

    public AuthenticatedUser(Long id, String email, UserRole role, String passwordHash) {
        this.id = id;
        this.email = email;
        this.role = role;
        this.passwordHash = passwordHash;
    }

    public Long id() {
        return id;
    }

    public UserRole role() {
        return role;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(role.authority()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public void eraseCredentials() {
        this.passwordHash = null;
    }

    @Override
    public String toString() {
        return "AuthenticatedUser[id=" + id + ", role=" + role + "]";
    }
}
