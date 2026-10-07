package com.manhwanet.work.application;

import com.manhwanet.work.dto.RatingSummaryResponse;
import java.util.Collection;
import java.util.Map;

/**
 * 작품 목록·상세에 넣을 공개 별점 집계 조회.
 *
 * <p>work 가 필요한 기능 하나만 정의하고 rating 이 구현한다. work 가 rating 에 의존하지 않게 해
 * 도메인 순환(rating → work → rating)을 막기 위한 인터페이스이며, 구현체는 하나다.
 */
public interface WorkRatingSummaryQuery {

    /**
     * @param workIds 집계할 작품 ID (비어 있으면 SQL 을 실행하지 않는다)
     * @return 별점이 하나 이상 있는 작품만 담는다. 없는 작품은 호출 측이 {@link RatingSummaryResponse#EMPTY}로 채운다.
     */
    Map<Long, RatingSummaryResponse> findByWorkIds(Collection<Long> workIds);
}
