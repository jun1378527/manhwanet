package com.manhwanet.work.application;

import java.util.Collection;
import java.util.Map;

/**
 * 작품 목록·상세에 넣을 공개 리뷰 수 조회.
 *
 * <p>{@link WorkRatingSummaryQuery} 와 같은 이유로 work 가 필요한 기능 하나만 정의하고 review 가 구현한다
 * (review → work 의존이 있으므로 work 가 review 에 직접 의존하면 도메인 순환이 생긴다). 구현체는 하나다.
 */
public interface WorkReviewCountQuery {

    /**
     * @param workIds 셀 작품 ID (비어 있으면 SQL 을 실행하지 않는다)
     * @return 리뷰가 하나 이상 있는 작품만 담는다. 없는 작품은 호출 측이 0 으로 채운다.
     */
    Map<Long, Long> countByWorkIds(Collection<Long> workIds);
}
