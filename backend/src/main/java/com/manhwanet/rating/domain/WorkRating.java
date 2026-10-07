package com.manhwanet.rating.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Immutable;

/**
 * 별점 읽기 모델. 쓰기(INSERT ... ON CONFLICT, DELETE)와 시각 설정은 {@link WorkRatingRepository}의 SQL로만 한다.
 * 그래서 JPA Auditing(BaseTimeEntity)을 쓰지 않고 Hibernate 가 수정하지 않도록 @Immutable 로 둔다.
 * 다른 도메인은 연관관계가 아니라 ID 값(userId, workId)으로 참조한다.
 */
@Entity
@Immutable
@Table(name = "work_ratings")
public class WorkRating {

    @Id
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "work_id", nullable = false)
    private Long workId;

    @Column(name = "score_half", nullable = false)
    private short scoreHalf;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WorkRating() {
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getWorkId() {
        return workId;
    }

    public short getScoreHalf() {
        return scoreHalf;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
