package com.manhwanet.user.domain;

import com.manhwanet.global.jpa.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;

@Entity
@Table(name = "users")
public class User extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "email", nullable = false, length = EmailAddresses.MAX_LENGTH)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "nickname", nullable = false, length = Nicknames.MAX_LENGTH)
    private String nickname;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private UserRole role;

    protected User() {
    }

    private User(String email, String passwordHash, String nickname, UserRole role) {
        this.email = Objects.requireNonNull(email);
        this.passwordHash = Objects.requireNonNull(passwordHash);
        this.nickname = Objects.requireNonNull(nickname);
        this.role = Objects.requireNonNull(role);
    }

    /**
     * @param email        정규화된 이메일
     * @param passwordHash PasswordEncoder 로 만든 해시 (평문 금지)
     * @param nickname     정규화된 닉네임
     */
    public static User create(String email, String passwordHash, String nickname, UserRole role) {
        return new User(email, passwordHash, nickname, role);
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getNickname() {
        return nickname;
    }

    public UserRole getRole() {
        return role;
    }
}
