package com.manhwanet.work.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * 작품-작가 연결(크레딧). 작가는 creator 도메인 엔티티가 아니라 creatorId 값으로 참조한다.
 * DB에는 creators(id) 외래 키가 있다.
 */
@Entity
@Table(name = "work_creators")
public class WorkCreator {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "work_id", nullable = false)
    private Work work;

    @Column(name = "creator_id", nullable = false)
    private Long creatorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private CreatorRole role;

    protected WorkCreator() {
    }

    WorkCreator(Work work, Long creatorId, CreatorRole role) {
        this.work = work;
        this.creatorId = creatorId;
        this.role = role;
    }

    public Long getId() {
        return id;
    }

    public Long getCreatorId() {
        return creatorId;
    }

    public CreatorRole getRole() {
        return role;
    }
}
