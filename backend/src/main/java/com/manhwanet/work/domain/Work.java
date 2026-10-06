package com.manhwanet.work.domain;

import com.manhwanet.global.jpa.BaseTimeEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 작품. WorkTitle, WorkCreator 를 포함하는 Aggregate Root.
 */
@Entity
@Table(name = "works")
public class Work extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "work_format", nullable = false, length = 20)
    private WorkFormat workFormat;

    @Column(name = "origin_country", length = 2)
    private String originCountry;

    @Column(name = "description")
    private String description;

    @OneToMany(mappedBy = "work", cascade = CascadeType.PERSIST)
    private List<WorkTitle> titles = new ArrayList<>();

    @OneToMany(mappedBy = "work", cascade = CascadeType.PERSIST)
    private List<WorkCreator> creators = new ArrayList<>();

    protected Work() {
    }

    private Work(WorkFormat workFormat, String originCountry, String description) {
        this.workFormat = Objects.requireNonNull(workFormat);
        this.originCountry = originCountry;
        this.description = description;
    }

    /**
     * 작품을 생성한다. 입력값은 서비스에서 검증·정규화된 값이어야 하며,
     * 아래 불변식을 위반하면 프로그래밍 오류로 보고 IllegalArgumentException 을 던진다.
     * <ul>
     *   <li>제목 1개 이상, 대표 제목 정확히 1개</li>
     *   <li>같은 언어·같은 제목 중복 없음</li>
     *   <li>작가 연결 1개 이상, 같은 (creatorId, role) 중복 없음</li>
     * </ul>
     */
    public static Work create(
            WorkFormat workFormat,
            String originCountry,
            String description,
            List<WorkTitleDraft> titles,
            List<WorkCreditDraft> credits) {
        validateTitles(titles);
        validateCredits(credits);

        Work work = new Work(workFormat, originCountry, description);
        titles.forEach(title -> work.titles.add(
                new WorkTitle(work, title.title(), title.languageCode(), title.primary())));
        credits.forEach(credit -> work.creators.add(
                new WorkCreator(work, credit.creatorId(), credit.role())));
        return work;
    }

    private static void validateTitles(List<WorkTitleDraft> titles) {
        if (titles == null || titles.isEmpty()) {
            throw new IllegalArgumentException("work must have at least one title");
        }
        long primaryCount = titles.stream().filter(WorkTitleDraft::primary).count();
        if (primaryCount != 1) {
            throw new IllegalArgumentException("work must have exactly one primary title but was " + primaryCount);
        }
        Set<List<String>> keys = new HashSet<>();
        for (WorkTitleDraft title : titles) {
            if (!keys.add(List.of(title.languageCode(), title.title()))) {
                throw new IllegalArgumentException("duplicate title for the same language: " + title.languageCode());
            }
        }
    }

    private static void validateCredits(List<WorkCreditDraft> credits) {
        if (credits == null || credits.isEmpty()) {
            throw new IllegalArgumentException("work must have at least one creator");
        }
        if (new HashSet<>(credits).size() != credits.size()) {
            throw new IllegalArgumentException("duplicate creator role in work");
        }
    }

    public Long getId() {
        return id;
    }

    public WorkFormat getWorkFormat() {
        return workFormat;
    }

    public String getOriginCountry() {
        return originCountry;
    }

    public String getDescription() {
        return description;
    }

    public List<WorkTitle> getTitles() {
        return Collections.unmodifiableList(titles);
    }

    public List<WorkCreator> getCreators() {
        return Collections.unmodifiableList(creators);
    }
}
