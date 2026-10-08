package com.manhwanet.review.application;

import com.manhwanet.global.error.BusinessException;
import com.manhwanet.global.error.ConstraintViolations;
import com.manhwanet.global.error.ErrorCode;
import com.manhwanet.global.error.InvalidInputException;
import com.manhwanet.global.response.ApiError;
import com.manhwanet.review.domain.ReviewBody;
import com.manhwanet.review.domain.WorkReview;
import com.manhwanet.review.domain.WorkReviewRepository;
import com.manhwanet.review.domain.WorkReviewRepository.LockedReview;
import com.manhwanet.review.dto.CreateReviewRequest;
import com.manhwanet.review.dto.JsonFlag;
import com.manhwanet.review.dto.JsonInteger;
import com.manhwanet.review.dto.JsonText;
import com.manhwanet.review.dto.MyReviewResponse;
import com.manhwanet.review.dto.ReviewResponse;
import com.manhwanet.review.dto.UpdateReviewRequest;
import com.manhwanet.work.application.WorkService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 내 리뷰 조회·작성·수정·삭제 (docs/review-design-draft.md §4·§5).
 * 사용자 ID는 항상 호출 측(Controller)이 인증 정보에서 얻어 넘긴다.
 * 처리 순서: 입력 검증(400, 오류를 모아서) → 작품 존재(404) → 리뷰 상태(404·409) → 실행.
 */
@Service
@Transactional(readOnly = true)
public class ReviewService {

    static final String SPOILER_REQUIRED = "스포일러 여부를 선택해 주세요.";
    static final String SPOILER_NOT_BOOLEAN = "스포일러 여부는 true 또는 false여야 합니다.";
    static final String REVIEW_ID_REQUIRED = "리뷰 ID가 필요합니다.";
    static final String REVIEW_ID_INVALID = "리뷰 ID는 1 이상의 정수여야 합니다.";
    static final String VERSION_REQUIRED = "리뷰 버전이 필요합니다.";
    static final String VERSION_INVALID = "리뷰 버전은 0 이상의 정수여야 합니다.";

    /** 존재 확인과 저장 사이에 작품이 삭제된 경우만 404 로 바꾼다. 그 밖의 무결성 오류는 500 으로 둔다. */
    static final Set<String> WORK_MISSING_CONSTRAINTS = Set.of("fk_work_reviews_work");

    private final WorkReviewRepository workReviewRepository;
    private final WorkService workService;

    public ReviewService(WorkReviewRepository workReviewRepository, WorkService workService) {
        this.workReviewRepository = workReviewRepository;
        this.workService = workService;
    }

    public MyReviewResponse getMyReview(Long userId, Long workId) {
        requireWork(workId);
        return workReviewRepository.findByUserIdAndWorkId(userId, workId)
                .map(review -> new MyReviewResponse(workId, ReviewResponse.from(review)))
                .orElseGet(() -> MyReviewResponse.none(workId));
    }

    /** 작성. 이미 리뷰가 있으면 409 REVIEW_ALREADY_EXISTS 이고 아무것도 바꾸지 않는다. */
    @Transactional
    public MyReviewResponse create(Long userId, Long workId, CreateReviewRequest request) {
        List<ApiError.FieldError> errors = new ArrayList<>();
        String body = body(request == null ? null : request.body(), errors);
        Boolean containsSpoiler = spoiler(request == null ? null : request.containsSpoiler(), errors);
        throwIfAny(errors);
        requireWork(workId);

        int inserted;
        try {
            inserted = workReviewRepository.insertIfAbsent(userId, workId, body, containsSpoiler);
        } catch (DataIntegrityViolationException e) {
            throw translateWorkMissing(e);
        }
        if (inserted == 0) {
            throw new BusinessException(ErrorCode.REVIEW_ALREADY_EXISTS);
        }
        return reload(userId, workId);
    }

