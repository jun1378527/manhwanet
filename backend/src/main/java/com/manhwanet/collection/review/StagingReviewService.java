package com.manhwanet.collection.review;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.manhwanet.audit.application.AdminChangeLog;
import com.manhwanet.audit.domain.ChangeAction;
import com.manhwanet.audit.domain.ChangeEntityType;
import com.manhwanet.collection.domain.DetailStatus;
import com.manhwanet.collection.domain.ReviewStatus;
import com.manhwanet.collection.domain.StagingCreditRow;
import com.manhwanet.collection.domain.StagingRepository;
import com.manhwanet.collection.domain.StagingRow;
import com.manhwanet.creator.application.CreatorService;
import com.manhwanet.creator.dto.CreateCreatorRequest;
import com.manhwanet.global.admin.AdminInputs;
import com.manhwanet.global.error.BusinessException;
import com.manhwanet.global.error.ErrorCode;
import com.manhwanet.global.error.ListingAlreadyLinkedException;
import com.manhwanet.global.error.VersionConflictException;
import com.manhwanet.platform.application.WorkListingService;
import com.manhwanet.platform.domain.AgeClass;
import com.manhwanet.platform.domain.AgeRatingClassifier;
import com.manhwanet.platform.domain.ListingRepository;
import com.manhwanet.platform.domain.ListingRow;
import com.manhwanet.platform.dto.CreateListingRequest;
import com.manhwanet.platform.dto.ListingInput;
import com.manhwanet.platform.dto.ListingMutationResponse;
import com.manhwanet.review.dto.JsonInteger;
import com.manhwanet.work.application.WorkService;
import com.manhwanet.work.dto.AdminWorkDetailResponse;
import com.manhwanet.work.dto.CreateWorkRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * staging 검수·공개 등록 연동(#51, 설계 §9).
 * <ul>
 *   <li>결정: 기존 작품 연결(LINK_EXISTING)·새 작품 등록(CREATE_WORK)·보류(HOLD)·제외(REJECT)</li>
 *   <li>등록·연결은 기존 관리자 서비스({@link WorkService#register}, {@link WorkListingService#create},
 *       {@link CreatorService#register})를 같은 트랜잭션에서 부른다. 작품 version·근거 필수·연결 키 유일·변경 기록 규칙이 그대로 적용된다</li>
 *   <li>공개 등록·연결·검수 상태·후보 판단·결정 기록·변경 기록은 한 트랜잭션이다. 어느 하나라도 실패하면 모두 롤백된다</li>
 *   <li>수집 revision 이 검수자가 본 값과 다르면 409. 이미 연결·등록으로 끝난 항목은 409(반복 승인). 항목 행 잠금과
 *       결정 유니크 인덱스(uk_srd_final)로 동시 승인도 하나만 성공한다</li>
 *   <li>제목만으로 병합하지 않는다. 승인 때 후보를 다시 만들고, 열린 후보 전부에 대한 검수자 판단이 없으면 409</li>
 *   <li>근거는 검수자가 보낸 것만 쓴다(수집기 근거 COLLECTOR 는 D-19 미결정이라 만들지 않음). 줄거리·표지는 옮기지 않는다</li>
 *   <li>새 작품 등록은 원래 등급 표기로 18세 미만이 확인된 항목(GENERAL)만. 공개 상태(HIDDEN) 모델이 아직 없어(#41)
 *       새 작품은 바로 공개 목록에 보이므로, ADULT·UNKNOWN 은 공개 표시 정책(D-15) 전까지 새 작품으로 만들지 않는다</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class StagingReviewService {

    public enum Decision {
        LINK_EXISTING, CREATE_WORK, HOLD, REJECT
    }

    public enum CandidateJudgement {
        SAME_WORK, DIFFERENT
    }

    private static final Set<ReviewStatus> FINAL = EnumSet.of(ReviewStatus.LINKED, ReviewStatus.NEW_WORK, ReviewStatus.MERGED_ALIAS);
    private static final Set<DetailStatus> LINKABLE = EnumSet.of(DetailStatus.OK, DetailStatus.GATED, DetailStatus.SKIPPED_ADULT);
    private static final Set<String> ROLES = Set.of("STORY", "ART", "ORIGINAL_STORY");
    private static final Set<String> FORMATS = Set.of("WEBTOON", "PAGE_COMIC");

    private final StagingRepository stagingRepository;
    private final StagingReviewRepository repository;
    private final StagingCandidateGenerator generator;
    private final ListingRepository listingRepository;
    private final WorkService workService;
    private final WorkListingService workListingService;
    private final CreatorService creatorService;
    private final AdminChangeLog changeLog;
    private final ObjectMapper objectMapper;

    public StagingReviewService(StagingRepository stagingRepository, StagingReviewRepository repository,
            StagingCandidateGenerator generator, ListingRepository listingRepository, WorkService workService,
            WorkListingService workListingService, CreatorService creatorService, AdminChangeLog changeLog,
            ObjectMapper objectMapper) {
        this.stagingRepository = stagingRepository;
        this.repository = repository;
        this.generator = generator;
        this.listingRepository = listingRepository;
        this.workService = workService;
        this.workListingService = workListingService;
        this.creatorService = creatorService;
        this.changeLog = changeLog;
        this.objectMapper = objectMapper;
    }

    /** 검수 화면: 항목·revision·저장된 후보·지난 결정(후보를 새로 만들지 않음) */
    public StagingReviewView view(long stagingId) {
        StagingRow staging = stagingRepository.findById(stagingId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STAGING_LISTING_NOT_FOUND));
        return view(staging, repository.candidates(stagingId));
    }

    /** 후보를 다시 만든다(같은 입력이면 행이 늘지 않음). 열린 후보가 있으면 NEW → CANDIDATES */
    @Transactional
    public StagingReviewView regenerateCandidates(long stagingId, long actorId) {
        StagingRow staging = stagingRepository.lockById(stagingId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STAGING_LISTING_NOT_FOUND));
        List<StagingReviewRepository.CandidateRow> candidates = generator.generate(staging,
                stagingRepository.findCredits(stagingId), stagingRepository.findAliases(stagingId), actorId);
        if (candidates.stream().anyMatch(c -> c.status().equals("OPEN"))) {
            repository.markCandidates(stagingId);
        }
        return view(stagingRepository.findById(stagingId).orElseThrow(), candidates);
    }

    /** 검수 결정. 전체가 한 트랜잭션이다 */
    @Transactional
    public StagingReviewView review(long stagingId, StagingReviewRequest request, long actorId) {
        if (request == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "요청 본문이 필요합니다.");
        }
        AdminInputs in = new AdminInputs();
        Decision decision = in.enumValue(request.decision(), Decision.class, "decision", true);
        Long revision = in.integer(request.stagingRevision(), "stagingRevision", 0, true);
        String reason = in.reason(request.reason(), true);
        Map<Long, CandidateJudgement> judgements = judgements(request.candidates(), in);
        Long workId = null;
        Long workVersion = null;
        if (decision == Decision.LINK_EXISTING) {
            workId = in.integer(request.workId(), "workId", 1, true);
            workVersion = in.integer(request.workVersion(), "workVersion", 0, true);
        }
        if (decision == Decision.LINK_EXISTING || decision == Decision.CREATE_WORK) {
            if (request.listing() == null) {
                in.add("listing", "연결 정보(listing.evidence 등)가 필요합니다.");
            }
        }
        if (decision == Decision.CREATE_WORK) {
            checkNewWork(request.work(), in);
        }
        in.throwIfAny();

        StagingRow staging = stagingRepository.lockById(stagingId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STAGING_LISTING_NOT_FOUND));
        if (FINAL.contains(staging.reviewStatus())) {
            throw new BusinessException(ErrorCode.STAGING_ALREADY_REVIEWED, ErrorCode.STAGING_ALREADY_REVIEWED.getMessage()
                    + " (상태 " + staging.reviewStatus() + ", 연결 " + staging.linkedListingId() + ")");
        }
        long current = stagingRepository.revision(stagingId);
        if (current != revision) {
            throw new VersionConflictException(ErrorCode.STAGING_REVISION_CONFLICT, current);
        }
        Map<String, Object> before = snapshot(staging.reviewStatus().name(), current, staging.linkedListingId(), null, null, null);

        if (decision == Decision.HOLD || decision == Decision.REJECT) {
            ReviewStatus to = decision == Decision.HOLD ? ReviewStatus.ON_HOLD : ReviewStatus.REJECTED;
            repository.updateReview(stagingId, to.name(), null);
            long decisionId = repository.insertDecision(stagingId, decision.name(), current, staging.reviewStatus().name(),
                    to.name(), null, null, "[]", reason, actorId);
            changeLog.record(ChangeEntityType.STAGING_LISTING, String.valueOf(stagingId), ChangeAction.STATUS_CHANGE, null, null,
                    before, snapshot(to.name(), current, null, decision.name(), null, decisionId), reason, actorId);
            return view(stagingId);
        }

        if (!LINKABLE.contains(staging.detailStatus())) {
            throw new BusinessException(ErrorCode.STAGING_NOT_REVIEWABLE,
                    "상세를 확인하지 못한 항목(" + staging.detailStatus() + ")은 연결·등록할 수 없습니다. 재수집 뒤 검수하거나 보류·제외하세요.");
        }
        Optional<AgeClass> rawClass = AgeRatingClassifier.classify(staging.ageRatingRaw());
        if (decision == Decision.CREATE_WORK
                && !(rawClass.isPresent() && rawClass.get() == AgeClass.GENERAL && "GENERAL".equals(staging.ageClass()))) {
            throw new BusinessException(ErrorCode.STAGING_NOT_REVIEWABLE, "새 작품 등록은 원래 등급 표기로 18세 미만이 확인된 항목만 "
                    + "할 수 있습니다(현재 " + staging.ageClass() + "). 공개 상태(HIDDEN, #41)와 공개 표시 정책(D-15) 전에는 "
                    + "ADULT·UNKNOWN 을 새 작품으로 만들지 않습니다. 기존 작품 연결·보류는 할 수 있습니다.");
        }

        // 승인 시점의 후보를 다시 만들고, 열린 후보 전부에 대한 판단을 요구한다(제목만으로 병합하지 않음)
        List<StagingCreditRow> credits = stagingRepository.findCredits(stagingId);
        List<String> aliases = stagingRepository.findAliases(stagingId);
        List<StagingReviewRepository.CandidateRow> candidates = generator.generate(staging, credits, aliases, actorId);
        Map<Long, StagingReviewRepository.CandidateRow> open = new LinkedHashMap<>();
        candidates.stream().filter(c -> c.status().equals("OPEN")).forEach(c -> open.put(c.id(), c));
        if (!open.keySet().equals(judgements.keySet())) {
            Set<Long> missing = new TreeSet<>(open.keySet());
            missing.removeAll(judgements.keySet());
            Set<Long> unknown = new TreeSet<>(judgements.keySet());
            unknown.removeAll(open.keySet());
            throw new BusinessException(ErrorCode.STAGING_REVIEW_CANDIDATES_UNRESOLVED,
                    ErrorCode.STAGING_REVIEW_CANDIDATES_UNRESOLVED.getMessage() + " (판단 없음 " + missing + ", 열린 후보 아님 "
                            + unknown + ")");
        }
        checkJudgements(decision, workId, open, judgements);

        // 식별 키 충돌: 같은 플랫폼 작품(대표 ID·별칭)이 이미 연결돼 있으면 같은 작품으로 연결하는 경우만 허용
        Optional<ListingRow> existing = listingRepository.findByPlatformWorkId(staging.platformCode(), staging.platformWorkId());
        for (String alias : aliases) {
            Optional<ListingRow> aliasListing = listingRepository.findByPlatformWorkId(staging.platformCode(), alias);
            if (aliasListing.isPresent() && !(decision == Decision.LINK_EXISTING && aliasListing.get().workId().equals(workId))) {
                throw new ListingAlreadyLinkedException(aliasListing.get().workId(), aliasListing.get().id());
            }
        }
        if (existing.isPresent() && !(decision == Decision.LINK_EXISTING && existing.get().workId().equals(workId))) {
            throw new ListingAlreadyLinkedException(existing.get().workId(), existing.get().id());
        }

        long listingId;
        long resultWorkId;
        Long resultWorkVersion;
        List<String> warnings = new ArrayList<>();
        StagingReviewRequest.ListingOptions options = request.listing();
        if (decision == Decision.LINK_EXISTING) {
            if (existing.isPresent()) {
                // 이미 같은 작품에 연결된 플랫폼 작품: 새 연결을 만들지 않고 검수만 마친다
                listingId = existing.get().id();
                resultWorkId = workId;
                long currentVersion = repository.workVersion(workId).orElseThrow(() -> new BusinessException(ErrorCode.WORK_NOT_FOUND));
                if (currentVersion != workVersion) {
                    throw new VersionConflictException(ErrorCode.WORK_VERSION_CONFLICT, currentVersion);
                }
                resultWorkVersion = currentVersion;
                warnings.add("ALREADY_LINKED");
            } else {
                ListingMutationResponse created = workListingService.create(workId, new CreateListingRequest(
                        new JsonInteger(workVersion), staging.platformCode(), staging.platformWorkId(), staging.url(),
                        idScope(options, staging), listingKind(options, staging), staging.languageCode(), staging.ageRatingRaw(),
                        null, verifiedAt(options), options.note(), options.evidence(), reason), actorId);
                listingId = created.listing().id();
                resultWorkId = created.workId();
                resultWorkVersion = created.workVersion();
                if (created.warnings() != null) {
                    warnings.addAll(created.warnings());
                }
            }
        } else {
            CreateWorkRequest create = newWorkRequest(staging, request.work(), options, reason, actorId);
            AdminWorkDetailResponse detail = workService.register(create, actorId);
            resultWorkId = detail.id();
            resultWorkVersion = repository.workVersion(resultWorkId).orElseThrow();
            listingId = listingRepository.findByPlatformWorkId(staging.platformCode(), staging.platformWorkId())
                    .orElseThrow(() -> new IllegalStateException("registered listing not found")).id();
        }

        ReviewStatus to = decision == Decision.LINK_EXISTING ? ReviewStatus.LINKED : ReviewStatus.NEW_WORK;
        repository.updateReview(stagingId, to.name(), listingId);
        List<Map<String, Object>> judged = new ArrayList<>();
        for (Map.Entry<Long, CandidateJudgement> e : judgements.entrySet()) {
            repository.decideCandidate(e.getKey(), e.getValue() == CandidateJudgement.SAME_WORK ? "ACCEPTED" : "REJECTED",
                    actorId, null);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("candidateId", e.getKey());
            item.put("judgement", e.getValue().name());
            judged.add(item);
        }
        long decisionId = repository.insertDecision(stagingId, decision.name(), current, staging.reviewStatus().name(), to.name(),
                resultWorkId, listingId, json(judged), reason, actorId);
        changeLog.record(ChangeEntityType.STAGING_LISTING, String.valueOf(stagingId), ChangeAction.STATUS_CHANGE, resultWorkId,
                null, before, snapshot(to.name(), current, listingId, decision.name(), resultWorkId, decisionId), reason, actorId);
        return view(stagingId);
    }

    // ---------- 입력 ----------

    private Map<Long, CandidateJudgement> judgements(List<StagingReviewRequest.CandidateDecision> raw, AdminInputs in) {
        Map<Long, CandidateJudgement> out = new LinkedHashMap<>();
        if (raw == null) {
            return out;
        }
        if (raw.size() > 200) {
            in.add("candidates", "후보 판단은 200개 이하여야 합니다.");
            return out;
        }
        for (int i = 0; i < raw.size(); i++) {
            StagingReviewRequest.CandidateDecision item = raw.get(i);
            String p = "candidates[" + i + "]";
            if (item == null) {
                in.add(p, "후보 판단 항목은 비어 있을 수 없습니다.");
                continue;
            }
            Long id = in.integer(item.id(), p + ".id", 1, true);
            CandidateJudgement judgement = in.enumValue(item.decision(), CandidateJudgement.class, p + ".decision", true);
            if (id != null && judgement != null && out.put(id, judgement) != null) {
                in.add(p + ".id", "같은 후보를 두 번 판단할 수 없습니다.");
            }
        }
        return out;
    }

    private static void checkNewWork(StagingReviewRequest.NewWork work, AdminInputs in) {
        if (work == null) {
            in.add("work", "새 작품 정보(work)가 필요합니다.");
            return;
        }
        if (work.workFormat() == null || !FORMATS.contains(work.workFormat())) {
            in.add("work.workFormat", "작품 형식은 WEBTOON 또는 PAGE_COMIC 이어야 합니다.");
        }
        if (work.titles() != null) {
            if (work.titles().isEmpty() || work.titles().size() > 20) {
                in.add("work.titles", "제목은 1개 이상 20개 이하여야 합니다(생략하면 수집 제목).");
            } else {
                for (int i = 0; i < work.titles().size(); i++) {
                    StagingReviewRequest.Title t = work.titles().get(i);
                    if (t == null || t.title() == null || t.title().isBlank() || t.languageCode() == null || t.languageCode().isBlank()) {
                        in.add("work.titles[" + i + "]", "제목과 언어 코드는 필수입니다.");
                    }
                }
            }
        }
        if (work.credits() == null || work.credits().isEmpty() || work.credits().size() > 20) {
            in.add("work.credits", "작가 지정은 1개 이상 20개 이하여야 합니다. 수집한 작가 이름으로 자동 연결하지 않습니다.");
            return;
        }
        for (int i = 0; i < work.credits().size(); i++) {
            StagingReviewRequest.Credit c = work.credits().get(i);
            String p = "work.credits[" + i + "]";
            if (c == null) {
                in.add(p, "작가 지정 항목은 비어 있을 수 없습니다.");
                continue;
            }
            boolean hasId = c.creatorId() != null;
            boolean hasName = c.newCreatorName() != null && !c.newCreatorName().isBlank();
            if (hasId == hasName) {
                in.add(p, "creatorId(기존 작가)와 newCreatorName(새 작가) 중 하나만 지정하세요.");
            } else if (hasId) {
                in.integer(c.creatorId(), p + ".creatorId", 1, true);
            } else if (c.newCreatorName().strip().length() > 100) {
                in.add(p + ".newCreatorName", "작가 이름은 100자 이하여야 합니다.");
            }
            if (c.role() == null || !ROLES.contains(c.role())) {
                in.add(p + ".role", "작가 역할은 STORY, ART, ORIGINAL_STORY 중 하나여야 합니다(UNSPECIFIED 는 검수자가 정함).");
            }
        }
    }

    private static void checkJudgements(Decision decision, Long workId, Map<Long, StagingReviewRepository.CandidateRow> open,
            Map<Long, CandidateJudgement> judgements) {
        AdminInputs in = new AdminInputs();
        for (Map.Entry<Long, CandidateJudgement> e : judgements.entrySet()) {
            StagingReviewRepository.CandidateRow c = open.get(e.getKey());
            if (c.workId() == null) {
                continue; // 다른 수집 항목 후보: 판단만 기록(그 항목의 검수는 따로)
            }
            String p = "candidates(" + e.getKey() + ")";
            if (decision == Decision.CREATE_WORK && e.getValue() == CandidateJudgement.SAME_WORK) {
                in.add(p, "기존 작품 " + c.workId() + " 과 같은 작품이면 새 작품 대신 LINK_EXISTING 으로 연결하세요.");
            }
            if (decision == Decision.LINK_EXISTING) {
                boolean target = c.workId().equals(workId);
                if (target && e.getValue() == CandidateJudgement.DIFFERENT) {
                    in.add(p, "연결 대상 작품을 다른 작품(DIFFERENT)으로 판단할 수 없습니다.");
                }
                if (!target && e.getValue() == CandidateJudgement.SAME_WORK) {
                    in.add(p, "연결 대상이 아닌 작품 " + c.workId() + " 을 같은 작품으로 판단했습니다. 작품 중복은 #41 에서 정리하세요.");
                }
            }
        }
        in.throwIfAny();
    }

    private CreateWorkRequest newWorkRequest(StagingRow staging, StagingReviewRequest.NewWork work,
            StagingReviewRequest.ListingOptions options, String reason, long actorId) {
        List<CreateWorkRequest.TitleRequest> titles = new ArrayList<>();
        if (work.titles() == null) {
            String title = staging.titleClean() != null ? staging.titleClean() : staging.titleRaw();
            if (title == null || title.isBlank()) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "수집 제목이 없어 work.titles 를 직접 지정해야 합니다.");
            }
            titles.add(new CreateWorkRequest.TitleRequest(title, staging.languageCode(), true));
        } else {
            work.titles().forEach(t -> titles.add(new CreateWorkRequest.TitleRequest(t.title(), t.languageCode(), t.primary())));
        }
        AdminInputs in = new AdminInputs();
        Map<String, Long> createdByName = new HashMap<>();
        List<CreateWorkRequest.CreditRequest> credits = new ArrayList<>();
        for (int i = 0; i < work.credits().size(); i++) {
            StagingReviewRequest.Credit c = work.credits().get(i);
            Long creatorId;
            if (c.creatorId() != null) {
                creatorId = c.creatorId().value();
            } else {
                String name = c.newCreatorName().strip();
                List<Long> sameName = repository.creatorIdsByName(name);
                if (!sameName.isEmpty() && !Boolean.TRUE.equals(c.sameNameConfirmed()) && !createdByName.containsKey(name)) {
                    in.add("work.credits[" + i + "].newCreatorName", "같은 이름의 작가가 이미 있습니다(id " + sameName
                            + "). 같은 사람이면 creatorId 로 지정하고, 동명이인이면 sameNameConfirmed=true 로 보내세요.");
                    continue;
                }
                creatorId = createdByName.get(name);
                if (creatorId == null) {
                    creatorId = creatorService.register(new CreateCreatorRequest(name, c.newCreatorOriginalName(), null, reason),
                            actorId).id();
                    createdByName.put(name, creatorId);
                }
            }
            credits.add(new CreateWorkRequest.CreditRequest(creatorId, c.role()));
        }
        in.throwIfAny();
        ListingInput listing = new ListingInput(staging.platformCode(), staging.platformWorkId(), staging.url(),
                idScope(options, staging), listingKind(options, staging), staging.languageCode(), staging.ageRatingRaw(), null,
                verifiedAt(options), options.note(), options.evidence());
        return new CreateWorkRequest(work.workFormat(), work.originCountry(), null, titles, credits, List.of(listing), null, null,
                work.evidence(), reason);
    }

    private static String idScope(StagingReviewRequest.ListingOptions options, StagingRow staging) {
        return options.idScope() != null ? options.idScope() : staging.idScope();
    }

    private static String listingKind(StagingReviewRequest.ListingOptions options, StagingRow staging) {
        if (options.listingKind() != null) {
            return options.listingKind();
        }
        return staging.listingKind() != null ? staging.listingKind() : "MAIN";
    }

    private static Instant verifiedAt(StagingReviewRequest.ListingOptions options) {
        return options.verifiedAt() != null ? options.verifiedAt() : Instant.now();
    }

    // ---------- 응답 ----------

    private StagingReviewView view(StagingRow s, List<StagingReviewRepository.CandidateRow> rows) {
        Set<Long> workIds = new HashSet<>();
        Set<Long> otherIds = new HashSet<>();
        for (StagingReviewRepository.CandidateRow row : rows) {
            if (row.workId() != null) {
                workIds.add(row.workId());
            } else {
                otherIds.add(row.stagingId() == s.id() ? row.otherStagingId() : row.stagingId());
            }
        }
        Map<Long, String> titles = repository.primaryTitles(workIds);
        Map<Long, StagingRow> others = new HashMap<>();
        for (Long id : otherIds) {
            stagingRepository.findById(id).ifPresent(o -> others.put(id, o));
        }
        List<CandidateView> candidates = new ArrayList<>();
        for (StagingReviewRepository.CandidateRow row : rows) {
            Long other = row.workId() != null ? null : (row.stagingId() == s.id() ? row.otherStagingId() : row.stagingId());
            StagingRow o = other == null ? null : others.get(other);
            candidates.add(new CandidateView(row.id(), row.workId(), row.workId() == null ? null : titles.get(row.workId()),
                    other, o == null ? null : (o.titleClean() != null ? o.titleClean() : o.titleRaw()),
                    o == null ? null : o.platformCode(), row.score(), list(row.reasonsJson()), list(row.warningsJson()),
                    row.status()));
        }
        List<StagingReviewView.Credit> credits = stagingRepository.findCredits(s.id()).stream()
                .map(c -> new StagingReviewView.Credit(c.nameRaw(), c.roleRaw(), c.role().name())).toList();
        return new StagingReviewView(s.id(), s.platformCode(), s.platformWorkId(), s.url(), s.titleRaw(), s.titleClean(),
                s.ageRatingRaw(), s.ageClass(), s.detailStatus().name(), s.reviewStatus().name(), stagingRepository.revision(s.id()),
                s.linkedListingId(), credits, stagingRepository.findAliases(s.id()), candidates, repository.decisions(s.id()));
    }

    private static Map<String, Object> snapshot(String reviewStatus, long revision, Long listingId, String decision, Long workId,
            Long decisionId) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("reviewStatus", reviewStatus);
        map.put("revision", revision);
        map.put("linkedListingId", listingId);
        if (decision != null) {
            map.put("decision", decision);
            map.put("workId", workId);
            map.put("decisionId", decisionId);
        }
        return map;
    }

    private List<String> list(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
