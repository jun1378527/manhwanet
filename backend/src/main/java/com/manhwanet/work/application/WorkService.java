package com.manhwanet.work.application;

import com.manhwanet.creator.application.CreatorService;
import com.manhwanet.creator.application.CreatorSummary;
import com.manhwanet.global.error.BusinessException;
import com.manhwanet.global.error.ConstraintViolations;
import com.manhwanet.global.error.ErrorCode;
import com.manhwanet.global.error.InvalidInputException;
import com.manhwanet.global.response.ApiError;
import com.manhwanet.global.response.PageResponse;
import com.manhwanet.work.domain.CountryCodes;
import com.manhwanet.work.domain.CreatorRole;
import com.manhwanet.work.domain.CreditView;
import com.manhwanet.work.domain.LanguageTags;
import com.manhwanet.work.domain.PrimaryTitleView;
import com.manhwanet.work.domain.Work;
import com.manhwanet.work.domain.WorkCreator;
import com.manhwanet.work.domain.WorkCreatorRepository;
import com.manhwanet.work.domain.WorkCreditDraft;
import com.manhwanet.work.domain.WorkFormat;
import com.manhwanet.work.domain.WorkRepository;
import com.manhwanet.work.domain.WorkTitle;
import com.manhwanet.work.domain.WorkTitleDraft;
import com.manhwanet.work.domain.WorkTitleRepository;
import com.manhwanet.work.dto.CreateWorkRequest;
import com.manhwanet.work.dto.CreditResponse;
import com.manhwanet.work.dto.PrimaryTitleResponse;
import com.manhwanet.work.dto.WorkDetailResponse;
import com.manhwanet.work.dto.WorkSummaryResponse;
import com.manhwanet.work.dto.WorkTitleResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class WorkService {

    private static final Logger log = LoggerFactory.getLogger(WorkService.class);

    static final int DESCRIPTION_MAX_LENGTH = 5_000;

    /** 409 로 변환하는 중복 제약 이름. 그 밖의 무결성 오류는 다시 던져 500 으로 처리한다. */
    static final Set<String> DUPLICATE_CONSTRAINTS = Set.of(
            "uk_work_titles_work_language_title",
            "uk_work_titles_primary_per_work",
            "uk_work_creators_work_creator_role");

    private static final Comparator<CreditResponse> CREDIT_ORDER =
            Comparator.comparing(CreditResponse::role).thenComparing(CreditResponse::creatorId);

    private final WorkRepository workRepository;
    private final WorkTitleRepository workTitleRepository;
    private final WorkCreatorRepository workCreatorRepository;
    private final CreatorService creatorService;

    public WorkService(
            WorkRepository workRepository,
            WorkTitleRepository workTitleRepository,
            WorkCreatorRepository workCreatorRepository,
            CreatorService creatorService) {
        this.workRepository = workRepository;
        this.workTitleRepository = workTitleRepository;
        this.workCreatorRepository = workCreatorRepository;
        this.creatorService = creatorService;
    }

    @Transactional
    public WorkDetailResponse register(CreateWorkRequest request) {
        List<ApiError.FieldError> errors = new ArrayList<>();

        WorkFormat workFormat = WorkFormat.valueOf(request.workFormat());
        String originCountry = normalizeOriginCountry(request.originCountry(), errors);
        String description = normalizeDescription(request.description(), errors);
        List<WorkTitleDraft> titles = normalizeTitles(request.titles(), errors);
        List<WorkCreditDraft> credits = normalizeCredits(request.creators(), errors);

        if (!errors.isEmpty()) {
            throw new InvalidInputException(errors);
        }

        Work work = save(Work.create(workFormat, originCountry, description, titles, credits));
        return toDetail(work);
    }

    public WorkDetailResponse getWork(Long workId) {
        Work work = workRepository.findWithTitlesById(workId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WORK_NOT_FOUND));
        return toDetail(work);
    }

    /**
     * 목록 조회. 컬렉션 fetch join 과 페이징을 결합하지 않고,
     * 작품 페이지 조회 후 대표 제목·크레딧·작가를 페이지 단위로 일괄 조회한다.
     */
    public PageResponse<WorkSummaryResponse> getWorks(Pageable pageable) {
        Page<Work> page = workRepository.findAll(pageable);
        if (page.isEmpty()) {
            return PageResponse.of(page, List.of());
        }

        List<Long> workIds = page.getContent().stream().map(Work::getId).toList();
        Map<Long, PrimaryTitleView> primaryTitles = workTitleRepository.findPrimaryTitles(workIds).stream()
                .collect(Collectors.toMap(PrimaryTitleView::workId, Function.identity()));
        Map<Long, List<CreditView>> creditsByWork = workCreatorRepository.findCredits(workIds).stream()
                .collect(Collectors.groupingBy(CreditView::workId));
        Map<Long, CreatorSummary> creators = creatorService.getSummaries(
                creditsByWork.values().stream().flatMap(List::stream).map(CreditView::creatorId).toList());

        List<WorkSummaryResponse> content = page.getContent().stream()
                .map(work -> {
                    PrimaryTitleView primary = primaryTitles.get(work.getId());
                    if (primary == null) {
                        throw new IllegalStateException("work has no primary title: " + work.getId());
                    }
                    List<CreditResponse> credits = creditsByWork.getOrDefault(work.getId(), List.of()).stream()
                            .map(credit -> toCredit(credit.creatorId(), credit.role(), creators))
                            .sorted(CREDIT_ORDER)
                            .toList();
                    return new WorkSummaryResponse(
                            work.getId(),
                            work.getWorkFormat(),
                            work.getOriginCountry(),
                            new PrimaryTitleResponse(primary.title(), primary.languageCode()),
                            credits,
                            work.getCreatedAt());
                })
                .toList();
        return PageResponse.of(page, content);
    }

    private Work save(Work work) {
        try {
            return workRepository.saveAndFlush(work);
        } catch (DataIntegrityViolationException e) {
            if (ConstraintViolations.isOneOf(e, DUPLICATE_CONSTRAINTS)) {
                log.debug("Duplicate constraint violated: {}", ConstraintViolations.constraintName(e).orElse(""));
                throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE);
            }
            throw e;
        }
    }

    private WorkDetailResponse toDetail(Work work) {
        List<WorkTitle> sortedTitles = work.getTitles().stream()
                .sorted(Comparator.comparing((WorkTitle title) -> !title.isPrimary())
                        .thenComparing(WorkTitle::getId))
                .toList();
        WorkTitle primary = sortedTitles.stream()
                .filter(WorkTitle::isPrimary)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("work has no primary title: " + work.getId()));

        List<WorkCreator> workCreators = work.getCreators();
        Map<Long, CreatorSummary> creators = creatorService.getSummaries(
                workCreators.stream().map(WorkCreator::getCreatorId).toList());
        List<CreditResponse> credits = workCreators.stream()
                .map(credit -> toCredit(credit.getCreatorId(), credit.getRole(), creators))
                .sorted(CREDIT_ORDER)
                .toList();

        return new WorkDetailResponse(
                work.getId(),
                work.getWorkFormat(),
                work.getOriginCountry(),
                work.getDescription(),
                new PrimaryTitleResponse(primary.getTitle(), primary.getLanguageCode()),
                sortedTitles.stream()
                        .map(title -> new WorkTitleResponse(title.getTitle(), title.getLanguageCode(), title.isPrimary()))
                        .toList(),
                credits,
                work.getCreatedAt(),
                work.getUpdatedAt());
    }

    private static CreditResponse toCredit(Long creatorId, CreatorRole role, Map<Long, CreatorSummary> creators) {
        CreatorSummary creator = creators.get(creatorId);
        if (creator == null) {
            throw new IllegalStateException("creator not found for credit: " + creatorId);
        }
        return new CreditResponse(creatorId, creator.name(), role);
    }

    // ---------- 입력 정규화·검증 (docs/work-creator-spec.md 3, 4) ----------

    private static String normalizeOriginCountry(String raw, List<ApiError.FieldError> errors) {
        if (raw == null) {
            return null;
        }
        try {
            return CountryCodes.normalize(raw);
        } catch (IllegalArgumentException e) {
            errors.add(new ApiError.FieldError("originCountry", e.getMessage()));
            return null;
        }
    }

    private static String normalizeDescription(String raw, List<ApiError.FieldError> errors) {
        if (raw == null) {
            return null;
        }
        String value = raw.strip();
        if (value.isEmpty()) {
            errors.add(new ApiError.FieldError("description", "공백만 입력할 수 없습니다."));
            return null;
        }
        if (codePointLength(value) > DESCRIPTION_MAX_LENGTH) {
            errors.add(new ApiError.FieldError("description", "줄거리는 " + DESCRIPTION_MAX_LENGTH + "자 이하여야 합니다."));
            return null;
        }
        return value;
    }

    private static List<WorkTitleDraft> normalizeTitles(
            List<CreateWorkRequest.TitleRequest> requests, List<ApiError.FieldError> errors) {
        List<WorkTitleDraft> titles = new ArrayList<>();
        Set<List<String>> seen = new HashSet<>();
        int primaryCount = 0;

        for (int i = 0; i < requests.size(); i++) {
            CreateWorkRequest.TitleRequest request = requests.get(i);
            String prefix = "titles[" + i + "]";
            if (request.primary()) {
                primaryCount++;
            }

            String title = request.title().strip();
            boolean valid = true;
            if (title.isEmpty()) {
                errors.add(new ApiError.FieldError(prefix + ".title", "공백만 입력할 수 없습니다."));
                valid = false;
            } else if (codePointLength(title) > WorkTitle.TITLE_MAX_LENGTH) {
                errors.add(new ApiError.FieldError(prefix + ".title", "제목은 " + WorkTitle.TITLE_MAX_LENGTH + "자 이하여야 합니다."));
                valid = false;
            }

            String languageCode = null;
            try {
                languageCode = LanguageTags.normalize(request.languageCode());
            } catch (IllegalArgumentException e) {
                errors.add(new ApiError.FieldError(prefix + ".languageCode", e.getMessage()));
                valid = false;
            }

            if (valid) {
                if (!seen.add(List.of(languageCode, title))) {
                    errors.add(new ApiError.FieldError(prefix + ".title", "같은 언어의 동일한 제목이 이미 있습니다."));
                } else {
                    titles.add(new WorkTitleDraft(title, languageCode, request.primary()));
                }
            }
        }

        if (primaryCount != 1) {
            errors.add(new ApiError.FieldError("titles", "대표 제목은 정확히 1개여야 합니다."));
        }
        return titles;
    }

    private List<WorkCreditDraft> normalizeCredits(
            List<CreateWorkRequest.CreditRequest> requests, List<ApiError.FieldError> errors) {
        List<WorkCreditDraft> credits = new ArrayList<>();
        for (int i = 0; i < requests.size(); i++) {
            CreateWorkRequest.CreditRequest request = requests.get(i);
            WorkCreditDraft credit = new WorkCreditDraft(request.creatorId(), CreatorRole.valueOf(request.role()));
            if (credits.contains(credit)) {
                errors.add(new ApiError.FieldError("creators[" + i + "]", "같은 작가·역할 연결이 중복되었습니다."));
            } else {
                credits.add(credit);
            }
        }

        Set<Long> missing = creatorService.findMissingIds(
                requests.stream().map(CreateWorkRequest.CreditRequest::creatorId).collect(Collectors.toSet()));
        for (int i = 0; i < requests.size(); i++) {
            if (missing.contains(requests.get(i).creatorId())) {
                errors.add(new ApiError.FieldError("creators[" + i + "].creatorId", "존재하지 않는 작가입니다."));
            }
        }
        return credits;
    }

    private static int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }
}