    /**
     * 수정(전체 교체). reviewId·version 이 현재 리뷰와 모두 같을 때만 반영한다.
     * 현재 리뷰가 없으면 404 REVIEW_NOT_FOUND, 있지만 다르면 409 REVIEW_VERSION_CONFLICT.
     * 내용이 같으면 시각·버전을 바꾸지 않고 현재 값을 돌려준다.
     */
    @Transactional
    public MyReviewResponse update(Long userId, Long workId, UpdateReviewRequest request) {
        List<ApiError.FieldError> errors = new ArrayList<>();
        String body = body(request == null ? null : request.body(), errors);
        Boolean containsSpoiler = spoiler(request == null ? null : request.containsSpoiler(), errors);
        Long reviewId = integer(request == null ? null : request.reviewId(), "reviewId", 1,
                REVIEW_ID_REQUIRED, REVIEW_ID_INVALID, errors);
        Long version = integer(request == null ? null : request.version(), "version", 0,
                VERSION_REQUIRED, VERSION_INVALID, errors);
        throwIfAny(errors);
        requireWork(workId);

        LockedReview current = lockCurrent(userId, workId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REVIEW_NOT_FOUND));
        requireSameReview(current, reviewId, version);
        if (!current.getBody().equals(body) || current.getContainsSpoiler() != containsSpoiler.booleanValue()) {
            workReviewRepository.updateContent(current.getId(), body, containsSpoiler);
        }
        return reload(userId, workId);
    }

    /**
     * 삭제. reviewId·version 이 현재 리뷰와 모두 같을 때만 지운다.
     * 현재 리뷰가 없으면 성공(멱등, 204), 있지만 다르면 409 REVIEW_VERSION_CONFLICT.
     */
    @Transactional
    public void delete(Long userId, Long workId, Long reviewId, Long version) {
        List<ApiError.FieldError> errors = new ArrayList<>();
        requireRange(reviewId, "reviewId", 1, REVIEW_ID_REQUIRED, REVIEW_ID_INVALID, errors);
        requireRange(version, "version", 0, VERSION_REQUIRED, VERSION_INVALID, errors);
        throwIfAny(errors);
        requireWork(workId);

        Optional<LockedReview> current = lockCurrent(userId, workId);
        if (current.isEmpty()) {
            return;
        }
        requireSameReview(current.get(), reviewId, version);
        workReviewRepository.deleteReview(current.get().getId());
    }

    /**
     * 현재 리뷰를 잠그고 읽는다. 결과가 비면 새 문장으로 한 번 더 읽는다.
     * READ COMMITTED 에서 잠금을 기다리던 행이 삭제되면 첫 결과는 비지만, 그 사이 커밋된 새 리뷰는
     * 첫 문장의 스냅샷에 보이지 않는다. 새 문장은 새 스냅샷이므로 그 리뷰를 찾아 비교(→ 409)할 수 있다.
     */
    private Optional<LockedReview> lockCurrent(Long userId, Long workId) {
        Optional<LockedReview> first = workReviewRepository.lockByUserIdAndWorkId(userId, workId);
        if (first.isPresent()) {
            return first;
        }
        return workReviewRepository.lockByUserIdAndWorkId(userId, workId);
    }

    /** 삭제 후 재작성하면 version 이 0부터 다시 시작하므로 version 만이 아니라 reviewId 도 비교한다. */
    private static void requireSameReview(LockedReview current, Long reviewId, Long version) {
        if (!current.getId().equals(reviewId) || !current.getVersion().equals(version)) {
            throw new BusinessException(ErrorCode.REVIEW_VERSION_CONFLICT);
        }
    }

    private MyReviewResponse reload(Long userId, Long workId) {
        WorkReview review = workReviewRepository.findByUserIdAndWorkId(userId, workId)
                .orElseThrow(() -> new IllegalStateException("review not found right after write"));
        return new MyReviewResponse(workId, ReviewResponse.from(review));
    }

    private void requireWork(Long workId) {
        if (!workService.exists(workId)) {
            throw new BusinessException(ErrorCode.WORK_NOT_FOUND);
        }
    }

    private static RuntimeException translateWorkMissing(DataIntegrityViolationException e) {
        if (ConstraintViolations.isOneOf(e, WORK_MISSING_CONSTRAINTS)) {
            return new BusinessException(ErrorCode.WORK_NOT_FOUND);
        }
        return e;
    }

    private static String body(JsonText field, List<ApiError.FieldError> errors) {
        if (field != null && !field.isText()) {
            errors.add(new ApiError.FieldError("body", ReviewBody.NOT_TEXT));
            return null;
        }
        try {
            return ReviewBody.normalize(field == null ? null : field.value());
        } catch (IllegalArgumentException e) {
            errors.add(new ApiError.FieldError("body", e.getMessage()));
            return null;
        }
    }

    private static Boolean spoiler(JsonFlag field, List<ApiError.FieldError> errors) {
        if (field == null) {
            errors.add(new ApiError.FieldError("containsSpoiler", SPOILER_REQUIRED));
            return null;
        }
        if (!field.isBoolean()) {
            errors.add(new ApiError.FieldError("containsSpoiler", SPOILER_NOT_BOOLEAN));
            return null;
        }
        return field.value();
    }

    private static Long integer(JsonInteger field, String name, long min, String required, String invalid,
            List<ApiError.FieldError> errors) {
        if (field == null) {
            errors.add(new ApiError.FieldError(name, required));
            return null;
        }
        if (!field.isInteger() || field.value() < min) {
            errors.add(new ApiError.FieldError(name, invalid));
            return null;
        }
        return field.value();
    }

    /** query 값(Spring 이 이미 Long 으로 변환)의 누락·범위 검사. 형식 오류는 Controller 바인딩 단계에서 400 이 된다. */
    private static void requireRange(Long value, String name, long min, String required, String invalid,
            List<ApiError.FieldError> errors) {
        if (value == null) {
            errors.add(new ApiError.FieldError(name, required));
        } else if (value < min) {
            errors.add(new ApiError.FieldError(name, invalid));
        }
    }

    private static void throwIfAny(List<ApiError.FieldError> errors) {
        if (!errors.isEmpty()) {
            throw new InvalidInputException(errors);
        }
    }
}
