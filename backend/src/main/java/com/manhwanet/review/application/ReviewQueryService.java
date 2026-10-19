package com.manhwanet.review.application;

import com.manhwanet.global.error.BusinessException;
import com.manhwanet.global.error.ErrorCode;
import com.manhwanet.global.response.PageResponse;
import com.manhwanet.review.domain.WorkReview;
import com.manhwanet.review.domain.WorkReviewRepository;
import com.manhwanet.review.dto.PublicReviewResponse;
import com.manhwanet.review.dto.RecentReviewResponse;
import com.manhwanet.user.application.UserService;
import com.manhwanet.work.application.WorkService;
import com.manhwanet.work.dto.PrimaryTitleResponse;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공개 리뷰 조회: 작품별 공개 리뷰 목록 (docs/review-design-draft.md §4.1·§4.2)과 홈의 최근 공개 리뷰.
 * 리뷰 페이지를 읽은 뒤 작성자 닉네임을 페이지 단위로 한 번에 조회한다(N+1 없음).
 * 응답은 로그인 여부와 관계없이 같다. 다른 도메인의 Repository 는 쓰지 않는다
 * (최근 리뷰 SQL 만 개수를 맞추려고 works.visibility 를 함께 거른다. WorkReviewRepository#findRecentOfPublicWorks).
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

    /**
     * 최근 공개 리뷰(홈). 숨김 작품의 리뷰와 작품 정보는 넣지 않는다.
     * 리뷰·작품명·닉네임 조회가 같은 스냅샷을 보도록 REPEATABLE READ 로 읽는다(숨김 전환과 겹쳐도 숨김 작품이 섞이지 않음).
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<RecentReviewResponse> getRecentReviews(int size) {
        List<WorkReview> reviews = workReviewRepository.findRecentOfPublicWorks(size);
        if (reviews.isEmpty()) {
            return List.of();
        }
        Map<Long, PrimaryTitleResponse> titles = workService.findPublicPrimaryTitles(
                reviews.stream().map(WorkReview::getWorkId).collect(Collectors.toSet()));
        Map<Long, String> nicknames = userService.findNicknames(reviews.stream().map(WorkReview::getUserId).toList());
        return reviews.stream()
                .filter(review -> titles.containsKey(review.getWorkId()))
                .map(review -> RecentReviewResponse.of(review, titles.get(review.getWorkId()), nicknames.get(review.getUserId())))
                .toList();
    }

    /**
     * 공개 리뷰 한 건(홈에서 접힌 스포일러를 펼칠 때 본문을 받는다). 숨김 작품의 리뷰는 없는 리뷰와 같은 404.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RecentReviewResponse getPublicReview(Long reviewId) {
        WorkReview review = (reviewId == null || reviewId < 1) ? null : workReviewRepository.findById(reviewId).orElse(null);
        if (review == null) {
            throw new BusinessException(ErrorCode.REVIEW_NOT_FOUND);
        }
        PrimaryTitleResponse title = workService.findPublicPrimaryTitles(List.of(review.getWorkId())).get(review.getWorkId());
        if (title == null) {
            throw new BusinessException(ErrorCode.REVIEW_NOT_FOUND);
        }
        String nickname = userService.findNicknames(List.of(review.getUserId())).get(review.getUserId());
        return RecentReviewResponse.of(review, title, nickname);
    }
}
