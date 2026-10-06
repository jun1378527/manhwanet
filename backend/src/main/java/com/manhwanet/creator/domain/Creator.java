package com.manhwanet.creator.domain;

import com.manhwanet.global.jpa.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;

/**
 * 작가. 동명이인을 허용하므로 name 은 유니크가 아니다.
 */
@Entity
@Table(name = "creators")
public class Creator extends BaseTimeEntity {

    public static final int NAME_MAX_LENGTH = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = NAME_MAX_LENGTH)
    private String name;

    @Column(name = "original_name", length = NAME_MAX_LENGTH)
    private String originalName;

    protected Creator() {
    }

    private Creator(String name, String originalName) {
        this.name = Objects.requireNonNull(name);
        this.originalName = originalName;
    }

    /**
     * @param name         정규화(공백 제거·길이 검증)된 대표 표기
     * @param originalName 정규화된 원어 표기 또는 null
     */
    public static Creator create(String name, String originalName) {
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (originalName != null && originalName.isBlank()) {
            throw new IllegalArgumentException("originalName must be null or not blank");
        }
        return new Creator(name, originalName);
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getOriginalName() {
        return originalName;
    }
}
