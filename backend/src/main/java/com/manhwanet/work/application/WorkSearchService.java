package com.manhwanet.work.application;

import com.manhwanet.global.response.PageResponse;
import com.manhwanet.work.domain.WorkSearchRepository;
import com.manhwanet.work.domain.WorkSearchRepository.Filter;
import com.manhwanet.work.domain.WorkSearchRepository.SortKey;
import com.manhwanet.work.dto.WorkFilterOptionsResponse;
import com.manhwanet.work.dto.WorkSummaryResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 전체 공개 작품 대상 찾기(검색·필터·정렬). 현재 페이지가 아니라 전체 공개 작품에서 조건·순서를 정하고 page·size 로 자른다.
 * 건수·ID·표시 정보가 같은 스냅샷을 보도록 REPEATABLE READ 로 읽는다.
 */
@Service
public class WorkSearchService {

    private final WorkSearchRepository workSearchRepository;
    private final WorkService workService;
    private final Clock clock = Clock.systemUTC();

    public WorkSearchService(WorkSearchRepository workSearchRepository, WorkService workService) {
        this.workSearchRepository = workSearchRepository;
        this.workService = workService;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<WorkSummaryResponse> search(WorkSearchCriteria criteria, Pageable pageable) {
        Filter filter = new Filter(
                criteria.likePattern(),
                criteria.format() == null ? null : criteria.format().name(),
                criteria.platform());
        long total = workSearchRepository.count(filter);
        if (total == 0 || pageable.getOffset() >= total) {
            return PageResponse.of(new PageImpl<>(List.of(), pageable, total), List.of());
        }
        Instant activitySince = clock.instant().minus(Duration.ofDays(WorkSearchCriteria.POPULAR_WINDOW_DAYS));
        List<Long> ids = workSearchRepository.findIds(filter, sortKey(criteria.order()), activitySince,
                pageable.getPageSize(), pageable.getOffset());
        List<WorkSummaryResponse> content = workService.getPublicSummaries(ids);
        return PageResponse.of(new PageImpl<>(content, pageable, total), content);
    }

    @Transactional(readOnly = true)
    public WorkFilterOptionsResponse filterOptions() {
        return new WorkFilterOptionsResponse(workSearchRepository.findListedPlatforms().stream()
                .map(p -> new WorkFilterOptionsResponse.PlatformOption(p.code(), p.name(), p.workCount()))
                .toList());
    }

    private static SortKey sortKey(WorkSearchCriteria.Order order) {
        return switch (order) {
            case RECENT -> SortKey.RECENT;
            case POPULAR -> SortKey.POPULAR;
            case RATING -> SortKey.RATING;
            case RATING_COUNT -> SortKey.RATING_COUNT;
            case REVIEW_COUNT -> SortKey.REVIEW_COUNT;
            case TITLE -> SortKey.TITLE;
        };
    }
}
