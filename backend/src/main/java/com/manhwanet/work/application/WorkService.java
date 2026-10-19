package com.manhwanet.work.application;

import com.manhwanet.audit.application.AdminChangeLog;
import com.manhwanet.audit.domain.ChangeAction;
import com.manhwanet.audit.domain.ChangeEntityType;
import com.manhwanet.creator.application.CreatorService;
import com.manhwanet.creator.application.CreatorSummary;
import com.manhwanet.global.admin.AdminInputs;
import com.manhwanet.global.error.BusinessException;
import com.manhwanet.global.error.ConstraintViolations;
import com.manhwanet.global.error.ErrorCode;
import com.manhwanet.global.error.InvalidInputException;
import com.manhwanet.global.error.VersionConflictException;
import com.manhwanet.global.response.ApiError;
import com.manhwanet.global.response.PageResponse;
import com.manhwanet.platform.application.PublicPlatformLinkService;
import com.manhwanet.platform.application.WorkListingService;
import com.manhwanet.platform.domain.ListingRepository;
import com.manhwanet.platform.dto.ListingResponse;
import com.manhwanet.provenance.application.Evidence;
import com.manhwanet.provenance.application.ExternalIdDraft;
import com.manhwanet.provenance.application.LinkDraft;
import com.manhwanet.provenance.application.ProvenanceInputs;
import com.manhwanet.provenance.application.ProvenanceQuery;
import com.manhwanet.provenance.application.ProvenanceWriter;
import com.manhwanet.provenance.domain.ExternalIdOwner;
import com.manhwanet.provenance.domain.SourceField;
import com.manhwanet.review.dto.JsonInteger;
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
import com.manhwanet.work.domain.WorkRepository.LockedWork;
import com.manhwanet.work.domain.WorkRepository;
import com.manhwanet.work.domain.WorkTitle;
import com.manhwanet.work.domain.WorkTitleDraft;
import com.manhwanet.work.domain.WorkTitleRepository;
import com.manhwanet.work.domain.WorkVisibility;
import com.manhwanet.work.dto.ChangeVisibilityRequest;
import com.manhwanet.platform.domain.AgeClass;
import com.manhwanet.platform.domain.ListingRow;
import com.manhwanet.platform.domain.ListingStatus;
import com.manhwanet.work.dto.ActivityWorkResponse;
import com.manhwanet.work.dto.AdminWorkDetailResponse;
import com.manhwanet.work.dto.CreateWorkRequest;
import com.manhwanet.work.dto.CreditResponse;
import com.manhwanet.work.dto.PrimaryTitleResponse;
import com.manhwanet.work.dto.RatingSummaryResponse;
import com.manhwanet.work.dto.UpdateWorkRequest;
import com.manhwanet.work.dto.WorkDetailResponse;
import com.manhwanet.work.dto.WorkSummaryResponse;
import com.manhwanet.work.dto.WorkTitleResponse;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
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
    private final WorkRatingSummaryQuery ratingSummaryQuery;
    private final WorkReviewCountQuery reviewCountQuery;
    private final PublicPlatformLinkService publicPlatformLinkService;
    private final WorkListingService workListingService;
    private final ListingRepository listingRepository;
    private final ProvenanceWriter provenanceWriter;
    private final ProvenanceQuery provenanceQuery;
    private final AdminChangeLog changeLog;
    private final Clock clock = Clock.systemUTC();

    public WorkService(
            WorkRepository workRepository,
            WorkTitleRepository workTitleRepository,
            WorkCreatorRepository workCreatorRepository,
            CreatorService creatorService,
            WorkRatingSummaryQuery ratingSummaryQuery,
            WorkReviewCountQuery reviewCountQuery,
            PublicPlatformLinkService publicPlatformLinkService,
            WorkListingService workListingService,
            ListingRepository listingRepository,
            ProvenanceWriter provenanceWriter,
            ProvenanceQuery provenanceQuery,
            AdminChangeLog changeLog) {
        this.workRepository = workRepository;
        this.workTitleRepository = workTitleRepository;
        this.workCreatorRepository = workCreatorRepository;
        this.creatorService = creatorService;
        this.ratingSummaryQuery = ratingSummaryQuery;
        this.reviewCountQuery = reviewCountQuery;
        this.publicPlatformLinkService = publicPlatformLinkService;
        this.workListingService = workListingService;
        this.listingRepository = listingRepository;
        this.provenanceWriter = provenanceWriter;
        this.provenanceQuery = provenanceQuery;
        this.changeLog = changeLog;
    }

    /**
     * 작품 등록(#40 §5.6 확장). 순서: 입력 검증(제목·크레딧·근거·연결·외부 ID·링크, 400) → 작품 저장 → 변경 기록(WORK CREATE)
     * → 연결 → 외부 ID → 공식 링크 → 근거. 모두 한 트랜잭션이라 연결·외부 ID 중복(409)이나 기록 실패 시 작품도 남지 않는다.
     * 새 작품의 version 은 0 이다(함께 넣은 하위 리소스는 첫 상태의 일부).
     */
    @Transactional
    public AdminWorkDetailResponse register(CreateWorkRequest request, long actorId) {
        return register(request, actorId, WorkVisibility.PUBLIC);
    }

    /**
     * 공개 상태를 정해 등록한다(#41·#51). 수집 대기 항목에서 만든 작품은 HIDDEN 으로 INSERT 해 공개 경로에 한 번도 드러나지 않는다.
     * 관리자 등록 API(POST /admin/works)는 지금처럼 PUBLIC 이다.
     */
    @Transactional
    public AdminWorkDetailResponse register(CreateWorkRequest request, long actorId, WorkVisibility visibility) {
        List<ApiError.FieldError> errors = new ArrayList<>();
        AdminInputs in = new AdminInputs(errors);

        WorkFormat workFormat = WorkFormat.valueOf(request.workFormat());
        String originCountry = normalizeOriginCountry(request.originCountry(), errors);
        String description = normalizeDescription(request.description(), errors);
        List<WorkTitleDraft> titles = normalizeTitles(request.titles(), errors);
        List<WorkCreditDraft> credits = normalizeCredits(request.creators(), errors);
        List<Evidence> evidence = ProvenanceInputs.evidence(request.evidence(), "evidence", in, clock,
                ProvenanceInputs.WORK_FIELDS, null, false);
        ProvenanceInputs.require(evidence, SourceField.TITLE, "evidence", in);
        ProvenanceInputs.require(evidence, SourceField.CREDIT, "evidence", in);
        if (description != null) {
            ProvenanceInputs.require(evidence, SourceField.DESCRIPTION, "evidence", in);
        }
        List<ExternalIdDraft> externalIds = ProvenanceInputs.externalIds(request.externalIds(), "externalIds", in,
                clock);
        List<LinkDraft> links = ProvenanceInputs.links(request.links(), "links", in, clock);
        String reason = in.reason(request.reason(), false);
        List<WorkListingService.PreparedListing> listings = workListingService.prepareForNewWork(request.listings(),
                in);
        if (errors.isEmpty()) {
            provenanceWriter.rejectPlatformHosts(links, in);
        }
        if (!errors.isEmpty()) {
            throw new InvalidInputException(errors);
        }

        Work work = save(Work.create(workFormat, originCountry, description, titles, credits, visibility));
        long workId = work.getId();
        Map<String, Object> created = snapshot(workFormat.name(), originCountry, description, titles, credits);
        created.put("visibility", visibility.name());
        changeLog.record(ChangeEntityType.WORK, String.valueOf(workId), ChangeAction.CREATE, workId, null, null,
                created, reason, actorId);
        workListingService.insertForNewWork(workId, listings, actorId, reason);
        for (ExternalIdDraft externalId : externalIds) {
            provenanceWriter.addExternalId(ExternalIdOwner.WORK, workId, externalId, actorId, reason);
        }
        for (LinkDraft link : links) {
            provenanceWriter.addLink(workId, link, actorId, reason);
        }
        provenanceWriter.addEvidence(workId, evidence, actorId, reason);
        // 새 작품에는 별점이 없으므로 집계 SQL 을 실행하지 않는다.
        return adminDetail(work.getVersion(), work.getVisibility(), toDetail(work, RatingSummaryResponse.EMPTY));
    }

    /**
     * 공개 상세. 작품·제목과 크레딧을 서로 다른 SQL 로 읽으므로, 그 사이에 관리자 수정이 커밋돼도
     * 한 응답에 이전·이후 값이 섞이지 않도록 REPEATABLE READ 스냅샷 하나로 읽는다(읽기 전용이라 직렬화 실패 없음).
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public WorkDetailResponse getWork(Long workId) {
        // 숨김 작품은 공개 경로에서 없는 작품과 같다(ADMIN 도 관리자 경로로 조회)
        Work work = workRepository.findWithTitlesById(workId)
                .filter(w -> w.getVisibility() == WorkVisibility.PUBLIC)
                .orElseThrow(() -> new BusinessException(ErrorCode.WORK_NOT_FOUND));
        RatingSummaryResponse rating = ratingSummaryQuery.findByWorkIds(List.of(work.getId()))
                .getOrDefault(work.getId(), RatingSummaryResponse.EMPTY);
        return toDetail(work, rating);
    }

    /** 관리자용 상세: 공개 상세 + 수정용 version. 공개 상세와 같은 이유로 스냅샷 하나로 읽는다. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdminWorkDetailResponse getAdminWork(Long workId) {
        Work work = workRepository.findWithTitlesById(workId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WORK_NOT_FOUND));
        RatingSummaryResponse rating = ratingSummaryQuery.findByWorkIds(List.of(work.getId()))
                .getOrDefault(work.getId(), RatingSummaryResponse.EMPTY);
        return adminDetail(work.getVersion(), work.getVisibility(), toDetail(work, rating));
    }

    private AdminWorkDetailResponse adminDetail(long version, WorkVisibility visibility, WorkDetailResponse detail) {
        long workId = detail.id();
        return AdminWorkDetailResponse.of(version, visibility, detail,
                listingRepository.findByWorkId(workId).stream().map(ListingResponse::from).toList(),
                provenanceQuery.externalIds(ExternalIdOwner.WORK, workId),
                provenanceQuery.links(workId),
                provenanceQuery.fieldSources(workId));
    }

    /** 변경 기록에 남기는 작품 핵심 정보(제목·크레딧은 요청 순서) */
    private static Map<String, Object> snapshot(String workFormat, String originCountry, String description,
            List<WorkTitleDraft> titles, List<WorkCreditDraft> credits) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("workFormat", workFormat);
        map.put("originCountry", originCountry);
        map.put("description", description);
        map.put("titles", titles);
        map.put("credits", credits);
        return map;
    }

    /**
     * 핵심 정보 수정(전체 교체). docs/work-platform-admin-spec.md §5.1.
     * <ol>
     *   <li>입력 검증(400). 규칙은 등록과 같다.</li>
     *   <li>작품 행 잠금(없으면 404). 같은 작품의 다른 수정은 커밋까지 기다린다.</li>
     *   <li>version 비교(다르면 409 WORK_VERSION_CONFLICT, 아무것도 바꾸지 않음).</li>
     *   <li>내용이 같으면 version·updated_at 을 바꾸지 않고 현재 값을 돌려준다(멱등).</li>
     *   <li>제목·크레딧 행을 지우고 다시 넣은 뒤 핵심 필드와 version 을 조건부 UPDATE 한다.
     *       모두 한 트랜잭션이므로 중간에 실패하면 전부 되돌아간다(대표 제목·크레딧 원자적 교체).</li>
     * </ol>
     * 작품 ID는 바뀌지 않는다. 별점·리뷰는 작품 ID에만 묶여 있어 영향을 받지 않는다.
     */
    @Transactional
    public AdminWorkDetailResponse update(Long workId, UpdateWorkRequest request, long actorId) {
        List<ApiError.FieldError> errors = new ArrayList<>();
        AdminInputs in = new AdminInputs(errors);
        Long version = version(request.version(), errors);
        WorkFormat workFormat = WorkFormat.valueOf(request.workFormat());
        String originCountry = normalizeOriginCountry(request.originCountry(), errors);
        String description = normalizeDescription(request.description(), errors);
        List<WorkTitleDraft> titles = normalizeTitles(request.titles(), errors);
        List<WorkCreditDraft> credits = normalizeCredits(request.creators(), errors);
        List<Evidence> evidence = ProvenanceInputs.evidence(request.evidence(), "evidence", in, clock,
                ProvenanceInputs.WORK_FIELDS, null, false);
        String reason = in.reason(request.reason(), true);
        if (!errors.isEmpty()) {
            throw new InvalidInputException(errors);
        }
        Work.validateReplacement(titles, credits);

        LockedWork current = workRepository.lockById(workId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WORK_NOT_FOUND));
        if (!current.getVersion().equals(version)) {
            throw new VersionConflictException(ErrorCode.WORK_VERSION_CONFLICT, current.getVersion());
        }

        boolean coreChanged = !current.getWorkFormat().equals(workFormat.name())
                || !Objects.equals(current.getOriginCountry(), originCountry)
                || !Objects.equals(current.getDescription(), description);
        List<WorkTitleDraft> currentTitles = workTitleRepository.findDrafts(workId);
        List<WorkCreditDraft> currentCredits = workCreatorRepository.findDrafts(workId);
        boolean titlesChanged = !Set.copyOf(currentTitles).equals(Set.copyOf(titles));
        boolean creditsChanged = !Set.copyOf(currentCredits).equals(Set.copyOf(credits));
        if (!coreChanged && !titlesChanged && !creditsChanged) {
            return getAdminWork(workId);
        }
        // 근거 필수 규칙(명세 §5.4): 실제로 바뀐 필드만 확인한다. 줄거리를 비우는 경우는 근거가 필요 없다
        if (titlesChanged) {
            ProvenanceInputs.require(evidence, SourceField.TITLE, "evidence", in);
        }
        if (creditsChanged) {
            ProvenanceInputs.require(evidence, SourceField.CREDIT, "evidence", in);
        }
        if (description != null && !Objects.equals(current.getDescription(), description)) {
            ProvenanceInputs.require(evidence, SourceField.DESCRIPTION, "evidence", in);
        }
        in.throwIfAny();
        Map<String, Object> before = snapshot(current.getWorkFormat(), current.getOriginCountry(),
                current.getDescription(), currentTitles, currentCredits);

        try {
            if (titlesChanged) {
                workTitleRepository.deleteByWorkId(workId);
                for (WorkTitleDraft title : titles) {
                    workTitleRepository.insertTitle(workId, title.title(), title.languageCode(), title.primary());
                }
            }
            if (creditsChanged) {
                workCreatorRepository.deleteByWorkId(workId);
                for (WorkCreditDraft credit : credits) {
                    workCreatorRepository.insertCredit(workId, credit.creatorId(), credit.role().name());
                }
            }
        } catch (DataIntegrityViolationException e) {
            if (ConstraintViolations.isOneOf(e, DUPLICATE_CONSTRAINTS)) {
                log.debug("Duplicate constraint violated: {}", ConstraintViolations.constraintName(e).orElse(""));
                throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE);
            }
            throw e;
        }
        int updated = workRepository.updateCore(workId, version, workFormat.name(), originCountry, description);
        if (updated != 1) {
            throw new IllegalStateException("locked work was not updated: " + workId);
        }
        changeLog.record(ChangeEntityType.WORK, String.valueOf(workId), ChangeAction.UPDATE, workId, null, before,
                snapshot(workFormat.name(), originCountry, description, titles, credits), reason, actorId);
        provenanceWriter.addEvidence(workId, evidence, actorId, reason);
        return getAdminWork(workId);
    }

    /** #41: 공개 경로에서 본 작품 상태 */
    public enum Access {
        PUBLIC, HIDDEN, MISSING
    }

    /** 공개 경로에서 본 작품 상태(읽기). 별점·리뷰 조회가 쓴다 */
    public Access access(Long workId) {
        if (workId == null || workId <= 0) {
            return Access.MISSING;
        }
        return workRepository.findVisibility(workId)
                .map(v -> WorkVisibility.valueOf(v) == WorkVisibility.PUBLIC ? Access.PUBLIC : Access.HIDDEN)
                .orElse(Access.MISSING);
    }

    /**
     * 별점·리뷰를 새로 쓰거나 고치기 전 확인. 공개 작품이 아니면 없는 작품과 같은 404 WORK_NOT_FOUND.
     * FOR SHARE 로 읽어 호출 트랜잭션이 끝날 때까지 숨김 전환과 직렬화한다(호출자 트랜잭션 필수).
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void requirePublicForWrite(Long workId) {
        boolean visible = workId != null && workId > 0 && workRepository.findVisibilityForShare(workId)
                .map(v -> WorkVisibility.valueOf(v) == WorkVisibility.PUBLIC).orElse(false);
        if (!visible) {
            throw new BusinessException(ErrorCode.WORK_NOT_FOUND);
        }
    }

    /**
     * 공개 상태 변경(#41 최소 범위). POST /admin/works/{workId}/visibility
     * <ul>
     *   <li>version 이 같을 때만 바꾸고 1 올린다(다르면 409 WORK_VERSION_CONFLICT). 사유 필수, 변경 기록(WORK·STATUS_CHANGE)</li>
     *   <li>같은 상태로 보내면 version·기록을 그대로 두고 현재 상세를 돌려준다</li>
     *   <li>PUBLIC 전환은 ACTIVE 연결이 하나 이상 있고 모두 GENERAL 일 때만. ADULT·UNKNOWN 공개 표시는 미결정(D-15)이라
     *       409 WORK_PUBLICATION_BLOCKED. HIDDEN 전환은 언제나 가능</li>
     *   <li>별점·리뷰·연결·작품 ID 는 바꾸지 않는다</li>
     * </ul>
     */
    @Transactional
    public AdminWorkDetailResponse changeVisibility(Long workId, ChangeVisibilityRequest request, long actorId) {
        AdminInputs in = new AdminInputs();
        Long version = in.version(request == null ? null : request.version());
        WorkVisibility target = in.enumValue(request == null ? null : request.visibility(), WorkVisibility.class, "visibility", true);
        String reason = in.reason(request == null ? null : request.reason(), true);
        in.throwIfAny();
        LockedWork current = workRepository.lockById(workId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WORK_NOT_FOUND));
        if (!current.getVersion().equals(version)) {
            throw new VersionConflictException(ErrorCode.WORK_VERSION_CONFLICT, current.getVersion());
        }
        WorkVisibility before = WorkVisibility.valueOf(current.getVisibility());
        if (before == target) {
            return getAdminWork(workId);
        }
        if (target == WorkVisibility.PUBLIC) {
            requirePublishable(workId);
        }
        if (workRepository.updateVisibility(workId, version, target.name()) != 1) {
            throw new IllegalStateException("locked work visibility was not updated: " + workId);
        }
        changeLog.record(ChangeEntityType.WORK, String.valueOf(workId), ChangeAction.STATUS_CHANGE, workId, null,
                Map.of("visibility", before.name()), Map.of("visibility", target.name()), reason, actorId);
        return getAdminWork(workId);
    }

    private void requirePublishable(long workId) {
        List<ListingRow> active = listingRepository.findByWorkId(workId).stream()
                .filter(l -> l.status() == ListingStatus.ACTIVE).toList();
        List<String> notGeneral = active.stream().filter(l -> l.ageClass() != AgeClass.GENERAL)
                .map(l -> l.platformCode() + ":" + l.platformWorkId() + "=" + l.ageClass()).toList();
        if (active.isEmpty() || !notGeneral.isEmpty()) {
            throw new BusinessException(ErrorCode.WORK_PUBLICATION_BLOCKED, ErrorCode.WORK_PUBLICATION_BLOCKED.getMessage()
                    + (active.isEmpty() ? " (ACTIVE 플랫폼 연결이 없어 등급을 확인할 수 없음)" : " (GENERAL 이 아닌 연결 " + notGeneral + ")"));
        }
    }

    /** 작품 존재 여부. 다른 도메인(rating)이 작품 ID를 검증할 때 사용한다. */
    public boolean exists(Long workId) {
        return workId != null && workId > 0 && workRepository.existsById(workId);
    }

    /**
     * 목록 조회. 컬렉션 fetch join 과 페이징을 결합하지 않고,
     * 작품 페이지 조회 후 대표 제목·크레딧·작가·별점 집계를 페이지 단위로 일괄 조회한다.
     * 빈 페이지는 연관 조회와 집계를 모두 생략한다.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<WorkSummaryResponse> getWorks(Pageable pageable) {
        Page<Work> page = workRepository.findAllByVisibility(WorkVisibility.PUBLIC, pageable);
        if (page.isEmpty()) {
            return PageResponse.of(page, List.of());
        }

        return PageResponse.of(page, summarize(page.getContent()));
    }

    /**
     * 공개 작품 요약을 주어진 ID 순서대로 돌려준다. 공개가 아니거나 없는 ID 는 건너뛴다.
     * 다른 도메인의 공개 목록(별점 순위)이 순서를 정하고 표시 정보는 작품 목록과 같은 모양으로 받는다.
     */
    @Transactional(readOnly = true)
    public List<WorkSummaryResponse> getPublicSummaries(List<Long> workIds) {
        if (workIds.isEmpty()) {
            return List.of();
        }
        Map<Long, Work> works = workRepository.findAllById(workIds).stream()
                .filter(work -> work.getVisibility() == WorkVisibility.PUBLIC)
                .collect(Collectors.toMap(Work::getId, Function.identity()));
        List<Work> ordered = workIds.stream().distinct().map(works::get).filter(Objects::nonNull).toList();
        return ordered.isEmpty() ? List.of() : summarize(ordered);
    }

    /**
     * 내 활동 목록용 공개 작품 정보. 공개가 아니거나 없는 작품은 결과에 없다(숨김 작품 정보는 내보내지 않음).
     * 작품 수만큼이 아니라 묶음 단위로 조회한다(getPublicSummaries 와 같은 SQL 수).
     */
    @Transactional(readOnly = true)
    public Map<Long, ActivityWorkResponse> findPublicActivityWorks(Collection<Long> workIds) {
        if (workIds.isEmpty()) {
            return Map.of();
        }
        return getPublicSummaries(List.copyOf(new java.util.LinkedHashSet<>(workIds))).stream()
                .collect(Collectors.toMap(WorkSummaryResponse::id, ActivityWorkResponse::from));
    }

    /** 공개 작품의 대표 제목. 숨김·없는 작품은 결과에 없다(최근 리뷰 목록이 작품명을 붙일 때 사용) */
    @Transactional(readOnly = true)
    public Map<Long, PrimaryTitleResponse> findPublicPrimaryTitles(Collection<Long> workIds) {
        if (workIds.isEmpty()) {
            return Map.of();
        }
        Set<Long> publicIds = workRepository.findAllById(workIds).stream()
                .filter(work -> work.getVisibility() == WorkVisibility.PUBLIC)
                .map(Work::getId)
                .collect(Collectors.toSet());
        if (publicIds.isEmpty()) {
            return Map.of();
        }
        return workTitleRepository.findPrimaryTitles(publicIds).stream()
                .collect(Collectors.toMap(PrimaryTitleView::workId,
                        view -> new PrimaryTitleResponse(view.title(), view.languageCode())));
    }

    /** 작품 묶음의 대표 제목·크레딧·작가·별점 집계를 묶음 단위로 일괄 조회해 요약을 만든다(입력 순서 유지) */
    private List<WorkSummaryResponse> summarize(List<Work> works) {
        List<Long> workIds = works.stream().map(Work::getId).toList();
        Map<Long, PrimaryTitleView> primaryTitles = workTitleRepository.findPrimaryTitles(workIds).stream()
                .collect(Collectors.toMap(PrimaryTitleView::workId, Function.identity()));
        Map<Long, List<CreditView>> creditsByWork = workCreatorRepository.findCredits(workIds).stream()
                .collect(Collectors.groupingBy(CreditView::workId));
        Map<Long, CreatorSummary> creators = creatorService.getSummaries(
                creditsByWork.values().stream().flatMap(List::stream).map(CreditView::creatorId).toList());
        Map<Long, RatingSummaryResponse> ratings = ratingSummaryQuery.findByWorkIds(workIds);
        Map<Long, Long> reviewCounts = reviewCountQuery.countByWorkIds(workIds);

        return works.stream()
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
                            ratings.getOrDefault(work.getId(), RatingSummaryResponse.EMPTY),
                            work.getCreatedAt(),
                            reviewCounts.getOrDefault(work.getId(), 0L));
                })
                .toList();
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

    private WorkDetailResponse toDetail(Work work, RatingSummaryResponse rating) {
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
                rating,
                work.getCreatedAt(),
                work.getUpdatedAt(),
                reviewCountQuery.countByWorkIds(List.of(work.getId())).getOrDefault(work.getId(), 0L),
                publicPlatformLinkService.findPublicLinks(work.getId()));
    }

    private static CreditResponse toCredit(Long creatorId, CreatorRole role, Map<Long, CreatorSummary> creators) {
        CreatorSummary creator = creators.get(creatorId);
        if (creator == null) {
            throw new IllegalStateException("creator not found for credit: " + creatorId);
        }
        return new CreditResponse(creatorId, creator.name(), role);
    }

    // ---------- 입력 정규화·검증 (docs/work-creator-spec.md 3, 4) ----------

    static final String VERSION_REQUIRED = "version 은 필수입니다.";
    static final String VERSION_INVALID = "version 은 0 이상의 정수여야 합니다.";

    private static Long version(JsonInteger field, List<ApiError.FieldError> errors) {
        if (field == null) {
            errors.add(new ApiError.FieldError("version", VERSION_REQUIRED));
            return null;
        }
        if (!field.isInteger() || field.value() < 0) {
            errors.add(new ApiError.FieldError("version", VERSION_INVALID));
            return null;
        }
        return field.value();
    }

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
