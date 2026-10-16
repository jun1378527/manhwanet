package com.manhwanet.rating.application;

import com.manhwanet.global.error.BusinessException;
import com.manhwanet.global.error.ConstraintViolations;
import com.manhwanet.global.error.ErrorCode;
import com.manhwanet.global.error.InvalidInputException;
import com.manhwanet.global.response.ApiError;
import com.manhwanet.rating.domain.RatingScore;
import com.manhwanet.rating.domain.WorkRating;
import com.manhwanet.rating.domain.WorkRatingRepository;
import com.manhwanet.rating.dto.MyRatingResponse;
import com.manhwanet.rating.dto.RatingRequest;
import com.manhwanet.work.application.WorkService;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 내 별점 조회·저장·삭제. 사용자 ID는 항상 호출 측(Controller)이 인증 정보에서 얻어 넘긴다.
 * 처리 순서: 입력 검증(400) → 작품 존재(404) → 실행.
 */
@Service
@Transactional(readOnly = true)
public class RatingService {

    static final String SCORE_REQUIRED = "별점을 선택해 주세요.";
    static final String SCORE_NOT_NUMBER = "별점은 숫자여야 합니다.";

    /** 존재 확인과 저장 사이에 작품이 삭제된 경우만 404 로 바꾼다. 그 밖의 무결성 오류는 500 으로 둔다. */
    static final Set<String> WORK_MISSING_CONSTRAINTS = Set.of("fk_work_ratings_work");

    private final WorkRatingRepository workRatingRepository;
    private final WorkService workService;

    public RatingService(WorkRatingRepository workRatingRepository, WorkService workService) {
        this.workRatingRepository = workRatingRepository;
        this.workService = workService;
    }

    /**
     * 내 별점. 숨김 작품(#41)은 내 기록이 있을 때만 돌려주고(작성자 본인 조회 유지), 없으면 없는 작품과 같은 404 다.
     */
    public MyRatingResponse getMyRating(Long userId, Long workId) {
        WorkService.Access access = workService.access(workId);
        if (access == WorkService.Access.MISSING) {
            throw new BusinessException(ErrorCode.WORK_NOT_FOUND);
        }
        Optional<WorkRating> mine = workRatingRepository.findByUserIdAndWorkId(userId, workId);
        if (access == WorkService.Access.HIDDEN && mine.isEmpty()) {
            throw new BusinessException(ErrorCode.WORK_NOT_FOUND);
        }
        return mine.map(RatingService::toResponse).orElseGet(() -> MyRatingResponse.unrated(workId));
    }

    /**
     * 등록·수정. upsert 한 문장으로 기록한 뒤 같은 트랜잭션에서 행을 다시 읽어 응답한다.
     * upsert 가 잡은 행 잠금은 커밋까지 유지되므로 다시 읽은 값은 이 요청이 기록한 값이다.
     * 동시에 다른 요청이 커밋 후 덮어쓸 수 있으므로 응답이 최종 상태라는 보장은 없다.
     */
    @Transactional
    public MyRatingResponse putMyRating(Long userId, Long workId, RatingRequest request) {
        short scoreHalf = validateScore(request);
        // 숨김 작품에는 새로 쓰거나 고치지 않는다(없는 작품과 같은 404). 숨김 전환과 직렬화
        workService.requirePublicForWrite(workId);
        try {
            workRatingRepository.upsert(userId, workId, scoreHalf);
        } catch (DataIntegrityViolationException e) {
            if (ConstraintViolations.isOneOf(e, WORK_MISSING_CONSTRAINTS)) {
                throw new BusinessException(ErrorCode.WORK_NOT_FOUND);
            }
            throw e;
        }
        WorkRating saved = workRatingRepository.findByUserIdAndWorkId(userId, workId)
                .orElseThrow(() -> new IllegalStateException("rating not found right after upsert"));
        return toResponse(saved);
    }

    /**
     * 삭제. 공개 작품은 별점이 없어도 성공한다(멱등). 숨김 작품(#41)은 내 별점이 있으면 지울 수 있고(작성자 본인 삭제 유지),
     * 없으면 없는 작품과 같은 404 다.
     */
    @Transactional
    public void deleteMyRating(Long userId, Long workId) {
        WorkService.Access access = workService.access(workId);
        if (access == WorkService.Access.MISSING) {
            throw new BusinessException(ErrorCode.WORK_NOT_FOUND);
        }
        int deleted = workRatingRepository.deleteByUserIdAndWorkId(userId, workId);
        if (access == WorkService.Access.HIDDEN && deleted == 0) {
            throw new BusinessException(ErrorCode.WORK_NOT_FOUND);
        }
    }

    private static short validateScore(RatingRequest request) {
        RatingRequest.Score score = request == null ? null : request.score();
        if (score == null) {
            throw invalidScore(SCORE_REQUIRED);
        }
        if (!score.isNumber()) {
            throw invalidScore(SCORE_NOT_NUMBER);
        }
        try {
            return RatingScore.toScoreHalf(score.number());
        } catch (IllegalArgumentException e) {
            throw invalidScore(e.getMessage());
        }
    }

    private static InvalidInputException invalidScore(String reason) {
        return new InvalidInputException(List.of(new ApiError.FieldError("score", reason)));
    }

    private static MyRatingResponse toResponse(WorkRating rating) {
        return new MyRatingResponse(
                rating.getWorkId(),
                RatingScore.fromScoreHalf(rating.getScoreHalf()),
                rating.getCreatedAt(),
                rating.getUpdatedAt());
    }
}
