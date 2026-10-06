package com.manhwanet.work.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * 작품 제목. 대표 제목과 대체 제목을 모두 이 엔티티로 관리한다.
 */
@Entity
@Table(name = "work_titles")
public class WorkTitle {

    public static final int TITLE_MAX_LENGTH = 255;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "work_id", nullable = false)
    private Work work;

    @Column(name = "title", nullable = false, length = TITLE_MAX_LENGTH)
    private String title;

    @Column(name = "language_code", nullable = false, length = LanguageTags.MAX_LENGTH)
    private String languageCode;

    @Column(name = "is_primary", nullable = false)
    private boolean primary;

    protected WorkTitle() {
    }

    WorkTitle(Work work, String title, String languageCode, boolean primary) {
        this.work = work;
        this.title = title;
        this.languageCode = languageCode;
        this.primary = primary;
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getLanguageCode() {
        return languageCode;
    }

    public boolean isPrimary() {
        return primary;
    }
}
