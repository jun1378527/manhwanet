package com.manhwanet.collection.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.manhwanet.audit.application.AdminChangeLog;
import com.manhwanet.audit.domain.ChangeAction;
import com.manhwanet.audit.domain.ChangeEntityType;
import com.manhwanet.collection.domain.BasisKind;
import com.manhwanet.collection.domain.CollectionPolicy;
import com.manhwanet.collection.domain.CollectionPolicyRepository;
import com.manhwanet.collection.domain.CoverPolicy;
import com.manhwanet.collection.domain.CrawlRunRepository;
import com.manhwanet.collection.domain.CrawlTaskRepository;
import com.manhwanet.collection.domain.DetailStatus;
import com.manhwanet.collection.domain.PolicyRow;
import com.manhwanet.collection.domain.ReviewStatus;
import com.manhwanet.collection.domain.RunRow;
import com.manhwanet.collection.domain.StagingRepository;
import com.manhwanet.collection.domain.StagingRow;
import com.manhwanet.collection.domain.SynopsisPolicy;
import com.manhwanet.collection.domain.TaskStatus;
import com.manhwanet.collection.dto.CollectionPolicyResponse;
import com.manhwanet.collection.dto.CrawlRunResponse;
import com.manhwanet.collection.dto.PageResponseWithCursor;
import com.manhwanet.collection.dto.StagingListingResponse;
import com.manhwanet.collection.dto.UpdateCollectionPolicyRequest;
import com.manhwanet.global.admin.AdminInputs;
import com.manhwanet.global.error.BusinessException;
import com.manhwanet.global.error.ErrorCode;
import com.manhwanet.global.error.VersionConflictException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 수집 설정·실행·staging 관리자 기능 (#45). ADMIN + CSRF 는 컨트롤러 경로(/api/v1/admin/**)가 보장한다.
 * <ul>
 *   <li>수집 설정 변경: version 이 같을 때만, reason 필수, 같은 트랜잭션에서 변경 기록(COLLECTION_POLICY). 내용이 같으면 그대로.
 *       바꾼 관리자·시각을 남긴다. 외부 근거는 선택이다.</li>
 *   <li>실행·staging 은 조회만(실행 중단 요청 제외). staging 의 검수 상태 변경과 작품 등록은 #51 에서 한다.</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class CollectionAdminService {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 100;
    static final int CHANGE_LIMIT = 50;

    private final CollectionPolicyRepository policyRepository;
    private final CrawlRunRepository runRepository;
    private final CrawlTaskRepository taskRepository;
    private final StagingRepository stagingRepository;
    private final AdminChangeLog changeLog;
    private final ObjectMapper objectMapper;

    public CollectionAdminService(CollectionPolicyRepository policyRepository, CrawlRunRepository runRepository,
            CrawlTaskRepository taskRepository, StagingRepository stagingRepository, AdminChangeLog changeLog,
            ObjectMapper objectMapper) {
        this.policyRepository = policyRepository;
        this.runRepository = runRepository;
        this.taskRepository = taskRepository;
        this.stagingRepository = stagingRepository;
        this.changeLog = changeLog;
        this.objectMapper = objectMapper;
    }

    // ---------- 수집 설정 ----------

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<CollectionPolicyResponse> getPolicies() {
        return policyRepository.findAll().stream().map(this::policyResponse).toList();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CollectionPolicyResponse getPolicy(String code) {
        return policyResponse(findPolicy(normalize(code)));
    }

    @Transactional
    public CollectionPolicyResponse updatePolicy(String rawCode, UpdateCollectionPolicyRequest request, long actorId) {
        String code = normalize(rawCode);
        AdminInputs in = new AdminInputs();
        Long version = in.version(request.version());
        CollectionPolicy collection = in.enumValue(request.collectionPolicy(), CollectionPolicy.class, "collectionPolicy", true);
        SynopsisPolicy synopsis = in.enumValue(request.synopsisPolicy(), SynopsisPolicy.class, "synopsisPolicy", true);
        CoverPolicy cover = in.enumValue(request.coverPolicy(), CoverPolicy.class, "coverPolicy", true);
        String note = in.text(request.note(), "note", 1000, false);
        BasisKind basisKind = in.enumValue(request.basisKind(), BasisKind.class, "basisKind", false);
        String basisNote = in.text(request.basisNote(), "basisNote", 1000, false);
        if (basisNote != null && request.basisKind() == null) {
            in.add("basisKind", "basisNote 를 적으려면 basisKind 가 필요합니다.");
        }
        Long rate = in.integer(request.rateIntervalMs(), "rateIntervalMs", 1000, true);
        if (rate != null && rate > 3_600_000) {
            in.add("rateIntervalMs", "rateIntervalMs 는 1000~3600000 사이여야 합니다.");
        }
        Long cap = in.integer(request.dailyRequestCap(), "dailyRequestCap", 1, true);
        if (cap != null && cap > 1_000_000) {
            in.add("dailyRequestCap", "dailyRequestCap 은 1~1000000 사이여야 합니다.");
        }
        String reason = in.reason(request.reason(), true);
        in.throwIfAny();

        PolicyRow current = policyRepository.lockForUpdate(code)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLATFORM_NOT_FOUND));
        if (current.version() != version) {
            throw new VersionConflictException(ErrorCode.COLLECTION_POLICY_VERSION_CONFLICT, current.version());
        }
        boolean same = current.collectionPolicy() == collection && current.synopsisPolicy() == synopsis
                && current.coverPolicy() == cover && Objects.equals(current.note(), note)
                && current.basisKind() == basisKind && Objects.equals(current.basisNote(), basisNote)
                && current.rateIntervalMs() == rate && current.dailyRequestCap() == cap;
        if (same) {
            return policyResponse(current);
        }
        int updated = policyRepository.update(code, version, collection, synopsis, cover, note, basisKind, basisNote,
                actorId, rate.intValue(), cap.intValue());
        if (updated != 1) {
            throw new IllegalStateException("locked collection policy was not updated: " + code);
        }
        PolicyRow after = findPolicy(code);
        changeLog.record(ChangeEntityType.COLLECTION_POLICY, code, ChangeAction.UPDATE, null, null, policySnapshot(current),
                policySnapshot(after), reason, actorId);
        return policyResponse(after);
    }

    // ---------- 실행 ----------

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponseWithCursor<CrawlRunResponse> getRuns(String platformCode, String limit, String beforeId) {
        AdminInputs in = new AdminInputs();
        int size = limit(limit, in);
        long before = cursor(beforeId, in);
        in.throwIfAny();
        List<RunRow> rows = runRepository.findPage(platformCode == null ? null : normalize(platformCode), before, size + 1);
        Long next = rows.size() > size ? rows.get(size - 1).id() : null;
        return new PageResponseWithCursor<>(rows.stream().limit(size)
                .map(r -> CrawlRunResponse.from(r, node(r.statsJson()), null, null)).toList(), next);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CrawlRunResponse getRun(long runId) {
        RunRow run = runRepository.findById(runId).orElseThrow(() -> new BusinessException(ErrorCode.CRAWL_RUN_NOT_FOUND));
        Map<String, Long> counts = new LinkedHashMap<>();
        Map<TaskStatus, Long> raw = taskRepository.countByStatus(runId);
        raw.forEach((status, n) -> counts.put(status.name(), n));
        return CrawlRunResponse.from(run, node(run.statsJson()), counts, taskRepository.countSamples(runId));
    }

    // ---------- staging ----------

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponseWithCursor<StagingListingResponse> getStagingListings(String platformCode, String reviewStatus,
            String detailStatus, String limit, String beforeId) {
        AdminInputs in = new AdminInputs();
        ReviewStatus review = in.enumValue(reviewStatus, ReviewStatus.class, "reviewStatus", false);
        DetailStatus detail = in.enumValue(detailStatus, DetailStatus.class, "detailStatus", false);
        int size = limit(limit, in);
        long before = cursor(beforeId, in);
        in.throwIfAny();
        List<StagingRow> rows = stagingRepository.findPage(platformCode == null ? null : normalize(platformCode), review,
                detail, before, size + 1);
        Long next = rows.size() > size ? rows.get(size - 1).id() : null;
        return new PageResponseWithCursor<>(rows.stream().limit(size).map(r -> staging(r, false)).toList(), next);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public StagingListingResponse getStagingListing(long id) {
        StagingRow row = stagingRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.STAGING_LISTING_NOT_FOUND));
        return staging(row, true);
    }

    // ---------- 내부 ----------

    private StagingListingResponse staging(StagingRow r, boolean detail) {
        List<Map<String, Object>> credits = detail ? stagingRepository.findCredits(r.id()).stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("nameRaw", c.nameRaw());
            m.put("roleRaw", c.roleRaw());
            m.put("role", c.role().name());
            m.put("platformCreatorId", c.platformCreatorId());
            return m;
        }).collect(Collectors.toList()) : null;
        List<Map<String, Object>> changes = detail ? stagingRepository.findChanges(r.id(), CHANGE_LIMIT).stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>(c);
            m.put("changedFields", node((String) c.get("changedFields")));
            return m;
        }).collect(Collectors.toList()) : null;
        return new StagingListingResponse(r.id(), r.platformCode(), r.platformWorkId(), r.idScope(), r.listingKind(),
                r.languageCode(), r.url(), r.titleRaw(), r.titleClean(), node(r.genresJson()), r.ageRatingRaw(), r.ageClass(),
                node(r.ageSignalsJson()), r.serialStatusRaw(), r.serialStatus().name(), r.coverUrl(),
                detail ? r.synopsisText() : null, r.synopsisSha256(), r.synopsisLength(), node(r.extraJson()),
                node(r.fieldSourcesJson()), r.detailStatus().name(), r.contentHash(), r.firstSeenAt(), r.lastSeenAt(),
                r.lastChangedAt(), r.firstRunId(), r.lastRunId(),
                r.lastFailureReason() == null ? null : r.lastFailureReason().name(), r.lastFailureAt(),
                r.lastFailureRunId(), r.consecutiveFailures(), r.reviewStatus().name(), r.linkedListingId(), credits,
                detail ? stagingRepository.findAliases(r.id()) : null,
                detail ? stagingRepository.findDiscoveries(r.id()) : null, changes);
    }

    private CollectionPolicyResponse policyResponse(PolicyRow row) {
        Long active = runRepository.findActive(row.platformCode()).map(RunRow::id).orElse(null);
        List<String> warnings = active != null && row.collectionPolicy() == CollectionPolicy.DISABLED
                ? List.of("ACTIVE_RUN_REMAINS") : List.of();
        return CollectionPolicyResponse.from(row, active, warnings);
    }

    private static Map<String, Object> policySnapshot(PolicyRow row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("collectionPolicy", row.collectionPolicy());
        m.put("synopsisPolicy", row.synopsisPolicy());
        m.put("coverPolicy", row.coverPolicy());
        m.put("note", row.note());
        m.put("basisKind", row.basisKind());
        m.put("basisNote", row.basisNote());
        m.put("rateIntervalMs", row.rateIntervalMs());
        m.put("dailyRequestCap", row.dailyRequestCap());
        m.put("version", row.version());
        return m;
    }

    private PolicyRow findPolicy(String code) {
        return policyRepository.findByCode(code).orElseThrow(() -> new BusinessException(ErrorCode.PLATFORM_NOT_FOUND));
    }

    private JsonNode node(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored json is invalid", e);
        }
    }

    static String normalize(String code) {
        return code == null ? "" : code.strip().toUpperCase(Locale.ROOT);
    }

    private static int limit(String raw, AdminInputs in) {
        if (raw == null) {
            return DEFAULT_LIMIT;
        }
        if (!raw.matches("[0-9]{1,3}") || Integer.parseInt(raw) < 1 || Integer.parseInt(raw) > MAX_LIMIT) {
            in.add("limit", "limit 은 1 이상 " + MAX_LIMIT + " 이하여야 합니다.");
            return DEFAULT_LIMIT;
        }
        return Integer.parseInt(raw);
    }

    private static long cursor(String raw, AdminInputs in) {
        if (raw == null) {
            return Long.MAX_VALUE;
        }
        try {
            long value = raw.matches("[0-9]{1,19}") ? Long.parseLong(raw) : -1;
            if (value < 1) {
                in.add("beforeId", "beforeId 는 1 이상의 정수여야 합니다.");
            }
            return value;
        } catch (NumberFormatException e) {
            in.add("beforeId", "beforeId 가 너무 큽니다.");
            return Long.MAX_VALUE;
        }
    }
}
