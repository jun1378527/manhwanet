package com.manhwanet.review.application;

import com.manhwanet.global.error.BusinessException;
import com.manhwanet.global.error.ErrorCode;
import com.manhwanet.global.response.PageResponse;
import com.manhwanet.review.domain.WorkReview;
import com.manhwanet.review.domain.WorkReviewRepository;
import com.manhwanet.review.dto.PublicReviewResponse;
import com.manhwanet.user.application.UserService;
import com.manhwanet.work.application.WorkService;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 작품별 공개 리뷰 목록 (docs/review-design-draft.md §4.1·§4.2).
 * 리뷰 페이지를 읽은 뒤 작성자 닉네임을 페이지 단위로 한 번에 조회한다(N+1 없음).
 * 응답은 로그인 여부와 관계없이 같다. 다른 도메인의 Repository·테이블은 직접 읽지 않는다.
 */
@Service
@Transactional(readOnly = true)
public class ReviewQueryService {

    private final WorkReviewRepository workReviewRepository;
    private final WorkService workService;
    private final UserService userService;

    public ReviewQueryService(WorkReviewRepository workReviewRepository, WorkService workService,
            UserService userService) {
        this.workReviewRepository = workReviewRepository;
        this.workService = workService;
        this.userService = userService;
    }

    /** @param pageable created_at DESC, id DESC 로 정렬이 고정된 페이지 */
    public PageResponse<PublicReviewResponse> getWorkReviews(Long workId, Pageable pageable) {
        // 숨김 작품(#41)의 공개 리뷰 목록은 없는 작품과 같은 404
        if (workService.access(workId) != WorkService.Access.PUBLIC) {
            throw new BusinessException(ErrorCode.WORK_NOT_FOUND);
        }
        Page<WorkReview> page = workReviewRepository.findByWorkId(workId, pageable);
        if (page.isEmpty()) {
            return PageResponse.of(page, List.of());
        }
        Map<Long, String> nicknames = userService.findNicknames(
                page.getContent().stream().map(WorkReview::getUserId).toList());
        List<PublicReviewResponse> content = page.getContent().stream()
                .map(review -> PublicReviewResponse.of(review, nicknames.get(review.getUserId())))
                .toList();
        return PageResponse.of(page, content);
    }
}
