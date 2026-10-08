package com.manhwanet.review.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Immutable;

/**
 * 리뷰 읽기 모델. 쓰기(INSERT ... ON CONFLICT DO NOTHING, 잠금 후 UPDATE·DELETE)와 시각·버전 설정은
 * {@link WorkReviewRepository}의 SQL로만 한다. 그래서 JPA Auditing·{@code @Version}을 쓰지 않고 {@code @Immutable}로 둔다.
 * 다른 도메인은 연관관계가 아니라 ID 값(userId, workId)으로 참조한다.
 */
@Entity
@Immutable
@Table(name = "work_reviews")
public class WorkReview {

    @Id
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "work_id", nullable = false)
    private Long workId;

    @Column(name = "body", nullable = false)
    private String body;

    @Column(name = "contains_spoiler", nullable = false)
    private boolean containsSpoiler;

    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WorkReview() {
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

    public String getBody() {
        return body;
    }

    public boolean isContainsSpoiler() {
        return containsSpoiler;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** 작성 후 내용이 바뀐 적이 있는지. 같은 내용 저장은 시각을 바꾸지 않는다. */
    public boolean isEdited() {
        return updatedAt.isAfter(createdAt);
    }
}
